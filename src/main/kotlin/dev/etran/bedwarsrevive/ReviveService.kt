package dev.etran.bedwarsrevive

import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.arena.team.ITeam
import com.andrei1058.bedwars.api.configuration.ConfigPath
import dev.etran.bedwarsrevive.bed.BedRestoreResult
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffectType

/** A user-facing failure. [key] is a message key from config.yml. */
class ReviveFailure(val key: String, vararg val placeholders: Pair<String, String>) : Exception(key)

enum class ReviveKind { REVIVED, SWITCHED, BED_ONLY }

enum class BedOutcome { NOT_REQUESTED, NO_PERMISSION, ALREADY_INTACT, RESTORED, NO_SNAPSHOT, FAILED }

class ReviveResult(val kind: ReviveKind, val bed: BedOutcome)

/**
 * Puts a player on a team of a running BedWars1058 game, whatever state the player and the team
 * are in: never in the game, dead, alive on another team; team full, team dead, bed gone.
 *
 * Everything here goes through BedWars1058's public API (IArena / ITeam / VersionSupport).
 */
class ReviveService(private val plugin: BedWarsRevive) {

    private val api get() = plugin.api

    fun revive(
        player: Player,
        arena: IArena,
        team: ITeam,
        wantBed: Boolean,
        mayRestoreBed: Boolean
    ): ReviveResult {
        if (arena.status != GameState.playing) throw ReviveFailure("arena-not-playing")
        if (arena.teams.none { it === team }) throw ReviveFailure("unknown-team")

        val currentArena = api.arenaUtil.getArenaByPlayer(player)
        if (currentArena != null && currentArena !== arena) throw ReviveFailure("in-other-arena")

        val alive = currentArena != null && arena.isPlayer(player) && !arena.isSpectator(player)
        val bedWanted = wantBed && mayRestoreBed
        var bedOutcome = when {
            !wantBed -> BedOutcome.NOT_REQUESTED
            !mayRestoreBed -> BedOutcome.NO_PERMISSION
            else -> BedOutcome.NOT_REQUESTED // decided below
        }

        // ---- alive on this very team: the only thing left to do is the bed.
        if (alive && arena.getTeam(player) === team) {
            if (bedWanted && team.isBedDestroyed) {
                bedOutcome = restoreBed(arena, team)
                return ReviveResult(ReviveKind.BED_ONLY, bedOutcome)
            }
            throw ReviveFailure("already-on-team")
        }

        if (alive) {
            if (!plugin.config.getBoolean("allow-team-switch", true)) throw ReviveFailure("switch-disabled")
            if (arena.isReSpawning(player)) throw ReviveFailure("respawning")
        }

        var joinedAsOutsider = false
        try {
            // ---- 1. get the player into the arena at all (never played / left)
            if (currentArena == null) {
                joinArenaAsSpectator(player, arena, team)
                joinedAsOutsider = true
            }

            // ---- 2. make sure they are a *player* (not a spectator) that belongs to no team
            if (alive) {
                detachFromTeams(player, arena)
            } else {
                spectatorToPlayer(player, arena)
                detachFromTeams(player, arena)
            }

            // ---- 3. bed: done before joining so the player's bed hologram shows the right state
            if (bedWanted) {
                bedOutcome = if (team.isBedDestroyed) restoreBed(arena, team) else BedOutcome.ALREADY_INTACT
            }

            // ---- 4. shop / upgrade NPCs for a team that nobody ever played
            val neverHadPlayers = team.membersCache.isEmpty()

            // ---- 5. join the team (NO size check: full teams are allowed on purpose)
            team.addPlayers(player)
            if (neverHadPlayers && plugin.config.getBoolean("spawn-npcs-for-empty-team", true) &&
                arena.config.getBoolean(ConfigPath.ARENA_DISABLE_NPCS_FOR_EMPTY_TEAMS)
            ) {
                try {
                    team.spawnNPCs()
                } catch (t: Throwable) {
                    plugin.logger.warning("Could not spawn the NPCs of ${team.name}: $t")
                }
            }
            team.firstSpawn(player)

            // ---- 6. UI
            refreshSidebar(player, arena)
        } catch (failure: ReviveFailure) {
            rollback(player, arena, joinedAsOutsider)
            throw failure
        } catch (t: Throwable) {
            plugin.logger.severe("Reviving ${player.name} onto ${team.name} in ${arena.arenaName} failed: $t")
            t.printStackTrace()
            rollback(player, arena, joinedAsOutsider)
            throw ReviveFailure("internal-error")
        }

        return ReviveResult(if (alive) ReviveKind.SWITCHED else ReviveKind.REVIVED, bedOutcome)
    }

    // ------------------------------------------------------------------ steps

    /** BedWars1058 only lets late joiners in as spectators, and only if the arena allows spectating. */
    private fun joinArenaAsSpectator(player: Player, arena: IArena, team: ITeam) {
        val originalAllow = arena.isAllowSpectate
        arena.isAllowSpectate = true
        try {
            if (!arena.addSpectator(player, false, team.spawn)) throw ReviveFailure("spectate-failed")
        } finally {
            arena.isAllowSpectate = originalAllow
        }
        if (!arena.isSpectator(player)) throw ReviveFailure("spectate-failed")
    }

    /** Undoes what BedWars1058's spectator mode did to the player and registers them as a player. */
    private fun spectatorToPlayer(player: Player, arena: IArena) {
        player.removePotionEffect(PotionEffectType.INVISIBILITY)
        arena.spectators.remove(player)
        if (arena.isSpectator(player)) {
            plugin.logger.severe(
                "Could not take ${player.name} out of the spectator list of ${arena.arenaName}. " +
                    "BedWars1058 returned a copy of the list; this build needs another approach."
            )
            throw ReviveFailure("internal-error")
        }
        if (!arena.players.contains(player)) arena.players.add(player)
        api.arenaUtil.setArenaByPlayer(player, arena)
        resetPlayerState(player)
        syncVisibility(player, arena)
    }

    private fun detachFromTeams(player: Player, arena: IArena) {
        for (t in arena.teams) {
            if (t.isMember(player)) {
                t.members.remove(player)
                try {
                    t.destroyBedHolo(player)
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun restoreBed(arena: IArena, team: ITeam): BedOutcome {
        if (!team.isBedDestroyed) return BedOutcome.ALREADY_INTACT
        return when (plugin.beds.restore(arena, team)) {
            BedRestoreResult.RESTORED -> {
                team.isBedDestroyed = false
                try {
                    api.scoreboardManager.refreshPlaceholders(arena)
                } catch (_: Throwable) {
                }
                BedOutcome.RESTORED
            }
            BedRestoreResult.NO_SNAPSHOT -> BedOutcome.NO_SNAPSHOT
            BedRestoreResult.FAILED -> BedOutcome.FAILED
        }
    }

    private fun resetPlayerState(player: Player) {
        try {
            player.gameMode = GameMode.SURVIVAL
            player.allowFlight = false
            player.isFlying = false
            player.fireTicks = 0
            player.fallDistance = 0f
            player.foodLevel = 20
            @Suppress("DEPRECATION")
            player.health = minOf(player.maxHealth, 20.0)
        } catch (t: Throwable) {
            plugin.debug("resetPlayerState: $t")
        }
    }

    /** Spectators are hidden from players; a new player must become visible to everyone in the arena. */
    private fun syncVisibility(player: Player, arena: IArena) {
        try {
            val vs = api.versionSupport
            for (other in arena.players) {
                if (other == player) continue
                vs.spigotShowPlayer(other, player)
                vs.spigotShowPlayer(player, other)
            }
            for (spectator in arena.spectators) {
                if (spectator == player) continue
                vs.spigotShowPlayer(spectator, player)
                vs.spigotHidePlayer(player, spectator)
            }
        } catch (t: Throwable) {
            plugin.debug("syncVisibility: $t")
        }
    }

    private fun refreshSidebar(player: Player, arena: IArena) {
        try {
            api.scoreboardManager.giveSidebar(player, arena, false)
            api.scoreboardManager.refreshPlaceholders(arena)
        } catch (t: Throwable) {
            plugin.debug("refreshSidebar: $t")
        }
    }

    /** Best effort: don't leave a half-converted player stuck inside the arena. */
    private fun rollback(player: Player, arena: IArena, joinedAsOutsider: Boolean) {
        if (!joinedAsOutsider) return
        try {
            if (arena.isSpectator(player)) arena.removeSpectator(player, false)
            else if (arena.isPlayer(player)) arena.removePlayer(player, false)
        } catch (t: Throwable) {
            plugin.logger.warning("Rollback for ${player.name} failed: $t")
        }
    }
}
