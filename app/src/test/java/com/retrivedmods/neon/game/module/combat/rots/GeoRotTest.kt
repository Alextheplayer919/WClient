package com.retrivedmods.neon.game.module.combat.rots

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoRotTest {

    @Test
    fun eachPatternUsesItsExpectedStartingPoint() {
        val oldRadius = GeoRot.radius
        val oldSpeed = GeoRot.speed
        try {
            GeoRot.radius = 2f
            GeoRot.speed = 1f

            assertOffset(GeoRot.offset("Circle", 0L), 2f, 0f, 0f)
            assertOffset(GeoRot.offset("Square", 0L), 2f, 0f, -2f)
            assertOffset(GeoRot.offset("Zigzag", 0L), -2f, 0f, 0f)
            assertOffset(GeoRot.offset("Spiral", 0L), 1f, 0f, 0f)
        } finally {
            GeoRot.radius = oldRadius
            GeoRot.speed = oldSpeed
        }
    }

    @Test
    fun unknownPatternFallsBackToCircle() {
        val oldRadius = GeoRot.radius
        val oldSpeed = GeoRot.speed
        try {
            GeoRot.radius = 0.45f
            GeoRot.speed = 1f

            val fallback = GeoRot.offset("Unknown", 0L)
            val circle = GeoRot.offset("Circle", 0L)
            assertOffset(fallback, circle.first, circle.second, circle.third)
        } finally {
            GeoRot.radius = oldRadius
            GeoRot.speed = oldSpeed
        }
    }

    private fun assertOffset(actual: Triple<Float, Float, Float>, x: Float, y: Float, z: Float) {
        assertEquals(x, actual.first, 1e-5f)
        assertEquals(y, actual.second, 1e-5f)
        assertEquals(z, actual.third, 1e-5f)
    }
}
