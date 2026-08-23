package io.bigmoeonedge.example

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where the engine is in its lifecycle. Unlike the old per-run boolean pair, a session process
 * outlives a single generation: it loads once (LOADING), then sits READY between prompts, and
 * flips to GENERATING only while a prompt is being answered.
 */
enum class EngineState { IDLE, LOADING, READY, GENERATING, ERROR }

/**
 * One committed message in the multi-turn transcript. metrics is a compact per-turn line.
 * reasoning is the model's thinking span (assistant turns only), shown as a collapsible block
 * above the answer; empty when the model did not reason.
 */
data class ChatTurn(val role: String, val text: String, val metrics: String = "", val reasoning: String = "")

/** Immutable snapshot of the session + current generation, observed by the Compose UI. */
data class UiState(
    val state: EngineState = EngineState.IDLE,
    val telemetry: Telemetry = Telemetry(),
    val answer: String = "",
    val reasoning: String = "",      // in-flight thinking span; streams before the answer while the model reasons
    val summary: String = "",
    val error: String? = null,
    val ioMode: String? = null,     // effective read mode reported by the engine (direct / buffered)
    val cpuTempC: Double? = null,   // SoC/CPU temperature (°C), sampled while generating (battery fallback)
    val sessionSig: String? = null, // signature of the loaded session (AppSettings.sessionSignature)
    val arch: String? = null,       // native model architecture reported by BMOE_READY
    // How the loaded model can honour "Thinking off", reported once at BMOE_READY: "template" (its
    // chat template reads the flag), "prefill" (it does not, so the engine closes the reasoning span
    // in the prompt), or "none" (neither — the model always reasons, and the switch is hidden rather
    // than left there doing nothing). Null until a session reports it. See docs/telemetry.md.
    val thinkControl: String? = null,
    // Experts the loaded model routes per token, from BMOE_READY. Null = nothing loaded yet,
    // 0 = not MoE. Settings needs it because "Drop cold experts" is a fraction of 1/top-k, so the
    // same percentage means something very different on a narrow routing.
    val nExpertUsed: Int? = null,
    val transcript: List<ChatTurn> = emptyList(), // committed turns; the in-flight answer is `answer`
    val agentTranscript: List<ChatTurn> = emptyList(), // Agent-only visible turns; never mixed with ordinary chat
    val streaming: Boolean = true,  // is the loaded session using the MoE streamer (vs mmap baseline)?
    // Incremented only after BMOE_DONE was processed. The foreground network-agent coordinator
    // waits on this rather than guessing from READY, which can also mean a model just finished loading.
    val generationId: Long = 0,
    val lastCompletedText: String = "",
    // OpenAI-compatible tool calls emitted by a structured chat template, kept separate from the
    // visible answer because GPT-OSS Harmony tool turns can have empty final content.
    val lastCompletedToolCalls: String = "[]",
    // Agent control prompts are deliberately hidden from the ordinary transcript. They also must
    // not become an invisible continuation context for the next ordinary chat turn.
    val clearKvOnNextPrompt: Boolean = false,
    // Network-analysis is a foreground-only, bounded loop. Tool data stays separate from chat text
    // so a model-produced control JSON is never rendered as an assistant answer.
    val agentActive: Boolean = false,
    val agentStatus: String? = null,
    val agentError: String? = null,
    val agentTools: List<AgentToolRecord> = emptyList(),
    val agentAllowedTools: Set<String> = emptySet(),
    val agentPromptPreview: String = "",
    val agentCompactions: Int = 0,
    val agentRunId: Long = 0,
    val agentRunStartedAtMs: Long = 0,
    val agentStages: List<AgentStageRecord> = emptyList(),
    val agentTokens: List<AgentTokenSample> = emptyList(),
    val agentTokensSeen: Int = 0,
    val agentTokensDropped: Int = 0,
    val agentRequestedTokens: Int = 256,
    val agentEffectiveTokens: Int = 0,
    val agentPromptTokens: Int = 0,
    val agentContextTokens: Int = 0,
    val agentContextUsedTokens: Int = 0,
    val agentBudgetClamped: Boolean = false,
) {
    val loading get() = state == EngineState.LOADING
    val generating get() = state == EngineState.GENERATING
    val ready get() = state == EngineState.READY
    val busy get() = state == EngineState.LOADING || state == EngineState.GENERATING
}

/**
 * Single source of truth shared between the RunService (writer) and the UI (reader). The service
 * pushes updates as the session process reports progress; the UI collects the StateFlow. One
 * session at a time, one generation at a time within it, so a single flow is enough.
 */
object RunBus {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Reset the per-generation fields for a new prompt, preserving session state and signature. */
    fun resetGeneration() = _state.update {
        it.copy(telemetry = Telemetry(), answer = "", reasoning = "", summary = "", error = null,
            lastCompletedText = "", lastCompletedToolCalls = "[]", clearKvOnNextPrompt = false)
    }

    fun resetAgentObservation(requestedTokens: Int, contextTokens: Int, nowMs: Long) = _state.update {
        it.copy(
            agentRunStartedAtMs = nowMs,
            agentStages = emptyList(),
            agentTokens = emptyList(),
            agentTokensSeen = 0,
            agentTokensDropped = 0,
            agentRequestedTokens = requestedTokens,
            agentEffectiveTokens = 0,
            agentPromptTokens = 0,
            agentContextTokens = contextTokens,
            agentContextUsedTokens = 0,
            agentBudgetClamped = false,
        )
    }

    fun beginAgentStage(kind: AgentStageKind, title: String, nowMs: Long, detail: String = ""): Long {
        val id = AgentObservation.nextStageId()
        _state.update {
            it.copy(agentStages = it.agentStages + AgentStageRecord(
                id,
                kind,
                title,
                AgentStageStatus.ACTIVE,
                nowMs,
                tokenStart = it.agentTokensSeen + 1,
                detail = detail,
            ))
        }
        return id
    }

    fun updateAgentStage(id: Long, detail: String, tokenEnd: Int? = null) = _state.update {
        it.copy(agentStages = it.agentStages.map { stage ->
            if (stage.id == id) stage.copy(detail = detail, tokenEnd = tokenEnd ?: stage.tokenEnd) else stage
        })
    }

    fun updateActiveAgentStage(kind: AgentStageKind, detail: String, tokenEnd: Int? = null) = _state.update {
        val active = it.agentStages.indexOfLast { stage -> stage.kind == kind && stage.status == AgentStageStatus.ACTIVE }
        if (active < 0) it else it.copy(agentStages = it.agentStages.toMutableList().also { stages ->
            val stage = stages[active]
            stages[active] = stage.copy(detail = detail, tokenEnd = tokenEnd ?: stage.tokenEnd)
        })
    }

    fun updateLatestActiveAgentStage(detail: String, tokenEnd: Int? = null) = _state.update {
        val active = it.agentStages.indexOfLast { stage -> stage.status == AgentStageStatus.ACTIVE }
        if (active < 0) it else it.copy(agentStages = it.agentStages.toMutableList().also { stages ->
            val stage = stages[active]
            stages[active] = stage.copy(detail = detail, tokenEnd = tokenEnd ?: stage.tokenEnd)
        })
    }

    fun finishAgentStage(id: Long, status: AgentStageStatus, nowMs: Long, detail: String = "", tokenEnd: Int? = null) =
        _state.update {
            it.copy(agentStages = it.agentStages.map { stage ->
                if (stage.id == id) stage.copy(
                    status = status,
                    endedAtMs = nowMs,
                    detail = detail.ifBlank { stage.detail },
                    tokenEnd = tokenEnd ?: stage.tokenEnd,
                ) else stage
            })
        }

    fun finishLatestAgentStage(kind: AgentStageKind, status: AgentStageStatus, nowMs: Long, detail: String = "") =
        _state.update {
            val index = it.agentStages.indexOfLast { stage -> stage.kind == kind && stage.status == AgentStageStatus.ACTIVE }
            if (index < 0) it else it.copy(agentStages = it.agentStages.toMutableList().also { stages ->
                val stage = stages[index]
                stages[index] = stage.copy(status = status, endedAtMs = nowMs, detail = detail.ifBlank { stage.detail })
            })
        }

    fun finishActiveAgentStages(status: AgentStageStatus, nowMs: Long, detail: String) = _state.update {
        it.copy(agentStages = it.agentStages.map { stage ->
            if (stage.status == AgentStageStatus.ACTIVE) stage.copy(status = status, endedAtMs = nowMs, detail = detail) else stage
        })
    }

    fun appendAgentToken(sample: AgentTokenSample, stageDetail: String? = null) = _state.update {
        val retained = (it.agentTokens + sample).takeLast(AgentObservation.TOKEN_RETENTION_LIMIT)
        val stages = if (stageDetail == null) it.agentStages else {
            val index = it.agentStages.indexOfLast { stage -> stage.status == AgentStageStatus.ACTIVE }
            if (index < 0) it.agentStages else it.agentStages.toMutableList().also { mutableStages ->
                mutableStages[index] = mutableStages[index].copy(
                    detail = stageDetail,
                    tokenEnd = it.agentTokensSeen + 1,
                )
            }
        }
        it.copy(
            agentTokens = retained,
            agentStages = stages,
            agentTokensSeen = it.agentTokensSeen + 1,
            agentTokensDropped = (it.agentTokensSeen + 1 - retained.size).coerceAtLeast(0),
            agentEffectiveTokens = sample.steps,
            agentPromptTokens = it.agentPromptTokens,
            agentBudgetClamped = it.agentRequestedTokens > 0 && sample.steps < it.agentRequestedTokens,
        )
    }

    fun updateAgentBudget(promptTokens: Int, effectiveTokens: Int, contextUsedTokens: Int = 0) = _state.update {
        it.copy(
            agentPromptTokens = promptTokens,
            agentEffectiveTokens = effectiveTokens,
            agentContextUsedTokens = if (contextUsedTokens > 0) contextUsedTokens else it.agentContextUsedTokens,
            agentBudgetClamped = it.agentRequestedTokens > 0 && effectiveTokens < it.agentRequestedTokens,
        )
    }

    fun update(block: (UiState) -> UiState) = _state.update(block)
}
