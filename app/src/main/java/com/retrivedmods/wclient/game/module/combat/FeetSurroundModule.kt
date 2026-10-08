package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.utils.combat.HotbarLock
import com.retrivedmods.wclient.game.utils.math.RotationUtils
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.nbt.NbtMap
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleBlockDefinition
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.ItemUseTransaction
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Keeps the four cardinal slots at feet level filled with surround blocks (default: obsidian).
 *
 * Every auth-input tick the module looks at the block the player is standing in, takes its four
 * horizontal neighbours at the same level and — as often as [placeInterval] allows — places a
 * block into the first candidate that is:
 *
 *  1. replaceable (air or liquid),
 *  2. supported (solid block underneath — the block is placed *on top* of it),
 *  3. visible from the eye (voxel raycast, optional via [requireLos]).
 *
 * Candidates are ranked by crosshair alignment so the placement the server "sees" (rotation +
 * click point) is the most natural one.
 *
 * Placement protocol (see `docs/FEET_SURROUND_DESIGN.md` for the full packet anatomy):
 * the vendored codec speaks the legacy `ITEM_USE` transaction for block placement
 * (`InventoryTransactionType.ITEM_USE`, actionType 0, target position/face + click point), which
 * is exactly what the v712+ serializers expect. The obsidian slot is equipped server-side with
 * `MobEquipmentPacket` around the transaction (same silent-swap pattern as
 * [BaseConsumeModule]) — the real client's hotbar is never touched.
 *
 * Block state knowledge comes from [com.retrivedmods.wclient.game.world.World]; before the
 * player's subchunks arrive (or on protocol versions without block-state assets) every slot is
 * "unknown" and the module simply stays put.
 */
class FeetSurroundModule : Module("feet_surround", ModuleCategory.Combat) {

    // ── Config ──────────────────────────────────────────────────────────

    private var placeInterval by intValue("place_interval_ms", 60, 20..200)
    private var silentRot by boolValue("silent_rot", true)
    private var requireLos by boolValue("require_los", true)
    private var surroundBlock by enumValue("block", SurroundBlock.OBSIDIAN, SurroundBlock::class.java)

    // ── State ───────────────────────────────────────────────────────────

    private var nextPlaceAt = 0L

    // ── Cardinal offsets at feet level ──────────────────────────────────

    private val CARDINALS = arrayOf(
        Vector3i.from(1, 0, 0),   // +X
        Vector3i.from(-1, 0, 0),  // -X
        Vector3i.from(0, 0, 1),   // +Z
        Vector3i.from(0, 0, -1),  // -Z
    )

    // ── Packet interception ─────────────────────────────────────────────

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        if (now < nextPlaceAt) return

        val target = pickSurroundSlot() ?: run {
            // No candidate (unknown chunks / everything filled): retry soon.
            nextPlaceAt = now + NO_SLOT_RETRY_MS
            return
        }

        val player = session.localPlayer
        // The block is placed on the TOP face of the block below the target slot — Bedrock
        // resolves the placement cell as clicked block + face direction. The click point is
        // the centre of that top face.
        val clickPoint = Vector3f.from(target.x + 0.5f, target.y.toFloat(), target.z + 0.5f)

        // Silent aim at the block face we're placing against (server-side rotation only).
        if (silentRot) {
            RotationUtils.aimSilently(player, clickPoint, EYE_HEIGHT)
        }

        if (placeBlockAt(target, clickPoint)) {
            nextPlaceAt = now + placeInterval
        } else {
            nextPlaceAt = now + NO_SLOT_RETRY_MS
        }
    }

    override fun onDisconnect(reason: String) {
        super.onDisconnect(reason)
        nextPlaceAt = 0L
    }

    // ── Slot selection (single slot, feet-level cardinals) ─────────────

    private fun pickSurroundSlot(): Vector3i? {
        val player = session.localPlayer
        val feet = player.vec3Position
        val own = Vector3i.from(
            floor(feet.x).toInt(),
            floor(feet.y).toInt(),
            floor(feet.z).toInt()
        )
        val world = session.world
        val eye = feet.add(0f, EYE_HEIGHT, 0f)

        val candidates = CARDINALS
            .map { add(own, it) }
            .filter { world.isReplaceable(it.x, it.y, it.z) }
            .filter { world.isSolid(it.x, it.y - 1, it.z) }
            .filter { !requireLos || world.canSee(eye, center(it)) }

        if (candidates.isEmpty()) return null

        // Prefer the slot most aligned with the crosshair (most natural placement).
        // Convention matches RotationUtils.toRotation (yaw 0 = +Z, 90 = -X).
        val yawRad = Math.toRadians(player.rotationYaw.toDouble()).toFloat()
        val look = Vector3f.from(-sin(yawRad), 0f, cos(yawRad))
        return candidates.minByOrNull { slot ->
            val toSlot = Vector3f.from((slot.x - own.x).toFloat(), 0f, (slot.z - own.z).toFloat()).normalize()
            1f - toSlot.dot(look)
        }
    }

    // ── Placement ───────────────────────────────────────────────────────

    /**
     * Silent-swaps to the surround-block slot and sends the placement transaction, then swaps
     * back. The client's screen never sees any of it. The swap runs under [HotbarLock] so it
     * cannot interleave with the crystal smash's weapon swap.
     */
    private fun placeBlockAt(target: Vector3i, clickPoint: Vector3f): Boolean {
        val player = session.localPlayer
        val inventory = player.inventory

        val slot = findBlockSlot() ?: return false
        val item = inventory.content[slot]
        if (item.netId == 0 || item.count <= 0) return false

        if (!HotbarLock.tryAcquire(name, HotbarLock.PRIORITY_BLOCK)) return false

        val previousSlot = inventory.heldItemSlot
        if (previousSlot != slot) {
            equipServerSide(slot, item)
        }
        try {
            // Click the TOP face of the block below the target slot: the placement cell is
            // resolved as clicked block + face direction, so the new block lands in the
            // target slot itself.
            val support = Vector3i.from(target.x, target.y - 1, target.z)
            session.serverBound(InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType = ACTION_USE_BLOCK
                blockPosition = support
                blockFace = FACE_TOP
                hotbarSlot = slot
                itemInHand = item
                playerPosition = player.vec3Position
                clickPosition = clickPoint
                // Required by the v712+ serializers; ignored by older codecs.
                triggerType = ItemUseTransaction.TriggerType.PLAYER_INPUT
                clientInteractPrediction = ItemUseTransaction.PredictedResult.SUCCESS
                blockDefinition = AIR_BLOCK
            })
        } finally {
            if (previousSlot != slot) {
                equipServerSide(previousSlot, inventory.content[previousSlot])
            }
            HotbarLock.release(name)
        }
        return true
    }

    /** Hotbar slot holding the configured surround block, or null (mapping not loaded, none in hotbar). */
    private fun findBlockSlot(): Int? {
        if (!session.mappingsLoaded) return null
        val wanted = surroundBlock.item
        return session.localPlayer.inventory.searchForItemInHotbar { item ->
            item.netId != 0 &&
                runCatching { session.itemMapping.getDefinition(item.netId).identifier }
                    .getOrNull() == wanted
        }
    }

    /** Server-bound only: the real client's screen and hotbar never change. */
    private fun equipServerSide(slot: Int, item: ItemData) {
        val player = session.localPlayer
        val packet = MobEquipmentPacket()
        packet.runtimeEntityId = player.runtimeEntityId
        packet.item = item
        packet.inventorySlot = slot
        packet.hotbarSlot = slot
        packet.containerId = ContainerId.INVENTORY
        session.serverBound(packet)
    }

    private fun add(a: Vector3i, b: Vector3i): Vector3i =
        Vector3i.from(a.x + b.x, a.y + b.y, a.z + b.z)

    private fun center(block: Vector3i): Vector3f =
        Vector3f.from(block.x + 0.5f, block.y + 0.5f, block.z + 0.5f)

    companion object {
        private const val EYE_HEIGHT = 1.62f
        private const val NO_SLOT_RETRY_MS = 50L

        /** ITEM_USE actionType: 0 = use on block, 1 = use item in air (see BaseConsumeModule). */
        private const val ACTION_USE_BLOCK = 0

        /** Bedrock face ids: 0 = -Y, 1 = +Y, 2 = -Z, 3 = +Z, 4 = -X, 5 = +X. */
        private const val FACE_TOP = 1

        private val AIR_BLOCK = SimpleBlockDefinition("minecraft:air", 0, NbtMap.EMPTY)
    }

    /** Blocks the module will place; the item identifier is looked up in the hotbar. */
    enum class SurroundBlock(val item: String) {
        OBSIDIAN("minecraft:obsidian"),
        CRYING_OBSIDIAN("minecraft:crying_obsidian")
    }
}
