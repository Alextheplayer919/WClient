package com.retrivedmods.neon.game.module.combat

import com.retrivedmods.neon.game.InterceptablePacket
import com.retrivedmods.neon.game.Module
import com.retrivedmods.neon.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

class AntiKnockbackModule : Module("anti_knockback", ModuleCategory.Combat) {

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet is SetEntityMotionPacket) {


            if (packet.runtimeEntityId == session.localPlayer.runtimeEntityId) {
                interceptablePacket.intercept()
            }
        }
    }
}
