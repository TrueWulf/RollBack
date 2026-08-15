package com.rollback.listener

import com.rollback.data.EventDatabase
import com.rollback.data.EventType
import com.rollback.data.RollbackEvent
import com.rollback.service.InventorySnapshot
import com.rollback.service.InventorySnapshotCodec
import com.rollback.platform.ServerScheduler
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class GameplayEventListener(
    private val database: EventDatabase,
    private val scheduler: ServerScheduler,
) : Listener {
    private val activitySessions = ConcurrentHashMap<UUID, String>()
    private val inventorySnapshots = ConcurrentHashMap<UUID, InventorySnapshot>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        activitySessions[player.uniqueId] = UUID.randomUUID().toString().replace("-", "").take(12)
        scheduler.runAt(player.location) { inventorySnapshots[player.uniqueId] = snapshot(player) }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        inventorySnapshots.remove(player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        if (!database.isLoggingEnabled(EventType.CHAT)) return
        recordText(event.player, EventType.CHAT, event.message, "message=${event.message}")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!database.isLoggingEnabled(EventType.COMMAND)) return
        recordText(event.player, EventType.COMMAND, event.message, "command=${event.message}")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!database.isLoggingEnabled(EventType.SESSION)) return
        recordText(event.player, EventType.SESSION, "login", "action=login")
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        if (!database.isLoggingEnabled(EventType.SESSION)) return
        recordText(event.player, EventType.SESSION, "logout", "action=logout")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        if (!database.isLoggingEnabled(EventType.SIGN)) return
        val text = event.lines.joinToString("\\n")
        database.record(RollbackEvent(
            timestamp = System.currentTimeMillis(), type = EventType.SIGN,
            world = event.block.world.name, x = event.block.x, y = event.block.y, z = event.block.z,
            actor = event.player.uniqueId, actorName = event.player.name, subject = null,
            beforeState = null, afterState = text, metadata = "text=$text",
        ))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        if (!database.isLoggingEnabled(EventType.INVENTORY)) return
        val player = event.whoClicked as? Player ?: return
        val before = inventorySnapshots[player.uniqueId] ?: snapshot(player)
        val transaction = InventorySnapshotCodec.transactionId()
        val action = inventoryAction(event)
        val item = itemSummary(event.currentItem) ?: "air"
        val cursor = itemSummary(event.cursor) ?: "air"
        val container = event.view.title
        val session = activitySessions[player.uniqueId] ?: "initial"
        scheduler.runAt(player.location) {
            val after = snapshot(player)
            inventorySnapshots[player.uniqueId] = after
            recordInventory(player, event.view.topInventory, before, after,
                "action=$action;slot=${event.slot};item=$item;cursor=$cursor;container=$container;session=$session;transaction=$transaction")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (!database.isLoggingEnabled(EventType.INVENTORY)) return
        val player = event.whoClicked as? Player ?: return
        val before = inventorySnapshots[player.uniqueId] ?: snapshot(player)
        val transaction = InventorySnapshotCodec.transactionId()
        val slots = event.rawSlots.sorted().joinToString(",")
        val item = itemSummary(event.oldCursor) ?: "air"
        val container = event.view.title
        val session = activitySessions[player.uniqueId] ?: "initial"
        scheduler.runAt(player.location) {
            val after = snapshot(player)
            inventorySnapshots[player.uniqueId] = after
            recordInventory(player, event.view.topInventory, before, after,
                "action=DRAG;slots=$slots;item=$item;container=$container;session=$session;transaction=$transaction")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryMove(event: InventoryMoveItemEvent) {
        if (!database.isLoggingEnabled(EventType.CONTAINER)) return
        val source = event.source.location ?: event.destination.location ?: return
        val world = source.world ?: return
        database.record(
            RollbackEvent(
                timestamp = System.currentTimeMillis(), type = EventType.CONTAINER,
                world = world.name, x = source.blockX, y = source.blockY, z = source.blockZ,
                actor = null, actorName = "#${event.initiator.type.name.lowercase()}", subject = null,
                beforeState = itemSummary(event.item), afterState = itemSummary(event.item),
                metadata = "action=MOVE;item=${itemSummary(event.item) ?: "air"};from=${locationId(event.source.location)};to=${locationId(event.destination.location)};source=${event.source.type.name};destination=${event.destination.type.name}"
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        if (!database.isLoggingEnabled(EventType.CRAFT)) return
        val player = event.whoClicked as? Player ?: return
        database.record(
            RollbackEvent(
                timestamp = System.currentTimeMillis(), type = EventType.CRAFT,
                world = player.world.name, x = player.location.blockX, y = player.location.blockY, z = player.location.blockZ,
                actor = player.uniqueId, actorName = player.name, subject = null,
                beforeState = null, afterState = itemSummary(event.recipe.result),
                metadata = "action=CRAFT;item=${itemSummary(event.recipe.result) ?: "air"};container=${event.inventory.type.name}"
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (!database.isLoggingEnabled(EventType.ITEM)) return
        val item = event.itemDrop.itemStack
        recordItem(event.player, "drop", itemSummary(item) ?: "air")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        if (!database.isLoggingEnabled(EventType.ITEM)) return
        val player = event.entity as? Player ?: return
        recordItem(player, "pickup", itemSummary(event.item.itemStack) ?: "air")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        if (!database.isLoggingEnabled(EventType.ITEM)) return
        recordItem(event.player, "consume", itemSummary(event.item) ?: "air")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBreak(event: PlayerItemBreakEvent) {
        if (!database.isLoggingEnabled(EventType.ITEM)) return
        recordItem(event.player, "break", itemSummary(event.brokenItem) ?: "air")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(event: PlayerItemDamageEvent) {
        if (!database.isLoggingEnabled(EventType.ITEM)) return
        recordItem(event.player, "damage:${event.damage}", itemSummary(event.item) ?: "air")
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDeath(event: PlayerDeathEvent) {
        if (!database.isLoggingEnabled(EventType.DEATH)) return
        val player = event.entity
        database.record(
            RollbackEvent(
                timestamp = System.currentTimeMillis(), type = EventType.DEATH,
                world = player.world.name, x = player.location.blockX, y = player.location.blockY, z = player.location.blockZ,
                actor = player.killer?.uniqueId, actorName = player.killer?.name ?: "#environment", subject = player.uniqueId,
                beforeState = player.inventory.contents.filterNotNull().joinToString(",") { itemSummary(it) ?: "air" },
                afterState = null, metadata = "death:${event.deathMessage ?: "unknown"}"
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        if (!database.isLoggingEnabled(EventType.KILL)) return
        val killer = event.entity.killer ?: return
        database.record(
            RollbackEvent(
                timestamp = System.currentTimeMillis(), type = EventType.KILL,
                world = event.entity.world.name, x = event.entity.location.blockX, y = event.entity.location.blockY, z = event.entity.location.blockZ,
                actor = killer.uniqueId, actorName = killer.name, subject = event.entity.uniqueId,
                beforeState = event.entity.type.name, afterState = null,
                metadata = "drops:${event.drops.joinToString(",") { itemSummary(it) ?: "air" }}"
            )
        )
    }

    private fun recordItem(player: Player, action: String, item: String) {
        database.record(
            RollbackEvent(
                timestamp = System.currentTimeMillis(), type = EventType.ITEM,
                world = player.world.name, x = player.location.blockX, y = player.location.blockY, z = player.location.blockZ,
                actor = player.uniqueId, actorName = player.name, subject = null,
                beforeState = if (action == "drop") item else null, afterState = if (action == "pickup") item else null,
                metadata = "action=$action;item=$item;session=${activitySessions[player.uniqueId] ?: "initial"}"
            )
        )
    }

    private fun recordText(player: Player, type: EventType, text: String, metadata: String) {
        database.record(RollbackEvent(
            timestamp = System.currentTimeMillis(), type = type,
            world = player.world.name, x = player.location.blockX, y = player.location.blockY, z = player.location.blockZ,
            actor = player.uniqueId, actorName = player.name, subject = null,
            beforeState = null, afterState = text, metadata = metadata,
        ))
    }

    private fun inventoryAction(event: InventoryClickEvent): String = when (event.action.name) {
        "PICKUP_ALL", "PICKUP_HALF", "PICKUP_ONE", "PICKUP_SOME" -> "take"
        "PLACE_ALL", "PLACE_ONE", "PLACE_SOME" -> "put"
        "MOVE_TO_OTHER_INVENTORY" -> "move"
        "HOTBAR_MOVE_AND_READD", "HOTBAR_SWAP" -> "swap"
        "DROP_ALL_SLOT", "DROP_ONE_SLOT" -> "drop"
        else -> event.action.name.lowercase()
    }

    private fun itemSummary(item: org.bukkit.inventory.ItemStack?): String? = item?.let {
        if (it.type.isAir) return null
        "${it.type.name}x${it.amount}"
    }

    private fun locationId(location: org.bukkit.Location?): String = location?.let { "${it.world?.name}:${it.blockX},${it.blockY},${it.blockZ}" } ?: "unknown"

    private fun eventLocation(inventory: org.bukkit.inventory.Inventory, player: Player): org.bukkit.Location =
        inventory.location ?: player.location

    private fun recordInventory(
        player: Player,
        inventory: org.bukkit.inventory.Inventory,
        before: InventorySnapshot,
        after: InventorySnapshot,
        metadata: String,
    ) {
        val location = eventLocation(inventory, player)
        database.record(RollbackEvent(
            timestamp = System.currentTimeMillis(), type = EventType.INVENTORY,
            world = location.world?.name, x = location.blockX, y = location.blockY, z = location.blockZ,
            actor = player.uniqueId, actorName = player.name, subject = null,
            beforeState = null, afterState = null,
            metadata = "$metadata;before=${InventorySnapshotCodec.encode(before)};after=${InventorySnapshotCodec.encode(after)}",
        ))
    }

    private fun snapshot(player: Player): InventorySnapshot = InventorySnapshot(
        top = player.openInventory.topInventory.contents.map { it?.clone() }.toTypedArray(),
        bottom = player.inventory.contents.map { it?.clone() }.toTypedArray(),
    )
}
