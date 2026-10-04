package com.retrivedmods.wclient.game.utils.math

import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.LocalPlayer
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import kotlin.math.abs
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
 *  - Rotations are silent. Modules never send anything to the client; they only request a
 *    rotation via [LocalPlayer.silentRotation], and GameSession swaps it into the outgoing
 *    PlayerAuthInputPacket. The player's camera is untouched.
 *  - Aiming is instant. A combat module never has its own rotation speed.
 *  - Orbiting (strafing) around a target never has its own speed either. The angular step each
 *    tick is derived from how fast the player is actually moving, which is whatever the motion
 *    modules (Speed, Bhop, Fly, ...) produced. Standing still means the orbit does not advance.
 */
object RotationUtils {

    /** Rotation needed to look from [from] at [to], packed as (pitch, yaw, headYaw). */
    fun lookAt(from: Vector3f, to: Vector3f): Vector3f {
        val rotation = toRotation(from, to)
        return Vector3f.from(rotation.pitch, rotation.yaw, rotation.yaw)
    }

    fun lookAt(player: Entity, target: Entity): Vector3f = lookAt(player.vec3Position, target.vec3Position)

    /**
     * Hard physical limits, not tuning knobs: a vanilla client cannot turn more than this per tick
     * without it being a camera snap, and the body yaw never strays further than this from the head.
     */
    private const val MAX_TURN_PER_TICK_DEG = 60f
    private const val MAX_BODY_HEAD_DELTA_DEG = 75f

    private const val DEFAULT_WIDTH = 0.6f
    private const val DEFAULT_HEIGHT = 1.8f
    private const val PLAYER_EYE_HEIGHT = 1.62f

    private var jitterPhase = 0

    /**
     * Closest point on the target's hitbox to the observer's eye, given a predicted centre.
     * Target positions for players are already at eye level; mobs are at their feet.
     */
    fun hitboxAimPoint(player: Entity, target: Entity, predictedPosition: Vector3f): Vector3f {
        val width = (target.metadata[EntityDataTypes.WIDTH] as? Float)?.takeIf { it > 0f } ?: DEFAULT_WIDTH
        val height = (target.metadata[EntityDataTypes.HEIGHT] as? Float)?.takeIf { it > 0f } ?: DEFAULT_HEIGHT
        val half = width / 2f

        val feetY = if (target is com.retrivedmods.wclient.game.entity.Player) predictedPosition.y - PLAYER_EYE_HEIGHT else predictedPosition.y
        val eye = player.vec3Position

        val x = eye.x.coerceIn(predictedPosition.x - half, predictedPosition.x + half)
        val z = eye.z.coerceIn(predictedPosition.z - half, predictedPosition.z + half)
        // Aim slightly below the top so pitch error does not skim over the box.
        val y = eye.y.coerceIn(feetY + 0.1f, feetY + height - 0.1f)
        return Vector3f.from(x, y, z)
    }

    /**
     * Silently aims the server-side rotation at [point] for the current tick.
     * Nothing is sent to the client.
     *
     * Legitimacy: the turn from the last rotation the server received is capped at a physical
     * per-tick maximum (spread over the next tick, not a speed setting), body yaw stays within
     * the vanilla head/body limit, and a sub-degree deterministic jitter keeps consecutive packets
     * from being bit-identical.
     */
    fun aimSilently(player: LocalPlayer, point: Vector3f): Vector3f {
        val wanted = lookAt(player.vec3Position, point)
        val last = player.serverRotation

        var pitch = wanted.x
        var headYaw = wanted.y

        val yawDelta = getAngleDifference(headYaw, last.z)
        if (abs(yawDelta) > MAX_TURN_PER_TICK_DEG) {
            headYaw = last.z + sign(yawDelta) * MAX_TURN_PER_TICK_DEG
        }
        val pitchDelta = pitch - last.x
        if (abs(pitchDelta) > MAX_TURN_PER_TICK_DEG) {
            pitch = last.x + sign(pitchDelta) * MAX_TURN_PER_TICK_DEG
        }

        // Body yaw follows the head but may lag behind within the vanilla limit.
        var bodyYaw = last.y
        val bodyDelta = getAngleDifference(headYaw, bodyYaw)
        if (abs(bodyDelta) > MAX_BODY_HEAD_DELTA_DEG) {
            bodyYaw = headYaw - sign(bodyDelta) * MAX_BODY_HEAD_DELTA_DEG
        }

        jitterPhase = (jitterPhase + 1) and 7
        val jitter = (jitterPhase - 3.5f) * 0.04f   // +-0.14 deg, deterministic

        val rotation = Vector3f.from(
            (pitch + jitter * 0.5f).coerceIn(-90f, 90f),
            wrapDegrees(bodyYaw + jitter),
            wrapDegrees(headYaw + jitter)
        )
        player.silentRotation = rotation
        return rotation
    }

    private fun sign(v: Float) = if (v < 0f) -1f else 1f

    private fun wrapDegrees(deg: Float): Float {
        var d = deg % 360f
        if (d >= 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
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
