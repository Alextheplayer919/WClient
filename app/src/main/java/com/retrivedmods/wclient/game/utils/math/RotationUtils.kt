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
 *  - Orbiting (strafing) uses a configured, steady speed rather than noisy sampled player speed.
 *    Output steps are bounded and pause briefly after server corrections, so a noisy sample cannot
 *    produce a position snap.
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

    /**
     * Tracks a bounded orbit around a target at a fixed, caller-configured speed. Each emitted
     * position is rate-limited to avoid snapping, but ordinary player-velocity fluctuations do not
     * change the orbit rate.
     */
    class Orbit {

        companion object {
            private const val DEFAULT_ORBIT_SPEED_PER_TICK = 0.15f
            private const val MIN_ORBIT_SPEED_PER_TICK = 0.05f
            private const val MAX_ORBIT_SPEED_PER_TICK = 0.30f
            private const val MAX_OBSERVED_SPEED_PER_TICK = 3.0f
            private const val MAX_CORRECTION_DISTANCE = 4.0f
            private const val MAX_ANGLE_STEP = 0.35f
            private const val MAX_OUTPUT_STEP = 0.30f
            private const val MIN_OUTPUT_STEP = 0.01f
            private const val CORRECTION_SETTLE_TICKS = 3
        }

        private var targetId: Long? = null
        private var lastPlayerPosition: Vector3f? = null
        private var lastTick: Long? = null
        private var settleTicks = 0

        fun reset() {
            targetId = null
            lastPlayerPosition = null
            lastTick = null
            settleTicks = 0
        }

        /**
         * Drop the old orbit state after a server correction. Waiting a few input ticks before
         * starting again avoids immediately replaying the same position that caused a rubber-band.
         */
        fun onServerCorrection() {
            targetId = null
            lastPlayerPosition = null
            lastTick = null
            settleTicks = CORRECTION_SETTLE_TICKS
        }

        /**
         * Returns the next bounded orbit position around [target]. [speedPerTick] is a stable
         * tangential speed in blocks per game tick, independent of the player's current velocity.
         * Position discontinuities are used only to detect corrections, never to tune orbit speed.
         */
        fun next(
            player: Entity,
            target: Entity,
            radius: Float,
            tick: Long,
            speedPerTick: Float = DEFAULT_ORBIT_SPEED_PER_TICK
        ): Vector3f? {
            val current = player.vec3Position
            if (targetId != target.runtimeEntityId) {
                targetId = target.runtimeEntityId
                lastPlayerPosition = current
                lastTick = tick
                if (settleTicks > 0) settleTicks--
                return null
            }

            val previous = lastPlayerPosition ?: current
            val elapsedTicks = (tick - (lastTick ?: tick - 1L)).coerceAtLeast(1L)
            val dx = current.x - previous.x
            val dy = current.y - previous.y
            val dz = current.z - previous.z
            val observedDistance = hypot(dx, dz)
            val totalObservedDistance = hypot(observedDistance, dy)
            val observedSpeed = observedDistance / elapsedTicks.toFloat()
            lastPlayerPosition = current
            lastTick = tick

            if (settleTicks > 0) {
                settleTicks--
                return null
            }

            // A large discontinuity is a teleport/correction, not useful movement input. Rebase
            // and briefly settle rather than allowing the old orbit to fight the server correction.
            if (!observedSpeed.isFinite() || observedSpeed > MAX_OBSERVED_SPEED_PER_TICK ||
                totalObservedDistance > MAX_CORRECTION_DISTANCE
            ) {
                onServerCorrection()
                return null
            }

            val r = radius.takeIf { it.isFinite() }?.coerceAtLeast(0.1f) ?: 2.5f
            val relativeX = current.x - target.posX
            val relativeZ = current.z - target.posZ
            if (hypot(relativeX, relativeZ) < 0.1f) return null

            val speed = speedPerTick.takeIf { it.isFinite() }
                ?.coerceIn(MIN_ORBIT_SPEED_PER_TICK, MAX_ORBIT_SPEED_PER_TICK)
                ?: DEFAULT_ORBIT_SPEED_PER_TICK
            val travel = (speed * elapsedTicks.toFloat()).coerceAtMost(MAX_OUTPUT_STEP)

            // Rebase the phase on the player's actual position every tick. This keeps the orbit
            // aligned after target movement, small corrections, and radius changes.
            val currentAngle = atan2(relativeZ, relativeX)
            val nextAngle = currentAngle + (travel / r).coerceAtMost(MAX_ANGLE_STEP)
            val desiredX = target.posX + cos(nextAngle) * r
            val desiredZ = target.posZ + sin(nextAngle) * r

            var moveX = desiredX - current.x
            var moveZ = desiredZ - current.z
            val requestedDistance = hypot(moveX, moveZ)
            if (requestedDistance < 1e-4f) return null

            // Keep the absolute step at the configured travel distance. If the player is
            // off-radius or the target moved, convergence is gradual rather than a snap.
            val maxStep = travel.coerceIn(MIN_OUTPUT_STEP, MAX_OUTPUT_STEP)
            if (requestedDistance > maxStep) {
                val scale = maxStep / requestedDistance
                moveX *= scale
                moveZ *= scale
            }

            return Vector3f.from(current.x + moveX, current.y, current.z + moveZ)
        }
    }
}
