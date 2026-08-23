package io.bigmoeonedge.example

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MetricsSelectionTest {
    @Test
    fun togglesOnlyTheRequestedAgentLog() {
        val first = File("agent-1.jsonl")
        val second = File("agent-2.jsonl")
        assertEquals(listOf(first), toggleSelectedFile(emptyList(), first))
        assertEquals(listOf(first, second), toggleSelectedFile(listOf(first), second))
        assertEquals(listOf(second), toggleSelectedFile(listOf(first, second), first))
        assertEquals(emptyList<File>(), toggleSelectedFile(listOf(second), second))
    }
}
