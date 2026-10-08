package com.retrivedmods.wclient.game.utils.combat

import com.retrivedmods.wclient.game.GameSession
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityFlag
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket

/**
 * Detects when the LOCAL PLAYER is eating or drinking, from three independent signals:
 *
 *  1. client -> server: [PlayerAuthInputPacket] carrying [PlayerAuthInputData.START_USING_ITEM]
 *     while a consumable is held,
 *  2. client -> server: legacy [InventoryTransactionPacket] ITEM_USE (click air, actionType 1)
 *     with a consumable in hand — cleared again by the matching ITEM_RELEASE,
 *  3. server -> client: [SetEntityDataPacket] setting/clearing [EntityFlag.USING_ITEM] /
 *     [EntityFlag.EATING] on our own entity (authoritative state).
 *
 * AutoEat checks [isUserConsuming] and holds off while the user eats manually,
 * and use [serverUsingItem] as the server-side "consume started" confirmation checkpoint.
 *
 * Note: packets injected by modules via [GameSession.serverBound] bypass the relay's
 * handler chain, so this tracker only ever sees what the real game client sends.
 */
class ConsumeTracker(private val session: GameSession) {

    companion object {
        /** No vanilla consume takes longer than this; fail-safe against a missed clear. */
        private const val MAX_CONSUME_MS = 4000L

        private val CONSUMABLE_IDENTIFIERS = setOf(
            "minecraft:potion",
            "minecraft:splash_potion",
            "minecraft:lingering_potion",
            "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple",
            "minecraft:apple",
            "minecraft:bread",
            "minecraft:milk_bucket",
            "minecraft:honey_bottle",
            "minecraft:suspicious_stew",
            "minecraft:chorus_fruit",
            "minecraft:dried_kelp",
            "minecraft:sweet_berries",
            "minecraft:glow_berries",
            "minecraft:melon_slice",
            "minecraft:cookie",
            "minecraft:carrot",
            "minecraft:golden_carrot",
            "minecraft:potato",
            "minecraft:baked_potato",
            "minecraft:beetroot",
            "minecraft:beetroot_soup",
            "minecraft:mushroom_stew",
            "minecraft:rabbit_stew",
            "minecraft:pumpkin_pie"
        )

        fun isConsumable(item: ItemData): Boolean {
            if (item == ItemData.AIR) return false
            val id = item.definition?.identifier ?: return false
            return id in CONSUMABLE_IDENTIFIERS ||
                    id.startsWith("minecraft:cooked_") ||
                    id.endsWith("_stew") ||
                    id.endsWith("_soup")
        }
    }

    /** True while the user themselves is (very likely) mid-eat / mid-drink. */
    @Volatile
    var isUserConsuming = false
        private set

    /** Authoritative server state: our entity currently has USING_ITEM / EATING set. */
    @Volatile
    var serverUsingItem = false
        private set

    /** Timestamp of the last serverUsingItem flip, for checkpoint timing. */
    @Volatile
    var lastServerUsingItemChangeMs = 0L
        private set

    @Volatile
    private var userConsumeStartMs = 0L

    fun onPacket(packet: BedrockPacket) {
        val now = System.currentTimeMillis()
        when (packet) {
            is PlayerAuthInputPacket -> {
                if (packet.inputData.contains(PlayerAuthInputData.START_USING_ITEM) &&
                    isConsumable(session.localPlayer.inventory.hand)
                ) {
                    isUserConsuming = true
                    userConsumeStartMs = now
                }
                // Fail-safe: never stay latched longer than a real consume can take.
                if (isUserConsuming && now - userConsumeStartMs > MAX_CONSUME_MS) {
                    isUserConsuming = false
                }
                // Same fail-safe for the server flag: a lost "stop using" must not block AutoEat forever.
                if (serverUsingItem && now - lastServerUsingItemChangeMs > MAX_CONSUME_MS) {
                    serverUsingItem = false
                }
            }

            is InventoryTransactionPacket -> {
                when (packet.transactionType) {
                    InventoryTransactionType.ITEM_USE -> {
                        // actionType 1 = click air (start eating / drinking)
                        if (packet.actionType == 1 && isConsumable(packet.itemInHand)) {
                            isUserConsuming = true
                            userConsumeStartMs = now
                        }
                    }

                    InventoryTransactionType.ITEM_RELEASE -> {
                        // 0 = release (bow), 1 = consume — either way the use window ended.
                        isUserConsuming = false
                    }

                    else -> {}
                }
            }

            is SetEntityDataPacket -> {
                if (packet.runtimeEntityId != session.localPlayer.runtimeEntityId) return
                val metadata = packet.metadata
                // Only react when this delta actually carries the flag set.
                if (metadata.isFlagPresent(EntityFlag.USING_ITEM) ||
                    metadata.isFlagPresent(EntityFlag.EATING)
                ) {
                    val using = metadata.getFlag(EntityFlag.USING_ITEM) ||
                            metadata.getFlag(EntityFlag.EATING)
                    if (using != serverUsingItem) {
                        serverUsingItem = using
                        lastServerUsingItemChangeMs = now
                    }
                    if (!using) {
                        isUserConsuming = false
                    }
                }
            }
        }
    }

    fun reset() {
        isUserConsuming = false
        serverUsingItem = false
        lastServerUsingItemChangeMs = 0L
        userConsumeStartMs = 0L
    }
}
