package dev.etran.bedwarsrevive.bed

import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.arena.team.ITeam
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent
import dev.etran.bedwarsrevive.BedWarsRevive
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

enum class BedRestoreResult { RESTORED, NO_SNAPSHOT, FAILED }

/**
 * Remembers what every team's bed looked like when the match started, so it can be put back
 * exactly there later.
 */
class BedManager(private val plugin: BedWarsRevive) : Listener {

    private class Piece(val world: org.bukkit.World, val x: Int, val y: Int, val z: Int, val state: Any) {
        fun block(): Block = world.getBlockAt(x, y, z)
    }

    private class Snapshot(val pieces: List<Piece>)

    private val adapter: BedAdapter = BedAdapter.create()

    /** arena name -> team identity -> snapshot */
    private val snapshots = HashMap<String, MutableMap<UUID, Snapshot>>()

    // ---------------------------------------------------------------- capture

    @EventHandler
    fun onStateChange(event: GameStateChangeEvent) {
        val arena = event.arena
        when (event.newState) {
            GameState.playing ->
                // Wait a couple of ticks: BedWars1058 recolours the beds while the game starts.
                plugin.server.scheduler.runTaskLater(plugin, { captureArena(arena) }, 2L)
            GameState.waiting, GameState.restarting -> snapshots.remove(arena.arenaName)
            else -> Unit
        }
    }

    /** Snapshots every team of an arena that is currently running (also used after a reload). */
    fun captureArena(arena: IArena) {
        if (arena.status != GameState.playing) return
        val perTeam = snapshots.getOrPut(arena.arenaName) { HashMap() }
        for (team in arena.teams) {
            if (team.isBedDestroyed || perTeam.containsKey(team.identity)) continue
            try {
                capture(team)?.let { perTeam[team.identity] = it }
                    ?: plugin.debug("No bed found to snapshot for ${team.name} in ${arena.arenaName}")
            } catch (t: Throwable) {
                plugin.logger.warning("Could not snapshot the bed of ${team.name} in ${arena.arenaName}: $t")
            }
        }
    }

    private fun capture(team: ITeam): Snapshot? {
        val block = team.bed?.block ?: return null
        val first = adapter.read(block) ?: return null
        val partnerFace = if (first.isHead) first.facing.oppositeFace else first.facing
        val partnerBlock = block.getRelative(partnerFace)
        val partner = adapter.read(partnerBlock) ?: return null
        // A real pair: opposite halves with the same orientation.
        if (partner.isHead == first.isHead || partner.facing != first.facing) return null
        return Snapshot(
            listOf(
                Piece(block.world, block.x, block.y, block.z, first.state),
                Piece(partnerBlock.world, partnerBlock.x, partnerBlock.y, partnerBlock.z, partner.state)
            )
        )
    }

    // ---------------------------------------------------------------- restore

    /** Puts the bed back where it was at the start of the match. Does not touch the team's bed flag. */
    fun restore(arena: IArena, team: ITeam): BedRestoreResult {
        val snapshot = snapshots[arena.arenaName]?.get(team.identity) ?: return BedRestoreResult.NO_SNAPSHOT
        return try {
            for (piece in snapshot.pieces) {
                val block = piece.block()
                adapter.write(block, piece.state)
                // Make sure BedWars1058 doesn't consider it a player-placed block.
                try {
                    arena.removePlacedBlock(block)
                } catch (_: Throwable) {
                }
            }
            if (plugin.config.getBoolean("recolor-bed", true)) recolor(team)
            BedRestoreResult.RESTORED
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to restore the bed of ${team.name} in ${arena.arenaName}: $t")
            BedRestoreResult.FAILED
        }
    }

    private fun recolor(team: ITeam) {
        try {
            plugin.api.versionSupport.colorBed(team)
        } catch (t: Throwable) {
            plugin.debug("colorBed failed (bed left as snapshotted): $t")
        }
    }
}
