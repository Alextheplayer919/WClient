package com.retrivedmods.wclient.game.utils.math

import com.retrivedmods.wclient.game.entity.Entity
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Predicts where a target will be a few ticks from now so that rotations aimed through the relay
 * (which sees the target slightly in the past and whose hits reach the server slightly in the
 * future) still line up during fast fights.
 *
 * How positions reach us (per packet):
 *  - Other players: `MovePlayerPacket` (absolute position + rotation) roughly every server tick.
 *  - Mobs / other entities: `MoveEntityAbsolutePacket` or `MoveEntityDeltaPacket`.
 * `Entity.move()` already turns those into `posX/Y/Z` + `motionX/Y/Z`, so we sample the entity's
 * position whenever it changes and keep a short history per target.
 *
 * From the history we derive, in the local player's polar frame (angle / radius around us):
 *  - linear velocity                         -> where a target running in a straight line goes
 *  - angular velocity (strafe left / right)  -> where a target circling us goes
 *  - radial velocity                         -> closing in / backing off
 *  - sign flips of the angular velocity      -> the target is juking, so trust the guess less
 *
 * If the target's motion is dominated by movement *around* us (typical strafing / W-tapping
 * fights) we extrapolate along the arc; otherwise we extrapolate along the straight line.
 */
class TargetPredictor {

    private class Sample(val time: Long, val x: Float, val y: Float, val z: Float)

    private class History {
        val samples = ArrayDeque<Sample>()
        var lastAngularSign = 0f
        var lastFlipTime = 0L
    }

    private val histories = HashMap<Long, History>()

    companion object {
        private const val MAX_SAMPLES = 10
        private const val SAMPLE_TTL_MS = 1500L
        private const val MIN_SAMPLES = 3

        /** Nothing in vanilla Bedrock sustains more than ~1 block / tick without flying. */
        private const val MAX_SPEED_PER_TICK = 1.0f

        /** How long a recent strafe-direction change keeps the prediction damped. */
        private const val FLIP_MEMORY_MS = 350L

        /** Prediction weight while damped by a recent direction change. */
        private const val FLIP_DAMPING = 0.35f

        private const val TICK_MS = 50f
    }

    fun reset() = histories.clear()

    fun forget(entity: Entity) {
        histories.remove(entity.runtimeEntityId)
    }

    /** Record the target's current position. Call this every tick for every candidate target. */
    fun record(target: Entity, now: Long = System.currentTimeMillis()) {
        val history = histories.getOrPut(target.runtimeEntityId) { History() }
        val last = history.samples.lastOrNull()

        // Only store a sample when the server actually moved the entity.
        if (last != null && last.x == target.posX && last.y == target.posY && last.z == target.posZ) return

        history.samples.addLast(Sample(now, target.posX, target.posY, target.posZ))
        while (history.samples.size > MAX_SAMPLES) history.samples.removeFirst()
        while (history.samples.isNotEmpty() && now - history.samples.first().time > SAMPLE_TTL_MS) {
            history.samples.removeFirst()
        }
    }

    /**
     * Predicted position of [target] after [lookaheadTicks] ticks, as seen from [observer].
     * Falls back to the target's current position when there is not enough data.
     */
    fun predict(observer: Entity, target: Entity, lookaheadTicks: Float, now: Long = System.currentTimeMillis()): Vector3f {
        val current = target.vec3Position
        if (lookaheadTicks <= 0f) return current

        val history = histories[target.runtimeEntityId] ?: return current
        val samples = history.samples
        if (samples.size < MIN_SAMPLES) return current

        val newest = samples.last()
        val oldest = samples.first()
        val dtTicks = (newest.time - oldest.time) / TICK_MS
        if (dtTicks < 1f) return current

        // --- Linear model -------------------------------------------------------------------
        var vx = (newest.x - oldest.x) / dtTicks
        var vy = (newest.y - oldest.y) / dtTicks
        var vz = (newest.z - oldest.z) / dtTicks

        val speed = hypot(vx, vz)
        if (speed > MAX_SPEED_PER_TICK) {           // teleport / lag spike: do not extrapolate it
            val k = MAX_SPEED_PER_TICK / speed
            vx *= k; vz *= k
        }

        // --- Polar model around the observer --------------------------------------------------
        val ox = observer.posX
        val oz = observer.posZ

        val angleNew = atan2(newest.z - oz, newest.x - ox)
        val angleOld = atan2(oldest.z - oz, oldest.x - ox)
        val radiusNew = hypot(newest.x - ox, newest.z - oz)
        val radiusOld = hypot(oldest.x - ox, oldest.z - oz)

        val angularVelocity = wrapAngle(angleNew - angleOld) / dtTicks   // rad / tick, + = counter-clockwise
        val radialVelocity = (radiusNew - radiusOld) / dtTicks           // blocks / tick, + = moving away

        // --- Direction-change detection (left <-> right strafe switches) --------------------------
        val recentAngularSign = recentAngularSign(samples, ox, oz)
        if (recentAngularSign != 0f && history.lastAngularSign != 0f && recentAngularSign != history.lastAngularSign) {
            history.lastFlipTime = now
        }
        if (recentAngularSign != 0f) history.lastAngularSign = recentAngularSign

        var weight = 1f
        if (now - history.lastFlipTime < FLIP_MEMORY_MS) weight = FLIP_DAMPING

        // --- Pick the model ----------------------------------------------------------------------
        val tangentialSpeed = abs(angularVelocity) * radiusNew
        val circular = radiusNew > 0.5f && tangentialSpeed > abs(radialVelocity) * 1.5f && tangentialSpeed > 0.03f

        val t = lookaheadTicks * weight

        val px: Float
        val pz: Float
        if (circular) {
            val angle = angleNew + angularVelocity * t
            val radius = (radiusNew + radialVelocity * t).coerceAtLeast(0.1f)
            px = ox + cos(angle) * radius
            pz = oz + sin(angle) * radius
        } else {
            px = newest.x + vx * t
            pz = newest.z + vz * t
        }
        // Vertical motion is dominated by gravity/jumps which are poorly modelled linearly; keep it conservative.
        val py = newest.y + vy * min(t, 2f)

        // Never predict further than the target could physically travel.
        val maxTravel = MAX_SPEED_PER_TICK * lookaheadTicks
        val dx = px - current.x
        val dz = pz - current.z
        val travel = hypot(dx, dz)
        if (travel > maxTravel) {
            val k = maxTravel / travel
            return Vector3f.from(current.x + dx * k, py, current.z + dz * k)
        }
        return Vector3f.from(px, py, pz)
    }

    /** Sign of the angular velocity over the last two intervals only (fast reaction to strafe switches). */
    private fun recentAngularSign(samples: ArrayDeque<Sample>, ox: Float, oz: Float): Float {
        if (samples.size < 3) return 0f
        val a = samples[samples.size - 3]
        val b = samples[samples.size - 1]
        val delta = wrapAngle(atan2(b.z - oz, b.x - ox) - atan2(a.z - oz, a.x - ox))
        return if (abs(delta) < 0.01f) 0f else sign(delta)
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle
        while (a > PI) a -= (2 * PI).toFloat()
        while (a < -PI) a += (2 * PI).toFloat()
        return a
    }
}
