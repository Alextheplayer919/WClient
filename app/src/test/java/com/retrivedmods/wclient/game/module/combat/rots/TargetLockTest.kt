package com.retrivedmods.wclient.game.module.combat.rots

import com.retrivedmods.wclient.game.entity.Entity
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetLockTest {
    @Test fun holdsSimilarTargetsButSwitchesToClearlyCloserAfterMinimumHold() {
        val rotator = PredictiveRotator()
        val local = Entity(0L, 0L)
        val old = Entity(1L, 1L).apply { posX = 4f }
        val other = Entity(2L, 2L).apply { posX = 3.9f }
        rotator.selectTarget(mutableListOf(old, other), local, 1L)
        val similar = mutableListOf(other, old)
        rotator.selectTarget(similar, local, 10L)
        assertEquals(old, similar.first())
        other.posX = 2f
        val closer = mutableListOf(other, old)
        rotator.selectTarget(closer, local, 11L)
        assertEquals(other, closer.first())
        // Invalid/out-of-range targets are never retained by the lock.
        val remaining = mutableListOf(old)
        rotator.selectTarget(remaining, local, 12L)
        assertEquals(old, remaining.first())
    }

    @Test fun doesNotSwitchDuringMinimumHoldAndResetReleasesLock() {
        val rotator = PredictiveRotator()
        val local = Entity(0L, 0L)
        val old = Entity(1L, 1L).apply { posX = 4f }
        val near = Entity(2L, 2L).apply { posX = 1f }
        rotator.selectTarget(mutableListOf(old), local, 1L)
        val early = mutableListOf(near, old)
        rotator.selectTarget(early, local, 2L)
        assertEquals(old, early.first())
        rotator.reset()
        val fresh = mutableListOf(near, old)
        rotator.selectTarget(fresh, local, 3L)
        assertEquals(near, fresh.first())
    }
}
