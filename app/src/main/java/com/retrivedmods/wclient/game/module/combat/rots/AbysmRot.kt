package com.retrivedmods.wclient.game.module.combat.rots

import com.retrivedmods.wclient.game.module.combat.rots.HvHGod.Style
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/** Proportional, speed-capped tracking with target-style multipliers.
 * State belongs to one Killaura instance. This is not an acceleration-limited engine;
 * use PredictiveAim for angular velocity/acceleration tracking. No random noise.
 */
class AbysmRot {

    /** Grados maximos por tick antes de multiplicar por el estilo. */
    @Volatile var maxSpeed = 35f

    private var yaw = Float.NaN
    private var pitch = 0f

    /** Llamar cuando no hay objetivo, para que al volver a agarrar uno no arrastre el giro viejo. */
    fun reset() {
        yaw = Float.NaN
        pitch = 0f
    }

    fun seed(currentYaw: Float, currentPitch: Float) {
        yaw = wrap180(currentYaw)
        pitch = currentPitch.coerceIn(-90f, 90f)
    }

    /**
     * Devuelve (pitch, yaw) para este tick.
     * (tx,ty,tz) = punto a apuntar del enemigo (ya con altura de pecho);
     * (tvx,tvy,tvz) = su velocidad por tick; (ex,ey,ez) = tus ojos.
     */
    fun aim(
        tx: Float, ty: Float, tz: Float,
        tvx: Float, tvy: Float, tvz: Float,
        ex: Float, ey: Float, ez: Float,
        style: Style
    ): Pair<Float, Float> {
        val lead = when (style) {
            Style.STRAFER, Style.AIR -> 3f
            Style.SPINNER -> 1f
            else -> 2f
        }
        val px = tx + tvx * lead
        val py = if (style == Style.AIR) ty + tvy * lead else ty
        val pz = tz + tvz * lead

        val dx = px - ex
        val dy = py - ey
        val dz = pz - ez
        val wantYaw = (-atan2(dx.toDouble(), dz.toDouble()) * RAD2DEG).toFloat()
        val wantPitch = (-atan2(dy.toDouble(), hypot(dx.toDouble(), dz.toDouble())) * RAD2DEG)
            .toFloat().coerceIn(-90f, 90f)

        if (yaw.isNaN()) {
            yaw = wantYaw
            pitch = wantPitch
        }

        val cap = maxSpeed * speedMultiplier(style)
        yaw = wrap180(yaw + step(wrap180(wantYaw - yaw), cap))
        pitch = (pitch + step(wantPitch - pitch, cap)).coerceIn(-90f, 90f)

        return pitch to yaw
    }

    /**
     * Paso hacia el objetivo: 45% del error (acelera al inicio y frena al llegar), al menos 1 grado
     * mientras este lejos, sin pasarse nunca del error y sin superar [cap].
     */
    private fun step(d: Float, cap: Float): Float {
        val a = abs(d)
        if (a < 0.05f) return d
        val s = max(min(a * 0.45f, cap), min(a, 1f))
        return sign(d) * min(s, a)
    }

    private fun speedMultiplier(style: Style): Float = when (style) {
        Style.SPINNER -> 1.6f
        Style.SNAP -> 1.8f
        Style.JITTER -> 1.3f
        Style.AIR -> 1.2f
        Style.STRAFER -> 1.1f
        Style.LOCKED -> 0.8f
        Style.IDLE -> 0.6f
        Style.UNKNOWN -> 1f
    }

    private fun wrap180(a: Float): Float {
        if (!a.isFinite()) return 0f
        var r = (a + 180f) % 360f
        if (r < 0f) r += 360f
        return r - 180f
    }

    private val RAD2DEG = 57.29577951308232
}
