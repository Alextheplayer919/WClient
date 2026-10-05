package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.data.Effect
import com.retrivedmods.wclient.game.utils.combat.ConsumeLock
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket

/**
 * Anarchy-style AutoPot: maintains Strength / Fire Resistance / Regeneration /
 * Swiftness by drinking normal (drinkable) potions before the effect runs out.
 * Packet flow lives in [BaseConsumeModule]; a client-only chat message is
 * shown on every drink.
 */
class AutoPotModule : BaseConsumeModule(
    "Auto Pot",
    ConsumeLock.PRIORITY_POT,
    holdDurationMs = 1650L // 31 ticks of drinking + safety margin
) {

    private class PotionType(
        val effectId: Int,
        val displayName: String,
        /** Bedrock potion metadata values (normal / long / strong variants). */
        val metas: Set<Int>
    )

    companion object {
        private const val POTION = "minecraft:potion"

        private val STRENGTH = PotionType(Effect.STRENGTH, "Strength", setOf(31, 32, 33))
        private val FIRE_RESISTANCE = PotionType(Effect.FIRE_RESISTANCE, "Fire Resistance", setOf(12, 13))
        private val REGENERATION = PotionType(Effect.REGENERATION, "Regeneration", setOf(28, 29, 30))
        private val SWIFTNESS = PotionType(Effect.SPEED, "Swiftness", setOf(14, 15, 16))
    }

    private var maintainStrength by boolValue("Strength", true)
    private var maintainFireRes by boolValue("Fire Resistance", true)
    private var maintainRegen by boolValue("Regeneration", false)
    private var maintainSwiftness by boolValue("Swiftness", false)
    private var rePotSeconds by intValue("Repot Below (s)", 8, 1..60)

    private var target: PotionType? = null

    private fun enabledPotions(): List<PotionType> {
        val list = mutableListOf<PotionType>()
        if (maintainStrength) list.add(STRENGTH)
        if (maintainFireRes) list.add(FIRE_RESISTANCE)
        if (maintainRegen) list.add(REGENERATION)
        if (maintainSwiftness) list.add(SWIFTNESS)
        return list
    }

    private fun remainingSeconds(effectId: Int): Int {
        val effect = session.localPlayer.getEffectById(effectId) ?: return 0
        return (effect.duration / 20).coerceAtLeast(0)
    }

    private fun findPotionSlot(type: PotionType): Int? {
        val content = session.localPlayer.inventory.content
        for (slot in 0 until 36) {
            val item = content[slot]
            if (item != ItemData.AIR &&
                item.definition?.identifier == POTION &&
                item.damage in type.metas
            ) {
                return slot
            }
        }
        return null
    }

    override fun shouldConsume(): Boolean {
        for (potion in enabledPotions()) {
            if (remainingSeconds(potion.effectId) <= rePotSeconds) {
                target = potion
                return true
            }
        }
        target = null
        return false
    }

    override fun findItemSlot(): Int? {
        val potion = target ?: return null
        return findPotionSlot(potion)
    }

    override fun isConsumeConfirmation(packet: BedrockPacket): Boolean {
        val potion = target ?: return false
        if (packet !is MobEffectPacket) return false
        if (packet.runtimeEntityId != session.localPlayer.runtimeEntityId) return false
        if (packet.event != MobEffectPacket.Event.ADD &&
            packet.event != MobEffectPacket.Event.MODIFY
        ) return false
        return packet.effectId == potion.effectId
    }

    override fun successMessage(item: ItemData): String {
        val name = target?.displayName ?: "potion"
        return "§8[§cWClient§8] §dDrank §5$name Potion"
    }

    override fun missingItemMessage(): String {
        val name = target?.displayName ?: "potion"
        return "§8[§cWClient§8] §cOut of §5$name §cpotions — Auto Pot can't re-pot!"
    }
}
