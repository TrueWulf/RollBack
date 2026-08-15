package com.rollback.command

import com.rollback.RollBackPlugin
import com.rollback.data.EventDatabase
import com.rollback.data.EventPage
import com.rollback.data.EventType
import com.rollback.data.RollbackEvent
import com.rollback.data.QueryScope
import com.rollback.platform.ServerScheduler
import com.rollback.service.BlockRollbackService
import com.rollback.service.RollbackQueryService
import com.rollback.service.SelectionBounds
import com.rollback.service.WorldEditSelectionService
import com.rollback.service.InventoryRollbackService
import com.rollback.service.DatabaseMigrationService
import com.rollback.api.RollbackRequest
import com.rollback.ui.RollbackColors
import com.rollback.ui.LocaleManager
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.TextComponent
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

class RollbackCommand(
    private val plugin: RollBackPlugin,
    private val database: EventDatabase,
    private val scheduler: ServerScheduler,
) : CommandExecutor, TabCompleter {
    private data class PreviewRecord(val key: RollbackArguments, val expiresAt: Long)
    private data class LookupSession(
        val arguments: RollbackArguments,
        val center: Location?,
        val target: Location?,
        val selection: SelectionBounds?,
        val expiresAt: Long,
    )

    private val queryService = RollbackQueryService(database)
    private val blockRollback = BlockRollbackService(plugin, database, scheduler)
    private val inventoryRollback = InventoryRollbackService(plugin, database, scheduler)
    private val previews = ConcurrentHashMap<String, PreviewRecord>()
    private val lookupSessions = ConcurrentHashMap<String, LookupSession>()
    private val activeSessions = ConcurrentHashMap<String, String>()
    private val activeCountOnly = ConcurrentHashMap<String, Boolean>()
    private val queryIds = AtomicLong()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val requiredPermission = when (args.firstOrNull()?.lowercase()) {
            "lookup", "page", "inspect" -> "rollback.lookup"
            "rollback" -> "rollback.rollback"
            "restore" -> "rollback.restore"
            "undo" -> "rollback.undo"
            "purge" -> "rollback.purge"
            "reload" -> "rollback.reload"
            "migrate-db" -> "rollback.migrate"
            else -> "rollback.admin"
        }
        if (!sender.hasPermission(requiredPermission) && !sender.hasPermission("rollback.admin")) {
            RollbackColors.sendError(sender, LocaleManager.text("permission", "You do not have permission: {permission}.", "permission" to requiredPermission))
            return true
        }
        when (args.firstOrNull()?.lowercase()) {
            "help" -> sendHelp(sender)
            "status" -> sendStatus(sender)
            "lookup" -> query(sender, args, preview = true, recordPreview = false)
            "inspect" -> query(sender, arrayOf("lookup", "10s", "--scope=looking"), preview = true, recordPreview = false)
            "rollback" -> query(
                sender,
                args,
                preview = args.any { it.equals("--preview", true) } || args.any { it.equals("--preview=true", true) },
                recordPreview = true,
            )
            "restore" -> query(sender, args, preview = args.any { it.equals("--preview", true) }, recordPreview = false, restore = true)
            "undo" -> undo(sender)
            "reload" -> reload(sender)
            "migrate-db" -> migrateDatabase(sender)
            "purge" -> purge(sender, args)
            "page" -> openPage(sender, args)
            else -> sendHelp(sender)
        }
        return true
    }

    private fun sendStatus(sender: CommandSender) {
        RollbackColors.sendSuccess(sender, "RollBack ${plugin.description.version} active. Platform: ${scheduler.platformName}.")
        RollbackColors.sendInfo(sender, "Queued events: ${database.queuedEventCount()}.")
        RollbackColors.sendInfo(sender, "Dropped events: ${database.droppedEventCount()}.")
    }

    private fun query(sender: CommandSender, args: Array<out String>, preview: Boolean, recordPreview: Boolean, restore: Boolean = false) {
        val defaultDuration = DurationParser.parse(plugin.config.getString("query.default-time", "10m"))
        val defaultScope = runCatching {
            QueryScope.valueOf(plugin.config.getString("query.default-scope", "looking")!!.uppercase())
        }.getOrDefault(QueryScope.LOOKING)
        val parsed = RollbackArgumentsParser.parse(
            args,
            plugin.config.getInt("query.max-radius", 256),
            defaultDuration,
            defaultScope,
        )
        if (parsed == null) {
            RollbackColors.sendError(sender, LocaleManager.text("invalid", "Invalid arguments. Example: /rb lookup 10m Steve BLOCK"))
            return
        }
        if (parsed.countOnly) activeCountOnly[sender.name] = true
        val player = sender as? Player
        val center = player?.location?.let { Location(it.world, it.blockX.toDouble(), it.blockY.toDouble(), it.blockZ.toDouble()) }
        val target = if (parsed.scope == QueryScope.LOOKING) player?.getTargetBlockExact(128)?.location else null
        val selectionResult = if (parsed.scope == QueryScope.SELECTION && player != null) {
            WorldEditSelectionService.selectionResult(player)
        } else null
        val selection = selectionResult?.bounds
        if (parsed.radius != null && center == null) {
            RollbackColors.sendError(sender, LocaleManager.text("radius-player", "Radius filters are available only to players in a world."))
            return
        }
        if (parsed.scope == QueryScope.LOOKING && target == null) {
            RollbackColors.sendError(sender, LocaleManager.text("target-missing", "No block found under your crosshair within 128 blocks."))
            return
        }
        if (parsed.scope == QueryScope.CHUNK && center == null) {
            RollbackColors.sendError(sender, LocaleManager.text("chunk-player", "Chunk scope is available only to players."))
            return
        }
        if (parsed.scope == QueryScope.SELECTION && selection == null) {
            RollbackColors.sendError(sender, selectionResult?.reason ?: "WorldEdit selection is unavailable. Install WorldEdit and select a region.")
            return
        }
        val sessionToken = if (preview) UUID.randomUUID().toString().replace("-", "").take(10) else null
        if (sessionToken != null) {
            lookupSessions[sessionToken] = LookupSession(
                arguments = parsed.copy(page = 1),
                center = center?.let { Location(it.world, it.x, it.y, it.z) },
                target = target?.let { Location(it.world, it.x, it.y, it.z) },
                selection = selection,
                expiresAt = System.currentTimeMillis() + plugin.config.getLong("query.session-timeout-seconds", 300L).coerceIn(30L, 3_600L) * 1_000L,
            )
            activeSessions[sender.name] = sessionToken
        }
        if (!preview && !parsed.preview && plugin.config.getBoolean("rollback.require-preview", false)) {
            val saved = previews[sender.name]
            if (saved == null || saved.expiresAt < System.currentTimeMillis() || saved.key != parsed.copy(preview = false)) {
                RollbackColors.sendError(sender, LocaleManager.text("preview-required", "Run preview with the same parameters before rollback."))
                return
            }
            previews.remove(sender.name, saved)
        }
        val queryId = queryIds.incrementAndGet()
        CompletableFuture.supplyAsync {
            if (preview || parsed.preview) {
                queryService.findPage(parsed, center, target, selection = selection)
            } else {
                EventPage(queryService.find(parsed, center, target, database.rollbackLimit(), selection), 0, 1, Int.MAX_VALUE)
            }
        }
            .orTimeout(plugin.config.getLong("query.timeout-seconds", 15L).coerceIn(1L, 120L), TimeUnit.SECONDS)
            .whenComplete { events, error ->
                val failure = error?.cause ?: error
                deliver(sender, "запроса #$queryId") {
                        if (failure != null) {
                            plugin.logger.log(java.util.logging.Level.WARNING, "Rollback query #$queryId failed", failure)
                            RollbackColors.sendError(sender, LocaleManager.text("query-failed", "Query #{id} failed: {error}", "id" to queryId, "error" to (failure.message ?: failure.javaClass.simpleName)))
                        } else if (preview || parsed.preview) {
                            if (recordPreview) {
                                previews[sender.name] = PreviewRecord(parsed.copy(preview = false), System.currentTimeMillis() + 60_000L)
                            }
                             sendPreview(sender, events ?: EventPage(emptyList(), 0, parsed.page, plugin.config.getInt("query.page-size", 10)), sessionToken)
                        } else {
                            val selected = events?.events ?: emptyList()
                            if (!plugin.api.allowRollback(RollbackRequest(sender.name, selected.toList(), restore))) {
                                RollbackColors.sendError(sender, "Rollback cancelled by an API listener.")
                                return@deliver
                            }
                            if (restore) {
                                blockRollback.restore(sender, selected)
                                inventoryRollback.restore(sender, selected)
                            } else {
                                blockRollback.apply(sender, selected)
                                inventoryRollback.apply(sender, selected)
                            }
                        }
                }
            }
        if (sender is Player) {
            sender.spigot().sendMessage(
                ChatMessageType.ACTION_BAR,
                TextComponent("§7${LocaleManager.text("loading", "Loading history {id}...", "id" to queryId)}")
            )
        }
    }

    private fun deliver(sender: CommandSender, operation: String, task: () -> Unit) {
        try {
            scheduler.runGlobal {
                runCatching { task() }.onFailure { failure ->
                    plugin.logger.log(java.util.logging.Level.WARNING, "Could not complete RollBack $operation", failure)
                    RollbackColors.sendError(sender, "Ошибка $operation: ${failure.message ?: failure.javaClass.simpleName}")
                }
            }
        } catch (failure: Throwable) {
            plugin.logger.log(java.util.logging.Level.WARNING, "Could not schedule RollBack $operation", failure)
            RollbackColors.sendError(sender, "Не удалось выполнить $operation: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    private fun sendPreview(sender: CommandSender, page: EventPage, sessionToken: String? = activeSessions[sender.name]) {
        val events = page.events
        val lines = mutableListOf("§x§5§B§8§D§F§F§l${LocaleManager.text("history", "HISTORY  /  {total} events  /  {page}/{pages}", "total" to page.total, "page" to page.page, "pages" to page.pageCount)}")
        if (activeCountOnly[sender.name] == true) {
            lines += "§7${page.total}"
            activeCountOnly.remove(sender.name)
            RollbackColors.sendCard(sender, lines)
            return
        }
        if (events.isEmpty()) {
            lines += "§7${LocaleManager.text("no-results", "Nothing found. Increase the time or radius and check the current position.")}"
            RollbackColors.sendCard(sender, lines)
            return
        }
        val blocks = events.filter { it.type == EventType.BLOCK }
        if (blocks.isNotEmpty()) {
            val placed = blocks.count(::isPlacement)
            lines += "§x§5§B§D§9§D§1${LocaleManager.text("blocks-label", "Blocks")}  §a${LocaleManager.text("placed", "+{count} placed", "count" to placed)}  §c${LocaleManager.text("broken", "-{count} broken", "count" to blocks.size - placed)}"
        }
        lines += events.map(::formatEvent)
        RollbackColors.sendCard(sender, lines)
        if (page.pageCount > 1) {
            val session = sessionToken?.let(lookupSessions::get)
            if (database.clickableNavigation() && session != null && session.expiresAt >= System.currentTimeMillis()) {
                RollbackColors.sendNavigation(sender, page.page, page.pageCount) { requestedPage ->
                    "/rb page $sessionToken $requestedPage"
                }
            } else {
                RollbackColors.sendHint(sender, LocaleManager.text("page-console", "Page {page}/{pages}. Use --page=N.", "page" to page.page, "pages" to page.pageCount))
            }
        }
    }

    private fun formatEvent(event: RollbackEvent): String {
        val metadata = event.metadata.orEmpty()
        val action = metadataValue(metadata, "action") ?: metadata.substringBefore(':').takeIf { ':' in metadata }
        val item = metadataValue(metadata, "item") ?: metadata.substringAfter(':', "").takeIf { ':' in metadata }
        val from = metadataValue(metadata, "from")
        val to = metadataValue(metadata, "to")
        val container = metadataValue(metadata, "container")
        val location = if (event.x != null && event.y != null && event.z != null) {
            "${event.world ?: "?"} ${RollbackColors.coordinate(event.x, event.y, event.z)}"
        } else "без координат"
        val actionLabel = when {
            event.type == EventType.BLOCK && isPlacement(event) -> "§a+"
            event.type == EventType.BLOCK -> "§c-"
            else -> "§b${displayAction(action ?: event.type.name)}"
        }
        val details = buildList {
            add(actionLabel)
            add("§f${event.actorName}")
            if (event.type == EventType.BLOCK) {
                add("§e${blockName(event.beforeState)} §8→ §e${blockName(event.afterState)}")
            }
            item?.let { add("§e${displayItem(it)}") }
            if (from != null || to != null) add("§7${from ?: "?"} → ${to ?: "?"}")
            container?.let { add("§8${it.take(24)}") }
            add("§7$location")
            add("§8${timeSince(event.timestamp)}")
        }
        return "§8#${event.id} ${details.joinToString("  ")}"
    }

    private fun metadataValue(metadata: String, key: String): String? =
        Regex("(?:^|;)$key=([^;]*)").find(metadata)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    private fun blockName(state: String?): String = state
        ?.substringBefore('[')
        ?.substringAfterLast(':')
        ?.lowercase()
        ?: "?"

    private fun displayItem(value: String): String = value.replace(Regex("x(\\d+)$"), " §7× $1")

    private fun displayAction(value: String): String = LocaleManager.action(value)

    private fun isPlacement(event: RollbackEvent): Boolean =
        event.beforeState?.substringBefore('[')?.endsWith(":air", true) == true || event.beforeState?.equals("air", true) == true

    private fun timeSince(timestamp: Long): String {
        val seconds = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L)) / 1_000L
        return when {
            seconds < 5 -> "только что"
            seconds < 60 -> "$seconds с назад"
            seconds < 3_600 -> "${seconds / 60} мин назад"
            seconds < 86_400 -> "${seconds / 3_600} ч назад"
            else -> "${seconds / 86_400} дн назад"
        }
    }

    private fun undo(sender: CommandSender) {
        CompletableFuture.supplyAsync { database.latestUndoableOperation() }
            .orTimeout(15, TimeUnit.SECONDS)
            .whenComplete { operation, error ->
                val failure = error?.cause ?: error
                deliver(sender, "undo") {
                    if (failure != null) {
                        RollbackColors.sendError(sender, "Undo не выполнен: ${failure.message ?: failure.javaClass.simpleName}")
                        return@deliver
                    }
                    scheduler.runGlobal {
                if (operation == null) {
                    blockRollback.undo(sender, null)
                    return@runGlobal
                }
                if (operation.second.any { it.type == EventType.BLOCK }) blockRollback.undo(sender, operation)
                if (operation.second.any { it.type == EventType.INVENTORY }) inventoryRollback.undo(sender, operation)
                    }
                }
            }
        RollbackColors.sendInfo(sender, "Undo запущен. Результат появится отдельным сообщением.")
    }

    private fun sendHelp(sender: CommandSender) {
        RollbackColors.sendSuccess(sender, "Команды RollBack")
        RollbackColors.sendInfo(sender, "/rb status")
        RollbackColors.sendInfo(sender, "/rb inspect")
        RollbackColors.sendInfo(sender, "/rb lookup <time> [player] [type]")
        RollbackColors.sendInfo(sender, "/rb rollback <time> [radius] [player] [type] [--preview]")
        RollbackColors.sendInfo(sender, "/rb restore <time> [radius] [player] [type]")
        RollbackColors.sendInfo(sender, "/rb lookup 10m --scope=chunk [player] [type]")
        RollbackColors.sendInfo(sender, "/rb lookup 10m --world=world_nether --include=diamond --action=-BLOCK")
        RollbackColors.sendHint(sender, "Use --count for a result count. Scope looking targets the block under the crosshair.")
        RollbackColors.sendInfo(sender, "/rb undo")
        RollbackColors.sendInfo(sender, "/rb purge <30d> или /rb reload")
        RollbackColors.sendHint(sender, "Types: BLOCK, CONTAINER, INVENTORY, ITEM, DEATH, KILL, CRAFT.")
        RollbackColors.sendHint(sender, "Flags: --time=1h --radius=50 --near=10 --page=2 --world=world --include=stone --action=-BLOCK --preview.")
    }

    private fun reload(sender: CommandSender) {
        plugin.reloadConfig()
        plugin.mergeConfigurationDefaults()
        RollbackColors.configure(plugin.config.getConfigurationSection("ui.colors"))
        LocaleManager.configure(plugin)
        previews.clear()
        lookupSessions.clear()
        activeSessions.clear()
        RollbackColors.sendSuccess(sender, LocaleManager.text("reload", "Configuration reloaded."))
    }

    private fun openPage(sender: CommandSender, args: Array<out String>) {
        val token = args.getOrNull(1)
        val page = args.getOrNull(2)?.toIntOrNull()
        val session = token?.let(lookupSessions::get)
        if (session == null || page == null || page < 1 || session.expiresAt < System.currentTimeMillis()) {
            RollbackColors.sendError(sender, "Page session expired. Run lookup again.")
            return
        }
        lookupSessions[token] = session.copy(expiresAt = System.currentTimeMillis() + plugin.config.getLong("query.session-timeout-seconds", 300L).coerceIn(30L, 3_600L) * 1_000L)
        activeSessions[sender.name] = token
        val arguments = session.arguments.copy(page = page)
        val queryId = queryIds.incrementAndGet()
        CompletableFuture.supplyAsync { queryService.findPage(arguments, session.center, session.target, selection = session.selection) }
            .orTimeout(plugin.config.getLong("query.timeout-seconds", 15L).coerceIn(1L, 120L), TimeUnit.SECONDS)
            .whenComplete { result, error ->
                deliver(sender, "page #$queryId") {
                    if (error != null) RollbackColors.sendError(sender, "Page failed: ${error.cause?.message ?: error.message}")
                    else sendPreview(sender, result ?: EventPage(emptyList(), 0, page, database.queryPageSize()), token)
                }
            }
        if (sender is Player) sender.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent("§7Loading..."))
    }

    private fun purge(sender: CommandSender, args: Array<out String>) {
        val duration = DurationParser.parse(args.getOrNull(1))
        if (duration == null || duration < 60_000L) {
            RollbackColors.sendError(sender, "Использование: /rb purge 30d. Минимум: 1m.")
            return
        }
        CompletableFuture.supplyAsync { database.purgeBefore(System.currentTimeMillis() - duration) }
            .orTimeout(30, TimeUnit.SECONDS)
            .whenComplete { count, error ->
                val failure = error?.cause ?: error
                deliver(sender, "очистки") {
                    if (failure != null) {
                        RollbackColors.sendError(sender, "Ошибка очистки: ${failure.message ?: failure.javaClass.simpleName}")
                    } else {
                        RollbackColors.sendSuccess(sender, "Удалено старых событий: ${count ?: 0}.")
                    }
                }
            }
        RollbackColors.sendInfo(sender, "Очистка базы запущена. Результат появится отдельным сообщением.")
    }

    private fun migrateDatabase(sender: CommandSender) {
        CompletableFuture.supplyAsync { DatabaseMigrationService(plugin).migrateSqliteToDuckdb() }
            .orTimeout(5, TimeUnit.MINUTES)
            .whenComplete { result, error ->
                deliver(sender, "database migration") {
                    val failure = error?.cause ?: error
                    if (failure != null) {
                        RollbackColors.sendError(sender, "Database migration failed: ${failure.message ?: failure.javaClass.simpleName}")
                    } else {
                        RollbackColors.sendSuccess(sender, "Migrated ${result.events} events, ${result.operations} operations and ${result.blocks} block records to ${result.target.name}.")
                        RollbackColors.sendHint(sender, "Backup: ${result.backup.name}. Set database.type=duckdb and restart RollBack.")
                    }
                }
            }
        RollbackColors.sendInfo(sender, "Database migration started.")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val root = args.firstOrNull()?.lowercase()
        val current = args.lastOrNull().orEmpty()
        if (args.size == 1) return listOf("help", "status", "lookup", "rollback", "page", "undo", "purge", "reload", "migrate-db")
            .filter { it.startsWith(current, true) }
        if (root == "page") return if (args.size == 2) listOf("<session>") else listOf("1", "2", "3", "4", "5")
        if (root == "purge") return listOf("1h", "1d", "7d", "30d").filter { it.startsWith(current, true) }
        if (root !in setOf("lookup", "rollback")) return emptyList()
        val used = args.drop(1).mapNotNull { it.substringAfter("--", "").substringBefore('=').lowercase().takeIf { key -> key.isNotBlank() } }.toSet()
        val suggestions = mutableListOf<String>()
        if (args.size == 2 && current.isEmpty()) suggestions += listOf("10m", "30m", "1h", "1d")
        if ("time" !in used && args.size > 2) suggestions += listOf("--time=10m", "--time=1h")
        if ("radius" !in used && "near" !in used && "scope" !in used) suggestions += listOf("--near=10", "--radius=50")
        if ("scope" !in used) suggestions += listOf("--scope=looking", "--scope=chunk", "--scope=radius")
        if ("player" !in used) suggestions += "--player="
        if ("type" !in used) suggestions += EventType.entries.map { "--type=${it.name.lowercase()}" }
        if ("page" !in used && root == "lookup") suggestions += "--page=1"
        if (root == "rollback" && "preview" !in used) suggestions += "--preview"
        return suggestions.distinct().filter { it.startsWith(current, true) }
    }
}
