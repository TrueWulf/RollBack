package com.rollback.service

import com.rollback.RollBackPlugin
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant

data class MigrationResult(
    val events: Int,
    val operations: Int,
    val blocks: Int,
    val backup: File,
    val target: File,
)

class DatabaseMigrationService(private val plugin: RollBackPlugin) {
    fun migrateSqliteToDuckdb(): MigrationResult {
        val source = File(plugin.dataFolder, plugin.config.getString("database.file", "rollback.db") ?: "rollback.db")
        require(source.isFile) { "SQLite database does not exist: ${source.name}" }
        val target = File(plugin.dataFolder, plugin.config.getString("database.duckdb-file", "rollback.duckdb") ?: "rollback.duckdb")
        require(source.absoluteFile != target.absoluteFile) { "SQLite and DuckDB paths must be different" }
        require(!target.exists()) { "DuckDB target already exists: ${target.name}; remove it before retrying migration" }
        val backup = File(plugin.dataFolder, "${source.name}.${Instant.now().toEpochMilli()}.bak")
        Files.copy(source.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        Class.forName("org.sqlite.JDBC")
        Class.forName("org.duckdb.DuckDBDriver")
        DriverManager.getConnection("jdbc:sqlite:${source.absolutePath}").use { input ->
            DriverManager.getConnection("jdbc:duckdb:${target.absolutePath}").use { output ->
                createSchema(output, maxId(input, "events"), maxId(input, "rollback_operations"), maxId(input, "block_changes"))
                val events = copyEvents(input, output)
                val operations = copyOperations(input, output)
                val blocks = copyBlocks(input, output)
                return MigrationResult(events, operations, blocks, backup, target)
            }
        }
    }

    private fun createSchema(connection: Connection, eventMax: Long, operationMax: Long, blockMax: Long) {
        connection.createStatement().use { statement ->
            statement.execute("CREATE SEQUENCE IF NOT EXISTS events_id_seq START ${eventMax + 1}")
            statement.execute("CREATE SEQUENCE IF NOT EXISTS rollback_operations_id_seq START ${operationMax + 1}")
            statement.execute("CREATE SEQUENCE IF NOT EXISTS block_changes_id_seq START ${blockMax + 1}")
            statement.execute("""CREATE TABLE IF NOT EXISTS events (
                id BIGINT PRIMARY KEY DEFAULT nextval('events_id_seq'), timestamp BIGINT NOT NULL,
                type VARCHAR NOT NULL, world VARCHAR, x INTEGER, y INTEGER, z INTEGER,
                actor_uuid VARCHAR, actor_name VARCHAR NOT NULL, subject_uuid VARCHAR,
                before_state VARCHAR, after_state VARCHAR, metadata VARCHAR
            )""")
            statement.execute("""CREATE TABLE IF NOT EXISTS rollback_operations (
                id BIGINT PRIMARY KEY DEFAULT nextval('rollback_operations_id_seq'), timestamp BIGINT NOT NULL,
                actor_name VARCHAR NOT NULL, event_ids VARCHAR NOT NULL, applied_event_ids VARCHAR DEFAULT '',
                ready INTEGER DEFAULT 0, undone INTEGER DEFAULT 0
            )""")
            statement.execute("""CREATE TABLE IF NOT EXISTS block_changes (
                id BIGINT PRIMARY KEY DEFAULT nextval('block_changes_id_seq'), timestamp BIGINT NOT NULL,
                world VARCHAR NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
                actor_uuid VARCHAR NOT NULL, actor_name VARCHAR NOT NULL, old_data VARCHAR NOT NULL, new_data VARCHAR NOT NULL
            )""")
            statement.execute("CREATE TABLE IF NOT EXISTS rollback_migrations (name VARCHAR PRIMARY KEY, value BIGINT NOT NULL)")
        }
    }

    private fun maxId(connection: Connection, table: String): Long = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COALESCE(MAX(id), 0) FROM $table").use { rows ->
            if (rows.next()) rows.getLong(1) else 0L
        }
    }

    private fun copyEvents(input: Connection, output: Connection): Int = copyTable(input, output,
        "SELECT id, timestamp, type, world, x, y, z, actor_uuid, actor_name, subject_uuid, before_state, after_state, metadata FROM events ORDER BY id",
        "INSERT INTO events(id, timestamp, type, world, x, y, z, actor_uuid, actor_name, subject_uuid, before_state, after_state, metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

    private fun copyOperations(input: Connection, output: Connection): Int = copyTable(input, output,
        "SELECT id, timestamp, actor_name, event_ids, applied_event_ids, ready, undone FROM rollback_operations ORDER BY id",
        "INSERT INTO rollback_operations(id, timestamp, actor_name, event_ids, applied_event_ids, ready, undone) VALUES (?, ?, ?, ?, ?, ?, ?)")

    private fun copyBlocks(input: Connection, output: Connection): Int = copyTable(input, output,
        "SELECT id, timestamp, world, x, y, z, actor_uuid, actor_name, old_data, new_data FROM block_changes ORDER BY id",
        "INSERT INTO block_changes(id, timestamp, world, x, y, z, actor_uuid, actor_name, old_data, new_data) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")

    private fun copyTable(input: Connection, output: Connection, query: String, insert: String): Int {
        var count = 0
        input.createStatement().use { read ->
            read.executeQuery(query).use { rows ->
                output.prepareStatement(insert).use { write ->
                    while (rows.next()) {
                        for (index in 1..rows.metaData.columnCount) write.setObject(index, rows.getObject(index))
                        write.addBatch()
                        count++
                    }
                    write.executeBatch()
                }
            }
        }
        return count
    }
}
