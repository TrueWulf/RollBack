package com.rollback.benchmark

import com.rollback.data.EventType
import java.sql.DriverManager
import kotlin.system.measureTimeMillis

object BenchmarkMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val backend = args.firstOrNull()?.lowercase() ?: "sqlite"
        val file = args.getOrNull(1) ?: "benchmark-$backend.db"
        val url = if (backend == "duckdb") "jdbc:duckdb:$file" else "jdbc:sqlite:$file"
        if (backend == "duckdb") Class.forName("org.duckdb.DuckDBDriver") else Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                if (backend == "sqlite") statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER, timestamp BIGINT, type VARCHAR, world VARCHAR, x INTEGER, y INTEGER, z INTEGER, actor_name VARCHAR, metadata VARCHAR)")
            }
            val writes = measureTimeMillis {
                connection.prepareStatement("INSERT INTO events VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use { statement ->
                    repeat(100_000) { index ->
                        statement.setInt(1, index)
                        statement.setLong(2, System.currentTimeMillis())
                        statement.setString(3, EventType.BLOCK.name)
                        statement.setString(4, "world")
                        statement.setInt(5, index % 1_000)
                        statement.setInt(6, 64)
                        statement.setInt(7, index % 1_000)
                        statement.setString(8, "benchmark")
                        statement.setString(9, "stone")
                        statement.addBatch()
                    }
                    statement.executeBatch()
                }
            }
            val lookup = measureTimeMillis {
                connection.prepareStatement("SELECT COUNT(*) FROM events WHERE world = ? AND x BETWEEN ? AND ? AND z BETWEEN ? AND ?").use { statement ->
                    statement.setString(1, "world")
                    statement.setInt(2, 100)
                    statement.setInt(3, 900)
                    statement.setInt(4, 100)
                    statement.setInt(5, 900)
                    statement.executeQuery().use { it.next() }
                }
            }
            println("backend=$backend writes_100k_ms=$writes lookup_ms=$lookup")
        }
    }
}
