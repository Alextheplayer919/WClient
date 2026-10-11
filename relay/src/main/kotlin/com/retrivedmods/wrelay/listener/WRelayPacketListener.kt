package com.retrivedmods.wrelay.listener

import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

interface WRelayPacketListener {

    /**
     * Raw wire view of a packet that is about to be forwarded, called once per packet with the undecoded
     * wrapper: exact payload size, packet id and the decoded object.
     *
     * `clientToServer` is named after the traffic, not after the session it was read from: true means the
     * player's game client sent it and the relay is about to forward it upstream, false means it came back
     * from the server. See `PacketTraceListener`.
     */
    fun onPacketForwarded(wrapper: BedrockPacketWrapper, clientToServer: Boolean) {}

    fun beforeClientBound(packet: BedrockPacket): Boolean {
        return false
    }

    fun beforeServerBound(packet: BedrockPacket): Boolean {
        return false
    }

    fun afterClientBound(packet: BedrockPacket) {}

    fun afterServerBound(packet: BedrockPacket) {}

    fun onDisconnect(reason: String) {}

}