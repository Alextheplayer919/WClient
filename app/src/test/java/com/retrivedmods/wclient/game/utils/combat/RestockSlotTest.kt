package com.retrivedmods.wclient.game.utils.combat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RestockSlotTest {

    @Test
    fun prefersAnEmptySlotThatIsNotHeld() {
        val empty = setOf(0, 3, 8)
        // Slot 0 is empty but held, so the first usable empty slot is 3.
        assertEquals(3, chooseRestockSlot(heldSlot = 0) { it in empty })
    }

    @Test
    fun neverSwapsTheHeldSlotEvenWhenItIsTheOnlyEmptyOne() {
        val result = chooseRestockSlot(heldSlot = 2) { it == 2 }
        assertNotEquals(2, result)
        assertEquals(8, result)
    }

    @Test
    fun fallsBackToTheLastSlotThatIsNotHeld() {
        assertEquals(8, chooseRestockSlot(heldSlot = 0) { false })
        assertEquals(7, chooseRestockSlot(heldSlot = 8) { false })
    }
}
