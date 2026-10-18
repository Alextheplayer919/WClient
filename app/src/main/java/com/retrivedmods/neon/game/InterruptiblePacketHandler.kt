package com.retrivedmods.neon.game

import com.retrivedmods.neon.game.InterceptablePacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

interface InterruptiblePacketHandler {

    fun beforePacketBound(interceptablePacket: InterceptablePacket)

    fun afterPacketBound(packet: BedrockPacket) {}

    fun onDisconnect(reason: String) {}

}