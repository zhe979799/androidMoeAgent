package io.bigmoeonedge.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentObservationTest {
    @Test
    fun parsesDetailedProgressIntoOneTokenSample() {
        val parser = TelemetryParser()
        assertTrue(parser.onLine(
            "BMOE_PROGRESS {\"step\":7,\"steps\":1024,\"wall_ms\":120.5," +
                "\"io_ms\":20.0,\"compute_ms\":80.0,\"mgmt_ms\":4.0,\"stall_ms\":12.0," +
                "\"read_mb\":3.25,\"cache_hit_pct\":81.5,\"majflt\":2," +
                "\"cpu_ms\":90.0,\"dense_resident_frac\":0.75," +
                "\"delta_reasoning\":\"think\",\"delta_text\":\"答\"}",
        ))
        val token = parser.latestToken!!
        assertEquals(7, token.step)
        assertEquals(1024, token.steps)
        assertEquals(3.25, token.readMiB, 0.001)
        assertEquals(0.75, token.denseResidentFrac, 0.001)
        assertEquals("答", token.text)
        assertEquals("think", token.reasoning)
    }

    @Test
    fun retainsLatest512TokensAndCountsDroppedHistory() {
        RunBus.resetAgentObservation(4096, 8192, 10L)
        repeat(513) { index ->
            RunBus.appendAgentToken(
                AgentTokenSample(
                    ordinal = index + 1,
                    generation = 1,
                    step = index + 1,
                    steps = 4096,
                    text = index.toString(),
                    reasoning = "",
                    wallMs = 10.0,
                    computeMs = 8.0,
                    ioMs = 1.0,
                    stallMs = 0.0,
                    mgmtMs = 1.0,
                    readMiB = 0.0,
                    cacheHitPct = 100.0,
                    majflt = 0.0,
                    cpuMs = 8.0,
                    denseResidentFrac = 1.0,
                ),
            )
        }
        val state = RunBus.state.value
        assertEquals(513, state.agentTokensSeen)
        assertEquals(512, state.agentTokens.size)
        assertEquals(1, state.agentTokensDropped)
        assertEquals(2, state.agentTokens.first().ordinal)
        assertEquals(4096, state.agentEffectiveTokens)
    }

    @Test
    fun stageLifecycleRecordsDurationAndStatus() {
        RunBus.resetAgentObservation(256, 4096, 100L)
        val id = RunBus.beginAgentStage(AgentStageKind.MODEL_GENERATION, "模型回合", 120L)
        RunBus.finishAgentStage(id, AgentStageStatus.COMPLETE, 450L, "完成", tokenEnd = 3)
        val stage = RunBus.state.value.agentStages.single()
        assertEquals(AgentStageStatus.COMPLETE, stage.status)
        assertEquals(120L, stage.startedAtMs)
        assertEquals(450L, stage.endedAtMs)
        assertEquals(3, stage.tokenEnd)
    }
}
