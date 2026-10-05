package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.data.Effect
import com.retrivedmods.wclient.game.utils.combat.ConsumeLock
import com.retrivedmods.wclient.game.utils.constants.Attribute
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket

/**
 * Anarchy-style AutoEat: keeps Absorption hearts topped up with (enchanted)
 * golden apples and force-eats below an emergency health threshold.
 * Packet flow lives in [BaseConsumeModule]; a client-only chat message is
 * shown on every eat.
 */
class AutoEatModule : BaseConsumeModule(
    "Auto Eat",
    ConsumeLock.PRIORITY_EAT,
    holdDurationMs = 1700L // 32 ticks of eating + safety margin
) {

    companion object {
        private const val ENCHANTED_GAP = "minecraft:enchanted_golden_apple"
        private const val GAP = "minecraft:golden_apple"
    }

    private var keepAbsorption by boolValue("Keep Absorption", true)
    private var emergencyHealth by intValue("Emergency Health", 10, 1..20)
    private var preferEnchanted by boolValue("Prefer Enchanted", true)

    private var absorptionBefore = 0f
    private var emergencyEat = false

    private fun absorption(): Float =
        session.localPlayer.attributes[Attribute.ABSORPTION]?.value ?: 0f

    private fun health(): Float =
        session.localPlayer.attributes[Attribute.HEALTH]?.value ?: 20f

    override fun shouldConsume(): Boolean {
        val health = health()
        if (health <= 0f) return false
        emergencyEat = health <= emergencyHealth
        if (emergencyEat) return true
        return keepAbsorption && absorption() <= 0f
    }

    override fun findItemSlot(): Int? {
        val content = session.localPlayer.inventory.content
        val order = if (preferEnchanted) listOf(ENCHANTED_GAP, GAP) else listOf(GAP, ENCHANTED_GAP)
        for (identifier in order) {
            for (slot in 0 until 36) {
                val item = content[slot]
                if (item != ItemData.AIR && item.definition?.identifier == identifier) {
                    return slot
                }
            }
        }
        return null
    }

    override fun onSequenceStart() {
        absorptionBefore = absorption()
    }

    override fun isConsumeConfirmation(packet: BedrockPacket): Boolean {
        val player = session.localPlayer
        when (packet) {
            is MobEffectPacket -> {
                if (packet.runtimeEntityId != player.runtimeEntityId) return false
                if (packet.event != MobEffectPacket.Event.ADD &&
                    packet.event != MobEffectPacket.Event.MODIFY
                ) return false
                return packet.effectId == Effect.ABSORPTION ||
                        packet.effectId == Effect.REGENERATION ||
                        packet.effectId == Effect.FIRE_RESISTANCE
            }

            is UpdateAttributesPacket -> {
                if (packet.runtimeEntityId != player.runtimeEntityId) return false
                return packet.attributes.any {
                    it.name == Attribute.ABSORPTION && it.value > absorptionBefore
                }
            }
        }
        return false
    }

    override fun successMessage(item: ItemData): String {
        val itemName = when (item.definition?.identifier) {
            ENCHANTED_GAP -> "Enchanted Golden Apple"
            GAP -> "Golden Apple"
            else -> "food"
        }
        val reason = if (emergencyEat) "low HP" else "absorption"
        return "§8[§cWClient§8] §aAte §6$itemName §7($reason)"
    }

    override fun missingItemMessage(): String =
        "§8[§cWClient§8] §cNo golden apples left — Auto Eat has nothing to eat!"
}
