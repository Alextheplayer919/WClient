package com.retrivedmods.wclient.game.module.combat.rots

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class RotationEnginesTest {
    private fun step(aim: PredictiveAim, tick: Long, x: Float = 3f) {
        aim.step(0f, 1.62f, 0f, 1L, x, 0f, 3f, 0.6f, 1.8f, 0.3f, tick)
    }

    @Test fun predictiveRespectsSpeedAndAccelerationAndDoesNotAdvanceTwicePerTick() {
        val aim = PredictiveAim()
        aim.config = PredictiveAim.Config(maxYawSpeed = 20f, turnAccel = 4f)
        aim.seed(0f, 0f, 1L)
        var previousYaw = aim.yaw
        var previousStep = 0f
        for (tick in 1L..30L) {
            step(aim, tick)
            val delta = aim.yaw - previousYaw
            assertTrue(abs(delta) <= 20.001f)
            assertTrue(abs(delta - previousStep) <= 4.001f)
            assertTrue(aim.pitch in -90f..90f)
            val firstYaw = aim.yaw
            step(aim, tick)
            assertEquals(firstYaw, aim.yaw, 0f)
            previousYaw = aim.yaw
            previousStep = delta
        }
    }

    @Test fun predictiveReleasesToCameraAndResetClearsOverride() {
        val aim = PredictiveAim()
        aim.seed(80f, 20f, 1L)
        step(aim, 1L)
        for (tick in 2L..200L) aim.release(0f, 0f, tick)
        assertFalse(aim.isOverriding)
        assertTrue(abs(aim.yaw) < 1f)
        assertTrue(abs(aim.pitch) < 1f)
        aim.reset()
        assertEquals(0f, aim.yaw, 0f)
        assertFalse(aim.isOverriding)
    }

    @Test fun predictiveSurvivesTeleportAndTickRollback() {
        val aim = PredictiveAim()
        aim.seed(0f, 0f, 1L)
        for (tick in 1L..10L) step(aim, tick, 3f + tick * 0.1f)
        step(aim, 11L, 100f)
        step(aim, 1L, -100f)
        assertTrue(aim.yaw.isFinite())
        assertTrue(aim.pitch in -90f..90f)
    }

    @Test fun abysmIsInstanceOwnedAndStartsFromSeededRotation() {
        val a = AbysmRot()
        val b = AbysmRot()
        a.seed(0f, 0f)
        b.seed(90f, 0f)
        val result = a.aim(10f, 1.62f, 0f, 0f, 0f, 0f, 0f, 1.62f, 0f, HvHGod.Style.UNKNOWN)
        assertTrue(abs(result.second) <= a.maxSpeed)
        val other = b.aim(0f, 1.62f, 10f, 0f, 0f, 0f, 0f, 1.62f, 0f, HvHGod.Style.UNKNOWN)
        assertTrue(other.second > 0f)
        a.reset()
        assertTrue(other.first.isFinite())
    }

    @Test fun autoSelectorConfirmsSpinnerAndPrunesState() {
        val selector = HvHGod()
        for (tick in 1L..20L) {
            selector.observe(1L, tick * 45f, 3f, 0f, 3f, tick)
            selector.pick(1L, 0f, 0f, tick)
        }
        assertEquals(HvHGod.Style.SPINNER, selector.lastStyle)
        selector.retain(emptyList())
        assertEquals("FrontsX", selector.pick(1L, 0f, 0f, 21L))
        selector.reset()
        assertEquals(HvHGod.Style.UNKNOWN, selector.lastStyle)
    }
}
