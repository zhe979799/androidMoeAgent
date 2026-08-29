package io.bigmoeonedge.example

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Explicitly opt-in script execution scoped to this app's private files directory. */
object ShellTools {
    private const val MAX_SCRIPT_CHARS = 8 * 1024
    private val BACKGROUND_PATTERN = Regex("(?m)(^|[^&])&([^&]|$)|\\b(nohup|setsid|disown)\\b")
    private const val MAX_OUTPUT_BYTES = 16 * 1024

    suspend fun execute(context: Context, args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val script = args.getString("script").trim()
        require(script.length in 1..MAX_SCRIPT_CHARS) { "script must be 1..$MAX_SCRIPT_CHARS characters" }
        require(!BACKGROUND_PATTERN.containsMatchIn(script)) { "background commands are not allowed" }
        val timeoutMs = args.optInt("timeout_ms", 10_000).also {
            require(it in 500..30_000) { "timeout_ms must be 500..30000" }
        }
        val workDir = context.filesDir.apply { if (!exists()) mkdirs() }
        val process = ProcessBuilder("/system/bin/sh", "-c", script)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply {
                environment().clear()
                environment()["PATH"] = "/system/bin:/system/xbin"
                environment()["HOME"] = workDir.absolutePath
            }
            .start()
        val cancellation = coroutineContext[Job]?.invokeOnCompletion { runCatching { process.destroyForcibly() } }
        val output = try {
            val outputTask = java.util.concurrent.FutureTask {
                process.inputStream.use { it.readBounded(MAX_OUTPUT_BYTES) }
            }
            Thread(outputTask, "bmoe-shell-output").apply { isDaemon = true }.start()
            val completed = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (!completed) process.destroyForcibly()
            val bytes = runCatching { outputTask.get(2, TimeUnit.SECONDS) }
                .getOrDefault(ByteArray(0) to false)
            JSONObject().apply {
                put("status", if (completed) "ok" else "timeout")
                put("exit_code", if (completed) process.exitValue() else JSONObject.NULL)
                put("working_directory", workDir.absolutePath)
                put("truncated", bytes.second)
                put("output", bytes.first.toString(Charsets.UTF_8))
            }
        } finally {
            cancellation?.dispose()
            if (process.isAlive) process.destroyForcibly()
        }
        output
    }

    private fun java.io.InputStream.readBounded(maxBytes: Int): Pair<ByteArray, Boolean> {
        val out = ByteArray(maxBytes)
        var used = 0
        while (used < maxBytes) {
            val count = read(out, used, maxBytes - used)
            if (count < 0) break
            used += count
        }
        val truncated = used == maxBytes && available() > 0
        return out.copyOf(used) to truncated
    }
}
