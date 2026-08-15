package com.rollback.service

import com.rollback.command.RollbackArguments
import com.rollback.data.EventDatabase
import com.rollback.data.EventPage
import com.rollback.data.DatabaseSelection
import com.rollback.data.RollbackEvent
import com.rollback.data.QueryScope
import org.bukkit.Location

class RollbackQueryService(private val database: EventDatabase) {
    fun find(arguments: RollbackArguments, center: Location?, target: Location? = null, limit: Int, selection: SelectionBounds? = null): List<RollbackEvent> {
        return findPage(arguments, center, target, limit, selection).events
    }

    fun findPage(arguments: RollbackArguments, center: Location?, target: Location? = null, limit: Int = database.queryPageSize(), selection: SelectionBounds? = null): EventPage {
        if (database.shouldFlushBeforeQuery()) database.awaitWrites(database.flushTimeoutMs())
        val raw = database.findPage(
            since = System.currentTimeMillis() - arguments.duration,
            type = arguments.type,
            actorName = arguments.actor,
            actorNames = arguments.actors,
            world = arguments.world ?: center?.world?.name,
            include = arguments.include,
            action = arguments.action,
            limit = if (limit == database.queryPageSize()) database.lookupLimit() else limit,
            page = 1,
            radius = when (arguments.scope) {
                QueryScope.RADIUS -> arguments.radius ?: database.defaultRadius()
                QueryScope.CHUNK -> 15
                QueryScope.LOOKING -> 0
                QueryScope.SELECTION -> null
            },
            chunk = arguments.scope == QueryScope.CHUNK,
            center = when (arguments.scope) {
                QueryScope.RADIUS -> center
                QueryScope.CHUNK -> center?.let { Location(it.world, ((it.blockX shr 4) * 16).toDouble(), it.y, ((it.blockZ shr 4) * 16).toDouble()) }
                QueryScope.LOOKING -> target
                QueryScope.SELECTION -> center
            },
            selection = selection?.let { DatabaseSelection(it.world, it.minX, it.minY, it.minZ, it.maxX, it.maxY, it.maxZ) },
        )
        if (limit != database.queryPageSize()) return raw
        val aggregated = if (database.aggregationEnabled()) {
            EventAggregator.aggregate(raw.events, database.aggregationWindowMs(), database.aggregateOnlySameContainer())
        } else raw.events
        val pageSize = database.queryPageSize()
        val pageStart = ((arguments.page - 1).coerceAtMost(Int.MAX_VALUE / pageSize)) * pageSize
        return EventPage(
            events = aggregated.drop(pageStart).take(pageSize),
            total = aggregated.size,
            page = arguments.page,
            pageSize = pageSize,
        )
    }
}
