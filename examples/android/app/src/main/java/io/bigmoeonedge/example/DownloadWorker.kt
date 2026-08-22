package io.bigmoeonedge.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a gguf over HTTP straight into the app-internal models dir (real f2fs/ext4, where
 * O_DIRECT works — see [ModelManager.internalModelsDir]). This is why the app does the transfer
 * itself instead of using the system DownloadManager, which can only write to the emulated
 * external storage where O_DIRECT is silently unusable and the engine falls back to slow
 * buffered I/O.
 *
 * The bytes go to `<name>.gguf.part` and the file is renamed to `<name>.gguf` only once complete,
 * so a half-finished download is never listed as a runnable model. A `Range` request resumes an
 * interrupted `.part` instead of restarting a multi-GB transfer, and the worker runs as a
 * foreground service so a long download survives the app going to the background.
 */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val urls = inputData.getStringArray(KEY_URLS)?.toList().orEmpty()
        val labels = inputData.getStringArray(KEY_LABELS)?.toList().orEmpty()
        val name = inputData.getString(KEY_NAME) ?: return@withContext fail("missing filename")
        val expected = inputData.getLong(KEY_EXPECTED, -1L)
        if (urls.isEmpty() || labels.size != urls.size) return@withContext fail("missing download sources")

        val dir = ModelManager.internalModelsDir(applicationContext)
        val finalFile = File(dir, name)
        if (finalFile.isFile) return@withContext Result.success() // already downloaded

        val part = File(dir, name + PART_SUFFIX)
        // usableSpace already accounts for the bytes on disk in .part, so check the remainder.
        if (expected > 0 && expected - part.length() > dir.usableSpace) {
            part.delete()
            return@withContext fail("needs ${ModelCatalog.gbLabel(expected)}, only ${ModelCatalog.gbLabel(dir.usableSpace)} free")
        }

        setForeground(foregroundInfo(name, 0f))

        try {
            var lastError: Throwable? = null
            for (index in urls.indices) {
                try {
                    setProgressAsync(workDataOf(KEY_SOURCE to labels[index]))
                    probe(urls[index], name, expected)
                    val total = transfer(urls[index], name, part) { downloaded, size ->
                        setProgressAsync(workDataOf(KEY_DONE to downloaded, KEY_TOTAL to size, KEY_SOURCE to labels[index]))
                    }
                    if (total >= 0 && part.length() != total) throw java.io.IOException("download interrupted at ${part.length() shr 20} MiB")
                    if (expected > 0 && total >= 0 && total != expected) {
                        throw java.io.IOException("source returned ${total} bytes, expected $expected")
                    }
                    if (!part.renameTo(finalFile)) return@withContext fail("could not finalize the download")
                    return@withContext Result.success(workDataOf(KEY_SOURCE to labels[index]))
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                }
            }
            throw (lastError ?: java.io.IOException("all download sources failed"))
        } catch (c: CancellationException) {
            throw c // cancellation — leave the .part for a manual resume; the UI deletes it on Cancel
        } catch (t: Throwable) {
            // Transient network error: keep the .part and let WorkManager back off and resume.
            retryOrFail(t.message ?: "network error")
        }
    }

    /**
     * Stream the URL into [part], resuming from its current length with a `Range` request.
     * Returns the expected total size in bytes, or -1 if the server didn't report one.
     */
    private suspend fun transfer(
        url: String,
        expectedName: String,
        part: File,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        validateUrl(url, expectedName)
        var from = if (part.isFile) part.length() else 0L
        val conn = openWithRange(url, from, expectedName)
        try {
            val responseCode = conn.responseCode
            verifyResponseFileName(conn, expectedName)
            if (responseCode == 416) return part.length()
            from = resumeOffset(from, responseCode)
            // Total = bytes already on disk + what this response will deliver.
            val remaining = conn.contentLengthLong
            val total = if (remaining >= 0) from + remaining else -1L

            RandomAccessFile(part, "rw").use { out ->
                out.seek(from)
                if (from == 0L) out.setLength(0L)
                conn.inputStream.use { src ->
                    val buf = ByteArray(1 shl 20)
                    var done = from
                    var lastTick = 0L
                    onProgress(done, total)
                    while (true) {
                        if (isStopped) throw CancellationException("cancelled")
                        val n = src.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        // Throttle UI/DB writes: progress Data every ~500 ms, not every 1 MiB chunk.
                        val now = System.currentTimeMillis()
                        if (now - lastTick >= 500) {
                            onProgress(done, total)
                            lastTick = now
                        }
                    }
                    onProgress(done, total)
                }
            }
            return total
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Open [startUrl] following redirects MANUALLY, re-applying the Range and identity-encoding
     * headers on every hop. HttpURLConnection's automatic redirect (instanceFollowRedirects) drops
     * request headers across a cross-host 3xx — which is exactly Hugging Face's resolve → CDN
     * redirect — so a resume would silently lose its Range header, get a 200, and restart from zero.
     */
    private fun probe(url: String, expectedName: String, expectedBytes: Long) {
        val conn = openWithRange(url, 0L, expectedName, true)
        try {
            val code = conn.responseCode
            require(code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) {
                "server returned HTTP $code"
            }
            verifyResponseFileName(conn, expectedName)
            if (expectedBytes > 0 && conn.contentLengthLong > 0 && code == HttpURLConnection.HTTP_OK) {
                require(conn.contentLengthLong == expectedBytes) { "source size does not match expected file" }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openWithRange(
        startUrl: String,
        from: Long,
        expectedName: String,
        probeOnly: Boolean = false,
    ): HttpURLConnection {
        var url = startUrl
        var initialUrl = true
        var hops = 0
        while (true) {
            // Hugging Face's signed Xet CDN URL ends in a content hash, so only the catalog URL
            // itself can carry the expected filename. Every hop still passes the HTTPS/public-IP
            // checks below, and the final response is checked through Content-Disposition.
            validateUrl(url, expectedName.takeIf { initialUrl })
            initialUrl = false
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
                if (from > 0 || probeOnly) setRequestProperty("Range", if (probeOnly) "bytes=0-0" else "bytes=$from-")
            }
            when (conn.responseCode) {
                301, 302, 303, 307, 308 -> {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank() || ++hops > 5) throw java.io.IOException("too many redirects")
                    url = URL(URL(url), loc).toString()
                }
                else -> return conn
            }
        }
    }

    private fun validateUrl(raw: String, expectedName: String?) {
        val uri = java.net.URI(raw)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.port in setOf(-1, 443)) {
            "download sources must be HTTPS on port 443"
        }
        val host = uri.host ?: throw java.io.IOException("source has no hostname")
        val addresses = java.net.InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all(NetworkTools::isPublicAddress)) {
            "source resolves to a private or local address"
        }
        if (expectedName != null) {
            val pathName = safeFileName(uri.path.substringAfterLast('/'))
            require(pathName == expectedName) { "source does not serve the expected file" }
        }
    }

    private fun verifyResponseFileName(conn: HttpURLConnection, expectedName: String) {
        contentDispositionFileName(conn.getHeaderField("Content-Disposition"))?.let { name ->
            require(name == expectedName) { "source returned unexpected filename" }
        }
    }

    private fun retryOrFail(message: String): Result =
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else fail(message)

    private fun fail(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))

    private fun foregroundInfo(name: String, frac: Float): ForegroundInfo {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Model downloads", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(applicationContext, CHANNEL)
            .setContentTitle("Downloading model")
            .setContentText(name)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, (frac * 100).toInt().coerceIn(0, 100), frac < 0f)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    companion object {
        const val PART_SUFFIX = ".part"
        const val TAG = "model-download"

        const val KEY_URLS = "urls"
        const val KEY_LABELS = "labels"
        const val KEY_SOURCE = "source"
        const val KEY_NAME = "name"
        const val KEY_EXPECTED = "expected"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        private const val CHANNEL = "download"
        private const val NOTIF_ID = 42
        private const val MAX_ATTEMPTS = 5

        internal fun safeFileName(raw: String): String = raw.substringBefore('?').filter { it.isLetterOrDigit() || it in "._-" }

        /** Extract the optional RFC 5987 or quoted attachment filename from a CDN response. */
        internal fun contentDispositionFileName(header: String?): String? {
            val value = header ?: return null
            val extended = Regex("(?i)(?:^|;)\\s*filename\\*\\s*=\\s*UTF-8''([^;]+)")
                .find(value)?.groupValues?.get(1)
            val plain = Regex("(?i)(?:^|;)\\s*filename\\s*=\\s*\\\"?([^\\\";]+)")
                .find(value)?.groupValues?.get(1)
            val raw = extended?.let { java.net.URLDecoder.decode(it, Charsets.UTF_8.name()) } ?: plain ?: return null
            return safeFileName(raw.trim()).takeIf { it.isNotEmpty() }
        }

        internal fun resumeOffset(partLength: Long, responseCode: Int): Long = when (responseCode) {
            HttpURLConnection.HTTP_PARTIAL -> partLength
            HttpURLConnection.HTTP_OK -> 0L
            416 -> partLength
            else -> throw java.io.IOException("server returned HTTP $responseCode")
        }

        fun fileNameFromUrl(uri: Uri): String {
            val seg = uri.lastPathSegment?.substringAfterLast('/') ?: ""
            val cleaned = seg.substringBefore('?').filter { it.isLetterOrDigit() || it in "._-" }
            require(cleaned.isNotEmpty()) { "cannot derive a filename from the URL" }
            return cleaned
        }
    }
}
