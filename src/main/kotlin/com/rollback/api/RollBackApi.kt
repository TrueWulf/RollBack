package com.rollback.api

import com.rollback.data.EventDatabase
import com.rollback.data.EventType
import com.rollback.data.RollbackEvent
import java.util.concurrent.CopyOnWriteArrayList

data class LookupRequest(
    val since: Long,
    val type: EventType?,
    val actor: String?,
    val world: String?,
    val limit: Int,
)

data class RollbackRequest(
    val actorName: String,
    val events: List<RollbackEvent>,
    val restore: Boolean,
)

data class OperationResult(
    val operation: String,
    val actorName: String,
    val requested: Int,
    val applied: Int,
    val conflicts: Int = 0,
    val errors: Int = 0,
)

class RollBackApi internal constructor(private val database: EventDatabase) {
    private val lookupCallbacks = CopyOnWriteArrayList<(LookupRequest, List<RollbackEvent>) -> Unit>()
    private val beforeRollbackCallbacks = CopyOnWriteArrayList<(RollbackRequest) -> Boolean>()
    private val rollbackCallbacks = CopyOnWriteArrayList<(OperationResult) -> Unit>()

    fun lookup(since: Long, type: EventType? = null, actor: String? = null, world: String? = null, limit: Int = 100): List<RollbackEvent> =
        database.findSince(since = since, type = type, actorName = actor, world = world, limit = limit).also { events ->
            val request = LookupRequest(since, type, actor, world, limit)
            lookupCallbacks.forEach { callback -> runCatching { callback(request, events.toList()) } }
        }

    fun onLookup(callback: (LookupRequest, List<RollbackEvent>) -> Unit): AutoCloseable = register(lookupCallbacks, callback)

    fun onBeforeRollback(callback: (RollbackRequest) -> Boolean): AutoCloseable = register(beforeRollbackCallbacks, callback)

    fun onRollback(callback: (OperationResult) -> Unit): AutoCloseable = register(rollbackCallbacks, callback)

    internal fun allowRollback(request: RollbackRequest): Boolean = beforeRollbackCallbacks.all { callback -> runCatching { callback(request) }.getOrDefault(false) }

    internal fun complete(result: OperationResult) {
        rollbackCallbacks.forEach { callback -> runCatching { callback(result) } }
    }

    private fun <T> register(list: CopyOnWriteArrayList<T>, callback: T): AutoCloseable {
        list += callback
        return AutoCloseable { list.remove(callback) }
    }
}
