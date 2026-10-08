package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.utils.combat.ConsumeLock
import com.retrivedmods.wclient.game.utils.combat.chooseRestockSlot
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
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket

/**
 * How the held-slot switch that precedes a consume is shown to the real game client.
 *
 *  - [VISIBLE]: the client's hotbar moves onto the food/potion for the consume and back afterwards,
 *    exactly like a player pressing a hotbar key. Client and server agree on the held slot throughout.
 *  - [SILENT]: only the server is told (server-bound MobEquipmentPacket); the client keeps showing
 *    the real slot. The client's own attacks/uses during the window then reference a different slot
 *    than the server's.
 */
enum class ConsumeSwitchMode { VISIBLE, SILENT }

/**
 * Shared packet state machine for proxy-side eating and drinking
 * (see docs/AUTOEAT_AUTOPOT_DESIGN.md).
 *
 * Sequence: select slot -> ITEM_USE START -> checkpoint A (server echoes USING_ITEM; optional, not every
 * server does) -> HOLD -> ITEM_RELEASE CONSUME -> checkpoint B (subclass-defined confirmation, or our own
 * stack shrinking) -> restore the real held slot.
 *
 * A failed sequence is never blindly re-sent. If the server already applied a START, a second one could
 * consume a second item. The module is level-triggered instead: if the condition still holds after the
 * cooldown, the next decision round starts a fresh sequence. Releases that were not confirmed make the
 * next hold longer, which covers servers running below 20 TPS.
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
        private const val ABORT_COOLDOWN_MS = 600L
        private const val FAIL_COOLDOWN_MS = 1500L
        private const val FAIL_MESSAGE_INTERVAL_MS = 10000L
        private const val MISSING_ITEM_WARN_INTERVAL_MS = 15000L
        private const val WATCHDOG_EXTRA_MS = 4000L
        private const val HOLD_BONUS_STEP_MS = 200L
        private const val MAX_HOLD_BONUS_MS = 800L
    }

    /** See [ConsumeSwitchMode]. */
    private var switchMode by enumValue("Switch Mode", ConsumeSwitchMode.VISIBLE, ConsumeSwitchMode::class.java)

    /** Packets arrive on both the client and the server thread; every state change happens under this lock. */
    private val lock = Any()

    private var state = State.IDLE
    private var usedSlot = -1
    private var realSlot = -1            // held slot before the sequence; restored afterwards
    private var usedItem: ItemData = ItemData.AIR
    private var usedItemCount = 0
    private var startedAt = 0L           // when the START that is being measured was sent
    private var holdUntil = 0L
    private var releasedAt = 0L
    private var startAcknowledged = false
    private var startResent = false
    private var releaseResent = false
    private var holdBonusMs = 0L
    private var cooldownUntil = 0L
    private var lastMissingItemWarnAt = 0L
    private var lastFailMessageAt = 0L

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
        synchronized(lock) {
            if (isSessionCreated && state != State.IDLE) {
                abortSequence(restoreSlot = true)
            }
        }
    }

    override fun onDisconnect(reason: String) {
        synchronized(lock) {
            state = State.IDLE
            ConsumeLock.release(name)
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        val packet = interceptablePacket.packet

        synchronized(lock) {
            // Checkpoint B. Only a confirmation that arrives after the release was sent counts; anything
            // earlier is left over from an earlier consume.
            if (state == State.RELEASED && (isConsumeConfirmation(packet) || isOwnSlotDecrement(packet))) {
                onSuccess()
                return
            }

            // The player picked another hotbar slot mid-sequence: their choice wins and this consume is dropped.
            // That is not a failure: no hold bonus, no chat line. The next decision round retries if still needed.
            if (state != State.IDLE && isUserSlotChange(interceptablePacket)) {
                abortSequence(restoreSlot = true)
                cooldownUntil = System.currentTimeMillis() + ABORT_COOLDOWN_MS
                return
            }

            if (packet !is PlayerAuthInputPacket) return

            val now = System.currentTimeMillis()

            // Watchdog: a consume can never legitimately take this long.
            if (state != State.IDLE && now - startedAt > holdDurationMs + holdBonusMs + WATCHDOG_EXTRA_MS) {
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
    }

    // ------------------------------------------------------------------
    // State ticks (called with [lock] held)
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
            // Item only in the main inventory: swap it into the hotbar first. The next decision round
            // consumes it once the move has been synced back.
            val inventory = session.localPlayer.inventory
            inventory.moveItem(slot, pickHotbarTargetSlot(), inventory, session)
            ConsumeLock.release(name)
            cooldownUntil = now + 250
            return
        }

        beginSequence(slot, now)
    }

    private fun tickStarting(now: Long) {
        if (session.consumeTracker.serverUsingItem) {
            // Checkpoint A: the server acknowledged the start.
            startAcknowledged = true
            state = State.HOLDING
            return
        }
        val deadline = 250L + latencyAllowanceMs()
        if (!startResent && now - startedAt > deadline) {
            // The START may have been lost. Send it once more and measure the hold from this one,
            // because the server's use timer starts from whichever START it actually accepted.
            sendStart()
            startResent = true
            startedAt = now
            holdUntil = now + holdDurationMs + holdBonusMs
        } else if (now - startedAt > deadline + 650L) {
            // Some servers never echo USING_ITEM to the eater. Proceed; checkpoint B still decides.
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

        if (!releaseResent && session.consumeTracker.serverUsingItem) {
            // The server still counts us as eating, so the release came too early or was lost.
            // Release once more. Do NOT send a new START here: that could consume a second item.
            sendRelease()
            releaseResent = true
            releasedAt = now
            return
        }

        holdBonusMs = (holdBonusMs + HOLD_BONUS_STEP_MS).coerceAtMost(MAX_HOLD_BONUS_MS)
        onFailure(
            if (startAcknowledged) "the server never confirmed the consume"
            else "the server never showed the start"
        )
    }

    // ------------------------------------------------------------------
    // Sequence actions
    // ------------------------------------------------------------------

    private fun beginSequence(hotbarSlot: Int, now: Long) {
        val inventory = session.localPlayer.inventory
        usedSlot = hotbarSlot
        usedItem = inventory.content[hotbarSlot]
        usedItemCount = usedItem.count
        realSlot = inventory.heldItemSlot
        startAcknowledged = false
        startResent = false
        releaseResent = false
        startedAt = now
        holdUntil = now + holdDurationMs + holdBonusMs
        onSequenceStart()

        selectSlot(hotbarSlot)
        sendStart()
        state = State.STARTING
    }

    /** Moves the server's held slot to [slot]. In VISIBLE mode the client's hotbar follows and the model is updated. */
    private fun selectSlot(slot: Int) {
        val inventory = session.localPlayer.inventory
        sendEquip(slot, inventory.content[slot])
        if (switchMode == ConsumeSwitchMode.VISIBLE) {
            sendClientHotbar(slot)
            inventory.setHeldSlotLocally(slot)
        }
    }

    /**
     * Puts the real held slot back on the server, and on the client too if VISIBLE mode moved it.
     * If the player scrolled during the window, their own choice wins.
     */
    private fun restoreHeldSlot() {
        val inventory = session.localPlayer.inventory
        val target = if (inventory.heldItemSlot != usedSlot) inventory.heldItemSlot else realSlot
        if (target == usedSlot || target !in 0..8) return
        sendEquip(target, inventory.content[target])
        if (switchMode == ConsumeSwitchMode.VISIBLE) {
            sendClientHotbar(target)
        }
        inventory.setHeldSlotLocally(target)
    }

    /** Server-bound: tells the server which slot (and item) the following transaction refers to. */
    private fun sendEquip(slot: Int, item: ItemData) {
        val packet = MobEquipmentPacket()
        packet.runtimeEntityId = session.localPlayer.runtimeEntityId
        packet.item = item
        packet.inventorySlot = slot
        packet.hotbarSlot = slot
        packet.containerId = ContainerId.INVENTORY
        session.serverBound(packet)
    }

    /** Client-bound: makes the real game client show [slot] as its selected hotbar slot. */
    private fun sendClientHotbar(slot: Int) {
        val packet = PlayerHotbarPacket()
        packet.selectedHotbarSlot = slot
        packet.containerId = ContainerId.INVENTORY
        packet.selectHotbarSlot = true
        session.clientBound(packet)
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

    // ------------------------------------------------------------------
    // Outcomes (called with [lock] held)
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
        val now = System.currentTimeMillis()
        cooldownUntil = now + FAIL_COOLDOWN_MS
        if (now - lastFailMessageAt > FAIL_MESSAGE_INTERVAL_MS) {
            lastFailMessageAt = now
            session.displayClientMessage("§8[§cWClient§8] §c$name failed — $reason.")
        }
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

    /** Hotbar slot for a main-inventory consumable: never the held slot, an empty slot if there is one. */
    private fun pickHotbarTargetSlot(): Int {
        val inventory = session.localPlayer.inventory
        return chooseRestockSlot(inventory.heldItemSlot) { slot -> inventory.content[slot] == ItemData.AIR }
    }

    /**
     * The local game client picked a hotbar slot other than the one it is showing. In VISIBLE mode the client
     * shows the food slot; in SILENT mode it still shows the real slot. A selection of the slot it already shows
     * is only a re-sync, not a player action.
     */
    private fun isUserSlotChange(interceptablePacket: InterceptablePacket): Boolean {
        val packet = interceptablePacket.packet
        val clientShownSlot = if (switchMode == ConsumeSwitchMode.VISIBLE) usedSlot else realSlot
        return packet is MobEquipmentPacket &&
            interceptablePacket.serverBound == true &&
            packet.runtimeEntityId == session.localPlayer.runtimeEntityId &&
            packet.containerId == ContainerId.INVENTORY &&
            packet.hotbarSlot != clientShownSlot
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
