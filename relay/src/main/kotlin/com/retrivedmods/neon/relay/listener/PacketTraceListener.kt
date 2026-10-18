package com.retrivedmods.neon.relay.listener

import com.retrivedmods.neon.trace.WLevel
import com.retrivedmods.neon.trace.WPacketStats
import com.retrivedmods.neon.trace.WTrace
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.packet.ChunkRadiusUpdatedPacket
import org.cloudburstmc.protocol.bedrock.packet.ClientToServerHandshakePacket
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.NetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackDataInfoPacket
import org.cloudburstmc.protocol.bedrock.packet.ResourcePackStackPacket
import org.cloudburstmc.protocol.bedrock.packet.ResourcePacksInfoPacket
import org.cloudburstmc.protocol.bedrock.packet.ServerToClientHandshakePacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.bedrock.packet.TransferPacket
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket

/**
 * Logs every packet that crosses the relay, with its wire size and packet id.
 *
 * This is the listener that answers "what was the last thing that happened before it froze": the
 * console shows the exact packet sequence per direction, and the packet panel keeps per-type counters
 * with a last-seen age, so a stream that stopped stays visible even in a flood of movement packets.
 *
 * Noise control: chatty packet types (world time, chunk payloads) are rate limited by [WTrace.packet];
 * every type still updates the counters, so the per-type totals stay exact.
 */
class PacketTraceListener : WRelayPacketListener {

    override fun onPacketForwarded(wrapper: BedrockPacketWrapper, clientToServer: Boolean) {
        if (!WTrace.enabled) return
        val packet = wrapper.packet
        val name = packetName(packet)
        val size = wireSize(wrapper)
        val direction = if (clientToServer) WPacketStats.TO_SERVER else WPacketStats.TO_CLIENT

        WTrace.packet(
            direction = direction,
            name = name,
            sizeBytes = size,
            detail = detailFor(packet, direction, size),
            chatty = isChatty(packet),
            level = levelFor(packet)
        )
    }

    private fun wireSize(wrapper: BedrockPacketWrapper): Int {
        val buffer = wrapper.packetBuffer ?: return -1
        return runCatching { buffer.readableBytes() - wrapper.headerLength }.getOrDefault(-1)
    }

    private fun packetName(packet: Any): String {
        if (packet is UnknownPacket) {
            return "UnknownPacket(0x${Integer.toHexString(packet.packetId)})"
        }
        return packet.javaClass.simpleName
    }

    private fun levelFor(packet: Any): WLevel = when (packet) {
        is DisconnectPacket, is TransferPacket -> WLevel.WARN
        is StartGamePacket, is PlayStatusPacket, is ServerToClientHandshakePacket,
        is NetworkSettingsPacket, is ClientToServerHandshakePacket -> WLevel.INFO
        else -> WLevel.DEBUG
    }

    /** Packets that flow constantly on a live server; one line per second per type is plenty. */
    private fun isChatty(packet: Any): Boolean = when (packet) {
        is LevelChunkPacket -> true
        else -> false
    }

    /**
     * Extra context for the packets a join or a stall is read from. Everything else stays a single
     * line: `StartGamePacket.toString()` alone is the whole item registry.
     */
    private fun detailFor(packet: Any, direction: String, size: Int): String? = when (packet) {
        is PlayStatusPacket -> "status=${packet.status} ($direction, ${size}B)"
        is DisconnectPacket -> buildString {
            append("reason=").append(packet.reason)
            append(", messageSkipped=").append(packet.messageSkipped)
            val kick = packet.kickMessage?.toString().orEmpty()
            if (kick.isNotEmpty()) {
                append("\nkick message: ").append(kick.take(300))
            }
            val filtered = packet.filteredMessage?.toString().orEmpty()
            if (filtered.isNotEmpty()) {
                append("\nfiltered message: ").append(filtered.take(300))
            }
        }
        is NetworkSettingsPacket -> buildString {
            append("compression=").append(packet.compressionAlgorithm)
            append(", threshold=").append(packet.compressionThreshold)
            append(", throttleEnabled=").append(packet.clientThrottleEnabled)
            append(", throttleThreshold=").append(packet.clientThrottleThreshold)
        }
        is RequestNetworkSettingsPacket -> "clientProtocol=${packet.protocolVersion}"
        is StartGamePacket -> buildString {
            append("entityId=").append(packet.runtimeEntityId)
            append(", uniqueEntityId=").append(packet.uniqueEntityId)
            append(", gameType=").append(packet.playerGameType)
            append(", world=").append(packet.levelId)
            append(", seed=").append(packet.seed)
            append(", spawn=").append(packet.playerPosition)
            append(", blockPaletteEntries=").append(packet.blockProperties.size)
            append(", itemDefinitions=").append(packet.itemDefinitions.size)
        }
        is ChunkRadiusUpdatedPacket -> "radius=${packet.radius}"
        is TransferPacket -> "address=${packet.address}:${packet.port}, reloadWorld=${packet.reloadWorld}"
        is TextPacket -> "type=${packet.type}, source=${packet.sourceName}, message=${packet.message?.toString()?.take(200)}"
        is ResourcePacksInfoPacket -> buildString {
            append("resourcePacks=").append(packet.resourcePackInfos.size)
            append(", behaviorPacks=").append(packet.behaviorPackInfos.size)
            append(", forcedToAccept=").append(packet.forcedToAccept)
            append(", hasAddonPacks=").append(packet.hasAddonPacks)
        }
        is ResourcePackStackPacket -> buildString {
            append("resourcePacks=").append(packet.resourcePacks.size)
            append(", behaviorPacks=").append(packet.behaviorPacks.size)
            append(", gameVersion=").append(packet.gameVersion)
            append(", forcedToAccept=").append(packet.forcedToAccept)
        }
        is ResourcePackDataInfoPacket -> buildString {
            append("packId=").append(packet.packId)
            append(", chunkCount=").append(packet.chunkCount)
            append(", maxChunkSize=").append(packet.maxChunkSize)
            append(", compressedSize=").append(packet.compressedPackSize)
        }
        is LevelChunkPacket -> runCatching {
            "chunk=(${packet.chunkX},${packet.chunkZ}), subChunks=${packet.subChunksLength}, " +
                "caching=${packet.cachingEnabled}, payload=${packet.data?.readableBytes() ?: 0}B"
        }.getOrNull()
        is ServerToClientHandshakePacket -> "jwt=${packet.jwt.take(48)}…"
        is UnknownPacket -> "raw payload=${packet.payload?.readableBytes() ?: 0}B, id=0x${Integer.toHexString(packet.packetId)}"
        else -> null
    }
}
