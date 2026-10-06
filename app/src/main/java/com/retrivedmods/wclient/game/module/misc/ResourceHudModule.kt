package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.data.Effect
import com.retrivedmods.wclient.overlay.hud.ResourceHudOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData

/**
 * HUD card with live counts of the PvP consumables in your inventory —
 * totems of undying and strength potions by default, more on request — plus the
 * remaining time of the Strength effect.
 *
 * Counts come straight from the client's mirror of the inventory
 * (`session.localPlayer.inventory`), so they are correct the moment the server
 * confirms a slot change. Potions are told apart by their item data value, the
 * same way AutoPot does it. Position is a module value and can be dragged in
 * HUD edit mode.
 */
class ResourceHudModule : Module("resource_hud", ModuleCategory.Misc) {

    private var scope: CoroutineScope? = null

    // --- what to show -------------------------------------------------------
    private val showTotems by boolValue("Totems", true)
    private val showStrength by boolValue("Strength Potions", true)
    private val showStrengthTimer by boolValue("Strength Timer", true)
    private val showRegeneration by boolValue("Regeneration Potions", false)
    private val showFireResistance by boolValue("Fire Resistance Potions", false)
    private val showSwiftness by boolValue("Swiftness Potions", false)
    private val showGoldenApples by boolValue("Golden Apples", false)
    private val showEnchantedApples by boolValue("Enchanted Apples", false)
    private val showEnderPearls by boolValue("Ender Pearls", false)
    private val includeSplash by boolValue("Include Splash Potions", true)
    private val hideEmpty by boolValue("Hide Empty Rows", false)
    private val lowThreshold by intValue("Low Warning At", 1, 0..16)

    // --- look ---------------------------------------------------------------
    private val compactMode by boolValue("Compact Mode", false)
    private val fontSize by intValue("Font Size", 14, 10..24)
    private val minecraftFont by boolValue("Minecraft Font", true)
    private val showBackground by boolValue("Background", true)
    private val backgroundOpacity by floatValue("BG Opacity", 0.7f, 0.0f..1.0f)
    private val updateRate by intValue("Update Rate", 200, 100..1000)

    // --- layout (written back by the overlay after a drag in HUD edit mode) --
    private var position by enumValue("Position", Position.CENTER_LEFT, Position::class.java)
    // Signed: the CENTER_* anchors measure their offset from the middle.
    private var offsetX by intValue("Offset X", 20, -2000..2000)
    private var offsetY by intValue("Offset Y", 0, -2000..2000)

    override fun onEnabled() {
        super.onEnabled()
        if (!isSessionCreated) return

        ResourceHudOverlay.setOverlayEnabled(true)
        ResourceHudOverlay.onPositionChanged = { newPosition, x, y ->
            if (position != newPosition) position = newPosition
            if (offsetX != x) offsetX = x
            if (offsetY != y) offsetY = y
        }
        applySettings()
        ResourceHudOverlay.setRows(buildRows())

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()).apply {
            launch {
                while (isActive && isEnabled && isSessionCreated) {
                    applySettings()
                    try {
                        ResourceHudOverlay.setRows(buildRows())
                    } catch (_: Exception) {
                        // Inventory can be mid-update on the relay thread; try again next tick.
                    }
                    delay(updateRate.toLong())
                }
            }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        scope?.cancel()
        scope = null
        ResourceHudOverlay.onPositionChanged = null

        if (isSessionCreated) {
            ResourceHudOverlay.setOverlayEnabled(false)
        }
    }

    override fun onDisconnect(reason: String) {
        scope?.cancel()
        scope = null
        ResourceHudOverlay.onPositionChanged = null
        ResourceHudOverlay.setOverlayEnabled(false)
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
    }

    private fun applySettings() {
        ResourceHudOverlay.setPosition(position, offsetX, offsetY)
        ResourceHudOverlay.setFontSize(fontSize)
        ResourceHudOverlay.setCompact(compactMode)
        ResourceHudOverlay.setUseMinecraftFont(minecraftFont)
        ResourceHudOverlay.setShowBackground(showBackground)
        ResourceHudOverlay.setBackgroundOpacity(backgroundOpacity)
    }

    // ---------------------------------------------------------------------
    // Counting
    // ---------------------------------------------------------------------

    private fun buildRows(): List<ResourceHudOverlay.ResourceRow> {
        val player = session.localPlayer
        // 36 inventory + 4 armor + 1 off-hand; a totem in the off-hand counts.
        val content = player.inventory.content

        val rows = ArrayList<ResourceHudOverlay.ResourceRow>(9)

        if (showTotems) {
            rows += row("Totems", countItems(content) { it.isItem(TOTEM) })
        }
        if (showStrength) {
            val timer = if (showStrengthTimer) effectTimer(Effect.STRENGTH) else ""
            rows += row("Strength", countPotions(content, STRENGTH_METAS), timer)
        }
        if (showRegeneration) {
            rows += row("Regen", countPotions(content, REGENERATION_METAS), effectTimer(Effect.REGENERATION))
        }
        if (showFireResistance) {
            rows += row("Fire Res", countPotions(content, FIRE_RESISTANCE_METAS), effectTimer(Effect.FIRE_RESISTANCE))
        }
        if (showSwiftness) {
            rows += row("Speed", countPotions(content, SWIFTNESS_METAS), effectTimer(Effect.SPEED))
        }
        if (showGoldenApples) {
            rows += row("Gapples", countItems(content) { it.isItem(GOLDEN_APPLE) })
        }
        if (showEnchantedApples) {
            rows += row("E-Gapples", countItems(content) { it.isItem(ENCHANTED_GOLDEN_APPLE) })
        }
        if (showEnderPearls) {
            rows += row("Pearls", countItems(content) { it.isItem(ENDER_PEARL) })
        }

        return if (hideEmpty) rows.filter { it.count > 0 } else rows
    }

    private fun row(label: String, count: Int, extra: String = "") =
        ResourceHudOverlay.ResourceRow(
            label = label,
            count = count,
            extra = extra,
            low = count <= lowThreshold
        )

    private inline fun countItems(content: Array<ItemData>, predicate: (ItemData) -> Boolean): Int {
        var total = 0
        for (item in content) {
            if (item == ItemData.AIR) continue
            if (predicate(item)) total += item.count.coerceAtLeast(1)
        }
        return total
    }

    private fun countPotions(content: Array<ItemData>, metas: Set<Int>): Int =
        countItems(content) { item ->
            val id = item.definition?.identifier ?: return@countItems false
            val isPotion = id == POTION ||
                    (includeSplash && (id == SPLASH_POTION || id == LINGERING_POTION))
            isPotion && item.damage in metas
        }

    private fun ItemData.isItem(identifier: String): Boolean =
        definition?.identifier == identifier

    /** "m:ss" of the remaining effect time, or "" when the effect is not active. */
    private fun effectTimer(effectId: Int): String {
        val effect = session.localPlayer.getEffectById(effectId) ?: return ""
        val seconds = (effect.duration / 20).coerceAtLeast(0)
        if (seconds <= 0) return ""
        val level = if (effect.amplifier >= 1) "${toRoman(effect.amplifier + 1)} " else ""
        return "$level${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }

    private fun toRoman(level: Int): String = when (level) {
        1 -> "I"
        2 -> "II"
        3 -> "III"
        4 -> "IV"
        5 -> "V"
        else -> level.toString()
    }

    enum class Position {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    companion object {
        private const val TOTEM = "minecraft:totem_of_undying"
        private const val POTION = "minecraft:potion"
        private const val SPLASH_POTION = "minecraft:splash_potion"
        private const val LINGERING_POTION = "minecraft:lingering_potion"
        private const val GOLDEN_APPLE = "minecraft:golden_apple"
        private const val ENCHANTED_GOLDEN_APPLE = "minecraft:enchanted_golden_apple"
        private const val ENDER_PEARL = "minecraft:ender_pearl"

        // Bedrock potion item data values (regular / extended / enhanced).
        // Same table AutoPotModule relies on.
        private val STRENGTH_METAS = setOf(31, 32, 33)
        private val REGENERATION_METAS = setOf(28, 29, 30)
        private val FIRE_RESISTANCE_METAS = setOf(12, 13)
        private val SWIFTNESS_METAS = setOf(14, 15, 16)
    }
}
