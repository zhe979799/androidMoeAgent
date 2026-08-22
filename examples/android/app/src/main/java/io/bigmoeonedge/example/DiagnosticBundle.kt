package io.bigmoeonedge.example

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a small, root-free diagnostic archive without ever traversing the model directory. */
internal object DiagnosticBundle {
    private const val MAX_AGENT_FILES = 20
    private const val MAX_CSV_FILES = 20
    private const val MAX_INPUT_BYTES = 12L * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 10L * 1024 * 1024
    private const val MAX_BUNDLES = 3

    data class Inputs(val agentLogs: List<File>, val metrics: List<File>)

    fun create(
        outputDir: File,
        inputs: Inputs,
        versionName: String,
        versionCode: Int,
        gitSha: String,
        nowMs: Long = System.currentTimeMillis(),
    ): File {
        val agents = boundedFiles(inputs.agentLogs, MAX_AGENT_FILES)
        val metrics = boundedFiles(inputs.metrics, MAX_CSV_FILES)
        val totalInput = (agents + metrics).sumOf { it.length() }
        require(totalInput <= MAX_INPUT_BYTES) {
            "Selected diagnostics are too large (${totalInput / (1024 * 1024)} MiB; limit 12 MiB)"
        }
        require(outputDir.isDirectory || outputDir.mkdirs()) { "Could not create bundle directory" }
        val output = File(outputDir, "bmoe-diagnostics-$nowMs-${System.nanoTime()}.zip")
        val partial = File.createTempFile(".bmoe-diagnostics-", ".part", outputDir)
        return runCatching {
            ZipOutputStream(BufferedOutputStream(LimitedOutputStream(FileOutputStream(partial), MAX_ARCHIVE_BYTES))).use { zip ->
                val manifest = manifestJson(versionName, versionCode, gitSha, agents.size, metrics.size, nowMs)
                putBytes(zip, "manifest.json", manifest.toByteArray(StandardCharsets.UTF_8))
                agents.forEachIndexed { index, file -> putAgentLogFile(zip, "agent-logs/${index + 1}-${safeName(file.name)}", file) }
                metrics.forEachIndexed { index, file -> putMetricsFile(zip, "metrics/${index + 1}-${safeName(file.name)}", file) }
            }
            check(partial.length() <= MAX_ARCHIVE_BYTES) {
                "Diagnostic archive exceeds ${MAX_ARCHIVE_BYTES / (1024 * 1024)} MiB"
            }
            check(partial.renameTo(output)) { "Could not finalize diagnostic archive" }
            output.parentFile?.listFiles { file -> file.isFile && file.name.endsWith(".zip") }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_BUNDLES)
                ?.forEach { it.delete() }
            output
        }.getOrElse {
            partial.delete()
            output.delete()
            throw IllegalStateException(it.message ?: "Could not create diagnostic archive", it)
        }
    }

    fun share(context: Context, bundle: File): Boolean {
        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bundle)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(send, "Share diagnostic bundle").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun boundedFiles(files: List<File>, maxCount: Int): List<File> = files
        .filter { Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) && it.canRead() }
        .sortedByDescending { it.lastModified() }
        .take(maxCount)

    private fun safeName(name: String): String = name
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
        .take(96)
        .ifEmpty { "diagnostic" }

    private fun manifestJson(
        versionName: String,
        versionCode: Int,
        gitSha: String,
        agentCount: Int,
        metricsCount: Int,
        nowMs: Long,
    ): String = """
        {
          "schema":"bmoe_diagnostic_bundle_v1",
          "created_at_ms":$nowMs,
          "app_version":"${jsonEscape(versionName)}",
          "version_code":$versionCode,
          "git_sha":"${jsonEscape(gitSha).take(64)}",
          "agent_log_count":$agentCount,
          "metrics_csv_count":$metricsCount,
          "privacy":"The manifest contains no device identifiers or absolute paths. Agent audit omits pasted log source text; exported CSV model paths are reduced to basenames. Requests, answers and network metadata may still be present. No model files are included."
        }
    """.trimIndent() + "\n"

    private fun jsonEscape(value: String): String = buildString {
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    private class LimitedOutputStream(
        private val delegate: OutputStream,
        private val limit: Long,
    ) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            ensureCapacity(1)
            delegate.write(value)
            written++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            ensureCapacity(length.toLong())
            delegate.write(bytes, offset, length)
            written += length
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun ensureCapacity(incoming: Long) {
            check(incoming <= limit - written) {
                "Diagnostic archive exceeds ${limit / (1024 * 1024)} MiB"
            }
        }
    }
    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    /** Agent JSONL is an audit, not a way to export local filesystem locations. */
    private fun putAgentLogFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        BufferedReader(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                val sanitized = LOCAL_PATH.replace(line, "[local path omitted]")
                zip.write(sanitized.toByteArray(StandardCharsets.UTF_8))
                zip.write('\n'.code)
            }
        }
        zip.closeEntry()
    }

    /** Metrics preambles contain the native model path; export only the useful basename. */
    private fun putMetricsFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        BufferedReader(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8)).useLines { lines ->
            lines.forEach { line ->
                val modelSanitized = MODEL_TOKEN.replace(line) { match ->
                    "${match.groupValues[1]}${basename(match.groupValues[2])}"
                }
                val sanitized = LOCAL_PATH.replace(modelSanitized, "[local path omitted]")
                zip.write(sanitized.toByteArray(StandardCharsets.UTF_8))
                zip.write('\n'.code)
            }
        }
        zip.closeEntry()
    }

    private fun basename(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
        .ifEmpty { "model" }

    private val MODEL_TOKEN = Regex("(^# model=)(.*?)(?= arch=|$)")
    private val LOCAL_PATH = Regex(
        "file:///[^\\s\\\"']*|(?<![:A-Za-z0-9._/-])(?:\\\\)?/(?:[^\\s\\\"']*)?|[A-Za-z]:\\\\(?:[^\\s\\\"']*)?",
    )
}

internal fun diagnosticBundleInputs(context: Context): DiagnosticBundle.Inputs {
    val root = context.getExternalFilesDir(null)
    if (root == null) return DiagnosticBundle.Inputs(emptyList(), emptyList())
    val agent = AgentLog.files(context)
    val metrics = File(root, "metrics").listFiles { file -> file.isFile && file.name.endsWith(".csv") }
        ?.toList().orEmpty()
    return DiagnosticBundle.Inputs(agent, metrics)
}