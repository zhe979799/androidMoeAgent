package io.bigmoeonedge.example

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Installs small, user-authorized POSIX shell commands inside this app's private files.
 * Android 10+ does not permit downloaded native code execution from writable app storage, so
 * installed commands always run through /system/bin/sh and never through execve.
 */
object CliTools {
    private const val MAX_TOOL_BYTES = 64L * 1024L * 1024L
    private const val MAX_OUTPUT_BYTES = 16 * 1024
    private const val MAX_ARGUMENTS = 32
    private const val MAX_ARGUMENT_CHARS = 1024
    private val BACKGROUND_PATTERN = Regex("(?m)(^|[^&])&([^&]|$)|\\b(nohup|setsid|disown)\\b")
    private const val MAX_REDIRECTS = 5
    private const val CONNECT_TIMEOUT_MS = 30_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_TIMEOUT_MS = 120_000
    private val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")
    private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

    suspend fun catalog(context: Context): JSONObject = withContext(Dispatchers.IO) {
        val dir = binDir(context)
        val files = dir.listFiles()?.filter { it.isFile && NAME_PATTERN.matches(it.name) }
            ?.sortedBy { it.name }
            .orEmpty()
        JSONObject().apply {
            put("status", "ok")
            put("directory", "bin")
            put("tool_count", files.size)
            put("tools", JSONArray(files.map { file ->
                JSONObject()
                    .put("name", file.name)
                    .put("path", "bin/${file.name}")
                    .put("bytes", file.length())
                    .put("kind", "posix_shell")
                    .put("modified_ms", file.lastModified())
            }))
        }
    }

    suspend fun install(context: Context, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val name = args.getString("name").trim().also(::validateName)
        val source = args.getString("url").trim()
        val expectedHash = args.getString("sha256").trim().lowercase().also(::validateHash)
        val replace = args.optBoolean("replace", false)
        val dir = binDir(context)
        val target = File(dir, name)
        val wasExisting = target.exists()
        require(!wasExisting || replace) { "tool already exists; set replace=true or remove it first" }
        validateUrl(source)

        val part = File(dir, ".$name.part")
        try {
            val (bytes, actualHash) = download(source, part)
            require(actualHash == expectedHash) { "sha256 mismatch: received $actualHash" }
            validateScript(part)
            validateNoBackground(part)
            if (target.exists()) {
                Files.move(
                    part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } else {
                require(part.renameTo(target)) { "could not finalize tool installation" }
            }
            target.setReadable(true, true)
            target.setWritable(false, true)
            JSONObject().apply {
                put("status", "ok")
                put("name", name)
                put("path", "bin/$name")
                put("bytes", bytes)
                put("sha256", actualHash)
                put("replaced", replace && wasExisting)
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    suspend fun remove(context: Context, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val name = args.getString("name").trim().also(::validateName)
        val target = File(binDir(context), name)
        require(target.isFile) { "tool is not installed: $name" }
        require(target.delete()) { "could not remove tool: $name" }
        JSONObject().put("status", "ok").put("name", name).put("removed", true)
    }

    suspend fun run(context: Context, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val name = args.getString("name").trim().also(::validateName)
        val target = File(binDir(context), name)
        require(target.isFile) { "tool is not installed: $name" }
        val argv = args.optJSONArray("args")?.let { array ->
            require(array.length() <= MAX_ARGUMENTS) { "args may contain at most $MAX_ARGUMENTS values" }
            (0 until array.length()).map { index ->
                require(array.get(index) is String) { "args must contain only strings" }
                array.getString(index).also { require(it.length <= MAX_ARGUMENT_CHARS) { "argument is too long" } }
            }
        }.orEmpty()
        val stdin = args.optString("stdin", "").also { require(it.length <= 8 * 1024) { "stdin is too large" } }
        val timeoutMs = args.optInt("timeout_ms", 10_000).also {
            require(it in 500..30_000) { "timeout_ms must be 500..30000" }
        }
        val workDir = context.filesDir.apply { mkdirs() }
        val process = ProcessBuilder(listOf("/system/bin/sh", target.absolutePath) + argv)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["PATH"] = "/system/bin:/system/xbin"
                environment()["HOME"] = workDir.absolutePath
            }
            .start()
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { runCatching { process.destroyForcibly() } }
        try {
            process.outputStream.use { output ->
                if (stdin.isNotEmpty()) output.write(stdin.toByteArray(Charsets.UTF_8))
            }
            val outputTask = java.util.concurrent.FutureTask {
                process.inputStream.use { it.readBounded(MAX_OUTPUT_BYTES) }
            }
            Thread(outputTask, "bmoe-cli-output").apply { isDaemon = true }.start()
            val completed = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!completed) process.destroyForcibly()
            val bytes = runCatching { outputTask.get(2, TimeUnit.SECONDS) }
                .getOrDefault(ByteArray(0) to false)
            JSONObject().apply {
                put("status", if (completed) "ok" else "timeout")
                put("name", name)
                put("exit_code", if (completed) process.exitValue() else JSONObject.NULL)
                put("truncated", bytes.second)
                put("output", bytes.first.toString(Charsets.UTF_8))
            }
        } finally {
            cancellation?.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
    }

    fun binDir(context: Context): File = File(context.filesDir, "bin").apply { mkdirs() }

    private fun validateName(name: String) {
        require(NAME_PATTERN.matches(name) && name != "." && name != "..") {
            "invalid tool name"
        }
    }

    private fun validateHash(hash: String) {
        require(SHA256_PATTERN.matches(hash)) { "sha256 must be exactly 64 hexadecimal characters" }
    }

    private fun validateUrl(raw: String) {
        val uri = URI(raw)
        require(uri.scheme.equals("https", ignoreCase = true) && uri.userInfo == null && uri.port in setOf(-1, 443)) {
            "tool source must be HTTPS on port 443"
        }
        val host = uri.host ?: throw IllegalArgumentException("tool source has no hostname")
        val addresses = java.net.InetAddress.getAllByName(host)
        require(addresses.isNotEmpty() && addresses.all(NetworkTools::isPublicAddress)) {
            "tool source resolves to a private or local address"
        }
    }

    private suspend fun download(source: String, part: File): Pair<Long, String> {
        val deadline = System.nanoTime() + DOWNLOAD_TIMEOUT_MS * 1_000_000L
        var current = source
        var connection: HttpURLConnection? = null
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { connection?.disconnect() }
        try {
            repeat(MAX_REDIRECTS + 1) { hop ->
                require(System.nanoTime() < deadline) { "tool download exceeded ${DOWNLOAD_TIMEOUT_MS / 1000}s" }
                validateUrl(current)
                connection = (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "BigMoeOnEdge-CLI/1")
                }
                when (val code = connection!!.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        val location = connection!!.getHeaderField("Location")
                        connection!!.disconnect()
                        require(!location.isNullOrBlank() && hop < MAX_REDIRECTS) { "too many redirects" }
                        current = URL(URL(current), location).toString()
                    }
                    HttpURLConnection.HTTP_OK -> {
                        val length = connection!!.contentLengthLong
                        require(length < 0 || length <= MAX_TOOL_BYTES) { "tool exceeds 64 MiB limit" }
                        return stream(connection!!, part, deadline)
                    }
                    else -> throw java.io.IOException("tool source returned HTTP $code")
                }
            }
            error("too many redirects")
        } finally {
            cancellation?.dispose()
            connection?.disconnect()
        }
    }

    private fun validateScript(file: File) {
        val header = ByteArray(2)
        java.io.FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) break
                offset += count
            }
            val script = offset == 2 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte()
            require(script) { "artifact must be a POSIX shell script with a shebang" }
        }
    }

    private fun validateNoBackground(file: File) {
        file.bufferedReader().useLines { lines ->
            require(lines.none { BACKGROUND_PATTERN.containsMatchIn(it) }) {
                "background commands are not allowed"
            }
        }
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): Pair<ByteArray, Boolean> {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var truncated = false
        while (output.size() < maxBytes) {
            val count = read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (count < 0) return output.toByteArray() to truncated
            output.write(buffer, 0, count)
        }
        truncated = read() >= 0
        return output.toByteArray() to truncated
    }

    private fun stream(connection: HttpURLConnection, part: File, deadline: Long): Pair<Long, String> {
        var total = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(part, false).use { output ->
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    require(System.nanoTime() < deadline) { "tool download exceeded ${DOWNLOAD_TIMEOUT_MS / 1000}s" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_TOOL_BYTES) { "tool exceeds 64 MiB limit" }
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
            output.fd.sync()
        }
        return total to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
