package com.rollback.ui

import org.bukkit.command.CommandSender
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.entity.Player
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.ComponentBuilder
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent

object RollbackColors {
    private var blue = "§x§5§B§8§D§F§F"
    private var aqua = "§x§5§B§D§9§D§1"
    private var gold = "§x§F§5§C§2§4§F"
    private var white = "§f"
    private var gray = "§7"
    private var dark = "§8"
    private var red = "§c"

    fun configure(colors: ConfigurationSection?) {
        if (colors == null) return
        blue = color(colors.getString("primary", "#5B8DFF")!!)
        aqua = color(colors.getString("accent", "#5BD9D1")!!)
        gold = color(colors.getString("item", "#F5C24F")!!)
        white = color(colors.getString("text", "&f")!!)
        gray = color(colors.getString("muted", "&7")!!)
        dark = color(colors.getString("separator", "&8")!!)
        red = color(colors.getString("negative", "&c")!!)
    }

    private fun color(value: String): String {
        val trimmed = value.trim()
        if (trimmed.matches(Regex("#[0-9a-fA-F]{6}"))) {
            return "§x" + trimmed.substring(1).map { "§${it.uppercaseChar()}" }.joinToString("")
        }
        return trimmed.replace('&', '§')
    }

    private fun prefix(): String = "$blue§lRollBack $dark» $white"

    fun success(message: String): String = "${prefix()}$aqua$message"
    fun info(message: String): String = "$white$message"
    fun hint(message: String): String = "$gray$message"
    fun error(message: String): String = "${prefix()}$red$message"
    fun header(message: String): String = "$blue§l$message"
    fun coordinate(x: Int, y: Int, z: Int): String = "§x§5§B§D§9§D§1X §f$x §x§5§B§D§9§D§1Y §f$y §x§5§B§D§9§D§1Z §f$z"
    fun block(name: String, amount: Int): String = "$gold$name $gray× $white$amount"

    fun sendCard(sender: CommandSender, lines: List<String>) = sender.sendMessage(lines.joinToString("\n"))

    fun sendNavigation(sender: CommandSender, page: Int, pageCount: Int, command: (Int) -> String) {
        if (sender !is Player) {
            sendHint(sender, LocaleManager.text("page-console", "Page {page}/{pages}. Use --page=N.", "page" to page, "pages" to pageCount))
            return
        }
        val components = mutableListOf<TextComponent>()
        if (page > 1) components += button("§b‹", command(page - 1), LocaleManager.text("page-previous", "Previous page"))
        components += TextComponent(" $dark[$white$page$dark/$white$pageCount$dark] ")
        if (page < pageCount) components += button("§b›", command(page + 1), LocaleManager.text("page-next", "Next page"))
        components += TextComponent(" ${gray}${LocaleManager.text("page-hint", "Click to navigate")}")
        sender.spigot().sendMessage(*components.toTypedArray())
    }

    @Suppress("DEPRECATION")
    private fun button(text: String, command: String, hint: String): TextComponent = TextComponent(text).apply {
        clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, command)
        hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, ComponentBuilder(hint).create())
    }

    fun sendSuccess(sender: CommandSender, message: String) = sender.sendMessage(success(message))
    fun sendInfo(sender: CommandSender, message: String) = sender.sendMessage(info(message))
    fun sendHint(sender: CommandSender, message: String) = sender.sendMessage(hint(message))
    fun sendError(sender: CommandSender, message: String) = sender.sendMessage(error(message))
}
