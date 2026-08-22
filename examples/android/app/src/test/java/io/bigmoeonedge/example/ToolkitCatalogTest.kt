package io.bigmoeonedge.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolkitCatalogTest {
    @Test
    fun catalogCombinesSelectedToolSetsAndRejectsUnknownIds() {
        val tools = ToolkitCatalog.toolsFor(setOf("device", "unknown"))
        assertTrue("device_info" in tools)
        assertTrue("battery_state" in tools)
        assertTrue("network_state" !in tools)
        assertTrue("runtime_metrics" !in tools)
        assertTrue("memory_state" in tools)
        assertTrue("network_capabilities" !in tools)
        assertTrue("read_selected_log" !in ToolkitCatalog.toolsFor(setOf("logs"), false))
        assertTrue("read_selected_log" in ToolkitCatalog.toolsFor(setOf("logs"), true))
        assertEquals(setOf("device"), ToolkitCatalog.definitionsFor(setOf("device", "unknown")).map { it.id }.toSet())
    }

    @Test
    fun everyRegisteredToolBelongsToTheAgentRegistry() {
        assertTrue(ToolkitCatalog.allTools.all { it in NetworkTools.names })
        assertEquals(ToolkitCatalog.allIds, ToolkitCatalog.entries.map { it.id }.toSet())
    }

    @Test
    fun protocolRejectsCallsOutsideSelectedToolkit() {
        val call = """{"tool_call":{"name":"device_info","arguments":{}}}"""
        assertTrue(NetworkAgentProtocol.parseToolCall(call, setOf("device_info")) != null)
        assertTrue(NetworkAgentProtocol.parseToolCall(call, setOf("network_state")) == null)
        assertTrue(NetworkAgentProtocol.parseToolCall(
            """{"tool_call":{"name":"runtime_metrics","arguments":{}}}""",
            setOf("runtime_metrics"),
        ) != null)
        assertTrue(NetworkAgentProtocol.parseToolCall(call, emptySet()) == null)
        assertTrue(!NetworkAgentProtocol.initialPrompt("test", emptySet()).contains("device_info"))
        assertTrue(NetworkAgentProtocol.parseToolCall(
            """{"tool_call":{"name":"http_probe","arguments":{"url":"https://example.com"}}}""",
            ToolkitCatalog.toolsFor(setOf("network", "logs"), true) - NetworkTools.networkToolNames,
        ) == null)
    }
}
