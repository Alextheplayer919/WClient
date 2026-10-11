/*
 * Copyright 2026 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.cloudburstmc.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.cloudburstmc.netty.channel.raknet.RakConstants;
import org.cloudburstmc.netty.channel.raknet.RakReliability;
import org.cloudburstmc.netty.channel.raknet.packet.EncapsulatedPacket;
import org.cloudburstmc.netty.util.SplitPacketHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Covers the reassembly table the relay depends on for every large server payload: chunk data, the block
 * palette and the item registry are all split, and a mis-assembled one of those is what makes a joined
 * world never load.
 */
public class SplitPacketHelperTests {

    private static final PooledByteBufAllocator ALLOC = PooledByteBufAllocator.DEFAULT;

    private static EncapsulatedPacket part(int partId, int partCount, int partIndex, int payloadBytes) {
        ByteBuf buffer = ALLOC.ioBuffer(payloadBytes);
        buffer.writeZero(payloadBytes);

        EncapsulatedPacket packet = EncapsulatedPacket.newInstance();
        packet.setReliability(RakReliability.RELIABLE);
        packet.setSplit(true);
        packet.setPartCount(partCount);
        packet.setPartId(partId);
        packet.setPartIndex(partIndex);
        packet.setBuffer(buffer);
        return packet;
    }

    @Test
    public void reassemblesInOrder() {
        SplitPacketHelper helper = new SplitPacketHelper(0, 3);

        EncapsulatedPacket p0 = part(0, 3, 0, 100);
        Assertions.assertNull(helper.add(p0, ALLOC));
        p0.release();

        EncapsulatedPacket p1 = part(0, 3, 1, 50);
        Assertions.assertNull(helper.add(p1, ALLOC));
        p1.release();

        EncapsulatedPacket p2 = part(0, 3, 2, 25);
        EncapsulatedPacket reassembled = helper.add(p2, ALLOC);
        Assertions.assertNotNull(reassembled);
        Assertions.assertEquals(175, reassembled.getBuffer().readableBytes());
        reassembled.release();
        p2.release();

        helper.release();
    }

    /**
     * The reassembly table is a 256 entry RoundRobinArray indexed by {@code partId & 255}, so a peer that has
     * more than 256 split packets in flight - routine while chunks stream in - aliases onto a slot that is still
     * waiting for its remaining parts. The helper has to know which packet it belongs to, or the two get merged
     * into one corrupt message, or a part index past the aliased helper's length throws out of channelRead.
     */
    @Test
    public void refusesPartsOfAnotherSplitPacket() {
        SplitPacketHelper helper = new SplitPacketHelper(5, 3);

        EncapsulatedPacket first = part(5, 3, 0, 64);
        Assertions.assertTrue(helper.matches(first), "a part of its own packet matches");
        Assertions.assertNull(helper.add(first, ALLOC));
        first.release();

        // Aliases onto the same slot: same partId modulo 256, different split packet.
        EncapsulatedPacket stranger = part(5 + 256, 3, 0, 64);
        Assertions.assertFalse(helper.matches(stranger), "a part of an unrelated packet does not match");
        stranger.release();

        // A part of the aliased packet with an index the in-progress reassembly cannot hold. Before the owner
        // check existed this reached add() and threw IllegalArgumentException into the pipeline.
        EncapsulatedPacket oversized = part(5 + 256, 900, 899, 64);
        Assertions.assertFalse(helper.matches(oversized));
        oversized.release();

        // The in-progress reassembly is untouched: completing it still works and carries only its own parts.
        EncapsulatedPacket p1 = part(5, 3, 1, 64);
        Assertions.assertNull(helper.add(p1, ALLOC));
        p1.release();
        EncapsulatedPacket p2 = part(5, 3, 2, 64);
        EncapsulatedPacket reassembled = helper.add(p2, ALLOC);
        Assertions.assertNotNull(reassembled);
        Assertions.assertEquals(192, reassembled.getBuffer().readableBytes());
        reassembled.release();
        p2.release();

        helper.release();
    }

    @Test
    public void reassembledSizeTracksRetainedBytes() {
        SplitPacketHelper helper = new SplitPacketHelper(0, 3);
        Assertions.assertEquals(0, helper.getReassembledSize());

        EncapsulatedPacket p0 = part(0, 3, 0, 100);
        Assertions.assertNull(helper.add(p0, ALLOC));
        Assertions.assertEquals(100, helper.getReassembledSize());
        p0.release();

        // Duplicate part must not be counted twice.
        EncapsulatedPacket dup = part(0, 3, 0, 100);
        Assertions.assertNull(helper.add(dup, ALLOC));
        Assertions.assertEquals(100, helper.getReassembledSize());
        dup.release();

        EncapsulatedPacket p1 = part(0, 3, 1, 50);
        Assertions.assertNull(helper.add(p1, ALLOC));
        Assertions.assertEquals(150, helper.getReassembledSize());
        p1.release();

        EncapsulatedPacket p2 = part(0, 3, 2, 25);
        EncapsulatedPacket reassembled = helper.add(p2, ALLOC);
        Assertions.assertNotNull(reassembled);
        Assertions.assertEquals(175, helper.getReassembledSize());
        reassembled.release();
        p2.release();

        helper.release();
    }

    @Test
    public void expiresAfterTimeout() {
        SplitPacketHelper helper = new SplitPacketHelper(0, 2);
        Assertions.assertFalse(helper.expired());
        helper.release();
    }

    @Test
    public void testAcceptsMorePartsThanItSends() {
        new SplitPacketHelper(0, RakConstants.MAXIMUM_RECEIVED_SPLIT_COUNT).release();
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SplitPacketHelper(0, RakConstants.MAXIMUM_RECEIVED_SPLIT_COUNT + 1));
    }
}
