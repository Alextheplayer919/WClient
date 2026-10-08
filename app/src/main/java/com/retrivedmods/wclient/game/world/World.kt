package com.retrivedmods.wclient.game.world

import com.retrivedmods.wclient.game.GameSession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.SubChunkRequestResult
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.ChangeDimensionPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.SubChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket
import kotlin.math.sqrt

/**
 * Minimal voxel store for the current session — the missing piece this client never had:
 * block states around the player, tracked from the stream.
 *
 * Data sources (all decoded by the vendored codec, see `relay/Protocol`):
 *
 *  | Packet             | Used for                                        |
 *  |--------------------|-------------------------------------------------|
 *  | `SubChunkPacket`   | bulk subchunks (16x8x16, v486+ addressing)       |
 *  | `LevelChunkPacket` | embedded column payload when `requestSubChunks`  |
 *  |                    | is false (v486+ layout only)                     |
 *  | `UpdateBlockPacket`| single-block updates (doors, break/place, ...)   |
 *
 * Version policy: the embedded/palette layouts before v486 (1.20.0) are not parsed, and
 * protocols 801+ ship no MCPEData block-state assets in `assets/mcpedata` yet, so block *names*
 * (and therefore classification) are unavailable there — [getBlockAt] then reports
 * [BlockCategory.UNKNOWN] and placement-style modules stay dormant. Everything degrades to
 * "unknown", never to "wrong".
 *
 * Storage: per dimension, one 4096-entry `IntArray` of runtime block states per loaded 16x8x16
 * subchunk (~16 KB). Thread model: every read/write happens on the session's relay thread
 * (packet callbacks and module `beforePacketBound` are synchronous on that thread), so the maps
 * are deliberately plain `HashMap`s — no locks, no concurrent containers.
 */
@Suppress("MemberVisibilityCanBePrivate")
class World(private val session: GameSession) {

    /** Subchunk world coords (x, y, z) -> 4096 runtime block states, per dimension. */
    private val dimensions = HashMap<Int, MutableMap<Long, IntArray>>()

    /** stateId -> resolved name ("" when the mapping is unavailable). */
    private val nameCache = HashMap<Int, String>()

    /** stateId -> category, so the hot path never touches strings after the first lookup. */
    private val categoryCache = HashMap<Int, BlockCategory>()

    /**
     * Dimension the local player is currently in. `UpdateBlockPacket` carries no dimension, so
     * single-block updates are filed under this one.
     */
    var dimension: Int = 0
        private set

    fun onPacketBound(packet: BedrockPacket) {
        when (packet) {
            is StartGamePacket -> {
                clear()
                dimension = packet.dimensionId
            }

            is ChangeDimensionPacket -> {
                clear()
                dimension = packet.dimension
            }

            is LevelChunkPacket -> onLevelChunk(packet)

            is SubChunkPacket -> onSubChunk(packet)

            is UpdateBlockPacket -> onUpdateBlock(packet)

            else -> {}
        }
    }

    fun onDisconnect() {
        clear()
    }

    fun clear() {
        dimensions.clear()
        nameCache.clear()
        categoryCache.clear()
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    fun getBlockAt(x: Int, y: Int, z: Int): Block? {
        val sub = dimensions[dimension]?.get(subChunkKey(x, y, z)) ?: return null
        val stateId = sub[blockIndex(x, y, z)]
        return Block(stateId, categoryFor(stateId))
    }

    fun getBlockAt(pos: Vector3f): Block? =
        getBlockAt(floorToInt(pos.x), floorToInt(pos.y), floorToInt(pos.z))

    /** True when the subchunk containing the position is loaded. */
    fun isLoaded(x: Int, y: Int, z: Int): Boolean =
        dimensions[dimension]?.containsKey(subChunkKey(x, y, z)) == true

    fun isReplaceable(x: Int, y: Int, z: Int): Boolean =
        getBlockAt(x, y, z)?.isReplaceable == true

    fun isSolid(x: Int, y: Int, z: Int): Boolean =
        getBlockAt(x, y, z)?.isSolid == true

    /**
     * Line of sight between two points (voxel DDA). Unloaded subchunks along the way count as
     * passable — placement is separately gated by [isReplaceable]/[isSolid], which are
     * conservative for unknown data.
     */
    fun canSee(from: Vector3f, to: Vector3f): Boolean {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz).toFloat()
        return VoxelRaycaster.firstOpaque(from, to, dist) { x, y, z ->
            getBlockAt(x, y, z)?.isOpaque == true
        } == null
    }

    // ------------------------------------------------------------------
    // Ingestion
    // ------------------------------------------------------------------

    private fun onLevelChunk(packet: LevelChunkPacket) {
        if (session.protocolVersion < MIN_PROTOCOL) return
        // Modern servers deliver the column via SubChunkPacket instead.
        if (packet.requestSubChunks) return

        val data = packet.data ?: return
        if (!data.isReadable) return

        val subChunks = ChunkDataParser.parseEmbeddedChunk(data, packet.subChunksLength) ?: return
        val dim = if (session.protocolVersion >= 649) packet.dimension else 0
        val map = subChunkMap(dim)
        for ((i, states) in subChunks.withIndex()) {
            map[subChunkKeyRaw(packet.chunkX, i, packet.chunkZ)] = states
        }
        evictIfNecessary(dim)
    }

    private fun onSubChunk(packet: SubChunkPacket) {
        if (session.protocolVersion < MIN_PROTOCOL) return
        val center = packet.centerPosition ?: return
        val map = subChunkMap(packet.dimension)

        for (subChunk in packet.subChunks) {
            val offset = subChunk.position ?: continue

            // v486-v817: center is the full-height column (y=0), offset.y = subchunk index 0..39.
            // v818+: center.y is the 16-block layer (0..19), offset.y = 0..1 within it.
            // The same formula covers both.
            val sx = center.x + offset.x
            val sy = center.y * 16 + offset.y * 8
            val sz = center.z + offset.z

            val states: IntArray? = when (subChunk.result) {
                SubChunkRequestResult.SUCCESS -> subChunk.data?.let(ChunkDataParser::readSubChunkStates)
                SubChunkRequestResult.SUCCESS_ALL_AIR -> IntArray(ChunkDataParser.SUB_CHUNK_BLOCK_COUNT)
                else -> null
            }
            if (states != null) {
                map[subChunkKeyRaw(sx, sy, sz)] = states
            }
        }
        evictIfNecessary(packet.dimension)
    }

    private fun onUpdateBlock(packet: UpdateBlockPacket) {
        if (session.protocolVersion < MIN_PROTOCOL) return
        val pos = packet.blockPosition ?: return
        val stateId = packet.definition?.runtimeId ?: return

        // Subchunk not loaded yet: a later chunk send carries the current state, so there is
        // nothing to store.
        val map = dimensions[dimension] ?: return
        val key = subChunkKey(pos.x, pos.y, pos.z)
        val sub = map[key] ?: return
        sub[blockIndex(pos.x, pos.y, pos.z)] = stateId
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun subChunkMap(dimension: Int): MutableMap<Long, IntArray> =
        dimensions.getOrPut(dimension) { HashMap() }

    /** World coords -> key of the containing subchunk. */
    private fun subChunkKey(x: Int, y: Int, z: Int): Long =
        subChunkKeyRaw(Math.floorDiv(x, 16), Math.floorDiv(y, 8), Math.floorDiv(z, 16))

    /**
     * Key for already-subchunk coordinates. 24-bit x | 8-bit y | 24-bit z — unique for any
     * realistic world.
     */
    private fun subChunkKeyRaw(subX: Int, subY: Int, subZ: Int): Long {
        val fx = subX.toLong() and 0xFFFFFFL
        val fy = subY.toLong() and 0xFFL
        val fz = subZ.toLong() and 0xFFFFFFL
        return (fx shl 32) or (fy shl 24) or fz
    }

    /** x fastest, then z, then y — the wire order of the 4096-entry subchunk arrays. */
    private fun blockIndex(x: Int, y: Int, z: Int): Int =
        (Math.floorMod(y, 8) shl 8) or (Math.floorMod(z, 16) shl 4) or Math.floorMod(x, 16)

    private fun categoryFor(stateId: Int): BlockCategory {
        categoryCache[stateId]?.let { return it }
        val category = BlockCategory.categoryFor(resolveName(stateId))
        categoryCache[stateId] = category
        return category
    }

    private fun resolveName(stateId: Int): String {
        nameCache[stateId]?.let { return it }
        val name = if (session.mappingsLoaded) {
            runCatching { session.blockMapping.getDefinition(stateId).identifier }.getOrNull() ?: ""
        } else {
            ""
        }
        nameCache[stateId] = name
        return name
    }

    /**
     * Coarse memory guard: a runaway/edge-case server must not be able to grow the store
     * without bound. Drop other dimensions first, then reset the current one as a last resort
     * (the server re-sends chunks on demand, so this only costs a short "unknown" window).
     */
    private fun evictIfNecessary(currentDimension: Int) {
        var total = 0
        for (map in dimensions.values) total += map.size
        if (total <= MAX_SUB_CHUNKS) return

        dimensions.keys.removeIf { it != currentDimension }
        val current = dimensions[currentDimension]
        if (current != null && current.size > MAX_SUB_CHUNKS) {
            current.clear()
        }
    }

    private fun floorToInt(v: Float): Int = Math.floor(v.toDouble()).toInt()

    companion object {
        /** v486 = 1.20.0: first version of the subchunk addressing/payload layout we parse. */
        private const val MIN_PROTOCOL = 486

        /** 4096 subchunks x 16 KB = 64 MB worst case. */
        private const val MAX_SUB_CHUNKS = 4096
    }
}
