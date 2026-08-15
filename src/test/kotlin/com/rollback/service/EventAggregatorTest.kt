package com.rollback.service

import com.rollback.data.EventType
import com.rollback.data.RollbackEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventAggregatorTest {
    @Test
    fun combinesNearbySameItemActions() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event(now, "DIAMONDx64", 2),
            event(now - 1_000, "DIAMONDx64", 1),
        )

        val result = EventAggregator.aggregate(events, 5_000)

        assertEquals(1, result.size)
        assertEquals("action=take;item=DIAMONDx128;aggregated=2;count=128", result.single().metadata)
    }

    @Test
    fun combinesInterleavedActionsWithinOneActivitySession() {
        val now = System.currentTimeMillis()
        val events = listOf(
            event(now, "DIAMONDx1", 3, "session-a"),
            event(now - 60_000, "COBBLESTONEx1", 2, "session-a"),
            event(now - 120_000, "DIAMONDx1", 1, "session-a"),
        )

        val result = EventAggregator.aggregate(events, 5_000)

        assertEquals(2, result.size)
        assertEquals("DIAMONDx2", result.first { it.metadata?.contains("item=DIAMOND") == true }.metadata?.substringAfter("item=")?.substringBefore(';'))
    }

    private fun event(timestamp: Long, item: String, id: Long, session: String? = null) = RollbackEvent(
        id = id,
        timestamp = timestamp,
        type = EventType.INVENTORY,
        world = "world",
        x = 1,
        y = 64,
        z = 1,
        actor = null,
        actorName = "Steve",
        subject = null,
        beforeState = null,
        afterState = null,
        metadata = "action=take;item=$item${session?.let { ";session=$it" } ?: ""}",
    )
}
