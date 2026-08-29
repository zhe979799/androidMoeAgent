package io.bigmoeonedge.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunConfigTest {
    @Test
    fun emptyContextDoesNotInjectAnOutputFormat() {
        assertEquals("", AgentContext().promptPrefix())
        assertEquals("", AgentContext(outputFormat = "").promptPrefix())
    }

    @Test
    fun configuredContextOnlyAddsTheFieldsThatWereProvided() {
        val prefix = AgentContext(goal = "列出应用文件").promptPrefix()
        assertTrue(prefix.contains("列出应用文件"))
        assertTrue(!prefix.contains("已知信息"))
        assertTrue(!prefix.contains("限制条件"))
        assertTrue(!prefix.contains("输出格式"))
    }

    @Test
    fun cliInstallIsOffUntilBothNetworkAndInstallPermissionAreEnabled() {
        val defaultTools = AgentPolicy().filterTools(setOf("cli_catalog", "install_cli", "remove_cli"))
        assertTrue("cli_catalog" in defaultTools)
        assertTrue("install_cli" !in defaultTools)
        assertTrue("remove_cli" !in defaultTools)
        val enabled = AgentPolicy(allowNetwork = true, allowCliInstall = true)
            .filterTools(setOf("cli_catalog", "install_cli", "remove_cli"))
        assertEquals(setOf("cli_catalog", "install_cli", "remove_cli"), enabled)
    }
    @Test
    fun answerOnlyModeNeverExposesTools() {
        val policy = AgentPolicy(mode = AgentMode.ANSWER_ONLY)
        assertTrue(policy.filterTools(NetworkTools.names).isEmpty())
        assertEquals(0, policy.effectiveRounds())
    }

    @Test
    fun policyKeepsExplicitlyEnabledCapabilitiesAvailable() {
        val policy = AgentPolicy(allowNetwork = true, allowWebSearch = true, allowLogs = true, allowScripts = true)
        val tools = policy.filterTools(NetworkTools.names)
        assertTrue("network_state" in tools)
        assertTrue("search_bing" in tools)
        assertTrue("read_selected_log" in tools)
        assertTrue("run_script" in tools)
    }

    @Test
    fun builtInTemplatesStayWithinTheRegisteredToolPolicy() {
        assertTrue(AgentRunPreferences.builtInTemplates.size >= 4)
        AgentRunPreferences.builtInTemplates.forEach { template ->
            assertTrue(template.config.policy.effectiveRounds() in 1..5)
            assertTrue(template.config.policy.filterTools(NetworkTools.names).all { it in NetworkTools.names })
        }
    }
}
