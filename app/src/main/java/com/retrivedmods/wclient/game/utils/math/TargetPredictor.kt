package com.retrivedmods.wclient.game.utils.math

import com.retrivedmods.wclient.game.entity.Entity
import org.cloudburstmc.math.vector.Vector3f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Predicts where a target will be a few ticks from now so that rotations aimed through the relay
 * (which sees the target slightly in the past and whose hits reach the server slightly in the
 * future) still line up during fast fights.
 *
 * Position sources: other players arrive via `MovePlayerPacket`, mobs via `MoveEntityAbsolute` /
 * `MoveEntityDeltaPacket`; both end in `Entity.move()`. Samples are stamped with the *client tick*
 * (PlayerAuthInputPacket.tick) instead of wall-clock time so RakNet batching (two move packets in
 * one datagram) does not look like a teleport followed by a stall.
 *
 * Models, in the local player's polar frame (angle / radius around us):
 *  - LINEAR   : constant velocity with Bedrock air drag (0.91 / tick) - straight runners.
 *  - CIRCULAR : constant angular + radial velocity - targets strafing around us.
 * Both are back-tested every tick: each predicts the newest sample from the older ones and the
 * one with the lower running error is used, so the choice is self-correcting.
 *
 * Intent signals:
 *  - Head yaw: a player's head turns 2-4 ticks before their velocity follows. If head yaw is
 *    swinging against the current strafe direction, a reversal is coming and the prediction is
 *    damped toward the current position.
 *  - Angular sign flips (position based) damp the prediction for a short memory window.
 *
 * Vertical: Bedrock jumps are deterministic (v0 = 0.42, g = 0.08, drag 0.98). An airborne target
 * is integrated along that parabola instead of being linearly extrapolated.
 */
class TargetPredictor {

    private class Sample(val tick: Long, val x: Float, val y: Float, val z: Float, val headYaw: Float)

    private class History {
        val samples = ArrayDeque<Sample>()
        var lastAngularSign = 0f
        var lastFlipTick = Long.MIN_VALUE
        var linearError = 0.2f
        var circularError = 0.2f
        var airborneTicks = 0
    }

    private val histories = HashMap<Long, History>()

    companion object {
        private const val MAX_SAMPLES = 12
        private const val SAMPLE_TTL_TICKS = 30L
        private const val MIN_SAMPLES = 3

        private const val MAX_SPEED_PER_TICK = 1.0f
        private const val FLIP_MEMORY_TICKS = 7L
        private const val FLIP_DAMPING = 0.35f
        private const val HEAD_INTENT_DAMPING = 0.5f
        private const val HEAD_INTENT_THRESHOLD_DEG = 25f

        // Bedrock player physics
        private const val AIR_DRAG = 0.91f
        private const val JUMP_VELOCITY = 0.42f
        private const val GRAVITY = 0.08f
        private const val VERTICAL_DRAG = 0.98f

        private const val ERROR_EMA = 0.3f
    }

    fun reset() = histories.clear()

    fun forget(entity: Entity) {
        histories.remove(entity.runtimeEntityId)
    }

    /** Record the target's current position at client [tick]. Call every tick for every candidate. */
    fun record(target: Entity, tick: Long) {
        val history = histories.getOrPut(target.runtimeEntityId) { History() }
        val last = history.samples.lastOrNull()

        if (last != null && last.x == target.posX && last.y == target.posY && last.z == target.posZ) return
        if (last != null && tick <= last.tick) return

        val sample = Sample(tick, target.posX, target.posY, target.posZ, target.rotationYawHead)
        history.samples.addLast(sample)
        while (history.samples.size > MAX_SAMPLES) history.samples.removeFirst()
        while (history.samples.isNotEmpty() && tick - history.samples.first().tick > SAMPLE_TTL_TICKS) {
            history.samples.removeFirst()
        }

        if (history.samples.size >= 2) {
            val prev = history.samples[history.samples.size - 2]
            val vy = (sample.y - prev.y) / max(1L, sample.tick - prev.tick)
            history.airborneTicks = if (abs(vy) > 0.02f) history.airborneTicks + 1 else 0
        }

        if (history.samples.size >= MIN_SAMPLES + 1) backTest(history)
    }

    /**
     * Predicted position of [target] after [lookaheadTicks] ticks as seen from [observer].
     * Falls back to the current position when there is not enough data.
     */
    fun predict(observer: Entity, target: Entity, lookaheadTicks: Float, nowTick: Long): Vector3f {
        val current = target.vec3Position
        if (lookaheadTicks <= 0f) return current

        val history = histories[target.runtimeEntityId] ?: return current
        val samples = history.samples
        if (samples.size < MIN_SAMPLES) return current

        val newest = samples.last()
        val oldest = samples.first()
        val dt = (newest.tick - oldest.tick).toFloat()
        if (dt < 1f) return current

        val ox = observer.posX
        val oz = observer.posZ

        // ---- confidence --------------------------------------------------------------------
        var weight = 1f

        val recentSign = recentAngularSign(samples, ox, oz)
        if (recentSign != 0f && history.lastAngularSign != 0f && recentSign != history.lastAngularSign) {
            history.lastFlipTick = nowTick
        }
        if (recentSign != 0f) history.lastAngularSign = recentSign
        if (nowTick - history.lastFlipTick < FLIP_MEMORY_TICKS) weight = min(weight, FLIP_DAMPING)

        // Head-yaw intent: head swinging against current strafe direction => reversal incoming.
        val headDelta = getAngleDifference(newest.headYaw, samples[samples.size - 2].headYaw)
        if (recentSign != 0f && abs(headDelta) > HEAD_INTENT_THRESHOLD_DEG) {
            // Positive angular sign = counter-clockwise around us as seen from above. For a target
            // strafing left around us the head turns the opposite way to keep facing us, so a head
            // swing with the *same* sign as the angular motion means they are turning away/reversing.
            if (sign(headDelta) == recentSign) weight = min(weight, HEAD_INTENT_DAMPING)
        }

        val t = lookaheadTicks * weight

        // ---- horizontal ---------------------------------------------------------------------
        val useCircular = history.circularError < history.linearError
        val (px, pz) = if (useCircular) predictCircular(samples, ox, oz, t) else predictLinear(samples, t)

        // ---- vertical -----------------------------------------------------------------------
        val py = predictVertical(samples, history, t)

        // ---- physical clamp -----------------------------------------------------------------
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

    // ---- models ---------------------------------------------------------------------------------

    private fun velocity(samples: List<Sample>): Pair<Float, Float> {
        // Weighted toward the most recent interval so W-tap starts/stops show up quickly.
        val n = samples.size
        val a = samples[n - 1]
        val b = samples[n - 2]
        val c = samples[0]
        val dtRecent = max(1L, a.tick - b.tick).toFloat()
        val dtAll = max(1L, a.tick - c.tick).toFloat()
        var vx = 0.6f * (a.x - b.x) / dtRecent + 0.4f * (a.x - c.x) / dtAll
        var vz = 0.6f * (a.z - b.z) / dtRecent + 0.4f * (a.z - c.z) / dtAll
        val speed = hypot(vx, vz)
        if (speed > MAX_SPEED_PER_TICK) {   // teleport / lag spike: do not extrapolate it
            val k = MAX_SPEED_PER_TICK / speed
            vx *= k; vz *= k
        }
        return vx to vz
    }

    /** Constant velocity with per-tick air drag: sum of a geometric series. */
    private fun predictLinear(samples: List<Sample>, t: Float): Pair<Float, Float> {
        val (vx, vz) = velocity(samples)
        val newest = samples.last()
        val dragSum = if (t <= 0f) 0f else (1f - Math.pow(AIR_DRAG.toDouble(), t.toDouble()).toFloat()) / (1f - AIR_DRAG)
        // Blend drag model (air) with pure-linear (ground, where friction and input cancel out).
        val travel = 0.5f * t + 0.5f * dragSum
        return (newest.x + vx * travel) to (newest.z + vz * travel)
    }

    private fun predictCircular(samples: List<Sample>, ox: Float, oz: Float, t: Float): Pair<Float, Float> {
        val n = samples.size
        val a = samples[n - 1]
        val b = samples[max(0, n - 4)]
        val dt = max(1L, a.tick - b.tick).toFloat()

        val angleA = atan2(a.z - oz, a.x - ox)
        val angleB = atan2(b.z - oz, b.x - ox)
        val radiusA = hypot(a.x - ox, a.z - oz)
        val radiusB = hypot(b.x - ox, b.z - oz)

        val angularVelocity = wrapAngle(angleA - angleB) / dt
        val radialVelocity = (radiusA - radiusB) / dt

        val angle = angleA + angularVelocity * t
        val radius = (radiusA + radialVelocity * t).coerceAtLeast(0.1f)
        return (ox + cos(angle) * radius) to (oz + sin(angle) * radius)
    }

    private fun predictVertical(samples: List<Sample>, history: History, t: Float): Float {
        val n = samples.size
        val a = samples[n - 1]
        val b = samples[n - 2]
        var vy = (a.y - b.y) / max(1L, a.tick - b.tick).toFloat()

        if (history.airborneTicks == 0 || abs(vy) < 0.02f) return a.y

        // First airborne sample moving up at ~jump speed => a jump; integrate the known parabola.
        if (history.airborneTicks == 1 && vy > 0.3f) vy = JUMP_VELOCITY

        var y = a.y
        var v = vy
        var remaining = t
        while (remaining > 0f) {
            val step = min(1f, remaining)
            v = (v - GRAVITY * step) * VERTICAL_DRAG
            y += v * step
            remaining -= step
        }
        return y
    }

    /** Each model predicts the newest sample from the older ones; running errors pick the winner. */
    private fun backTest(history: History) {
        val samples = history.samples
        val newest = samples.last()
        val older = samples.subList(0, samples.size - 1)
        val dt = (newest.tick - older.last().tick).toFloat()
        if (dt < 1f || dt > 5f) return

        // Observer position at back-test time is unknown; use the target's own frame midpoint as
        // a stable proxy (circular-ness is about curvature, which is frame-independent enough).
        val ox = older.first().x
        val oz = older.first().z

        val (lx, lz) = predictLinear(older, dt)
        val (cx, cz) = if (older.size >= 3) predictCircular(older, ox, oz, dt) else (lx to lz)

        val linErr = hypot(lx - newest.x, lz - newest.z)
        val cirErr = hypot(cx - newest.x, cz - newest.z)
        history.linearError = (1f - ERROR_EMA) * history.linearError + ERROR_EMA * linErr
        history.circularError = (1f - ERROR_EMA) * history.circularError + ERROR_EMA * cirErr
    }

    private fun recentAngularSign(samples: List<Sample>, ox: Float, oz: Float): Float {
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
