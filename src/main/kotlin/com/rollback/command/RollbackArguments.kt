package com.rollback.command

import com.rollback.data.EventType
import com.rollback.data.QueryScope

data class RollbackArguments(
    val duration: Long,
    val radius: Int?,
    val actor: String?,
    val actors: List<String> = actor?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty(),
    val type: EventType?,
    val preview: Boolean,
    val scope: QueryScope,
    val page: Int = 1,
    val world: String? = null,
    val include: String? = null,
    val action: String? = null,
    val countOnly: Boolean = false,
)

object RollbackArgumentsParser {
    private val commands = setOf("help", "status", "lookup", "rollback", "restore", "inspect", "undo", "purge", "reload", "page")
    private val optionNames = setOf("time", "radius", "player", "type", "preview", "scope", "near", "chunk", "looking", "page", "world", "include", "action", "count")

    fun parse(
        args: Array<out String>,
        maxRadius: Int = Int.MAX_VALUE,
        defaultDuration: Long? = null,
        defaultScope: QueryScope = QueryScope.LOOKING,
    ): RollbackArguments? {
        val options = args.filter { it.startsWith("--") }
        val flags = setOf("--preview", "--chunk", "--looking", "--count")
        if (options.any { !it.contains('=') && it.lowercase() !in flags }) return null
        if (options.any { it.equals("--preview", true) && options.count { option -> option.equals("--preview", true) } > 1 }) return null
        if (options.any { it.startsWith("--") && !it.equals("--preview", true) && it.substringAfter("--").substringBefore("=").lowercase() !in optionNames }) {
            return null
        }
        if (options.any { it.startsWith("--preview=", true) } && options.count { it.startsWith("--preview=", true) } > 1) return null
        if (optionNames.any { key -> options.count { it.startsWith("--$key=", true) } > 1 }) return null
        val values = args.filterNot { it.startsWith("--") }
        val command = values.firstOrNull()?.lowercase()
        val positional = if (command in commands) values.drop(1) else values
        val hasTime = hasOption(args, "time")
        val duration = if (hasTime) {
            option(args, "time")?.let(DurationParser::parse)
        } else {
            DurationParser.parse(positional.firstOrNull()) ?: if (positional.isEmpty()) defaultDuration else null
        } ?: return null

        var positionalIndex = if (hasTime) 0 else 1
        val hasRadius = hasOption(args, "radius")
        val radius = if (hasRadius) {
            option(args, "radius")?.toIntOrNull() ?: return null
        } else {
            positional.getOrNull(positionalIndex)?.toIntOrNull()?.also { positionalIndex++ }
        }
        val allowedRadius = maxRadius.coerceAtLeast(0)
        if (radius != null && (radius < 0 || radius > allowedRadius)) return null

        val scopeOptions = listOf(
            hasFlag(args, "chunk") to QueryScope.CHUNK,
            hasFlag(args, "looking") to QueryScope.LOOKING,
            hasOption(args, "near") to QueryScope.RADIUS,
        ).filter { it.first }
        if (scopeOptions.size > 1) return null
        val explicitScope = option(args, "scope")?.let { value ->
            runCatching { QueryScope.valueOf(value.uppercase()) }.getOrNull()
        }
        if (hasOption(args, "scope") && explicitScope == null) return null
        if (explicitScope != null && scopeOptions.isNotEmpty() && explicitScope != scopeOptions.first().second) return null
        val scope = explicitScope ?: scopeOptions.firstOrNull()?.second
            ?: if (radius == null && !hasRadius && !hasOption(args, "near") && command in setOf("lookup", "rollback")) defaultScope else QueryScope.RADIUS
        if (scope != QueryScope.RADIUS && (radius != null || hasOption(args, "near"))) return null
        val nearRadius = option(args, "near")?.toIntOrNull()
        if (hasOption(args, "near") && (nearRadius == null || nearRadius < 0 || nearRadius > allowedRadius)) return null

        val page = option(args, "page")?.toIntOrNull() ?: 1
        if (hasOption(args, "page") && page < 1) return null

        var remaining = positional.drop(positionalIndex)
        val positionalPage = remaining.lastOrNull()?.toIntOrNull()?.takeIf { it > 0 }
        if (positionalPage != null && !hasOption(args, "page")) remaining = remaining.dropLast(1)
        val requestedPage = option(args, "page")?.toIntOrNull() ?: positionalPage ?: page
        if (requestedPage < 1) return null
        val hasType = hasOption(args, "type")
        val type = if (hasType) {
            option(args, "type")?.let(::parseType) ?: return null
        } else {
            remaining.firstNotNullOfOrNull(::parseType)
        }
        val hasPlayer = hasOption(args, "player")
        val actor = if (hasPlayer) {
            option(args, "player")?.takeIf { it.isNotBlank() } ?: return null
        } else {
            remaining.firstOrNull { parseType(it) == null }
        }
        if (remaining.any { parseType(it) == null && it != actor }) return null
        val hasPreviewValue = hasOption(args, "preview")
        if (hasPreviewValue && option(args, "preview")?.lowercase() !in setOf("true", "false")) return null
        val world = option(args, "world")?.takeIf { it.isNotBlank() }
        val include = option(args, "include")?.takeIf { it.isNotBlank() }
        val action = option(args, "action")?.takeIf { it.isNotBlank() }?.uppercase()
        if (action != null && action !in setOf("BLOCK", "+BLOCK", "-BLOCK", "CONTAINER", "+CONTAINER", "-CONTAINER", "INVENTORY", "ITEM", "DEATH", "KILL", "CRAFT")) return null
        return RollbackArguments(
            duration = duration,
            radius = nearRadius ?: radius,
            actor = actor,
            type = type,
            preview = args.any { it.equals("--preview", true) } || option(args, "preview")?.equals("true", true) == true,
            scope = scope,
            page = requestedPage,
            world = world,
            include = include,
            action = action,
            countOnly = hasFlag(args, "count") || option(args, "count")?.equals("true", true) == true,
        )
    }

    private fun option(args: Array<out String>, key: String): String? = args.firstNotNullOfOrNull { argument ->
        argument.substringAfter("--$key=", missingDelimiterValue = "").takeIf { argument.startsWith("--$key=") }
    }

    private fun hasOption(args: Array<out String>, key: String): Boolean = args.any { it.startsWith("--$key=") }

    private fun hasFlag(args: Array<out String>, key: String): Boolean = args.any { it.equals("--$key", true) }

    private fun parseType(value: String): EventType? = EventType.entries.firstOrNull { it.name.equals(value, true) }
}
