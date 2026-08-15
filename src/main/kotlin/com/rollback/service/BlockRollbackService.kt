package com.rollback.service

import com.rollback.RollBackPlugin
import com.rollback.data.EventDatabase
import com.rollback.data.EventType
import com.rollback.data.RollbackEvent
import com.rollback.platform.ServerScheduler
import com.rollback.ui.RollbackColors
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections

class BlockRollbackService(
    private val plugin: RollBackPlugin,
    private val database: EventDatabase,
    private val scheduler: ServerScheduler,
) {
    fun restore(sender: CommandSender, events: List<RollbackEvent>) {
        val blocks = events.filter { it.type == EventType.BLOCK && it.world != null && it.x != null && it.beforeState != null && it.afterState != null }
        if (blocks.isEmpty()) {
            RollbackColors.sendHint(sender, "No block changes are available to restore.")
            return
        }
        val grouped = blocks.groupBy { "${it.world}:${it.x}:${it.y}:${it.z}" }
        val pending = AtomicInteger(grouped.size)
        val restored = AtomicInteger(0)
        grouped.values.forEach { locationEvents ->
            val event = locationEvents.first()
            val world = Bukkit.getWorld(event.world!!) ?: run { pending.decrementAndGet(); return@forEach }
            val location = Location(world, event.x!!.toDouble(), event.y!!.toDouble(), event.z!!.toDouble())
            scheduler.runAt(location) {
                try {
                    database.setSuppressed(true)
                    val block = world.getBlockAt(event.x!!, event.y!!, event.z!!)
                    locationEvents.sortedBy { it.timestamp }.forEach { change ->
                        if (block.blockData.asString == change.beforeState) {
                            block.setBlockData(Bukkit.createBlockData(change.afterState!!), plugin.config.getBoolean("rollback.rollback-physics", false))
                            restored.incrementAndGet()
                        }
                    }
                } finally {
                    database.setSuppressed(false)
                    pending.decrementAndGet()
                }
            }
        }
        RollbackColors.sendSuccess(sender, "Restore queued: ${grouped.size} block locations.")
    }

    fun apply(sender: CommandSender, events: List<RollbackEvent>) {
        if (!plugin.config.getBoolean("rollback.apply-blocks", true)) {
            RollbackColors.sendHint(sender, "Откат блоков отключён в конфигурации.")
            return
        }
        val blocks = events.filter { it.type == EventType.BLOCK && it.world != null && it.x != null && it.beforeState != null && it.afterState != null }
        if (blocks.isEmpty()) {
            RollbackColors.sendHint(sender, "Безопасных блоковых изменений для отката не найдено.")
            return
        }

        val operationId = database.createRollbackOperation(sender.name, blocks)
        if (operationId <= 0) {
            RollbackColors.sendError(sender, "Не удалось создать операцию отката.")
            return
        }
        val grouped = blocks.groupBy { "${it.world}:${it.x}:${it.y}:${it.z}" }
        val missingWorlds = AtomicInteger(0)
        val conflictLocations = Collections.synchronizedList(mutableListOf<String>())
        val errorLocations = Collections.synchronizedList(mutableListOf<String>())
        val appliedEvents = AtomicInteger(0)
        val locations = grouped.values.mapNotNull { locationEvents ->
            val event = locationEvents.first()
            val world = Bukkit.getWorld(event.world!!) ?: run {
                missingWorlds.incrementAndGet()
                return@mapNotNull null
            }
            Triple(world, Location(world, event.x!!.toDouble(), event.y!!.toDouble(), event.z!!.toDouble()), locationEvents)
        }
        val pending = AtomicInteger(locations.size)
        fun finishLocation() {
            if (pending.decrementAndGet() == 0) {
                database.markOperationReady(operationId)
                sendRollbackReport(sender, operationId, appliedEvents.get(), conflictLocations, missingWorlds.get(), errorLocations)
            }
        }
        locations.forEach { (world, location, locationEvents) ->
            val event = locationEvents.first()
            runCatching {
                scheduler.runAt(location) {
                    try {
                            database.setSuppressed(true)
                            try {
                                val block = world.getBlockAt(event.x!!, event.y!!, event.z!!)
                                val changes = locationEvents.sortedByDescending { it.timestamp }
                                if (block.blockData.asString != changes.first().afterState) {
                                    conflictLocations += locationKey(event)
                                    return@runAt
                                }
                                val applied = mutableListOf<Long>()
                                for (change in changes) {
                                    if (block.blockData.asString != change.afterState) {
                                        conflictLocations += locationKey(event)
                                        break
                                    }
                                    val result = runCatching {
                                    block.setBlockData(
                                        Bukkit.createBlockData(change.beforeState!!),
                                        plugin.config.getBoolean("rollback.rollback-physics", false)
                                    )
                                }
                                    if (result.isSuccess) {
                                        applied += change.id
                                    } else {
                                        errorLocations += locationKey(event)
                                        result.onFailure {
                                        plugin.logger.warning("Cannot rollback ${change.world}:${change.x},${change.y},${change.z}: ${it.message}")
                                    }
                                    break
                                }
                                }
                                appliedEvents.addAndGet(applied.size)
                                database.markEventsApplied(operationId, applied)
                        } finally {
                            database.setSuppressed(false)
                        }
                    } finally {
                        finishLocation()
                    }
                }
            }.onFailure { failure ->
                plugin.logger.warning("Cannot schedule rollback operation #$operationId: ${failure.message}")
                errorLocations += locationKey(event)
                finishLocation()
            }
        }
        if (locations.isEmpty()) {
            database.markOperationReady(operationId)
            sendRollbackReport(sender, operationId, 0, conflictLocations, missingWorlds.get(), errorLocations)
        }
    }

    fun undo(sender: CommandSender, operation: Pair<Long, List<RollbackEvent>>?) {
        if (operation == null) {
            RollbackColors.sendHint(sender, "Нет операции для отмены.")
            return
        }
        val (id, events) = operation
        val blocks = events.filter { it.type == EventType.BLOCK && it.world != null && it.x != null && it.beforeState != null && it.afterState != null }
        if (blocks.isEmpty()) {
            database.markOperationUndone(id)
            RollbackColors.sendHint(sender, "Операция #$id не содержит восстанавливаемых блоков.")
            return
        }

        val grouped = blocks.groupBy { "${it.world}:${it.x}:${it.y}:${it.z}" }
        val locations = grouped.values.mapNotNull { locationEvents ->
            val event = locationEvents.first()
            val world = Bukkit.getWorld(event.world!!) ?: return@mapNotNull null
            Triple(world, Location(world, event.x!!.toDouble(), event.y!!.toDouble(), event.z!!.toDouble()), locationEvents)
        }
        val pending = AtomicInteger(locations.size)
        val failed = AtomicBoolean(locations.size != grouped.size)
        locations.forEach { (world, location, locationEvents) ->
            try {
                scheduler.runAt(location) {
                    try {
                        val event = locationEvents.first()
                        database.setSuppressed(true)
                        try {
                            val block = world.getBlockAt(event.x!!, event.y!!, event.z!!)
                            val changes = locationEvents.sortedBy { it.timestamp }
                            if (block.blockData.asString != changes.first().beforeState) {
                                failed.set(true)
                                return@runAt
                            }
                            for (change in changes) {
                                if (block.blockData.asString != change.beforeState) {
                                    failed.set(true)
                                    break
                                }
                                val result = runCatching {
                                    block.setBlockData(
                                        Bukkit.createBlockData(change.afterState!!),
                                        plugin.config.getBoolean("rollback.rollback-physics", false)
                                    )
                                }
                                if (result.isFailure) {
                                    failed.set(true)
                                    result.onFailure {
                                        plugin.logger.warning("Cannot undo ${change.world}:${change.x},${change.y},${change.z}: ${it.message}")
                                    }
                                    break
                                }
                            }
                        } finally {
                            database.setSuppressed(false)
                        }
                    } catch (failure: Throwable) {
                        failed.set(true)
                        plugin.logger.warning("Cannot undo operation #$id: ${failure.message}")
                    } finally {
                        if (pending.decrementAndGet() == 0 && !failed.get()) database.markOperationUndone(id)
                    }
                }
            } catch (failure: Throwable) {
                failed.set(true)
                pending.decrementAndGet()
                plugin.logger.warning("Cannot schedule undo operation #$id: ${failure.message}")
            }
        }
        RollbackColors.sendSuccess(sender, "Операция #$id поставлена на восстановление: ${locations.size} блоков.")
    }

    private fun locationKey(event: RollbackEvent): String =
        "${event.world ?: "?"} ${event.x ?: "?"},${event.y ?: "?"},${event.z ?: "?"}"

    private fun sendRollbackReport(
        sender: CommandSender,
        operationId: Long,
        applied: Int,
        conflicts: List<String>,
        missingWorlds: Int,
        errors: List<String>,
    ) {
        RollbackColors.sendSuccess(sender, "Откат #$operationId завершён: применено изменений блоков §f$applied§a.")
        if (conflicts.isNotEmpty()) {
            RollbackColors.sendError(sender, "Пропущено конфликтов: ${conflicts.size}. Текущий блок уже изменён.")
            conflicts.distinct().take(8).forEach { RollbackColors.sendHint(sender, "Конфликт: $it") }
            if (conflicts.distinct().size > 8) RollbackColors.sendHint(sender, "Ещё конфликтов: ${conflicts.distinct().size - 8}.")
        }
        if (missingWorlds > 0) RollbackColors.sendHint(sender, "Пропущено отсутствующих миров: $missingWorlds.")
        if (errors.isNotEmpty()) {
            RollbackColors.sendError(sender, "Ошибок применения: ${errors.size}.")
            errors.distinct().take(8).forEach { RollbackColors.sendHint(sender, "Ошибка: $it") }
        }
    }
}
