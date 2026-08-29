package io.bigmoeonedge.example

import android.content.Context
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Bounded per-generation trace for diagnosing prompt/answer mismatches on the device. */
internal class InferenceLog private constructor(
    private val file: File,
    private val startedAtMs: Long,
) {
    companion object {
        private const val MAX_FILES = 30
        private const val MAX_FIELD_CHARS = 128 * 1024
        private const val MAX_ANSWER_CHARS = 64 * 1024

        fun start(
            context: Context,
            model: String,
            prompt: String,
            messagesJson: String?,
            toolsJson: String?,
            chatTemplateKwargsJson: String?,
            nPredict: Int,
            think: Boolean,
            clearKv: Boolean,
            rawPrompt: Boolean,
            reusePromptPrefix: Boolean,
            displayPrompt: String?,
            suppressTranscript: Boolean,
        ): InferenceLog? = runCatching {
            val root = context.getExternalFilesDir(null) ?: return null
            val dir = File(root, "inference-logs")
            check(dir.isDirectory || dir.mkdirs()) { "Could not create inference log directory" }
            val now = System.currentTimeMillis()
            val log = InferenceLog(File(dir, "inference-$now.jsonl"), now)
            log.append(JSONObject().apply {
                put("schema", "bmoe_inference_trace_v1")
                put("event", "start")
                put("time_ms", now)
                put("model", File(model).name)
                put("mode", when {
                    messagesJson?.isNotBlank() == true -> "structured"
                    suppressTranscript -> "agent_control"
                    else -> "chat"
                })
                put("prompt", bounded(prompt))
                put("display_prompt", displayPrompt?.let(::bounded) ?: JSONObject.NULL)
                put("messages_json", messagesJson?.let(::bounded) ?: JSONObject.NULL)
                put("tools_json", toolsJson?.let(::bounded) ?: JSONObject.NULL)
                put("chat_template_kwargs_json", chatTemplateKwargsJson?.let(::bounded) ?: JSONObject.NULL)
                put("n_predict", nPredict)
                put("think", think)
                put("clear_kv", clearKv)
                put("raw_prompt", rawPrompt)
                put("reuse_prompt_prefix", reusePromptPrefix)
                put("suppress_transcript", suppressTranscript)
            })
            files(dir).drop(MAX_FILES).forEach { it.delete() }
            log
        }.getOrNull()

        fun files(context: Context): List<File> = context.getExternalFilesDir(null)
            ?.let { files(File(it, "inference-logs")) }
            .orEmpty()

        private fun files(dir: File): List<File> = dir.listFiles { f ->
            f.isFile && f.name.startsWith("inference-") && f.name.endsWith(".jsonl")
        }?.sortedByDescending { it.name }.orEmpty()

        private fun bounded(value: String, limit: Int = MAX_FIELD_CHARS): String =
            if (value.length <= limit) value else value.take(limit) + "\n[truncated]"
    }

    fun finish(
        status: String,
        answer: String,
        reasoning: String,
        renderedPrompt: String,
        toolCallsJson: String,
        summary: String,
        metricsJson: String,
        error: String? = null,
    ) {
        append(JSONObject().apply {
            put("event", "finish")
            put("time_ms", System.currentTimeMillis())
            put("elapsed_ms", System.currentTimeMillis() - startedAtMs)
            put("status", status)
            put("answer", bounded(answer, MAX_ANSWER_CHARS))
            put("reasoning", bounded(reasoning, MAX_ANSWER_CHARS))
            put("rendered_prompt", bounded(renderedPrompt))
            put("tool_calls_json", bounded(toolCallsJson))
            put("summary", bounded(summary, 16 * 1024))
            put("metrics_json", bounded(metricsJson, 16 * 1024))
            error?.let { put("error", bounded(it, 16 * 1024)) }
        })
    }

    private fun append(value: JSONObject) {
        runCatching {
            BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), StandardCharsets.UTF_8)).use {
                it.append(value.toString()).append('\n')
            }
        }
    }
}
