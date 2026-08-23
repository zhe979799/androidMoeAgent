package io.bigmoeonedge.example

import java.util.concurrent.atomic.AtomicLong

enum class AgentStageKind {
    PREPARING,
    MODEL_GENERATION,
    TOOL_CALL,
    COMPACTION,
    FINALIZING,
}

enum class AgentStageStatus { ACTIVE, COMPLETE, CANCELLED, FAILED }

data class AgentStageRecord(
    val id: Long,
    val kind: AgentStageKind,
    val title: String,
    val status: AgentStageStatus,
    val startedAtMs: Long,
    val endedAtMs: Long? = null,
    val tokenStart: Int = 0,
    val tokenEnd: Int = 0,
    val detail: String = "",
)

data class AgentTokenSample(
    val ordinal: Int,
    val generation: Int,
    val step: Int,
    val steps: Int,
    val text: String,
    val reasoning: String,
    val wallMs: Double,
    val computeMs: Double,
    val ioMs: Double,
    val stallMs: Double,
    val mgmtMs: Double,
    val readMiB: Double,
    val cacheHitPct: Double,
    val majflt: Double,
    val cpuMs: Double,
    val denseResidentFrac: Double,
) {
    val tokensPerSecond: Double get() = if (wallMs > 0.0) 1000.0 / wallMs else 0.0
    val displayText: String
        get() = when {
            text.isNotBlank() && reasoning.isNotBlank() -> "think:$reasoning | text:$text"
            text.isNotBlank() -> text
            reasoning.isNotBlank() -> "think:$reasoning"
            else -> ""
        }.replace("\n", "\\n")
}

object AgentObservation {
    const val TOKEN_RETENTION_LIMIT = 512
    private val stageIds = AtomicLong(1)

    fun nextStageId(): Long = stageIds.getAndIncrement()
}
