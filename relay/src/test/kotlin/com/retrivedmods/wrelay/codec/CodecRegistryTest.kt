package com.retrivedmods.wrelay.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The registry is the whole of "which game versions can this relay speak", and it fails quietly: codecs are
 * loaded reflectively, so a class that is not vendored only prints a line and the version disappears, and an
 * unregistered protocol falls back to *some* codec instead of refusing to connect. These assertions pin both.
 */
class CodecRegistryTest {

    @Test
    fun `every registered codec actually loaded`() {
        assertTrue(
            CodecRegistry.unregisteredProtocols.isEmpty(),
            "codec classes missing from the vendored Protocol tree: ${CodecRegistry.unregisteredProtocols}"
        )
    }

    @Test
    fun `bedrock 26-12 protocol 944 is an exact match`() {
        val info = VersionInfoProvider.getVersionInfo(944)
        assertTrue(info.isExactMatch, "protocol 944 fell back to ${info.protocolVersion}; it must be registered")
        assertEquals(944, info.codec.protocolVersion)
        assertEquals("1.26.10", info.codec.minecraftVersion)
        assertTrue(
            VersionDetector.getSupportedVersionsForProtocol(944).contains("1.26.12"),
            "26.12 should be listed against protocol 944"
        )
    }

    @Test
    fun `26-0 series protocol 924 is an exact match`() {
        val info = VersionInfoProvider.getVersionInfo(924)
        assertTrue(info.isExactMatch)
        assertEquals("1.26.0", info.codec.minecraftVersion)
    }

    /**
     * The advertisement and the default codec follow the newest registered protocol, so a version bump that
     * forgets this would have the relay announce 1.21.130 to a 26.12 client.
     */
    @Test
    fun `latest codec is the newest registered protocol`() {
        assertEquals(944, CodecRegistry.getLatestCodec().protocolVersion)
    }

    /**
     * Unknown protocols must degrade to the newest codec they can still talk to, not the oldest. This was
     * findLast() over a descending list, which handed a future or preview client the 1.2.0 codec.
     */
    @Test
    fun `unknown newer protocol falls to the newest codec, not the oldest`() {
        assertSame(
            CodecRegistry.getCodecByProtocol(944),
            CodecRegistry.getClosestCodec(950),
            "a protocol newer than anything registered should negotiate as 944"
        )
        assertSame(
            CodecRegistry.getCodecByProtocol(924),
            CodecRegistry.getClosestCodec(925),
            "925 is between two known protocols; it must pick 924, not 291"
        )
    }

    @Test
    fun `protocol older than everything still gets the oldest codec`() {
        assertEquals(291, CodecRegistry.getClosestCodec(200).protocolVersion)
    }
}
