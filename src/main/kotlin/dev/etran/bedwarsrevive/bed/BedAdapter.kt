package dev.etran.bedwarsrevive.bed

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import java.lang.reflect.Method

/** One half of a bed as it looked at a point in time. [state] is adapter specific and opaque. */
class BedPiece(val isHead: Boolean, val facing: BlockFace, val state: Any)

/**
 * Reads and writes bed blocks. The plugin is compiled against the 1.8.8 API, so everything that
 * only exists on newer servers (1.13+ block data) lives behind this interface and is reached
 * with reflection. That is what lets a single jar load on 1.8 through the newest releases.
 */
interface BedAdapter {
    fun read(block: Block): BedPiece?
    fun write(block: Block, state: Any)

    /** Builds the state for a brand-new bed half (used when no snapshot of the bed exists). */
    fun create(material: Material, isHead: Boolean, facing: BlockFace): Any

    companion object {
        /** Block data (flattening) exists from 1.13 onwards. */
        fun create(): BedAdapter {
            val modern = try {
                Class.forName("org.bukkit.block.data.BlockData")
                true
            } catch (_: ClassNotFoundException) {
                false
            }
            return if (modern) ModernBedAdapter() else LegacyBedAdapter()
        }

        internal fun isBedMaterial(material: Material): Boolean {
            val n = material.name
            return n == "BED_BLOCK" || n.endsWith("_BED")
        }
    }
}

/** 1.8 - 1.12: the whole bed state is the material plus one data byte. */
@Suppress("DEPRECATION")
class LegacyBedAdapter : BedAdapter {

    private class State(val typeId: Int, val data: Byte)

    override fun read(block: Block): BedPiece? {
        if (!BedAdapter.isBedMaterial(block.type)) return null
        val data = block.data
        val bits = data.toInt()
        val facing = when (bits and 3) {
            0 -> BlockFace.SOUTH
            1 -> BlockFace.WEST
            2 -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
        return BedPiece((bits and 8) != 0, facing, State(block.type.id, data))
    }

    override fun write(block: Block, state: Any) {
        val s = state as State
        block.setTypeIdAndData(s.typeId, s.data, false)
    }

    override fun create(material: Material, isHead: Boolean, facing: BlockFace): Any {
        val bits = when (facing) {
            BlockFace.SOUTH -> 0
            BlockFace.WEST -> 1
            BlockFace.NORTH -> 2
            else -> 3
        }
        // BedWars1058 returns BED_BLOCK here on legacy servers; fall back to it if it ever doesn't.
        val type = if (BedAdapter.isBedMaterial(material)) material else Material.valueOf("BED_BLOCK")
        return State(type.id, (bits or (if (isHead) 8 else 0)).toByte())
    }
}

/**
 * 1.13+ (including the 26.x releases): the state is the block data string,
 * e.g. `minecraft:red_bed[facing=north,occupied=false,part=foot]`.
 */
class ModernBedAdapter : BedAdapter {

    private val blockDataClass = Class.forName("org.bukkit.block.data.BlockData")
    private val getBlockData: Method = Block::class.java.getMethod("getBlockData")
    private val getAsString: Method = blockDataClass.getMethod("getAsString")
    private val createBlockData: Method = Bukkit::class.java.getMethod("createBlockData", String::class.java)
    private val setBlockData: Method =
        Block::class.java.getMethod("setBlockData", blockDataClass, java.lang.Boolean.TYPE)

    private val partRegex = Regex("part=(head|foot)")
    private val facingRegex = Regex("facing=(north|south|east|west)")

    override fun read(block: Block): BedPiece? {
        if (!BedAdapter.isBedMaterial(block.type)) return null
        val data = getAsString.invoke(getBlockData.invoke(block)) as String
        val head = partRegex.find(data)?.groupValues?.get(1) ?: return null
        val facing = facingRegex.find(data)?.groupValues?.get(1) ?: return null
        return BedPiece(head == "head", BlockFace.valueOf(facing.uppercase()), data)
    }

    override fun create(material: Material, isHead: Boolean, facing: BlockFace): Any =
        "minecraft:${material.name.lowercase()}[facing=${facing.name.lowercase()}," +
            "occupied=false,part=${if (isHead) "head" else "foot"}]"

    override fun write(block: Block, state: Any) {
        val data = createBlockData.invoke(null, state as String)
        // applyPhysics = false so the half-placed bed doesn't pop off while we set both pieces.
        setBlockData.invoke(block, data, false)
    }
}
