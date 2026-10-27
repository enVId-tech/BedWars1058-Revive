package dev.etran.bedwarsrevive

import org.bukkit.ChatColor
import org.bukkit.command.CommandSender

/** Reads messages from config.yml, applies `&` colour codes and `{placeholder}` values. */
class Messages(private val plugin: BedWarsRevive) {

    private var prefix = ""

    init {
        reload()
    }

    fun reload() {
        prefix = raw("prefix")
    }

    private fun raw(key: String): String =
        plugin.config.getString("messages.$key") ?: key

    fun format(key: String, vararg placeholders: Pair<String, String>): String {
        var text = raw(key)
        for ((name, value) in placeholders) text = text.replace("{$name}", value)
        return ChatColor.translateAlternateColorCodes('&', text)
    }

    fun send(to: CommandSender, key: String, vararg placeholders: Pair<String, String>) {
        to.sendMessage(ChatColor.translateAlternateColorCodes('&', prefix) + format(key, *placeholders))
    }
}
