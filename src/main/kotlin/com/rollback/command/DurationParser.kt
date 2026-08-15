package com.rollback.command

object DurationParser {
    private val pattern = Regex("^(\\d+)(s|m|h|d)$", RegexOption.IGNORE_CASE)

    fun parse(value: String?): Long? {
        if (value == null) return null
        val match = pattern.matchEntire(value) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val multiplier = when (match.groupValues[2].lowercase()) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            else -> return null
        }
        return amount.takeIf { it <= Long.MAX_VALUE / multiplier }?.times(multiplier)
    }
}
