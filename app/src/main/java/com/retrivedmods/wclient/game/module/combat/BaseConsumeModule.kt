package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.utils.combat.ConsumeLock
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.InventorySlotPacket
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Shared packet state machine for proxy-side eating and drinking
 * (see docs/AUTOEAT_AUTOPOT_DESIGN.md).
 *
 * Sequence: silent slot SELECT -> ITEM_USE START -> checkpoint A (server sets
 * USING_ITEM) -> HOLD for the consume duration -> ITEM_RELEASE CONSUME ->
 * checkpoint B (subclass-defined confirmation or the food stack shrinking) ->
 * RESTORE the real held slot. Each checkpoint has a latency-sized deadline,
 * one bounded retry, and a client-only chat message on success or failure.
 */
abstract class BaseConsumeModule(
    name: String,
    private val lockPriority: Int,
    private val holdDurationMs: Long
) : Module(name, ModuleCategory.Combat) {

    private enum class State { IDLE, STARTING, HOLDING, RELEASED }

    companion object {
        private val AIR_BLOCK = SimpleBlockDefinition("minecraft:air", 0, NbtMap.EMPTY)
        private const val CLICK_AIR = 1      // ITEM_USE actionType: use item in air
        private const val CONSUME = 1        // ITEM_RELEASE actionType: consume food/potion
        private const val BLOCK_FACE_NONE = 255
        private const val EYE_HEIGHT = 1.62f
        private const val SUCCESS_COOLDOWN_MS = 600L
        private const val FAIL_COOLDOWN_MS = 3000L
        private const val MISSING_ITEM_WARN_INTERVAL_MS = 15000L
        private const val WATCHDOG_EXTRA_MS = 4000L
    }

    private var state = State.IDLE
    private var usedSlot = -1
    private var usedItem: ItemData = ItemData.AIR
    private var usedItemCount = 0
    private var startedAt = 0L
    private var holdUntil = 0L
    private var releasedAt = 0L
    private var resentStart = false
    private var startConfirmed = false
    private var retries = 0
    private var cooldownUntil = 0L
    private var lastMissingItemWarnAt = 0L

    /** Whether conditions currently demand a consume (checked while IDLE). */
    protected abstract fun shouldConsume(): Boolean

    /** Inventory content index (0..35) of the item to consume, or null when out. */
    protected abstract fun findItemSlot(): Int?

    /** Server packet that proves the consume was applied (effect / attribute). */
    protected abstract fun isConsumeConfirmation(packet: BedrockPacket): Boolean

    /** Client-only chat line shown on success. */
    protected abstract fun successMessage(item: ItemData): String

    /** Client-only chat line shown (throttled) when the item ran out. */
    protected abstract fun missingItemMessage(): String

    /** Called when a sequence begins, so subclasses can snapshot state. */
    protected open fun onSequenceStart() {}

    protected val isSequenceActive: Boolean
        get() = state != State.IDLE

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated && state != State.IDLE) {
            abortSequence(restoreSlot = true)
        }
    }

    override fun onDisconnect(reason: String) {
        state = State.IDLE
        ConsumeLock.release(name)
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        val packet = interceptablePacket.packet

        // Checkpoint B listener: confirmation can land while HOLDING (some servers
        // apply effects on the release tick, some a tick early) or after RELEASED.
        if (state == State.HOLDING || state == State.RELEASED) {
            if (isConsumeConfirmation(packet) || isOwnSlotDecrement(packet)) {
                if (state == State.RELEASED) {
                    onSuccess()
                    return
                }
            }
        }

        if (packet !is PlayerAuthInputPacket) return
        val now = System.currentTimeMillis()

        // Watchdog: a consume can never legitimately take this long.
        if (state != State.IDLE && now - startedAt > holdDurationMs + WATCHDOG_EXTRA_MS) {
            onFailure("timed out")
            return
        }

        when (state) {
            State.IDLE -> tickIdle(now)
            State.STARTING -> tickStarting(now)
            State.HOLDING -> tickHolding(now)
            State.RELEASED -> tickReleased(now)
        }
    }

    // ------------------------------------------------------------------
    // State ticks
    // ------------------------------------------------------------------

    private fun tickIdle(now: Long) {
        if (now < cooldownUntil) return
        // Never fight the user's own eat/drink.
        if (session.consumeTracker.isUserConsuming || session.consumeTracker.serverUsingItem) return
        if (ConsumeLock.isHeldByOther(name)) return
        if (!shouldConsume()) return

        val slot = findItemSlot()
        if (slot == null) {
            if (now - lastMissingItemWarnAt > MISSING_ITEM_WARN_INTERVAL_MS) {
                lastMissingItemWarnAt = now
                session.displayClientMessage(missingItemMessage())
            }
            return
        }

        if (!ConsumeLock.tryAcquire(name, lockPriority)) return

        if (slot > 8) {
            // Item only in main inventory: restock it into the hotbar first and
            // let the next decision round use it once the server confirms.
            val hotbarTarget = pickHotbarTargetSlot()
            session.localPlayer.inventory.moveItem(slot, hotbarTarget, session.localPlayer.inventory, session)
            ConsumeLock.release(name)
            cooldownUntil = now + 250
            return
        }

        beginSequence(slot, now)
    }

    private fun tickStarting(now: Long) {
        if (session.consumeTracker.serverUsingItem) {
            // Checkpoint A passed: the server acknowledged the consume start.
            startConfirmed = true
            state = State.HOLDING
            return
        }
        val deadline = 250L + latencyAllowanceMs()
        if (!resentStart && now - startedAt > deadline) {
            sendStart()
            resentStart = true
        } else if (now - startedAt > deadline + 650L) {
            // Some servers never echo USING_ITEM to the eater — proceed unconfirmed;
            // checkpoint B still decides success or failure.
            state = State.HOLDING
        }
    }

    private fun tickHolding(now: Long) {
        if (now >= holdUntil) {
            sendRelease()
            releasedAt = now
            state = State.RELEASED
        }
    }

    private fun tickReleased(now: Long) {
        val deadline = 400L + latencyAllowanceMs()
        if (now - releasedAt <= deadline) return
        if (retries < 1) {
            // One full retry of the use->hold->consume cycle.
            retries++
            sendEquip(usedSlot, usedItem)
            sendStart()
            startedAt = now
            holdUntil = now + holdDurationMs
            resentStart = false
            startConfirmed = false
            state = State.STARTING
        } else {
            onFailure("server did not confirm it")
        }
    }

    // ------------------------------------------------------------------
    // Sequence actions
    // ------------------------------------------------------------------

    private fun beginSequence(hotbarSlot: Int, now: Long) {
        val inventory = session.localPlayer.inventory
        usedSlot = hotbarSlot
        usedItem = inventory.content[hotbarSlot]
        usedItemCount = usedItem.count
        retries = 0
        resentStart = false
        startConfirmed = false
        startedAt = now
        holdUntil = now + holdDurationMs
        onSequenceStart()

        sendEquip(hotbarSlot, usedItem)
        sendStart()
        state = State.STARTING
    }

    /** Server-bound only: the real client's screen and hotbar never change. */
    private fun sendEquip(slot: Int, item: ItemData) {
        val packet = MobEquipmentPacket()
        packet.runtimeEntityId = session.localPlayer.runtimeEntityId
        packet.item = item
        packet.inventorySlot = slot
        packet.hotbarSlot = slot
        packet.containerId = ContainerId.INVENTORY
        session.serverBound(packet)
    }

    private fun sendStart() {
        val player = session.localPlayer
        val packet = InventoryTransactionPacket()
        packet.transactionType = InventoryTransactionType.ITEM_USE
        packet.actionType = CLICK_AIR
        packet.hotbarSlot = usedSlot
        packet.itemInHand = usedItem
        packet.playerPosition = player.vec3Position
        packet.clickPosition = Vector3f.ZERO
        packet.blockPosition = Vector3i.ZERO
        packet.blockFace = BLOCK_FACE_NONE
        // Required by v712+ serializers; ignored by older codecs.
        packet.triggerType = ItemUseTransaction.TriggerType.UNKNOWN
        packet.clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
        packet.blockDefinition = AIR_BLOCK
        session.serverBound(packet)
    }

    private fun sendRelease() {
        val player = session.localPlayer
        val packet = InventoryTransactionPacket()
        packet.transactionType = InventoryTransactionType.ITEM_RELEASE
        packet.actionType = CONSUME
        packet.hotbarSlot = usedSlot
        packet.itemInHand = usedItem
        packet.headPosition = player.vec3Position.add(0f, EYE_HEIGHT, 0f)
        session.serverBound(packet)
    }

    private fun restoreHeldSlot() {
        val inventory = session.localPlayer.inventory
        val realSlot = inventory.heldItemSlot
        if (realSlot != usedSlot) {
            sendEquip(realSlot, inventory.content[realSlot])
        }
    }

    // ------------------------------------------------------------------
    // Outcomes
    // ------------------------------------------------------------------

    private fun onSuccess() {
        val item = usedItem
        restoreHeldSlot()
        ConsumeLock.release(name)
        state = State.IDLE
        cooldownUntil = System.currentTimeMillis() + SUCCESS_COOLDOWN_MS
        session.displayClientMessage(successMessage(item))
    }

    private fun onFailure(reason: String) {
        restoreHeldSlot()
        ConsumeLock.release(name)
        state = State.IDLE
        cooldownUntil = System.currentTimeMillis() + FAIL_COOLDOWN_MS
        session.displayClientMessage("§8[§cWClient§8] §c$name failed — $reason.")
    }

    private fun abortSequence(restoreSlot: Boolean) {
        if (restoreSlot) restoreHeldSlot()
        ConsumeLock.release(name)
        state = State.IDLE
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun latencyAllowanceMs(): Long {
        val latency = session.latency
        return if (latency.hasEstimate) latency.smoothedRttMs.toLong() else 150L
    }

    /** First empty hotbar slot, else the last hotbar slot (swap). */
    private fun pickHotbarTargetSlot(): Int {
        val content = session.localPlayer.inventory.content
        for (i in 0..8) {
            if (content[i] == ItemData.AIR) return i
        }
        return 8
    }

    /** Checkpoint B fallback: the server shrank the stack we just consumed from. */
    private fun isOwnSlotDecrement(packet: BedrockPacket): Boolean {
        if (packet !is InventorySlotPacket) return false
        if (packet.containerId != ContainerId.INVENTORY || packet.slot != usedSlot) return false
        val newItem = packet.item
        if (newItem == ItemData.AIR) return true
        val sameItem = newItem.definition?.identifier == usedItem.definition?.identifier
        return !sameItem || newItem.count < usedItemCount
    }
}
