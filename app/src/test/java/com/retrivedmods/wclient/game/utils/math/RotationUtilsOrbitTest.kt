package com.retrivedmods.wclient.game.utils.math

import com.retrivedmods.wclient.game.entity.Entity
import org.cloudburstmc.math.vector.Vector3f
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class RotationUtilsOrbitTest {

    @Test
    fun targetAcquisitionDoesNotSnapToConfiguredRadius() {
        val player = Entity(1L, 1L).apply { move(4f, 0f, 0f) }
        val target = Entity(2L, 2L).apply { move(0f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()

        // The first sample anchors the orbit without issuing a position update.
        assertNull(orbit.next(player, target, 2.5f, 1L))

        player.move(4.2f, 0f, 0f)
        val next = orbit.next(player, target, 2.5f, 2L)
        assertNotNull(next)
        assertTrue(horizontalDistance(player.vec3Position, next!!) <= 0.15f + 1e-5f)
    }

    @Test
    fun orbitRateStaysConstantInsteadOfFollowingPlayerVelocity() {
        val player = Entity(1L, 1L).apply { move(2.5f, 0f, 0f) }
        val target = Entity(2L, 2L).apply { move(0f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()

        assertNull(orbit.next(player, target, 2.5f, 1L, 0.15f))
        val first = orbit.next(player, target, 2.5f, 2L, 0.15f)
        assertNotNull(first)
        assertEquals(0.15f, horizontalDistance(player.vec3Position, first!!), 0.001f)

        // A different external movement sample does not alter the configured orbit pace.
        player.move(2.4f, 0f, 0.7f)
        val second = orbit.next(player, target, 2.5f, 3L, 0.15f)
        assertNotNull(second)
        assertEquals(0.15f, horizontalDistance(player.vec3Position, second!!), 0.001f)
    }

    @Test
    fun correctionReanchorsAndWaitsBeforeResumingOrbit() {
        val player = Entity(1L, 1L).apply { move(3f, 0f, 0f) }
        val target = Entity(2L, 2L).apply { move(0f, 0f, 0f) }
        val orbit = RotationUtils.Orbit()

        assertNull(orbit.next(player, target, 2.5f, 1L))
        orbit.onServerCorrection()

        assertNull(orbit.next(player, target, 2.5f, 2L))
        repeat(2) { index ->
            player.move(player.posX + 0.1f, player.posY, player.posZ)
            assertNull(orbit.next(player, target, 2.5f, 3L + index))
        }

        player.move(player.posX + 0.1f, player.posY, player.posZ)
        assertNotNull(orbit.next(player, target, 2.5f, 5L))
    }

    private fun horizontalDistance(a: Vector3f, b: Vector3f): Float = hypot(a.x - b.x, a.z - b.z)
}
