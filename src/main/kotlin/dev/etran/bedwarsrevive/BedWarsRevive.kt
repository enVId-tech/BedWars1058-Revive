package dev.etran.bedwarsrevive

import com.andrei1058.bedwars.api.BedWars
import dev.etran.bedwarsrevive.bed.BedManager
import org.bukkit.plugin.java.JavaPlugin

class BedWarsRevive : JavaPlugin() {

    lateinit var api: BedWars
        private set
    lateinit var messages: Messages
        private set
    lateinit var beds: BedManager
        private set
    lateinit var service: ReviveService
        private set

    override fun onEnable() {
        val registration = server.servicesManager.getRegistration(BedWars::class.java)
        if (registration == null) {
            logger.severe("BedWars1058 API not found. Is BedWars1058 installed and enabled? Disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        api = registration.provider

        saveDefaultConfig()
        messages = Messages(this)

        beds = BedManager(this)
        server.pluginManager.registerEvents(beds, this)
        service = ReviveService(this)

        val command = ReviveCommand(this)
        getCommand("revive")?.apply {
            setExecutor(command)
            tabCompleter = command
        } ?: logger.severe("Command 'revive' is missing from plugin.yml")

        // Cover games that are already running (e.g. after /reload).
        server.scheduler.runTask(this, Runnable {
            for (arena in api.arenaUtil.arenas) beds.captureArena(arena)
        })

        logger.info("Enabled. BedWars1058 server type: ${api.serverType}")
    }

    fun reload() {
        reloadConfig()
        messages.reload()
    }

    fun debug(message: String) {
        if (config.getBoolean("debug", false)) logger.info("[debug] $message")
    }
}
