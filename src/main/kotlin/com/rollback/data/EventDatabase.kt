package com.rollback.data

import com.rollback.RollBackPlugin
import org.bukkit.Location
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class DatabaseSelection(
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
)

data class EventPage(
    val events: List<RollbackEvent>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
) {
    val pageCount: Int get() = if (total == 0) 1 else (total + pageSize - 1) / pageSize
}

class EventDatabase(private val plugin: RollBackPlugin) {
    private val queue = LinkedBlockingQueue<RollbackEvent>(
        plugin.config.getInt("database.queue-capacity", 100_000).coerceAtLeast(1_000)
    )
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RollBack-DatabaseWriter").apply { isDaemon = true }
    }
    private var writeConnection: Connection? = null
    private var databaseUrl: String? = null
    @Volatile private var running = false
    @Volatile private var writing = false
    private val suppressed = ThreadLocal.withInitial { false }
    private val droppedEvents = AtomicLong()
    private val pendingEvents = AtomicLong()
    private val busyTimeoutMs = plugin.config.getLong("database.busy-timeout-ms", 5_000L).coerceAtLeast(0L)
    private val backend: String get() = plugin.config.getString("database.type", "sqlite")!!.lowercase()

    fun start() {
        val fileName = plugin.config.getString("database.file", "rollback.db") ?: "rollback.db"
        val file = File(plugin.dataFolder, fileName)
        plugin.dataFolder.mkdirs()
        if (backend == "duckdb") Class.forName("org.duckdb.DuckDBDriver") else Class.forName("org.sqlite.JDBC")
        databaseUrl = if (backend == "duckdb") "jdbc:duckdb:${File(plugin.dataFolder, plugin.config.getString("database.duckdb-file", "rollback.duckdb") ?: "rollback.duckdb").absolutePath}" else "jdbc:sqlite:${file.absolutePath}"
        writeConnection = DriverManager.getConnection(databaseUrl).also { connection ->
            configureConnection(connection)
            connection.createStatement().use { statement ->
                if (backend == "duckdb") {
                    statement.execute("CREATE SEQUENCE IF NOT EXISTS events_id_seq START 1")
                    statement.execute("CREATE SEQUENCE IF NOT EXISTS rollback_operations_id_seq START 1")
                    statement.execute("CREATE SEQUENCE IF NOT EXISTS block_changes_id_seq START 1")
                }
                if (backend == "sqlite") {
                    statement.execute("PRAGMA journal_mode=WAL")
                    statement.execute("PRAGMA synchronous=NORMAL")
                }
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS events (
                        id ${if (backend == "duckdb") "BIGINT PRIMARY KEY DEFAULT nextval('events_id_seq')" else "INTEGER PRIMARY KEY AUTOINCREMENT"},
                        timestamp INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        world TEXT,
                        x INTEGER,
                        y INTEGER,
                        z INTEGER,
                        actor_uuid TEXT,
                        actor_name TEXT NOT NULL,
                        subject_uuid TEXT,
                        before_state TEXT,
                        after_state TEXT,
                        metadata TEXT
                    )
                    """.trimIndent()
                )
                statement.execute("CREATE INDEX IF NOT EXISTS idx_events_time ON events(timestamp, id)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_events_location ON events(world, x, z, timestamp)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_events_actor ON events(actor_uuid, timestamp)")
                statement.execute("CREATE INDEX IF NOT EXISTS idx_events_type ON events(type, timestamp)")
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS rollback_operations (
                        id ${if (backend == "duckdb") "BIGINT PRIMARY KEY DEFAULT nextval('rollback_operations_id_seq')" else "INTEGER PRIMARY KEY AUTOINCREMENT"},
                        timestamp INTEGER NOT NULL,
                        actor_name TEXT NOT NULL,
                        event_ids TEXT NOT NULL,
                        applied_event_ids TEXT NOT NULL DEFAULT '',
                        ready INTEGER NOT NULL DEFAULT 0,
                        undone INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                runCatching {
                    statement.execute("ALTER TABLE rollback_operations ADD COLUMN applied_event_ids TEXT NOT NULL DEFAULT ''")
                }
                runCatching {
                    statement.execute("ALTER TABLE rollback_operations ADD COLUMN ready INTEGER NOT NULL DEFAULT 1")
                }
                statement.execute(
                    "UPDATE rollback_operations SET applied_event_ids = event_ids " +
                        "WHERE ready = 1 AND applied_event_ids = ''"
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS rollback_migrations (
                        name TEXT PRIMARY KEY,
                        value INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS block_changes (
                        id ${if (backend == "duckdb") "BIGINT PRIMARY KEY DEFAULT nextval('block_changes_id_seq')" else "INTEGER PRIMARY KEY AUTOINCREMENT"},
                        timestamp INTEGER NOT NULL,
                        world TEXT NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        actor_uuid TEXT NOT NULL,
                        actor_name TEXT NOT NULL,
                        old_data TEXT NOT NULL,
                        new_data TEXT NOT NULL
                    )
                    """.trimIndent()
                )
                migrateLegacyBlocks(connection, statement)
            }
        }
        running = true
        writer.submit(::writeLoop)
    }

    fun record(event: RollbackEvent) {
        if (!running || suppressed.get()) return
        pendingEvents.incrementAndGet()
        if (!queue.offer(event)) {
            pendingEvents.decrementAndGet()
            droppedEvents.incrementAndGet()
        }
    }

    fun recordBlock(location: Location, actor: UUID, actorName: String, oldData: String, newData: String) {
        val world = location.world ?: return
        record(
            RollbackEvent(
                timestamp = System.currentTimeMillis(),
                type = EventType.BLOCK,
                world = world.name,
                x = location.blockX,
                y = location.blockY,
                z = location.blockZ,
                actor = actor,
                actorName = actorName,
                subject = null,
                beforeState = oldData,
                afterState = newData,
                metadata = null,
            )
        )
    }

    fun droppedEventCount(): Long = droppedEvents.get()

    fun queuedEventCount(): Int = queue.size

    fun shouldFlushBeforeQuery(): Boolean = plugin.config.getBoolean("query.flush-before-query", true)

    fun flushTimeoutMs(): Long = plugin.config.getLong("query.flush-timeout-ms", 1_000L).coerceIn(0L, 10_000L)

    fun defaultRadius(): Int? = plugin.config.getInt("query.default-radius", 0).takeIf { it > 0 }

    fun queryPageSize(): Int = plugin.config.getInt("query.page-size", 10).coerceIn(1, 100)

    fun rollbackLimit(): Int = plugin.config.getInt("rollback.max-events-per-operation", 10_000).coerceIn(1, 100_000)

    fun lookupLimit(): Int = plugin.config.getInt("query.max-limit", 10_000).coerceIn(queryPageSize(), 100_000)

    fun aggregationEnabled(): Boolean = plugin.config.getBoolean("query.aggregate-events", true)

    fun aggregationWindowMs(): Long = plugin.config.getLong("query.aggregate-window-ms", 5_000L).coerceIn(0L, 60_000L)

    fun aggregateOnlySameContainer(): Boolean = plugin.config.getBoolean("query.aggregate-only-same-container", true)

    fun clickableNavigation(): Boolean = plugin.config.getBoolean("query.navigation-clickable", true)

    fun awaitWrites(timeoutMs: Long = 1_000L): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        while (pendingEvents.get() > 0L || writing) {
            if (System.nanoTime() >= deadline) return false
            try {
                Thread.sleep(5L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return true
    }

    fun isLoggingEnabled(type: EventType): Boolean = plugin.config.getBoolean(
        "logging.${loggingKey(type)}",
        true
    )

    private fun loggingKey(type: EventType): String = when (type) {
        EventType.BLOCK -> "blocks"
        EventType.CONTAINER -> "containers"
        EventType.INVENTORY -> "inventories"
        EventType.ITEM -> "items"
        EventType.DEATH -> "deaths"
        EventType.KILL -> "kills"
        EventType.CRAFT -> "crafting"
        EventType.CHAT -> "chat"
        EventType.COMMAND -> "commands"
        EventType.SESSION -> "sessions"
        EventType.SIGN -> "signs"
    }

    fun purgeBefore(cutoff: Long): Int {
        awaitWrites()
        val url = databaseUrl ?: return 0
        DriverManager.getConnection(url).use { connection ->
            configureConnection(connection)
            connection.prepareStatement(
                "DELETE FROM events WHERE timestamp < ? AND NOT EXISTS " +
                    "(SELECT 1 FROM rollback_operations o WHERE o.undone = 0 " +
                    "AND (',' || o.applied_event_ids || ',') LIKE '%,' || events.id || ',%')"
            ).use { statement ->
                statement.setLong(1, cutoff)
                return statement.executeUpdate()
            }
        }
    }

    fun setSuppressed(value: Boolean) {
        suppressed.set(value)
    }

    fun createRollbackOperation(actorName: String, events: List<RollbackEvent>): Long {
        if (events.isEmpty()) return 0
        val url = databaseUrl ?: return 0
        DriverManager.getConnection(url).use { connection ->
            configureConnection(connection)
            if (backend == "duckdb") {
                connection.prepareStatement(
                    "INSERT INTO rollback_operations(timestamp, actor_name, event_ids) VALUES (?, ?, ?) RETURNING id"
                ).use { statement ->
                    statement.setLong(1, System.currentTimeMillis())
                    statement.setString(2, actorName)
                    statement.setString(3, events.joinToString(",") { it.id.toString() })
                    statement.executeQuery().use { keys -> if (keys.next()) return keys.getLong(1) }
                }
            } else {
                connection.prepareStatement(
                    "INSERT INTO rollback_operations(timestamp, actor_name, event_ids) VALUES (?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS
                ).use { statement ->
                    statement.setLong(1, System.currentTimeMillis())
                    statement.setString(2, actorName)
                    statement.setString(3, events.joinToString(",") { it.id.toString() })
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys -> if (keys.next()) return keys.getLong(1) }
                }
            }
        }
        return 0
    }

    fun latestUndoableOperation(): Pair<Long, List<RollbackEvent>>? {
        val url = databaseUrl ?: return null
        DriverManager.getConnection(url).use { connection ->
            configureConnection(connection)
            connection.prepareStatement(
                "SELECT id, applied_event_ids FROM rollback_operations " +
                    "WHERE undone = 0 AND ready = 1 AND applied_event_ids <> '' ORDER BY id DESC LIMIT 1"
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    if (!rows.next()) return null
                    val operationId = rows.getLong("id")
                    val ids = rows.getString("applied_event_ids").split(',').mapNotNull { it.toLongOrNull() }
                    if (ids.isEmpty()) return operationId to emptyList()
                    val placeholders = ids.joinToString(",") { "?" }
                    connection.prepareStatement(
                        "SELECT id, timestamp, type, world, x, y, z, actor_uuid, actor_name, subject_uuid, before_state, after_state, metadata FROM events WHERE id IN ($placeholders) ORDER BY timestamp ASC, id ASC"
                    ).use { eventStatement ->
                        ids.forEachIndexed { index, id -> eventStatement.setLong(index + 1, id) }
                        val events = mutableListOf<RollbackEvent>()
                        eventStatement.executeQuery().use { eventRows ->
                            while (eventRows.next()) events += eventRows.toEvent()
                        }
                        return operationId to events
                    }
                }
            }
        }
    }

    fun markOperationUndone(id: Long) {
        databaseUrl?.let { url ->
            DriverManager.getConnection(url).use { connection ->
                connection.prepareStatement("UPDATE rollback_operations SET undone = 1 WHERE id = ?").use { statement ->
                    statement.setLong(1, id)
                    statement.executeUpdate()
                }
            }
        }
    }

    fun markEventsApplied(operationId: Long, eventIds: List<Long>) {
        if (eventIds.isEmpty()) return
        val url = databaseUrl ?: return
        val ids = eventIds.joinToString(",")
        DriverManager.getConnection(url).use { connection ->
            configureConnection(connection)
            connection.prepareStatement(
                "UPDATE rollback_operations SET applied_event_ids = " +
                    "CASE WHEN applied_event_ids = '' THEN ? ELSE applied_event_ids || ',' || ? END WHERE id = ?"
            ).use { statement ->
                statement.setString(1, ids)
                statement.setString(2, ids)
                statement.setLong(3, operationId)
                statement.executeUpdate()
            }
        }
    }

    fun markOperationReady(operationId: Long) {
        val url = databaseUrl ?: return
        DriverManager.getConnection(url).use { connection ->
            configureConnection(connection)
            connection.prepareStatement("UPDATE rollback_operations SET ready = 1 WHERE id = ?").use { statement ->
                statement.setLong(1, operationId)
                statement.executeUpdate()
            }
        }
    }

    fun findSince(
        since: Long,
        type: EventType? = null,
        actorName: String? = null,
        actorNames: List<String> = actorName?.let(::listOf).orEmpty(),
        world: String? = null,
        include: String? = null,
        action: String? = null,
        radius: Int? = null,
        center: Location? = null,
        selection: DatabaseSelection? = null,
        limit: Int = plugin.config.getInt("query.default-limit", 1_000)
            .coerceIn(1, plugin.config.getInt("query.max-limit", 10_000).coerceAtLeast(1)),
        chunk: Boolean = false,
    ): List<RollbackEvent> {
        return findPage(
            since = since,
            type = type,
            actorName = actorName,
            actorNames = actorNames,
            world = world,
            include = include,
            action = action,
            radius = radius,
            center = center,
            selection = selection,
            limit = limit,
            page = 1,
            chunk = chunk,
        ).events
    }

    fun findPage(
        since: Long,
        type: EventType? = null,
        actorName: String? = null,
        actorNames: List<String> = actorName?.let(::listOf).orEmpty(),
        world: String? = null,
        include: String? = null,
        action: String? = null,
        radius: Int? = null,
        center: Location? = null,
        selection: DatabaseSelection? = null,
        limit: Int = plugin.config.getInt("query.default-limit", 1_000),
        page: Int = 1,
        chunk: Boolean = false,
    ): EventPage {
        val pageSize = limit.coerceIn(1, 100_000)
        val safePage = page.coerceAtLeast(1)
        val conditions = buildString {
            append("timestamp >= ?")
            if (type != null) append(" AND type = ?")
            if (actorNames.isNotEmpty()) append(" AND lower(actor_name) IN (${actorNames.joinToString(",") { "lower(?)" }})")
            if (world != null) append(" AND world = ?")
            if (include != null) append(" AND (lower(before_state) LIKE lower(?) OR lower(after_state) LIKE lower(?) OR lower(metadata) LIKE lower(?))")
            actionCondition(action)?.let { append(" AND ($it)") }
            if (center != null && radius != null) {
                if (chunk) append(" AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?")
                else append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?")
            }
            if (selection != null) append(" AND world = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?")
        }
        val url = databaseUrl ?: return EventPage(emptyList(), 0, safePage, pageSize)
        DriverManager.getConnection(url).use { connection ->
            configureConnection(connection)
            val total = connection.prepareStatement("SELECT COUNT(*) FROM events WHERE $conditions").use { statement ->
                bindQuery(statement, since, type, actorNames, world, include, radius, center, chunk, action, selection)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
            val events = mutableListOf<RollbackEvent>()
            val sql = "SELECT id, timestamp, type, world, x, y, z, actor_uuid, actor_name, subject_uuid, before_state, after_state, metadata " +
                "FROM events WHERE $conditions ORDER BY timestamp DESC, id DESC LIMIT ? OFFSET ?"
            connection.prepareStatement(sql).use { statement ->
                var index = bindQuery(statement, since, type, actorNames, world, include, radius, center, chunk, action, selection)
                statement.setInt(index++, pageSize)
                statement.setInt(index, (safePage - 1).coerceAtMost(Int.MAX_VALUE / pageSize) * pageSize)
                statement.executeQuery().use { rows -> while (rows.next()) events += rows.toEvent() }
            }
            return EventPage(events, total, safePage, pageSize)
        }
    }

    private fun bindQuery(
        statement: java.sql.PreparedStatement,
        since: Long,
        type: EventType?,
        actorNames: List<String>,
        world: String?,
        include: String?,
        radius: Int?,
        center: Location?,
        chunk: Boolean,
        action: String?,
        selection: DatabaseSelection?,
    ): Int {
        var index = 1
        statement.setLong(index++, since)
        if (type != null) statement.setString(index++, type.name)
        actorNames.forEach { statement.setString(index++, it) }
        if (world != null) statement.setString(index++, world)
        if (include != null) {
            val pattern = "%${include.lowercase()}%"
            statement.setString(index++, pattern)
            statement.setString(index++, pattern)
            statement.setString(index++, pattern)
        }
        if (center != null && radius != null) {
            if (chunk) {
                statement.setInt(index++, center.blockX)
                statement.setInt(index++, center.blockX + 15)
                statement.setInt(index++, center.blockZ)
                statement.setInt(index++, center.blockZ + 15)
            } else {
                statement.setInt(index++, center.blockX - radius)
                statement.setInt(index++, center.blockX + radius)
                statement.setInt(index++, center.blockY - radius)
                statement.setInt(index++, center.blockY + radius)
                statement.setInt(index++, center.blockZ - radius)
                statement.setInt(index++, center.blockZ + radius)
            }
        }
        if (selection != null) {
            statement.setString(index++, selection.world)
            statement.setInt(index++, selection.minX)
            statement.setInt(index++, selection.maxX)
            statement.setInt(index++, selection.minY)
            statement.setInt(index++, selection.maxY)
            statement.setInt(index++, selection.minZ)
            statement.setInt(index++, selection.maxZ)
        }
        return index
    }

    private fun actionCondition(action: String?): String? = when (action?.uppercase()) {
        "BLOCK" -> "type = 'BLOCK'"
        "+BLOCK" -> "type = 'BLOCK' AND (lower(before_state) LIKE '%:air%' OR lower(before_state) = 'air')"
        "-BLOCK" -> "type = 'BLOCK' AND (lower(after_state) LIKE '%:air%' OR lower(after_state) = 'air')"
        "CONTAINER", "+CONTAINER", "-CONTAINER" -> "type = 'CONTAINER'"
        "INVENTORY" -> "type = 'INVENTORY'"
        "ITEM" -> "type = 'ITEM'"
        "DEATH" -> "type = 'DEATH'"
        "KILL" -> "type = 'KILL'"
        "CRAFT" -> "type = 'CRAFT'"
        else -> null
    }

    private fun writeLoop() {
        val sql = """
            INSERT INTO events(timestamp, type, world, x, y, z, actor_uuid, actor_name, subject_uuid, before_state, after_state, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        while (running || queue.isNotEmpty()) {
            val batch = mutableListOf<RollbackEvent>()
            queue.drainTo(batch, 500)
            if (batch.isEmpty()) {
                try {
                    queue.poll(100, TimeUnit.MILLISECONDS)?.let(batch::add)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                if (batch.isEmpty()) continue
            }
            try {
                writing = true
                writeConnection?.prepareStatement(sql)?.use { statement ->
                    for (event in batch) {
                        statement.setLong(1, event.timestamp)
                        statement.setString(2, event.type.name)
                        statement.setString(3, event.world)
                        if (event.x == null) statement.setNull(4, java.sql.Types.INTEGER) else statement.setInt(4, event.x)
                        if (event.y == null) statement.setNull(5, java.sql.Types.INTEGER) else statement.setInt(5, event.y)
                        if (event.z == null) statement.setNull(6, java.sql.Types.INTEGER) else statement.setInt(6, event.z)
                        statement.setString(7, event.actor?.toString())
                        statement.setString(8, event.actorName)
                        statement.setString(9, event.subject?.toString())
                        statement.setString(10, event.beforeState)
                        statement.setString(11, event.afterState)
                        statement.setString(12, event.metadata)
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            } catch (exception: Exception) {
                plugin.logger.log(java.util.logging.Level.SEVERE, "Could not write ${batch.size} rollback events", exception)
            } finally {
                pendingEvents.addAndGet(-batch.size.toLong())
                writing = false
            }
        }
    }

    fun close() {
        running = false
        writer.shutdown()
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.logger.warning("Database writer did not finish before shutdown")
                writer.shutdownNow()
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            writer.shutdownNow()
        }
        runCatching { writeConnection?.close() }
        writeConnection = null
        databaseUrl = null
    }

    private fun java.sql.ResultSet.toEvent(): RollbackEvent = RollbackEvent(
        id = getLong("id"),
        timestamp = getLong("timestamp"),
        type = runCatching { EventType.valueOf(getString("type")) }.getOrDefault(EventType.BLOCK),
        world = getString("world"),
        x = getIntOrNull("x"),
        y = getIntOrNull("y"),
        z = getIntOrNull("z"),
        actor = getString("actor_uuid")?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() },
        actorName = getString("actor_name"),
        subject = getString("subject_uuid")?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() },
        beforeState = getString("before_state"),
        afterState = getString("after_state"),
        metadata = getString("metadata"),
    )

    private fun java.sql.ResultSet.getIntOrNull(column: String): Int? {
        val value = getInt(column)
        return if (wasNull()) null else value
    }

    private fun configureConnection(connection: Connection) {
        if (backend == "sqlite") {
            connection.createStatement().use { statement -> statement.execute("PRAGMA busy_timeout=$busyTimeoutMs") }
        }
    }

    private fun migrateLegacyBlocks(connection: Connection, statement: java.sql.Statement) {
        val cursor = connection.prepareStatement(
            "SELECT value FROM rollback_migrations WHERE name = 'block_changes'"
        ).use { query ->
            query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        } ?: run {
            val legacyCount = statement.executeQuery(
                "SELECT COUNT(*) FROM events WHERE metadata = 'migrated-from-block_changes'"
            ).use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            val legacyCursor = if (legacyCount == 0L) 0L else connection.prepareStatement(
                "SELECT id FROM block_changes ORDER BY id LIMIT 1 OFFSET ?"
            ).use { query ->
                query.setLong(1, legacyCount - 1)
                query.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
            }
            connection.prepareStatement(
                "INSERT INTO rollback_migrations(name, value) VALUES ('block_changes', ?)"
            ).use { insert ->
                insert.setLong(1, legacyCursor)
                insert.executeUpdate()
            }
            legacyCursor
        }

        connection.prepareStatement(
            """
            INSERT INTO events(timestamp, type, world, x, y, z, actor_uuid, actor_name, before_state, after_state, metadata)
            SELECT b.timestamp, 'BLOCK', b.world, b.x, b.y, b.z, b.actor_uuid, b.actor_name,
                   b.old_data, b.new_data, 'migrated-from-block_changes:' || b.id
            FROM block_changes b
            WHERE b.id > ?
              AND NOT EXISTS (
                  SELECT 1 FROM events e WHERE e.metadata = 'migrated-from-block_changes:' || b.id
              )
            """.trimIndent()
        ).use { insert ->
            insert.setLong(1, cursor)
            insert.executeUpdate()
        }

        val latestId = statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM block_changes").use { rows ->
            if (rows.next()) rows.getLong(1) else cursor
        }
        connection.prepareStatement(
            "UPDATE rollback_migrations SET value = ? WHERE name = 'block_changes'"
        ).use { update ->
            update.setLong(1, maxOf(cursor, latestId))
            update.executeUpdate()
        }
    }
}
