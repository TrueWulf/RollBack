package com.rollback.command

import com.rollback.data.EventType
import com.rollback.data.QueryScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RollbackArgumentsParserTest {
    @Test
    fun parsesPositionalArguments() {
        val parsed = RollbackArgumentsParser.parse(arrayOf("rollback", "1h", "50", "Steve", "BLOCK"))

        assertEquals(3_600_000L, parsed?.duration)
        assertEquals(50, parsed?.radius)
        assertEquals("Steve", parsed?.actor)
        assertEquals(EventType.BLOCK, parsed?.type)
        assertTrue(parsed?.preview == false)
    }

    @Test
    fun parsesExplicitOptions() {
        val parsed = RollbackArgumentsParser.parse(
            arrayOf("lookup", "--time=5m", "--radius=25", "--player=Alex", "--type=craft", "--preview")
        )

        assertEquals(300_000L, parsed?.duration)
        assertEquals(25, parsed?.radius)
        assertEquals("Alex", parsed?.actor)
        assertEquals(EventType.CRAFT, parsed?.type)
        assertTrue(parsed?.preview == true)
    }

    @Test
    fun rejectsUnknownOptionsAndInvalidValues() {
        assertEquals(null, RollbackArgumentsParser.parse(arrayOf("rollback", "1h", "--wat=1")))
        assertTrue(RollbackArgumentsParser.parse(arrayOf("rollback", "--time=1h", "--preview=false"))?.preview == false)
        assertEquals(null, RollbackArgumentsParser.parse(arrayOf("rollback", "--time=1h", "--radius=nope")))
    }

    @Test
    fun parsesLookupScopes() {
        assertEquals(QueryScope.CHUNK, RollbackArgumentsParser.parse(arrayOf("lookup", "10m", "--scope=chunk"))?.scope)
        assertEquals(QueryScope.LOOKING, RollbackArgumentsParser.parse(arrayOf("lookup", "10m", "--looking"))?.scope)
        assertEquals(12, RollbackArgumentsParser.parse(arrayOf("lookup", "10m", "--near=12"))?.radius)
        assertEquals(QueryScope.LOOKING, RollbackArgumentsParser.parse(arrayOf("lookup", "10m", "TrueWulf", "BLOCK"))?.scope)
        assertEquals(QueryScope.RADIUS, RollbackArgumentsParser.parse(arrayOf("lookup", "10m", "50", "TrueWulf", "BLOCK"))?.scope)
    }

    @Test
    fun defaultsLookupToTargetBlock() {
        val parsed = RollbackArgumentsParser.parse(arrayOf("lookup"), defaultDuration = 600_000L)

        assertEquals(600_000L, parsed?.duration)
        assertEquals(QueryScope.LOOKING, parsed?.scope)
    }

    @Test
    fun supportsConfiguredDefaultScope() {
        assertEquals(
            QueryScope.CHUNK,
            RollbackArgumentsParser.parse(arrayOf("lookup"), defaultDuration = 600_000L, defaultScope = QueryScope.CHUNK)?.scope,
        )
    }

    @Test
    fun parsesLookupPage() {
        assertEquals(3, RollbackArgumentsParser.parse(arrayOf("lookup", "10m", "--page=3"))?.page)
        assertEquals(2, RollbackArgumentsParser.parse(arrayOf("lookup", "10m", "Steve", "BLOCK", "2"))?.page)
    }

    @Test
    fun parsesCoreProtectStyleFilters() {
        val parsed = RollbackArgumentsParser.parse(
            arrayOf("lookup", "1h", "--world=world_nether", "--include=diamond", "--action=-BLOCK", "--count")
        )

        assertEquals("world_nether", parsed?.world)
        assertEquals("diamond", parsed?.include)
        assertEquals("-BLOCK", parsed?.action)
        assertTrue(parsed?.countOnly == true)
    }

    @Test
    fun parsesMultipleActorsAndSelectionScope() {
        val parsed = RollbackArgumentsParser.parse(
            arrayOf("lookup", "1h", "--player=Steve,Alex", "--scope=selection")
        )

        assertEquals(listOf("Steve", "Alex"), parsed?.actors)
        assertEquals(QueryScope.SELECTION, parsed?.scope)
    }

    @Test
    fun rejectsConflictingScopes() {
        assertEquals(null, RollbackArgumentsParser.parse(arrayOf("lookup", "1h", "--scope=selection", "--chunk")))
    }
}
