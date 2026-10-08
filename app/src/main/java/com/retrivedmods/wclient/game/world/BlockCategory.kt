package com.retrivedmods.wclient.game.world

/**
 * Name-based block classification.
 *
 * The protocol does not ship per-block physics/collision flags: a
 * [org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition] only carries a name and a
 * runtime id, so categories are derived from the block *name* (the same data `BlockMapping`
 * resolves from the MCPEData assets).
 *
 * Everything with a `minecraft:` prefix that is not in one of the curated sets below is treated
 * as a plain solid block — which is the right answer for the vast majority of world content
 * (stone, dirt, obsidian, ...). The sets are deliberately conservative: when in doubt a block is
 * SOLID (it can support a placement) or OPAQUE (it blocks sight).
 */
enum class BlockCategory {

    /** Air family: no collision, no support, replaceable, does not block sight. */
    AIR,

    /** Water/lava: replaceable, no support, does not block sight. */
    LIQUID,

    /** Non-colliding, non-liquid (powder snow, bubble columns): replaceable, no support. */
    PASSABLE,

    /** Plants, carpets, torches, snow layers, signs...: no collision (no support) but blocks sight. */
    NON_SOLID,

    /** Normal collision: can support a block, blocks sight. */
    SOLID,

    /**
     * Unknown name — this happens when the block state mapping has not loaded for the server's
     * protocol version. Conservative: not placeable into, not usable as support, blocks sight.
     */
    UNKNOWN;

    val isAir: Boolean
        get() = this == AIR

    val isLiquid: Boolean
        get() = this == LIQUID

    /** A block can be placed into this space (surround semantics: air or liquid). */
    val isReplaceable: Boolean
        get() = isAir || isLiquid

    /** Can carry a block on top of it. */
    val isSolid: Boolean
        get() = this == SOLID

    /** A sight ray passes straight through. */
    val isPassable: Boolean
        get() = this == AIR || this == LIQUID || this == PASSABLE

    /** Blocks sight (conservative: UNKNOWN counts as opaque). */
    val isOpaque: Boolean
        get() = !isPassable

    companion object {
        private const val NS = "minecraft:"

        private val AIR_NAMES = setOf(
            NS + "air", NS + "cave_air", NS + "void_air", NS + "void_air_transparent"
        )

        private val LIQUID_NAMES = setOf(
            NS + "water", NS + "flowing_water", NS + "lava", NS + "flowing_lava"
        )

        private val PASSABLE_NAMES = setOf(
            NS + "powder_snow", NS + "bubble_column"
        )

        /**
         * No-collision decoration/plant blocks. Deliberately does NOT include cobwebs (they
         * impede movement in Bedrock) or leaves (they collide).
         */
        private val NON_SOLID_NAMES = setOf(
            // grass / ground plants
            NS + "short_grass", NS + "tall_grass", NS + "fern", NS + "dead_bush",
            NS + "seagrass", NS + "tall_seagrass", NS + "lily_pad",
            // crops / stems / fungi
            NS + "wheat", NS + "carrots", NS + "potatoes", NS + "beetroot",
            NS + "melon_stem", NS + "pumpkin_stem", NS + "cocoa", NS + "nether_wart",
            NS + "crimson_fungus", NS + "warped_fungus", NS + "crimson_roots", NS + "warped_roots",
            NS + "bamboo", NS + "bamboo_sapling", NS + "sugar_cane",
            // tall & flowering plants
            NS + "sunflower", NS + "double_plant", NS + "chorus_flower", NS + "chorus_plant",
            NS + "spore_blossom", NS + "azalea", NS + "flowering_azalea",
            NS + "kelp", NS + "kelp_plant", NS + "kelp_top",
            NS + "cave_vines", NS + "glow_berries",
            // vines / roots / dripleaves
            NS + "vine", NS + "weeping_vines", NS + "twisted_vines",
            NS + "hanging_roots", NS + "root",
            NS + "small_dripleaf", NS + "big_dripleaf", NS + "large_dripleaf",
            // light & signs
            NS + "torch", NS + "soul_torch", NS + "redstone_torch",
            NS + "unlit_redstone_torch", NS + "unlit_soul_redstone_torch",
            NS + "lantern", NS + "soul_lantern",
            NS + "standing_sign", NS + "standing_wall_sign",
            NS + "hanging_sign", NS + "hanging_wall_sign",
            NS + "banner", NS + "wall_banner",
            // carpets (all of them)
            NS + "oak_carpet", NS + "spruce_carpet", NS + "birch_carpet", NS + "jungle_carpet",
            NS + "acacia_carpet", NS + "dark_oak_carpet", NS + "crimson_carpet", NS + "warped_carpet",
            NS + "moss_carpet", NS + "bamboo_carpet", NS + "cherry_carpet", NS + "mangrove_carpet",
            // snow layers, fire, eggs
            NS + "snow", NS + "fire", NS + "soul_fire", NS + "turtle_egg",
            // sculk (no collision)
            NS + "sculk", NS + "sculk_vein"
        )

        /**
         * Category for a resolved block identifier, or [UNKNOWN] when the name could not be
         * resolved (missing mapping for this protocol version).
         */
        fun categoryFor(identifier: String): BlockCategory = when {
            identifier.isEmpty() -> UNKNOWN
            identifier in AIR_NAMES -> AIR
            identifier in LIQUID_NAMES -> LIQUID
            identifier in PASSABLE_NAMES -> PASSABLE
            identifier in NON_SOLID_NAMES -> NON_SOLID
            // potted_* / wall_* families are too long to enumerate — all are no-collision.
            identifier.startsWith(NS + "potted_") || identifier.startsWith(NS + "wall_") -> NON_SOLID
            identifier.startsWith(NS) -> SOLID
            else -> UNKNOWN
        }
    }
}
