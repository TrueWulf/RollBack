package com.rollback

import com.rollback.command.RollbackCommand
import com.rollback.command.DurationParser
import com.rollback.data.EventDatabase
import com.rollback.listener.BlockChangeListener
import com.rollback.listener.GameplayEventListener
import com.rollback.listener.ProxyCommandListener
import com.rollback.platform.ServerScheduler
import com.rollback.ui.RollbackColors
import com.rollback.ui.LocaleManager
import com.rollback.api.RollBackApi
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class RollBackPlugin : JavaPlugin() {
    lateinit var database: EventDatabase
        private set

    lateinit var serverScheduler: ServerScheduler
        private set

    val api: RollBackApi by lazy { RollBackApi(database) }

    override fun onEnable() {
        saveDefaultConfig()
        mergeConfigurationDefaults()
        RollbackColors.configure(config.getConfigurationSection("ui.colors"))
        LocaleManager.configure(this)

        serverScheduler = ServerScheduler(this)
        database = EventDatabase(this)
        database.start()

        server.pluginManager.registerEvents(BlockChangeListener(database), this)
        server.pluginManager.registerEvents(GameplayEventListener(database, serverScheduler), this)
        val proxyCommands = ProxyCommandListener()
        server.messenger.registerIncomingPluginChannel(this, ProxyCommandListener.CHANNEL, proxyCommands)

        val command = getCommand("rollback")
            ?: error("rollback command is missing from plugin.yml")
        val executor = RollbackCommand(this, database, serverScheduler)
        command.setExecutor(executor)
        command.tabCompleter = executor

        if (config.getBoolean("retention.enabled", false)) {
            val keepMs = DurationParser.parse(config.getString("retention.keep"))
            if (keepMs != null) {
                CompletableFuture.runAsync {
                    runCatching {
                        val deleted = database.purgeBefore(System.currentTimeMillis() - keepMs)
                        logger.info("Retention cleanup removed $deleted old events")
                    }.onFailure { logger.log(java.util.logging.Level.WARNING, "Retention cleanup failed", it) }
                }.orTimeout(30, TimeUnit.SECONDS)
                    .exceptionally { logger.log(java.util.logging.Level.WARNING, "Retention cleanup timed out", it); null }
            } else {
                logger.warning("Invalid retention.keep value; expected a duration such as 30d")
            }
        }

        logger.info("RollBack ${description.version} enabled (${serverScheduler.platformName})")
    }

    fun mergeConfigurationDefaults() {
        val resource = getResource("config.yml") ?: return
        resource.use { input ->
            val defaults = YamlConfiguration.loadConfiguration(InputStreamReader(input, StandardCharsets.UTF_8))
            mergeSection("", defaults)
        }
        saveConfig()
    }

    private fun mergeSection(path: String, defaults: ConfigurationSection) {
        defaults.getKeys(false).forEach { key ->
            val currentPath = if (path.isEmpty()) key else "$path.$key"
            val defaultSection = defaults.getConfigurationSection(key)
            if (defaultSection != null) {
                if (!config.isConfigurationSection(currentPath)) config.createSection(currentPath)
                mergeSection(currentPath, defaultSection)
            } else if (!config.contains(currentPath)) {
                config.set(currentPath, defaults.get(key))
            }
        }
    }

    override fun onDisable() {
        if (::database.isInitialized) database.close()
    }
}
