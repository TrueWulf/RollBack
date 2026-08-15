package com.rollback.command

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DurationParserTest {
    @Test
    fun parsesSupportedUnits() {
        assertEquals(30_000L, DurationParser.parse("30s"))
        assertEquals(300_000L, DurationParser.parse("5M"))
        assertEquals(3_600_000L, DurationParser.parse("1h"))
        assertEquals(86_400_000L, DurationParser.parse("1d"))
    }

    @Test
    fun rejectsInvalidOrOverflowingInput() {
        assertNull(DurationParser.parse(null))
        assertNull(DurationParser.parse("1x"))
        assertNull(DurationParser.parse("-1h"))
        assertNull(DurationParser.parse("999999999999999999999999h"))
    }
}
