package com.rollback.service

import com.rollback.data.EventType
import com.rollback.data.RollbackEvent

object EventAggregator {
    private val itemPattern = Regex("^(.+?)[xX](\\d+)$")

    fun aggregate(events: List<RollbackEvent>, windowMs: Long, sameContainerOnly: Boolean = true): List<RollbackEvent> {
        if (events.size < 2 || windowMs <= 0L) return events
        val result = mutableListOf<RollbackEvent>()
        val indexes = mutableMapOf<String, Int>()
        events.sortedWith(compareByDescending<RollbackEvent> { it.timestamp }.thenByDescending { it.id }).forEach { event ->
            val key = mergeKey(event, sameContainerOnly)
            val index = key?.let(indexes::get)
            val previous = index?.let(result::get)
            if (previous != null && canMerge(previous, event, windowMs, sameContainerOnly)) {
                result[index] = merge(previous, event)
            } else {
                if (key != null) indexes[key] = result.size
                result += event
            }
        }
        return result
    }

    private fun mergeKey(event: RollbackEvent, sameContainerOnly: Boolean): String? {
        if (event.type !in setOf(EventType.ITEM, EventType.INVENTORY, EventType.CONTAINER)) return null
        val values = metadata(event.metadata)
        val action = values["action"] ?: event.metadata?.substringBefore(':') ?: return null
        val item = itemBase(values["item"] ?: event.metadata?.substringAfter(':')) ?: return null
        return listOf(
            event.type.name,
            event.actorName,
            action.lowercase(),
            item.lowercase(),
            values["session"].orEmpty(),
            if (sameContainerOnly) values["container"].orEmpty() else "*",
            values["from"].orEmpty(),
            values["to"].orEmpty(),
        ).joinToString("\u0000")
    }

    private fun canMerge(first: RollbackEvent, second: RollbackEvent, windowMs: Long, sameContainerOnly: Boolean): Boolean {
        if (first.type !in setOf(EventType.ITEM, EventType.INVENTORY, EventType.CONTAINER)) return false
        if (first.type != second.type || first.actorName != second.actorName) return false
        val firstMetadata = metadata(first.metadata)
        val secondMetadata = metadata(second.metadata)
        val sameActivity = !firstMetadata["session"].isNullOrBlank() && firstMetadata["session"] == secondMetadata["session"]
        if (!sameActivity && first.timestamp - second.timestamp > windowMs) return false
        val firstAction = firstMetadata["action"] ?: first.metadata?.substringBefore(':')
        val secondAction = secondMetadata["action"] ?: second.metadata?.substringBefore(':')
        val firstItem = itemBase(firstMetadata["item"] ?: first.metadata?.substringAfter(':'))
        val secondItem = itemBase(secondMetadata["item"] ?: second.metadata?.substringAfter(':'))
        return firstAction != null && firstAction.equals(secondAction, true) &&
            firstItem != null && firstItem.equals(secondItem, true) &&
            (!sameContainerOnly || firstMetadata["container"] == secondMetadata["container"]) &&
            firstMetadata["session"] == secondMetadata["session"] &&
            firstMetadata["from"] == secondMetadata["from"] &&
            firstMetadata["to"] == secondMetadata["to"]
    }

    private fun merge(first: RollbackEvent, second: RollbackEvent): RollbackEvent {
        val firstMetadata = metadata(first.metadata)
        val secondMetadata = metadata(second.metadata)
        val firstItem = firstMetadata["item"] ?: first.metadata?.substringAfter(':').orEmpty()
        val secondItem = secondMetadata["item"] ?: second.metadata?.substringAfter(':').orEmpty()
        val item = mergeItem(firstItem, secondItem)
        val mergedMetadata = linkedMapOf<String, String>()
        firstMetadata.forEach { (key, value) -> mergedMetadata[key] = value }
        if (item != null) mergedMetadata["item"] = item
        mergedMetadata["aggregated"] = ((firstMetadata["aggregated"]?.toIntOrNull() ?: 1) + (secondMetadata["aggregated"]?.toIntOrNull() ?: 1)).toString()
        mergedMetadata["count"] = amount(item).toString()
        return first.copy(
            metadata = mergedMetadata.entries.joinToString(";") { (key, value) -> "$key=$value" },
        )
    }

    private fun metadata(value: String?): Map<String, String> = value.orEmpty()
        .split(';')
        .mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
        }
        .toMap()

    private fun itemBase(value: String?): String? = value?.let { itemPattern.matchEntire(it)?.groupValues?.get(1) ?: it }

    private fun amount(value: String?): Int = value?.let { itemPattern.matchEntire(it)?.groupValues?.get(2)?.toIntOrNull() } ?: 1

    private fun mergeItem(first: String, second: String): String? {
        val firstBase = itemBase(first) ?: return null
        if (!firstBase.equals(itemBase(second), true)) return null
        val total = amount(first).toLong() + amount(second).toLong()
        return "${firstBase}x${total.coerceAtMost(Int.MAX_VALUE.toLong())}"
    }
}
