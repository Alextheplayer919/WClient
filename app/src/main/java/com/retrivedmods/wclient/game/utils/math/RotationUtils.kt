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
 *  - Orbiting (strafing) has no speed of its own either. The travel each tick is the distance the
 *    player actually covered on their own - i.e. whatever the fly/motion modules are doing - and
 *    the orbit only bends that travel around the target. Standing still means no orbit.
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
     *
     * @param eyeHeight aim from the player's feet (`0f`, combat default) or from the eye —
     *                  block-placement modules aim at faces at feet level, so they pass the
     *                  real eye height (pitch error otherwise is ~atan(1.62 / distance)).
     */
    fun aimSilently(player: LocalPlayer, point: Vector3f, eyeHeight: Float = 0f): Vector3f {
        val origin = if (eyeHeight == 0f) player.vec3Position else player.vec3Position.add(0f, eyeHeight, 0f)
        val wanted = lookAt(origin, point)
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

    /** Which way the player travels around the target while spinning. */
    enum class SpinDirection {
        /** Counter-clockwise around the target: the player's left when facing it. */
        LEFT,

        /** Clockwise around the target: the player's right when facing it. */
        RIGHT
    }

    /**
     * Tracks an orbit around a target whose pace is the player's own movement instead of a
     * configured speed. The fly module decides how fast the player travels and this class only
     * bends that travel around the target, so there is nothing to tune and no "blocks per second"
     * anywhere: standing still means no orbit, fast fly means a fast spin.
     *
     * Each tick advances the player around the target by exactly the distance the client covered on
     * its own since the last position this class commanded - i.e. the fly's pace - while walking
     * them back onto the configured radius. The measurement deliberately excludes the commanded
     * steps, so the orbit cannot feed its own speed back into itself, and what stops the player from
     * being carried past the target is the radius, not a speed limit.
     */
    class Orbit {

        companion object {
            /**
             * Above this per tick the "movement" is a lagback/teleport rather than flying; the
             * orbit releases instead of chasing it. High enough for any legit motion module.
             */
            private const val MAX_PLAUSIBLE_TRAVEL_PER_TICK = 8f

            /** Below this the player is not going anywhere and there is nothing to bend. */
            private const val MIN_TRAVEL_PER_TICK = 0.02f

            private const val MIN_RADIUS = 0.6f
            private const val FALLBACK_RADIUS = 2.5f
            private const val CORRECTION_SETTLE_TICKS = 3

            /** Rises instantly with the fly, decays smoothly, so one noisy tick cannot double it. */
            private const val SPEED_DECAY = 0.5f

            /** Only take over once the player is this close to the orbit; otherwise the fly closes in. */
            private const val CAPTURE_RADIUS_FACTOR = 2f
            private const val CAPTURE_MARGIN = 2f
        }

        private var targetId: Long? = null
        private var lastTick: Long? = null

        /** Last position the orbit told the client to be at; the anchor for the next measurement. */
        private var commandedPosition: Vector3f? = null

        /** Smoothed travel per tick, in blocks per tick, as produced by the player's own movement. */
        private var travelPerTick = 0f
        private var settleTicks = 0

        fun reset() {
            targetId = null
            lastTick = null
            commandedPosition = null
            travelPerTick = 0f
            settleTicks = 0
        }

        /**
         * Drop the old orbit state after a server correction. Waiting a few input ticks before
         * starting again avoids immediately replaying the same position that caused a rubber-band.
         */
        fun onServerCorrection() {
            targetId = null
            lastTick = null
            commandedPosition = null
            travelPerTick = 0f
            settleTicks = CORRECTION_SETTLE_TICKS
        }

        /**
         * Returns the next orbit position around [target], or null when the orbit should not move
         * the player this tick (new target, still settling after a correction, or not moving).
         *
         * [direction] is the requested spin: LEFT is counter-clockwise around the target, RIGHT is
         * clockwise, both as seen from above while the player faces the target.
         */
        fun next(
            player: Entity,
            target: Entity,
            radius: Float,
            tick: Long,
            direction: SpinDirection
        ): Vector3f? {
            val current = player.vec3Position

            // New target: anchor on the player's real position and let the fly move first, so the
            // first measurement is pure player movement rather than a snap onto the orbit.
            if (targetId != target.runtimeEntityId) {
                targetId = target.runtimeEntityId
                lastTick = tick
                commandedPosition = current
                travelPerTick = 0f
                if (settleTicks > 0) settleTicks--
                return null
            }

            val elapsedTicks = (tick - (lastTick ?: tick)).coerceAtLeast(1L).toFloat()
            lastTick = tick

            val anchor = commandedPosition
            if (anchor == null) {
                commandedPosition = current
                return null
            }

            // How far the client moved on its own since the last command: this is the fly's work,
            // with the orbit's own steps subtracted out.
            val freeTravel = hypot(current.x - anchor.x, current.z - anchor.z) / elapsedTicks
            if (!freeTravel.isFinite() || freeTravel > MAX_PLAUSIBLE_TRAVEL_PER_TICK) {
                onServerCorrection()
                return null
            }
            travelPerTick = if (freeTravel > travelPerTick) {
                freeTravel
            } else {
                travelPerTick + (freeTravel - travelPerTick) * SPEED_DECAY
            }

            if (settleTicks > 0) {
                settleTicks--
                commandedPosition = current
                return null
            }
            if (travelPerTick < MIN_TRAVEL_PER_TICK) {
                // Not moving (or not flying): the orbit does not advance on its own.
                commandedPosition = current
                return null
            }

            val r = radius.takeIf { it.isFinite() }?.coerceAtLeast(MIN_RADIUS) ?: FALLBACK_RADIUS
            val relativeX = anchor.x - target.posX
            val relativeZ = anchor.z - target.posZ
            val distanceToTarget = hypot(relativeX, relativeZ)
            if (distanceToTarget < 1e-3f) {
                commandedPosition = current
                return null
            }

            if (distanceToTarget > r * CAPTURE_RADIUS_FACTOR + CAPTURE_MARGIN) {
                // Too far to orbit: let the fly bring us in rather than dragging the player across
                // the map. The distance to the target shrinks by the fly's own speed.
                commandedPosition = current
                return null
            }

            val travel = (travelPerTick * elapsedTicks).coerceAtMost(MAX_PLAUSIBLE_TRAVEL_PER_TICK)
            val currentAngle = atan2(relativeZ, relativeX)
            val arc = travel / r
            val nextAngle = if (direction == SpinDirection.LEFT) currentAngle + arc else currentAngle - arc

            val desiredX = target.posX + cos(nextAngle) * r
            val desiredZ = target.posZ + sin(nextAngle) * r

            var moveX = desiredX - anchor.x
            var moveZ = desiredZ - anchor.z
            val requestedDistance = hypot(moveX, moveZ)
            if (requestedDistance < 1e-4f) {
                commandedPosition = current
                return null
            }

            // The commanded step is never longer than the fly's own travel, so the player careers
            // around the circle at flying speed and an off-radius player is walked back onto it at
            // that same speed instead of being snapped there.
            if (requestedDistance > travel) {
                val scale = travel / requestedDistance
                moveX *= scale
                moveZ *= scale
            }

            // Stepped from our own last command, not from the client's drifting report, so the pace
            // the player travels at is the fly's pace and nothing is added on top of it.
            val next = Vector3f.from(anchor.x + moveX, current.y, anchor.z + moveZ)
            commandedPosition = next
            return next
        }
    }
}
