package com.retrivedmods.wclient.game.world

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import java.util.zip.GZIPInputStream

/**
 * Pure parsers for the two wire layouts the protocol library hands us as opaque [ByteBuf]s.
 *
 * The vendored codec decodes `LevelChunkPacket.data` and `SubChunkData.data` without
 * interpreting their internals (see `LevelChunkSerializer_v486` / `SubChunkSerializer_v486` in
 * `relay/Protocol`), so the app-side [World] parses them here. Only the v486+ (1.20.0+) layouts
 * are supported:
 *
 *  - LevelChunk embedded payload:
 *      u32le format (0 = none, 1 = gzip)
 *      per subchunk (x40 in a 16x320x16 column):
 *          u8 rawBlockStates (must be 1 — the legacy palette path is unsupported)
 *          4096 x i32le runtime block states (x fastest, then z, then y)
 *          u8 heightMapType      (+1024 bytes when 1)
 *          u8 renderHeightMapType (+1024 bytes when 1)
 *  - SubChunkPacket subchunk data: 4096 x i32le runtime block states
 *    (optionally prefixed by a `rawBlockStates` flag byte on some versions).
 *
 * Both parsers are defensive: any structural mismatch yields `null` so the caller keeps the
 * previous (or absent) data instead of storing garbage. Parsers never mutate the caller's
 * buffers.
 */
object ChunkDataParser {

    const val SUB_CHUNK_BLOCK_COUNT = 4096

    private const val FORMAT_NONE = 0
    private const val FORMAT_GZIP = 1
    private const val HEIGHT_MAP_LENGTH = 1024
    private const val MAX_SUB_CHUNKS_PER_COLUMN = 40

    /**
     * Parses the opaque `LevelChunkPacket.data` payload (v486+ layout).
     *
     * @return one [IntArray] of 4096 runtime block states per subchunk, bottom (y=0) to top,
     *         or `null` when the buffer does not match the expected structure.
     */
    fun parseEmbeddedChunk(chunkData: ByteBuf, subChunkCount: Int): List<IntArray>? {
        if (subChunkCount !in 1..MAX_SUB_CHUNKS_PER_COLUMN) return null
        if (chunkData.readableBytes < 4) return null

        val format = chunkData.getIntLE(chunkData.readerIndex())
        val body: ByteArray = when (format) {
            FORMAT_NONE -> copyFrom(chunkData, 4) ?: return null
            FORMAT_GZIP -> {
                val raw = copyFrom(chunkData, 4) ?: return null
                gunzip(raw) ?: return null
            }
            else -> return null
        }

        val buf = Unpooled.wrappedBuffer(body)
        return try {
            val subChunks = ArrayList<IntArray>(subChunkCount)
            for (i in 0 until subChunkCount) {
                if (!readRawSubChunk(buf, subChunks)) return null
            }
            if (buf.readableBytes != 0) null else subChunks
        } finally {
            buf.release()
        }
    }

    /**
     * Parses a decoded `SubChunkData.data` buffer (v486+): 4096 x i32le states. Some versions
     * prefix the array with a `rawBlockStates` flag byte, so both lengths are accepted;
     * anything else yields `null`.
     */
    fun readSubChunkStates(data: ByteBuf): IntArray? {
        val expected = SUB_CHUNK_BLOCK_COUNT * 4
        var offset = 0
        when (data.readableBytes) {
            expected -> {}
            expected + 1 -> {
                if (data.getByte(data.readerIndex()).toInt() != 1) return null
                offset = 1
            }
            else -> return null
        }
        val states = IntArray(SUB_CHUNK_BLOCK_COUNT)
        var idx = data.readerIndex() + offset
        for (i in 0 until SUB_CHUNK_BLOCK_COUNT) {
            states[i] = data.getIntLE(idx)
            idx += 4
        }
        return states
    }

    // ------------------------------------------------------------------

    /** Reads one raw subchunk (flag + 4096 states + both heightmaps) from [buf]. */
    private fun readRawSubChunk(buf: ByteBuf, out: ArrayList<IntArray>): Boolean {
        if (buf.readableBytes < 1 + SUB_CHUNK_BLOCK_COUNT * 4) return false
        if (buf.readByte() != 1.toByte()) return false // legacy palette subchunk — unsupported

        val states = IntArray(SUB_CHUNK_BLOCK_COUNT)
        for (i in 0 until SUB_CHUNK_BLOCK_COUNT) {
            states[i] = buf.readIntLE()
        }
        out.add(states)

        return skipHeightMap(buf) && skipHeightMap(buf)
    }

    /** Heightmap section: 1 type byte, then 1024 map bytes when the type is HAS_DATA (1). */
    private fun skipHeightMap(buf: ByteBuf): Boolean {
        if (buf.readableBytes < 1) return false
        if (buf.readByte().toInt() == 1) {
            if (buf.readableBytes < HEIGHT_MAP_LENGTH) return false
            buf.skipBytes(HEIGHT_MAP_LENGTH)
        }
        return true
    }

    private fun copyFrom(buf: ByteBuf, skip: Int): ByteArray? {
        val length = buf.readableBytes - skip
        if (length < 0) return null
        val bytes = ByteArray(length)
        buf.getBytes(buf.readerIndex() + skip, bytes)
        return bytes
    }

    private fun gunzip(data: ByteArray): ByteArray? = try {
        GZIPInputStream(java.io.ByteArrayInputStream(data)).use { it.readBytes() }
    } catch (e: Exception) {
        null
    }
}
