package com.retrivedmods.neon.game.module.motion

import com.retrivedmods.neon.game.InterceptablePacket
import com.retrivedmods.neon.game.Module
import com.retrivedmods.neon.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.sin

class JetPackModule : Module("jetpack", ModuleCategory.Motion) {

    private val speed by floatValue("speed", 2.5f, 1.0f..10.0f)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) {
            return
        }

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            val yaw = Math.toRadians(packet.rotation.y.toDouble())
            val pitch = Math.toRadians(packet.rotation.x.toDouble())

            val motionX = -sin(yaw) * cos(pitch) * speed
            val motionY = -sin(pitch) * speed
            val motionZ = cos(yaw) * cos(pitch) * speed

            val motionPacket = SetEntityMotionPacket().apply {
                runtimeEntityId = session.localPlayer.runtimeEntityId
                motion = Vector3f.from(
                    motionX.toFloat(),
                    motionY.toFloat(),
                    motionZ.toFloat()
                )
            }
            session.clientBound(motionPacket)
        }
    }
}