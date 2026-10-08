package com.retrivedmods.wclient.game.utils.combat

/**
 * Picks the hotbar slot a main-inventory consumable gets swapped into.
 *
 * Never [heldSlot]: swapping the held slot would pull the player's weapon out of their hand.
 * Prefers an empty slot; otherwise falls back to the last hotbar slot that is not held.
 */
fun chooseRestockSlot(heldSlot: Int, isEmpty: (Int) -> Boolean): Int {
    for (slot in 0..8) {
        if (slot != heldSlot && isEmpty(slot)) return slot
    }
    return if (heldSlot == 8) 7 else 8
}
