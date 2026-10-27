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
 * /revive <team> [bed|nobed] [arena]            join / switch team yourself
 * /revive bed [team] [arena]                    recreate a bed, independent of any revival
 * /revive set <player> <team> [bed|nobed] [arena]   admin: put someone on a team
 * /revive eliminate <player>                    admin: put someone into the dead-spectator role
 * /revive teams [arena] | /revive reload
 */
class ReviveCommand(private val plugin: BedWarsRevive) : CommandExecutor, TabCompleter {

    private val msg get() = plugin.messages
    private val bedWords = mapOf("bed" to true, "nobed" to false)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            if (sender.hasPermission("bwrevive.use")) msg.send(sender, "usage") else deny(sender)
            return true
        }
        when (args[0].lowercase()) {
            "reload" -> {
                if (!sender.hasPermission("bwrevive.admin")) return deny(sender)
                plugin.reload()
                msg.send(sender, "reloaded")
            }
            "teams" -> {
                if (!sender.hasPermission("bwrevive.use")) return deny(sender)
                val arena = resolveArena(sender, args.getOrNull(1)) ?: return true
                listTeams(sender, arena)
            }
            "bed" -> bedCommand(sender, args)
            "set" -> setCommand(sender, args)
            "eliminate", "spectate" -> eliminateCommand(sender, args)
            else -> selfCommand(sender, args)
        }
        return true
    }

    // ------------------------------------------------------------------ /revive <team> ...

    private fun selfCommand(sender: CommandSender, args: Array<out String>) {
        if (sender !is Player) return msg.send(sender, "player-only")
        if (!sender.hasPermission("bwrevive.use")) { deny(sender); return }

        val (flag, arenaName) = parseOptions(args.drop(1))
        val arena = resolveArena(sender, arenaName) ?: return
        val team = findTeam(arena, args[0]) ?: return msg.send(sender, "unknown-team", "teams" to teamNames(arena))

        if (plugin.api.arenaUtil.getArenaByPlayer(sender) == null) {
            if (!plugin.config.getBoolean("allow-outsiders", true)) return msg.send(sender, "outsiders-disabled")
            if (!sender.hasPermission("bwrevive.outsider")) return msg.send(sender, "no-outsider-permission")
        }
        perform(
            actor = sender, target = sender, arena = arena, team = team,
            wantBed = flag ?: plugin.config.getBoolean("bed-by-default", false),
            mayBed = sender.hasPermission("bwrevive.bed"),
            maySwitch = sender.hasPermission("bwrevive.switch")
        )
    }

    // ------------------------------------------------------------------ /revive bed [team] [arena]

    private fun bedCommand(sender: CommandSender, args: Array<out String>) {
        val own = sender.hasPermission("bwrevive.bed")
        val any = sender.hasPermission("bwrevive.admin.bed")
        if (!own && !any) { deny(sender); return }

        val arena = resolveArena(sender, args.getOrNull(2)) ?: return
        val team: ITeam = if (args.size >= 2) {
            findTeam(arena, args[1]) ?: return msg.send(sender, "unknown-team", "teams" to teamNames(arena))
        } else {
            (sender as? Player)?.let { arena.getTeam(it) } ?: return msg.send(sender, "bed-needs-team")
        }
        val isOwnTeam = sender is Player && arena.getTeam(sender) === team
        if (!any && !isOwnTeam) return msg.send(sender, "bed-other-no-permission")

        val label = coloredName(team)
        try {
            // Admins re-place the bed even when it still stands; everyone else only when it's gone.
            when (plugin.service.restoreBed(arena, team, force = any)) {
                BedOutcome.RESTORED -> msg.send(sender, "bed-recreated", "team" to label)
                BedOutcome.ALREADY_INTACT -> msg.send(sender, "bed-already-intact", "team" to label)
                BedOutcome.NO_SNAPSHOT -> msg.send(sender, "bed-no-snapshot")
                else -> msg.send(sender, "bed-failed")
            }
        } catch (f: ReviveFailure) {
            msg.send(sender, f.key, *f.placeholders)
        }
    }

    // ------------------------------------------------------------------ /revive set <player> <team> ...

    private fun setCommand(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("bwrevive.admin.move")) { deny(sender); return }
        if (args.size < 3) return msg.send(sender, "usage")

        val target = Bukkit.getPlayerExact(args[1]) ?: return msg.send(sender, "player-not-found", "player" to args[1])
        val (flag, arenaName) = parseOptions(args.drop(3))
        val arena = resolveArena(sender, arenaName, forPlayer = target) ?: return
        val team = findTeam(arena, args[2]) ?: return msg.send(sender, "unknown-team", "teams" to teamNames(arena))

        perform(
            actor = sender, target = target, arena = arena, team = team,
            wantBed = flag ?: plugin.config.getBoolean("bed-by-default", false),
            mayBed = true, maySwitch = true
        )
    }

    // ------------------------------------------------------------------ /revive eliminate <player>

    private fun eliminateCommand(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("bwrevive.admin.eliminate")) { deny(sender); return }
        if (args.size < 2) return msg.send(sender, "usage")

        val target = Bukkit.getPlayerExact(args[1]) ?: return msg.send(sender, "player-not-found", "player" to args[1])
        val arena = plugin.api.arenaUtil.getArenaByPlayer(target) ?: return msg.send(sender, "target-not-alive")
        try {
            plugin.service.eliminate(target, arena)
        } catch (f: ReviveFailure) {
            return msg.send(sender, f.key, *f.placeholders, "arena" to arena.displayName)
        }
        msg.send(sender, "eliminated", "player" to target.name)
        if (target != sender) msg.send(target, "eliminated-target")
    }

    // ------------------------------------------------------------------ shared

    /** Runs a revive and reports it to the target, the actor (if different) and the arena. */
    private fun perform(
        actor: CommandSender, target: Player, arena: IArena, team: ITeam,
        wantBed: Boolean, mayBed: Boolean, maySwitch: Boolean
    ) {
        val result = try {
            plugin.service.revive(target, arena, team, wantBed, mayBed, maySwitch)
        } catch (f: ReviveFailure) {
            msg.send(actor, f.key, *f.placeholders, "arena" to arena.displayName, "teams" to teamNames(arena))
            return
        }

        val label = coloredName(team)
        if (actor === target) {
            when (result.kind) {
                ReviveKind.REVIVED -> msg.send(target, "revived", "team" to label)
                ReviveKind.SWITCHED -> msg.send(target, "switched", "team" to label)
                ReviveKind.BED_ONLY -> msg.send(target, "bed-only", "team" to label)
            }
        } else {
            msg.send(actor, "admin-moved", "player" to target.name, "team" to label)
            msg.send(target, "moved-by-admin", "team" to label)
        }
        when (result.bed) {
            BedOutcome.RESTORED -> if (result.kind != ReviveKind.BED_ONLY) msg.send(target, "bed-restored")
            BedOutcome.NO_PERMISSION -> msg.send(target, "bed-no-permission")
            BedOutcome.ALREADY_INTACT -> msg.send(actor, "bed-intact")
            BedOutcome.NO_SNAPSHOT -> msg.send(actor, "bed-no-snapshot")
            BedOutcome.FAILED -> msg.send(actor, "bed-failed")
            BedOutcome.NOT_REQUESTED -> Unit
        }

        if (plugin.config.getBoolean("broadcast", true) && result.kind != ReviveKind.BED_ONLY) {
            val key = if (result.kind == ReviveKind.SWITCHED) "broadcast-switched" else "broadcast-revived"
            val text = msg.format(key, "player" to target.name, "team" to label)
            for (p in arena.players + arena.spectators) p.sendMessage(text)
        }
    }

    private fun deny(sender: CommandSender): Boolean {
        msg.send(sender, "no-permission")
        return true
    }

    /** Splits trailing arguments into an optional bed flag and an optional arena name. */
    private fun parseOptions(rest: List<String>): Pair<Boolean?, String?> {
        var flag: Boolean? = null
        var arena: String? = null
        for (token in rest) {
            val f = bedWords[token.lowercase()]
            if (f != null) flag = f else arena = token
        }
        return flag to arena
    }

    /** Explicit name, then the player's own arena, then "the only running game". */
    private fun resolveArena(sender: CommandSender, name: String?, forPlayer: Player? = null): IArena? {
        if (name != null) {
            val found = findArena(name)
            if (found == null) msg.send(sender, "unknown-arena", "arena" to name)
            return found
        }
        val whose = forPlayer ?: sender as? Player
        whose?.let { plugin.api.arenaUtil.getArenaByPlayer(it) }?.let { return it }
        val running = plugin.api.arenaUtil.arenas.filter { it.status == GameState.playing }
        if (running.size == 1) return running[0]
        msg.send(sender, "no-arena", "arenas" to if (running.isEmpty()) "-" else running.joinToString { it.arenaName })
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

    private fun listTeams(sender: CommandSender, arena: IArena) {
        msg.send(sender, "teams-header", "arena" to arena.displayName)
        for (team in arena.teams) {
            val bed = msg.format(if (team.isBedDestroyed) "bed-dead" else "bed-alive")
            sender.sendMessage(
                msg.format("team-line", "team" to coloredName(team), "members" to team.size.toString(), "bed" to bed)
            )
        }
    }

    // ------------------------------------------------------------------ tab completion

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val util = plugin.api.arenaUtil
        val arena = (sender as? Player)?.let { util.getArenaByPlayer(it) }
            ?: util.arenas.singleOrNull { it.status == GameState.playing }
        val arenaNames = util.arenas.filter { it.status == GameState.playing }.map { it.arenaName }
        val teams = (arena?.teams ?: emptyList()).map { it.name }
        val players = Bukkit.getOnlinePlayers().map { it.name }

        val sub = args.getOrNull(0)?.lowercase()
        val options: List<String> = when {
            args.size == 1 -> {
                val subs = mutableListOf("teams")
                if (sender.hasPermission("bwrevive.bed") || sender.hasPermission("bwrevive.admin.bed")) subs += "bed"
                if (sender.hasPermission("bwrevive.admin.move")) subs += "set"
                if (sender.hasPermission("bwrevive.admin.eliminate")) subs += "eliminate"
                if (sender.hasPermission("bwrevive.admin")) subs += "reload"
                teams + subs
            }
            sub == "teams" -> arenaNames
            sub == "bed" -> if (args.size == 2) teams else arenaNames
            sub == "set" -> when (args.size) {
                2 -> players
                3 -> teams
                else -> listOf("bed", "nobed") + arenaNames
            }
            sub == "eliminate" || sub == "spectate" -> if (args.size == 2) players else emptyList()
            args.size == 2 -> listOf("bed", "nobed") + arenaNames
            else -> arenaNames
        }
        val prefix = args.last().lowercase()
        return options.filter { it.lowercase().startsWith(prefix) }.distinct()
    }
}
