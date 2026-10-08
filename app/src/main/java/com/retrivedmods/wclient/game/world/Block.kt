package com.retrivedmods.wclient.game.world

/**
 * A single tracked block: its runtime block state id plus the derived [category].
 * Immutable; instances are created per lookup (cheap value object).
 */
class Block(val stateId: Int, val category: BlockCategory) {

    val isAir: Boolean
        get() = category.isAir

    val isLiquid: Boolean
        get() = category.isLiquid

    /** Replaceable = can place a block into this space (air or liquid). */
    val isReplaceable: Boolean
        get() = category.isReplaceable

    /** Solid = can support a block placed on top of it. */
    val isSolid: Boolean
        get() = category.isSolid

    /** Passable = a sight ray goes through it. */
    val isPassable: Boolean
        get() = category.isPassable

    /** Opaque = blocks sight (conservative for UNKNOWN). */
    val isOpaque: Boolean
        get() = category.isOpaque

    override fun toString() = "Block(stateId=$stateId, category=${category.name})"
}
