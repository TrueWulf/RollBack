package com.rollback.service

import com.rollback.RollBackPlugin
import com.rollback.data.EventDatabase
import com.rollback.data.EventType
import com.rollback.data.RollbackEvent
import com.rollback.platform.ServerScheduler
import com.rollback.ui.RollbackColors
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class InventoryRollbackService(
    private val plugin: RollBackPlugin,
    private val database: EventDatabase,
    private val scheduler: ServerScheduler,
) {
    fun apply(sender: CommandSender, events: List<RollbackEvent>) = execute(sender, events, restore = false)

    fun restore(sender: CommandSender, events: List<RollbackEvent>) = execute(sender, events, restore = true)

    fun undo(sender: CommandSender, operation: Pair<Long, List<RollbackEvent>>) {
        execute(sender, operation.second, restore = true, operationId = operation.first, markUndone = true)
    }

    private fun execute(
        sender: CommandSender,
        events: List<RollbackEvent>,
        restore: Boolean,
        operationId: Long? = null,
        markUndone: Boolean = false,
    ) {
        val transactions = events.mapNotNull { event ->
            val before = InventorySnapshotCodec.decode(metadataValue(event.metadata, if (restore) "after" else "before")) ?: return@mapNotNull null
            val after = InventorySnapshotCodec.decode(metadataValue(event.metadata, if (restore) "before" else "after")) ?: return@mapNotNull null
            event to InventoryTransaction(metadataValue(event.metadata, "transaction") ?: return@mapNotNull null, before, after)
        }.filter { (_, transaction) -> transaction.before.top.isNotEmpty() || transaction.before.bottom.isNotEmpty() }
        if (transactions.isEmpty()) {
            RollbackColors.sendHint(sender, "No safe inventory transactions are available.")
            if (markUndone) database.markOperationUndone(operationId ?: 0)
            return
        }
        val createdOperation = operationId ?: database.createRollbackOperation(sender.name, transactions.map { it.first })
        if (createdOperation <= 0) {
            RollbackColors.sendError(sender, "Could not create inventory rollback operation.")
            return
        }
        val conflicts = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val applied = AtomicInteger(0)
        val pending = AtomicInteger(transactions.size)
        transactions.forEach { (event, transaction) ->
            val player = event.actor?.let(Bukkit::getPlayer)
            if (player == null) {
                errors += event.actorName
                finish(sender, createdOperation, pending, applied, conflicts, errors, markUndone)
                return@forEach
            }
            scheduler.runAt(player.location) {
                try {
                    database.setSuppressed(true)
                    val top = player.openInventory.topInventory
                    val bottom = player.inventory
                    if (!transaction.after.matches(top, bottom)) {
                        conflicts += "${player.name} transaction ${transaction.id.take(8)}"
                    } else {
                        transaction.before.apply(top, bottom)
                        database.markEventsApplied(createdOperation, listOf(event.id))
                        applied.incrementAndGet()
                    }
                } catch (failure: Throwable) {
                    errors += "${player.name}: ${failure.message ?: failure.javaClass.simpleName}"
                } finally {
                    database.setSuppressed(false)
                    finish(sender, createdOperation, pending, applied, conflicts, errors, markUndone)
                }
            }
        }
    }

    private fun finish(
        sender: CommandSender,
        operationId: Long,
        pending: AtomicInteger,
        applied: AtomicInteger,
        conflicts: MutableList<String>,
        errors: MutableList<String>,
        markUndone: Boolean,
    ) {
        if (pending.decrementAndGet() != 0) return
        if (markUndone && errors.isEmpty() && conflicts.isEmpty()) database.markOperationUndone(operationId)
        RollbackColors.sendSuccess(sender, "Inventory operation #$operationId completed: ${applied.get()} transaction(s) applied.")
        if (conflicts.isNotEmpty()) RollbackColors.sendError(sender, "Skipped inventory conflicts: ${conflicts.size}.")
        if (errors.isNotEmpty()) RollbackColors.sendError(sender, "Inventory errors: ${errors.size}.")
    }

    private fun metadataValue(metadata: String?, key: String): String? =
        Regex("(?:^|;)$key=([^;]*)").find(metadata.orEmpty())?.groupValues?.get(1)
}
