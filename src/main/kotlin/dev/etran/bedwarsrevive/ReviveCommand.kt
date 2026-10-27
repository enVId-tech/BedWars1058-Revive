package dev.etran.bedwarsrevive

import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.arena.team.ITeam
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * /revive <team> [bed|nobed] [arena]
 * /revive teams [arena]
 * /revive reload
 */
class ReviveCommand(private val plugin: BedWarsRevive) : CommandExecutor, TabCompleter {

    private val msg get() = plugin.messages
    private val bedWords = mapOf("bed" to true, "nobed" to false)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
            if (!sender.hasPermission("bwrevive.admin")) return deny(sender)
            plugin.reload()
            msg.send(sender, "reloaded")
            return true
        }

        if (sender !is Player) {
            msg.send(sender, "player-only")
            return true
        }
        if (!sender.hasPermission("bwrevive.use")) return deny(sender)
        if (args.isEmpty()) {
            msg.send(sender, "usage")
            return true
        }

        if (args[0].equals("teams", ignoreCase = true)) {
            val arena = resolveArena(sender, args.getOrNull(1)) ?: return true
            listTeams(sender, arena)
            return true
        }

        // ---- parse: <team> [bed|nobed] [arena]
        val teamName = args[0]
        var wantBed = plugin.config.getBoolean("bed-by-default", false)
        var arenaName: String? = null
        for (extra in args.drop(1)) {
            val flag = bedWords[extra.lowercase()]
            if (flag != null) wantBed = flag else arenaName = extra
        }

        val arena = resolveArena(sender, arenaName) ?: return true
        val team = findTeam(arena, teamName)
        if (team == null) {
            msg.send(sender, "unknown-team", "teams" to teamNames(arena))
            return true
        }

        val outsider = plugin.api.arenaUtil.getArenaByPlayer(sender) == null
        if (outsider) {
            if (!plugin.config.getBoolean("allow-outsiders", true)) {
                msg.send(sender, "outsiders-disabled")
                return true
            }
            if (!sender.hasPermission("bwrevive.outsider")) {
                msg.send(sender, "no-outsider-permission")
                return true
            }
        }

        val result = try {
            plugin.service.revive(sender, arena, team, wantBed, sender.hasPermission("bwrevive.bed"))
        } catch (failure: ReviveFailure) {
            msg.send(sender, failure.key, *failure.placeholders, "arena" to arena.displayName, "teams" to teamNames(arena))
            return true
        }

        val teamLabel = coloredName(team)
        when (result.kind) {
            ReviveKind.REVIVED -> msg.send(sender, "revived", "team" to teamLabel)
            ReviveKind.SWITCHED -> msg.send(sender, "switched", "team" to teamLabel)
            ReviveKind.BED_ONLY -> msg.send(sender, "bed-only", "team" to teamLabel)
        }
        when (result.bed) {
            BedOutcome.RESTORED -> if (result.kind != ReviveKind.BED_ONLY) msg.send(sender, "bed-restored")
            BedOutcome.NO_PERMISSION -> msg.send(sender, "bed-no-permission")
            BedOutcome.ALREADY_INTACT -> msg.send(sender, "bed-intact")
            BedOutcome.NO_SNAPSHOT -> msg.send(sender, "bed-no-snapshot")
            BedOutcome.FAILED -> msg.send(sender, "bed-failed")
            BedOutcome.NOT_REQUESTED -> Unit
        }

        if (plugin.config.getBoolean("broadcast", true) && result.kind != ReviveKind.BED_ONLY) {
            val key = if (result.kind == ReviveKind.SWITCHED) "broadcast-switched" else "broadcast-revived"
            val text = msg.format(key, "player" to sender.name, "team" to teamLabel)
            for (p in arena.players + arena.spectators) p.sendMessage(text)
        }
        return true
    }

    // ------------------------------------------------------------------ helpers

    private fun deny(sender: CommandSender): Boolean {
        msg.send(sender, "no-permission")
        return true
    }

    /** Own arena first, then an explicit name, then "the only running game". */
    private fun resolveArena(player: Player, name: String?): IArena? {
        if (name != null) {
            val found = findArena(name)
            if (found == null) msg.send(player, "unknown-arena", "arena" to name)
            return found
        }
        plugin.api.arenaUtil.getArenaByPlayer(player)?.let { return it }
        val running = plugin.api.arenaUtil.arenas.filter { it.status == GameState.playing }
        if (running.size == 1) return running[0]
        msg.send(player, "no-arena", "arenas" to if (running.isEmpty()) "-" else running.joinToString { it.arenaName })
        return null
    }

    private fun findArena(name: String): IArena? =
        plugin.api.arenaUtil.arenas.firstOrNull {
            it.arenaName.equals(name, true) || it.worldName.equals(name, true) ||
                ChatColor.stripColor(it.displayName).equals(name, true)
        }

    private fun findTeam(arena: IArena, name: String): ITeam? =
        arena.teams.firstOrNull { it.name.equals(name, true) || it.color.name.equals(name, true) }

    private fun teamNames(arena: IArena) = arena.teams.joinToString { it.name }

    private fun coloredName(team: ITeam) = "${team.color.chat()}${team.name}${ChatColor.RESET}"

    private fun listTeams(player: Player, arena: IArena) {
        msg.send(player, "teams-header", "arena" to arena.displayName)
        for (team in arena.teams) {
            val bed = msg.format(if (team.isBedDestroyed) "bed-dead" else "bed-alive")
            player.sendMessage(
                msg.format("team-line", "team" to coloredName(team), "members" to team.size.toString(), "bed" to bed)
            )
        }
    }

    // ------------------------------------------------------------------ tab completion

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (sender !is Player) return emptyList()
        val arena = plugin.api.arenaUtil.getArenaByPlayer(sender)
            ?: plugin.api.arenaUtil.arenas.singleOrNull { it.status == GameState.playing }
        val arenaNames = plugin.api.arenaUtil.arenas.filter { it.status == GameState.playing }.map { it.arenaName }
        val options: List<String> = when {
            args.size == 1 -> {
                val teams = (arena?.teams ?: emptyList()).map { it.name }
                teams + listOf("teams") + if (sender.hasPermission("bwrevive.admin")) listOf("reload") else emptyList()
            }
            args[0].equals("teams", true) -> arenaNames
            args.size == 2 -> listOf("bed", "nobed") + arenaNames
            else -> arenaNames
        }
        val prefix = args.last().lowercase()
        return options.filter { it.lowercase().startsWith(prefix) }.distinct()
    }
}
