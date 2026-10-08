package com.retrivedmods.wclient.game.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The classification is name-based (the protocol carries no physics flags), so these tests pin
 * the curated sets and the conservative fallbacks.
 */
class BlockCategoryTest {

    private fun cat(name: String) = BlockCategory.categoryFor(name)

    @Test
    fun airFamily() {
        assertEquals(BlockCategory.AIR, cat("minecraft:air"))
        assertEquals(BlockCategory.AIR, cat("minecraft:cave_air"))
        assertEquals(BlockCategory.AIR, cat("minecraft:void_air"))
        assertEquals(BlockCategory.AIR, cat("minecraft:void_air_transparent"))
    }

    @Test
    fun liquids() {
        assertEquals(BlockCategory.LIQUID, cat("minecraft:water"))
        assertEquals(BlockCategory.LIQUID, cat("minecraft:flowing_water"))
        assertEquals(BlockCategory.LIQUID, cat("minecraft:lava"))
        assertEquals(BlockCategory.LIQUID, cat("minecraft:flowing_lava"))
    }

    @Test
    fun solids() {
        assertEquals(BlockCategory.SOLID, cat("minecraft:stone"))
        assertEquals(BlockCategory.SOLID, cat("minecraft:obsidian"))
        assertEquals(BlockCategory.SOLID, cat("minecraft:barrier"))
        assertEquals(BlockCategory.SOLID, cat("minecraft:bedrock"))
        // Cobweb impedes movement in Bedrock -> solid, despite looking "soft".
        assertEquals(BlockCategory.SOLID, cat("minecraft:cobweb"))
    }

    @Test
    fun nonSolidPlantsAndDecorations() {
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:short_grass"))
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:snow"))
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:torch"))
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:oak_carpet"))
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:sugar_cane"))
    }

    @Test
    fun prefixFamilies() {
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:potted_oak"))
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:potted_dragon_egg_block"))
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:wall_torch"))
        assertEquals(BlockCategory.NON_SOLID, cat("minecraft:wall_banner"))
    }

    @Test
    fun passableExtras() {
        assertEquals(BlockCategory.PASSABLE, cat("minecraft:powder_snow"))
        assertEquals(BlockCategory.PASSABLE, cat("minecraft:bubble_column"))
    }

    @Test
    fun unknownFallbacks() {
        assertEquals(BlockCategory.UNKNOWN, cat(""))
        assertEquals(BlockCategory.UNKNOWN, cat("unknown:0"))
        assertEquals(BlockCategory.UNKNOWN, cat("minecraft")) // no namespace -> unresolvable
    }

    @Test
    fun derivedPredicates() {
        assertTrue(BlockCategory.AIR.isReplaceable)
        assertTrue(BlockCategory.LIQUID.isReplaceable)
        assertTrue(BlockCategory.SOLID.isSolid)
        assertTrue(BlockCategory.SOLID.isOpaque)
        assertTrue(BlockCategory.NON_SOLID.isOpaque)
        assertFalse(BlockCategory.NON_SOLID.isSolid)
        assertFalse(BlockCategory.PASSABLE.isReplaceable)
        // UNKNOWN is conservative everywhere: no placement, no support, blocks sight.
        assertFalse(BlockCategory.UNKNOWN.isReplaceable)
        assertFalse(BlockCategory.UNKNOWN.isSolid)
        assertTrue(BlockCategory.UNKNOWN.isOpaque)
    }
}
