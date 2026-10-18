package com.retrivedmods.neon.game.module.visual

import com.retrivedmods.neon.game.InterceptablePacket
import com.retrivedmods.neon.game.Module
import com.retrivedmods.neon.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket

class PlayerJoinModule : Module("PlayerJoin", ModuleCategory.Visual) {

    private val trackedPlayers = mutableMapOf<String, String>()

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet is PlayerListPacket) {
            when (packet.action) {
                PlayerListPacket.Action.ADD -> {
                    for (entry in packet.entries) {
                        val uuid = entry.uuid.toString()
                        val name = entry.name?.toString() ?: "Unknown"
                        if (!trackedPlayers.containsKey(uuid)) {
                            trackedPlayers[uuid] = name
                            if (uuid != session.localPlayer.uuid.toString()) {
                                session.displayClientMessage("§a[+] §f$name §ajoined")
                            }
                        }
                    }
                }
                PlayerListPacket.Action.REMOVE -> {
                    for (entry in packet.entries) {
                        val uuid = entry.uuid.toString()
                        val name = trackedPlayers.remove(uuid)
                        if (name != null) {
                            session.displayClientMessage("§c[-] §f$name §cleft")
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    override fun onDisabled() {
        trackedPlayers.clear()
    }
}