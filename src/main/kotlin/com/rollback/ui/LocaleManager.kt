package com.rollback.ui

import com.rollback.RollBackPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object LocaleManager {
    private var values: Map<String, String> = emptyMap()

    fun configure(plugin: RollBackPlugin) {
        val language = plugin.config.getString("messages.language", "en")
            ?.lowercase()
            ?.replace('-', '_')
            ?: "en"
        values = load(plugin, language) ?: load(plugin, "en").orEmpty()
    }

    fun text(key: String, fallback: String = key, vararg replacements: Pair<String, Any?>): String {
        var value = values[key] ?: fallback
        replacements.forEach { (name, replacement) ->
            value = value.replace("{$name}", replacement?.toString() ?: "")
        }
        return value
    }

    fun action(value: String): String {
        val key = value.lowercase().replace(' ', '_')
        return text("action.$key", value.lowercase())
    }

    private fun load(plugin: RollBackPlugin, language: String): Map<String, String>? {
        val resource = plugin.getResource("locales/$language.yml") ?: return null
        resource.use { input ->
            val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(input, StandardCharsets.UTF_8))
            return yaml.getKeys(true)
                .filter { yaml.isString(it) }
                .associateWith { yaml.getString(it).orEmpty() }
        }
    }
}
