package com.retrivedmods.wclient.game.utils.math

import com.retrivedmods.wclient.game.entity.Entity
import org.cloudburstmc.math.vector.Vector3f
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * The orbit has no speed of its own: the pace is whatever the player's own movement (the fly)
 * produced this tick and the player is held on the orbit radius so they cannot fly past the target.
 */
class RotationUtilsOrbitTest {

    private val target = Entity(2L, 2L).apply { move(0f, 0f, 0f) }

    /** The fly moves the client on its own, before the orbit gets to answer. */
    private fun Entity.fly(x: Float = 0f, z: Float = 0f) {
        move(posX + x, posY, posZ + z)
    }

    /** The orbit answers and the client accepts the commanded position. */
    private fun Entity.orbitTo(
        orbit: RotationUtils.Orbit,
        tick: Long,
        direction: RotationUtils.SpinDirection = RotationUtils.SpinDirection.LEFT,
        radius: Float = 2.5f
    ): Vector3f? {
        val next = orbit.next(this, target, radius, tick, direction) ?: return null
        move(next)
        return next
    }

    @Test
    fun firstSampleAnchorsTheOrbitInsteadOfSnapping() {
        val player = Entity(1L, 1L).apply { move(4f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()

        player.fly(x = 0.5f)
        assertNull(player.orbitTo(orbit, 1L))
        // Anchoring must not move anybody: the player stays exactly where the fly put them.
        assertEquals(4.5f, player.posX, 1e-4f)
        assertEquals(0f, player.posZ, 1e-4f)
    }

    @Test
    fun spinPaceFollowsTheFlyInsteadOfASlider() {
        val slow = sweptAngle(RotationUtils.SpinDirection.LEFT, 0.5f)
        val fast = sweptAngle(RotationUtils.SpinDirection.LEFT, 1.0f)

        assertTrue("slow spin did not advance: $slow", slow > 0.2f)
        // Twice the fly pace is roughly twice the spin: no fixed speed setting anywhere.
        assertTrue("fast $fast should track the fly's pace ($slow)", fast > slow * 1.5f)
    }

    @Test
    fun playerIsHeldOnTheRadiusSoTheyCannotFlyPastTheTarget() {
        // Approaching the target from further out, flying straight at it at 1 block per tick.
        val player = Entity(1L, 1L).apply { move(6f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()
        player.fly(x = -1f)
        assertNull(player.orbitTo(orbit, 1L))

        for (tick in 2L..12L) {
            player.fly(x = -1f)
            assertNotNull("orbit lost the target on tick $tick", player.orbitTo(orbit, tick))
        }

        assertEquals(
            2.5f,
            horizontalDistance(target.vec3Position, player.vec3Position),
            0.05f
        )
    }

    @Test
    fun commandedStepNeverOutrunsTheFly() {
        val player = Entity(1L, 1L).apply { move(2.5f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()
        assertNull(player.orbitTo(orbit, 1L))

        val anchor = player.vec3Position
        player.fly(x = 2f)
        val next = player.orbitTo(orbit, 2L)!!

        // The commanded sequence advances by the fly's own travel, so the pace stays the fly's.
        assertTrue(horizontalDistance(anchor, next) <= 2f + 1e-4f)
        // And it is a real speed, not the old fixed cap: 0.30 blocks per tick could never do this.
        assertTrue(horizontalDistance(anchor, next) > 1f)
    }

    @Test
    fun spinDirectionReversesTheWayAroundTheTarget() {
        assertTrue(sweptAngle(RotationUtils.SpinDirection.LEFT, 0.5f) > 0f)
        assertTrue(sweptAngle(RotationUtils.SpinDirection.RIGHT, 0.5f) < 0f)
    }

    @Test
    fun standingStillMeansNoSpin() {
        val player = Entity(1L, 1L).apply { move(2.5f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()

        // No fly movement at all: the orbit never advances on its own.
        assertNull(player.orbitTo(orbit, 1L))
        assertNull(player.orbitTo(orbit, 2L))
        assertNull(player.orbitTo(orbit, 3L))
        assertEquals(2.5f, player.posX, 1e-4f)
    }

    @Test
    fun correctionReanchorsAndWaitsBeforeResuming() {
        val player = Entity(1L, 1L).apply { move(2.5f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()
        player.fly(z = 0.5f)
        assertNull(player.orbitTo(orbit, 1L))

        orbit.onServerCorrection()

        var tick = 2L
        repeat(3) {
            player.fly(z = 0.5f)
            assertNull(player.orbitTo(orbit, tick++))
        }

        player.fly(z = 0.5f)
        assertNotNull(player.orbitTo(orbit, tick))
    }

    /** Runs the loop with the fly pushing [flyPerTick] blocks every tick; total angle swept. */
    private fun sweptAngle(direction: RotationUtils.SpinDirection, flyPerTick: Float): Float {
        val player = Entity(1L, 1L).apply { move(2.5f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()

        player.fly(z = flyPerTick)
        assertNull(player.orbitTo(orbit, 1L, direction))

        var previous = 0f
        var swept = 0f
        for (tick in 2L..10L) {
            player.fly(z = flyPerTick)
            assertNotNull(player.orbitTo(orbit, tick, direction))
            val angle = atan2(player.posZ, player.posX)
            if (tick > 2L) swept += wrapAngle(angle - previous)
            previous = angle
        }
        return swept
    }

    private fun wrapAngle(angle: Float): Float {
        var a = angle
        while (a > PI.toFloat()) a -= (2 * PI).toFloat()
        while (a < -PI.toFloat()) a += (2 * PI).toFloat()
        return a
    }

    private fun horizontalDistance(a: Vector3f, b: Vector3f): Float = hypot(a.x - b.x, a.z - b.z)
}
