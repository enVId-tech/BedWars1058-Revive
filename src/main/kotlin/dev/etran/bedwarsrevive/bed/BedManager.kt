package dev.etran.bedwarsrevive.bed

import com.andrei1058.bedwars.api.arena.GameState
import com.andrei1058.bedwars.api.arena.IArena
import com.andrei1058.bedwars.api.arena.team.ITeam
import com.andrei1058.bedwars.api.events.gameplay.GameStateChangeEvent
import dev.etran.bedwarsrevive.BedWarsRevive
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.UUID

enum class BedRestoreResult { RESTORED, CREATED, NO_SNAPSHOT, FAILED }

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

    /**
     * Puts the team's bed on the map. Uses the match-start snapshot when there is one; otherwise
     * (the team had no bed at the start, or the plugin was installed mid-game) a new bed is built
     * at the team's configured bed location in the team's colour.
     * Does not touch the team's bed flag.
     */
    fun restore(arena: IArena, team: ITeam): BedRestoreResult {
        val perTeam = snapshots.getOrPut(arena.arenaName) { HashMap() }
        var snapshot = perTeam[team.identity]
        var created = false
        if (snapshot == null) {
            snapshot = try {
                plan(team)
            } catch (t: Throwable) {
                plugin.logger.warning("Could not plan a new bed for ${team.name} in ${arena.arenaName}: $t")
                null
            } ?: return BedRestoreResult.NO_SNAPSHOT
            created = true
        }
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
            if (created) perTeam[team.identity] = snapshot // later restores reuse the same layout
            if (plugin.config.getBoolean("recolor-bed", true)) recolor(team)
            if (created) BedRestoreResult.CREATED else BedRestoreResult.RESTORED
        } catch (t: Throwable) {
            plugin.logger.warning("Failed to restore the bed of ${team.name} in ${arena.arenaName}: $t")
            BedRestoreResult.FAILED
        }
    }

    /**
     * Designs a bed for a team that has no snapshot: the configured bed block is the foot, the head
     * goes on the neighbouring block that is free, has ground under it and is closest to the team spawn.
     */
    private fun plan(team: ITeam): Snapshot? {
        val foot = team.bed?.block ?: return null
        val spawn = team.spawn
        val faces = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

        fun free(b: Block) = b.type == Material.AIR || !b.type.isSolid
        fun ground(b: Block) = b.getRelative(BlockFace.DOWN).type.isSolid

        val facing = faces.maxByOrNull { f ->
            val head = foot.getRelative(f)
            var score = 0.0
            if (free(head)) score += 100
            if (ground(head)) score += 10
            if (spawn != null && spawn.world == head.world) score -= head.location.distanceSquared(spawn) / 1_000_000.0
            score
        } ?: return null
        val head = foot.getRelative(facing)

        val material = team.color.bedMaterial()
        return Snapshot(
            listOf(
                Piece(foot.world, foot.x, foot.y, foot.z, adapter.create(material, false, facing)),
                Piece(head.world, head.x, head.y, head.z, adapter.create(material, true, facing))
            )
        )
    }

    private fun recolor(team: ITeam) {
        try {
            plugin.api.versionSupport.colorBed(team)
        } catch (t: Throwable) {
            plugin.debug("colorBed failed (bed left as snapshotted): $t")
        }
    }
}
