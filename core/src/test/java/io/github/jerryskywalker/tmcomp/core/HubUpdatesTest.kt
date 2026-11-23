package io.github.jerryskywalker.tmcomp.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HubUpdatesTest {
    @Test
    fun signalsOnlyForCompleteUsageFrames() {
        val input = (": hb\r\n\r\n" +
            "event: snapshot\r\ndata: ${"x".repeat(12_000)}\r\n\r\n" +
            "event: warning\ndata: ignored\n\n" +
            "event: stats\ndata: first line\ndata: second line\n\n" +
            "data: legacy default event\n\n" +
            "event: stats\ndata: incomplete").byteInputStream()
        var updates = 0
        readHubEvents(input, { true }) { updates++ }
        assertEquals(3, updates)
    }
}
