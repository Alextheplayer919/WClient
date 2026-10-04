package com.retrivedmods.wclient.game.utils.math

import com.retrivedmods.wclient.game.entity.Entity
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class Rotation(var yaw: Float, var pitch: Float)

fun toRotation(from: Vector3f, to: Vector3f): Rotation {
    val diffX = (to.x - from.x).toDouble()
    val diffY = (to.y - from.y).toDouble()
    val diffZ = (to.z - from.z).toDouble()
    return Rotation(
        (Math.toDegrees(atan2(diffZ, diffX)).toFloat() - 90f),
        ((-Math.toDegrees(atan2(diffY, sqrt(diffX * diffX + diffZ * diffZ)))).toFloat())
    )
}

fun getRotationDifference(a: Rotation, b: Rotation) =
    hypot(getAngleDifference(a.yaw, b.yaw), a.pitch - b.pitch)


fun getAngleDifference(a: Float, b: Float) = ((a - b) % 360f + 540f) % 360f - 180f

/**
 * Shared rotation system for combat modules.
 *
 * Design rules:
 *  - Aiming is instant. A combat module never has its own rotation speed; it simply
 *    faces the target on the packet it is about to send.
 *  - Orbiting (strafing) around a target never has its own speed either. The angular
 *    step each tick is derived from how fast the player is actually moving, which is
 *    whatever the motion modules (Speed, Bhop, Fly, ...) produced. If the player is
 *    standing still, the orbit does not advance.
 */
object RotationUtils {

    /** Rotation the local player must have to look at [target], packed as (pitch, yaw, headYaw). */
    fun lookAt(player: Entity, target: Entity): Vector3f {
        val rotation = toRotation(player.vec3Position, target.vec3Position)
        return Vector3f.from(rotation.pitch, rotation.yaw, rotation.yaw)
    }

    /**
     * Rewrites the outgoing [packet] so the server sees the player looking directly at [target].
     * Also updates the local entity so later logic in the same tick sees the aimed rotation.
     */
    fun aim(packet: PlayerAuthInputPacket, player: Entity, target: Entity): Vector3f {
        val rotation = lookAt(player, target)
        packet.rotation = rotation
        player.rotate(rotation)
        return rotation
    }

    /** Horizontal distance the player moved during the last tick (blocks / tick). */
    fun horizontalSpeed(player: Entity): Float = hypot(player.motionX, player.motionZ)

    /**
     * Tracks an orbit around a target where the angular velocity is tied to the player's real
     * movement speed: each tick the player advances along the circle by exactly the distance
     * it moved in the world (arc length = speed), so the orbit is as fast as the motion modules
     * allow and no faster.
     */
    class Orbit {

        private var angle = 0f
        private var targetId: Long? = null

        fun reset() {
            targetId = null
        }

        /**
         * Returns the next orbit position around [target] at [radius], or null if the player is not
         * moving (so there is no movement speed to borrow).
         */
        fun next(player: Entity, target: Entity, radius: Float): Vector3f? {
            val r = radius.coerceAtLeast(0.1f)

            if (targetId != target.runtimeEntityId) {
                // Start the orbit from where the player currently stands relative to the target
                // so there is no snap when a new target is acquired.
                targetId = target.runtimeEntityId
                angle = atan2(player.posZ - target.posZ, player.posX - target.posX)
            }

            val speed = horizontalSpeed(player)
            if (speed < 1e-4f) return null

            angle += speed / r
            if (angle > Math.PI.toFloat() * 2f) angle -= Math.PI.toFloat() * 2f

            return Vector3f.from(
                target.posX + cos(angle) * r,
                player.posY,
                target.posZ + sin(angle) * r
            )
        }
    }
}
