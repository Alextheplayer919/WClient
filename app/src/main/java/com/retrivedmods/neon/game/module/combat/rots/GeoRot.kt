package com.retrivedmods.neon.game.module.combat.rots

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Geometric aim-point patterns from the Kiwi Killaura rotation set.
 *
 * The formulas intentionally mirror the uploaded implementation. The caller turns the returned
 * point into a server rotation and writes it to the outgoing packet; this helper does not modify
 * packets or the camera.
 */
object GeoRot {

    val MODES = listOf("Circle", "Square", "Zigzag", "Spiral")

    /** Radius of the pattern in blocks (a player hitbox is about 0.6 blocks wide). */
    @Volatile var radius = 0.45f

    /** Approximate turns per second = speed * 20 / 2pi (1.0 is one turn per second). */
    @Volatile var speed = 1.0f

    /** Pattern offset (dx, dy, dz) from the target's chest for this tick. */
    fun offset(mode: String, tick: Long): Triple<Float, Float, Float> {
        val phase = tick * speed * 0.05f * 2f * PI.toFloat()
        val r = radius
        return when (mode) {
            "Square" -> {
                val t = ((phase / (2f * PI.toFloat())) * 4f) % 4f
                val side = floor(t).toInt()
                val f = t - side
                val (x, z) = when (side) {
                    0 -> r to (-r + 2 * r * f)
                    1 -> (r - 2 * r * f) to r
                    2 -> -r to (r - 2 * r * f)
                    else -> (-r + 2 * r * f) to -r
                }
                Triple(x, 0f, z)
            }
            "Zigzag" -> {
                val p = (phase / (2f * PI.toFloat())) % 1f
                val tri = 1f - 4f * abs(p - 0.5f)
                Triple(r * tri, 0.25f * r * sin(phase * 2f), 0f)
            }
            "Spiral" -> {
                val rr = r * (0.5f + 0.5f * sin(phase * 0.3f))
                Triple(rr * cos(phase), 0f, rr * sin(phase))
            }
            else -> Triple(r * cos(phase), 0f, r * sin(phase)) // Circle
        }
    }
}
