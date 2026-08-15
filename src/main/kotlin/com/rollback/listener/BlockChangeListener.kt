package com.rollback.listener

import com.rollback.data.EventDatabase
import com.rollback.data.EventType
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent

class BlockChangeListener(private val database: EventDatabase) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!database.isLoggingEnabled(EventType.BLOCK)) return
        val block = event.block
        database.recordBlock(
            block.location,
            event.player.uniqueId,
            event.player.name,
            block.blockData.asString,
            "minecraft:air"
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!database.isLoggingEnabled(EventType.BLOCK)) return
        val block = event.blockPlaced
        database.recordBlock(
            block.location,
            event.player.uniqueId,
            event.player.name,
            event.blockReplacedState.blockData.asString,
            block.blockData.asString
        )
    }
}
