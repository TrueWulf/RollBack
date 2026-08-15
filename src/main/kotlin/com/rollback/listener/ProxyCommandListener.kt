package com.rollback.listener

import org.bukkit.entity.Player
import org.bukkit.plugin.messaging.PluginMessageListener
import java.nio.charset.StandardCharsets

class ProxyCommandListener : PluginMessageListener {
    override fun onPluginMessageReceived(channel: String, player: Player, message: ByteArray) {
        if (channel != CHANNEL) return
        val command = String(message, StandardCharsets.UTF_8).trim().removePrefix("/")
        if (command.isBlank() || command.length > 512 || command.any { it == '\n' || it == '\r' }) return
        player.performCommand(command)
    }

    companion object {
        const val CHANNEL = "rollback:command"
    }
}
