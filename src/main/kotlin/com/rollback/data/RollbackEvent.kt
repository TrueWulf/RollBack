package com.rollback.data

import java.util.UUID

enum class EventType {
    BLOCK,
    CONTAINER,
    INVENTORY,
    ITEM,
    DEATH,
    KILL,
    CRAFT,
    CHAT,
    COMMAND,
    SESSION,
    SIGN,
}

enum class QueryScope {
    RADIUS,
    CHUNK,
    LOOKING,
    SELECTION,
}

data class RollbackEvent(
    val id: Long = 0,
    val timestamp: Long,
    val type: EventType,
    val world: String?,
    val x: Int?,
    val y: Int?,
    val z: Int?,
    val actor: UUID?,
    val actorName: String,
    val subject: UUID?,
    val beforeState: String?,
    val afterState: String?,
    val metadata: String?,
)
