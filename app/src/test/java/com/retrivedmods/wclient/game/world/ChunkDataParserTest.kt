package com.retrivedmods.wclient.game.world

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * The vendored codec hands `LevelChunkPacket.data` / `SubChunkData.data` to the app as opaque
 * buffers; these tests pin down the v486+ payload layout the parser expects, including the
 * defensive "null on anything unexpected" contract.
 */
class ChunkDataParserTest {

    /** One subchunk of [count] distinct states starting at [start], raw flag 1. */
    private fun subChunkPayload(buf: ByteBuf, start: Int) {
        buf.writeByte(1) // rawBlockStates
        for (i in 0 until ChunkDataParser.SUB_CHUNK_BLOCK_COUNT) {
            buf.writeIntLE(start + i)
        }
        buf.writeByte(0) // heightMapType: NO_DATA
        buf.writeByte(0) // renderHeightMapType: NO_DATA
    }

    @Test
    fun parsesUncompressedRawSubChunks() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(0) // format: none
        subChunkPayload(buf, 0)
        subChunkPayload(buf, 1000)

        val out = ChunkDataParser.parseEmbeddedChunk(buf, 2)

        assertEquals(2, out!!.size)
        assertEquals(0, out[0][0])
        assertEquals(7, out[0][7])
        assertEquals(1000, out[1][0])
        assertEquals(1000 + 4095, out[1][4095])
    }

    @Test
    fun parsesGzippedPayload() {
        val raw = Unpooled.buffer()
        subChunkPayload(raw, 42)

        // Exactly the written bytes — buffer.capacity() can be larger than writerIndex.
        val payload = ByteArray(raw.readableBytes())
        raw.getBytes(raw.readerIndex(), payload)

        val body = ByteArrayOutputStream()
        GZIPOutputStream(body).use { it.write(payload) }

        val buf = Unpooled.buffer()
        buf.writeIntLE(1) // format: gzip
        buf.writeBytes(body.toByteArray())

        val out = ChunkDataParser.parseEmbeddedChunk(buf, 1)

        assertEquals(1, out!!.size)
        assertEquals(42, out[0][0])
        assertEquals(42 + 4095, out[0][4095])
    }

    @Test
    fun skipsHeightMapData() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(0)
        buf.writeByte(1) // rawBlockStates
        for (i in 0 until ChunkDataParser.SUB_CHUNK_BLOCK_COUNT) buf.writeIntLE(i)
        buf.writeByte(1) // heightMapType: HAS_DATA
        buf.writeZero(1024)
        buf.writeByte(1) // renderHeightMapType: HAS_DATA
        buf.writeZero(1024)

        val out = ChunkDataParser.parseEmbeddedChunk(buf, 1)

        assertEquals(1, out!!.size)
        assertEquals(4095, out[0][4095])
    }

    @Test
    fun rejectsUnknownFormat() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(7)
        buf.writeZero(64)
        assertNull(ChunkDataParser.parseEmbeddedChunk(buf, 1))
    }

    @Test
    fun rejectsLegacyPaletteSubChunk() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(0)
        buf.writeByte(0) // rawBlockStates = 0 -> legacy palette, unsupported
        buf.writeZero(64)
        assertNull(ChunkDataParser.parseEmbeddedChunk(buf, 1))
    }

    @Test
    fun rejectsTruncatedPayload() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(0)
        buf.writeByte(1)
        buf.writeZero(100) // far less than 4096 x 4 bytes
        assertNull(ChunkDataParser.parseEmbeddedChunk(buf, 1))
    }

    @Test
    fun rejectsWrongSubChunkCount() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(0)
        subChunkPayload(buf, 0)
        assertNull(ChunkDataParser.parseEmbeddedChunk(buf, 2)) // too few subchunks
        assertNull(ChunkDataParser.parseEmbeddedChunk(buf.copy(), 0)) // invalid count
    }

    @Test
    fun rejectsTrailingGarbage() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(0)
        subChunkPayload(buf, 0)
        buf.writeByte(1) // one byte too many
        assertNull(ChunkDataParser.parseEmbeddedChunk(buf, 1))
    }

    @Test
    fun corruptGzipYieldsNull() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(1)
        buf.writeZero(64)
        assertNull(ChunkDataParser.parseEmbeddedChunk(buf, 1))
    }

    @Test
    fun readSubChunkStatesIsStrict() {
        val ok = Unpooled.buffer()
        for (i in 0 until ChunkDataParser.SUB_CHUNK_BLOCK_COUNT) ok.writeIntLE(i)

        val states = ChunkDataParser.readSubChunkStates(ok)
        assertEquals(ChunkDataParser.SUB_CHUNK_BLOCK_COUNT, states!!.size)
        assertEquals(123, states[123])

        val short = ok.duplicate()
        short.writerIndex(short.writerIndex() - 4)
        assertNull(ChunkDataParser.readSubChunkStates(short))

        val long = ok.duplicate()
        long.writeByte(9)
        assertNull(ChunkDataParser.readSubChunkStates(long))
    }

    @Test
    fun parsingNeverMutatesInputBuffer() {
        val buf = Unpooled.buffer()
        buf.writeIntLE(0)
        subChunkPayload(buf, 0)
        val readerIndexBefore = buf.readerIndex()

        val out = ChunkDataParser.parseEmbeddedChunk(buf, 1)

        assertTrue(out != null)
        assertEquals(readerIndexBefore, buf.readerIndex())
    }
}
