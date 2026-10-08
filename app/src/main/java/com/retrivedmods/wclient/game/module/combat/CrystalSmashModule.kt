package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.utils.combat.ConsumeLock
import com.retrivedmods.wclient.game.utils.combat.HotbarLock
import com.retrivedmods.wclient.game.utils.math.RotationUtils
import com.retrivedmods.wclient.game.utils.math.TargetPredictor
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.inventory.ContainerId
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEquipmentPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import java.util.Collections
import java.util.WeakHashMap

/**
 * Smashes end crystals in range — close, fast, and first.
 *
 * Crystals come from [com.retrivedmods.wclient.game.world.Level.entityMap] by entity
 * identifier (the map already ingests Add/Remove/Move packets, so positions are live).
 *
 * Behaviour:
 *  - **Instant first hit** — the tick gate ([attackInterval]) is skipped for a crystal's first
 *    attack, so a freshly spawned crystal is hit on the very tick it becomes visible.
 *  - **Prediction** — crystals are knockbackable, so after one hit they drift; the aim point
 *    is projected with [TargetPredictor] using the same latency/hit-ratio lookahead Killaura
 *    uses ([predict]).
 *  - **Silent rotation** — server-bound facing only ([silentRot]), aimed at the crystal's
 *    centre (the entity position is the *bottom* of the box).
 *  - **Burst rhythm** — [burstSize] consecutive windows at [cps], then a [burstPause] ms lull
 *    (steady fire when [burstSize] is 1).
 *  - **Silent weapon swap** — when [useWeapon], the best sword in the hotbar is equipped
 *    server-side (MobEquipmentPacket, the real client never sees it) for the duration of the
 *    strike window. The swap goes through [HotbarLock] so it can never interleave with
 *    FeetSurroundModule's block swap.
 *  - **Hit confirmation** — a struck crystal is confirmed by server knockback (a move packet
 *    within [CONFIRM_MS]). A crystal that never moves after our hits is set aside
 *    ([DOUBT_MAX_MS]) instead of being spammed with voided attacks, and clears when it moves.
 *  - **Multi-target cone** — with [onlyClosest] off, every candidate inside a 60° cone of the
 *    aimed direction is hit (off-angle hits send knockback the wrong way).
 */
class CrystalSmashModule : Module("crystal_smash", ModuleCategory.Combat) {

    // ── Config ────────────────────────────────────────────────────────────
    private var rangeValue by floatValue("range", 4.0f, 2f..7f)
    private var attackInterval by intValue("delay", 5, 1..20)
    private var cpsValue by intValue("cps", 10, 1..20)
    private var packets by intValue("packets", 1, 1..10)
    private var silentRot by boolValue("silent_rot", true)
    private var onlyClosest by boolValue("only_closest", true)
    private var requireLos by boolValue("require_los", true)
    private var predict by boolValue("predict", true)
    private var useWeapon by boolValue("weapon", true)
    private var burstSize by intValue("burst", 1, 1..4)
    private var burstPause by intValue("burst_pause_ms", 300, 50..1000)

    // ── State (all relay-thread; weak keys die with the crystal's entity) ─
    private val predictor = TargetPredictor()
    private var lastWindowTime = 0L
    private var burstLeft = 0
    private var burstPauseUntil = 0L

    /** Crystals we have never struck — their first attack bypasses the tick gate. */
    private val firstHitPending: MutableSet<Entity> = Collections.newSetFromMap(WeakHashMap())

    /** Crystal -> time of our last strike, pending confirmation by server knockback. */
    private val lastStrike = WeakHashMap<Entity, Long>()

    /** Crystal -> time we declared its hits void; skipped until it moves or the doubt expires. */
    private val doubtful = WeakHashMap<Entity, Long>()

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        // A crystal that reacts to our hit (server-side knockback) is confirmed — clear doubt.
        val movedId = when (packet) {
            is MoveEntityAbsolutePacket -> packet.runtimeEntityId
            is MoveEntityDeltaPacket -> packet.runtimeEntityId
            else -> null
        }
        if (movedId != null) {
            clearDoubtFor(movedId)
        }

        if (packet !is PlayerAuthInputPacket) return

        val now = System.currentTimeMillis()
        refreshDoubt(now)

        val player = session.localPlayer
        val candidates = searchForCrystals(player)
        if (candidates.isEmpty()) return

        val tick = packet.tick
        candidates.forEach {
            predictor.record(it, tick)
            firstHitPending.add(it)
        }

        val primary = candidates.first()
        val aimPoint = aimAt(player, primary, tick)
        if (silentRot) {
            // Rotations run every gated tick, independent of the attack window below, so the
            // server-side facing converges on the crystal (Killaura's pattern).
            RotationUtils.aimSilently(player, aimPoint)
        }

        // A fresh crystal is hit the instant it appears — the tick gate only spaces follow-ups.
        if (primary !in firstHitPending && packet.tick % attackInterval != 0L) return
        if (now < burstPauseUntil) return
        if (now - lastWindowTime < 1000L / cpsValue.coerceAtLeast(1)) return

        val targets = selectTargets(player, primary, aimPoint, candidates)

        // Weapon decision: swap to the best hotbar sword unless we already hold one.
        val swap = pickWeaponSwap()
        if (swap != null && !HotbarLock.tryAcquire(name, HotbarLock.PRIORITY_WEAPON)) {
            // FeetSurround is mid-swap — skip this window, retry next tick.
            return
        }

        if (ConsumeLock.isBusy || session.consumeTracker.isUserConsuming) {
            if (swap != null) HotbarLock.release(name)
            return
        }

        lastWindowTime = now
        if (burstLeft <= 0) burstLeft = burstSize
        burstLeft--
        if (burstLeft == 0 && burstSize > 1) {
            burstPauseUntil = now + burstPause
        }

        try {
            if (swap == null) {
                targets.forEach { target ->
                    repeat(packets.coerceAtLeast(1)) { player.attack(target) }
                }
            } else {
                val (slot, item) = swap
                val previousSlot = player.inventory.heldItemSlot
                if (slot != previousSlot) equipServerSide(slot, item)
                targets.forEach { target ->
                    repeat(packets.coerceAtLeast(1)) { attackWith(player, target, slot, item) }
                }
                if (slot != previousSlot) equipServerSide(previousSlot, player.inventory.content[previousSlot])
            }
        } finally {
            if (swap != null) HotbarLock.release(name)
        }

        targets.forEach {
            firstHitPending.remove(it)
            lastStrike[it] = now
        }
    }

    // ── Target selection ──────────────────────────────────────────────────

    private fun searchForCrystals(player: Entity): List<Entity> {
        val now = System.currentTimeMillis()
        val eye = player.vec3Position.add(0f, EYE_HEIGHT, 0f)
        return session.level.entityMap.values
            .filter { it is EntityUnknown && it.identifier == END_CRYSTAL }
            .filter { it.distance(player) <= rangeValue }
            .filter { entity ->
                val doubtedAt = doubtful[entity] ?: return@filter true
                if (now - doubtedAt >= DOUBT_MAX_MS) {
                    doubtful.remove(entity)
                    true
                } else {
                    false
                }
            }
            .filter { !requireLos || session.world.canSee(eye, it.vec3Position) }
            .sortedBy { it.distance(player) }
    }

    /** Aim point: predicted position of the crystal, then its box centre. */
    private fun aimAt(player: Entity, primary: Entity, tick: Long): Vector3f {
        val position = if (predict) {
            val lookahead =
                (session.latency.lookaheadTicks + session.hitTracker.predictionOffsetTicks).coerceAtLeast(0f)
            predictor.predict(player, primary, lookahead, tick)
        } else {
            primary.vec3Position
        }
        return position.add(0f, CRYSTAL_CENTER, 0f)
    }

    /** Closest only, or every candidate inside the aim cone (multi-target). */
    private fun selectTargets(
        player: Entity,
        primary: Entity,
        aimPoint: Vector3f,
        candidates: List<Entity>,
    ): List<Entity> {
        if (onlyClosest) return listOf(primary)
        val eye = player.vec3Position.add(0f, EYE_HEIGHT, 0f)
        val toward = aimPoint.sub(eye).normalize()
        return candidates.filter { candidate ->
            candidate == primary ||
                candidate.vec3Position.add(0f, CRYSTAL_CENTER, 0f).sub(eye).normalize().dot(toward) >= CONE_COS
        }
    }

    // ── Hit confirmation (self-tuning) ────────────────────────────────────

    /** Strikes older than [CONFIRM_MS] with no knockback -> void; set the crystal aside. */
    private fun refreshDoubt(now: Long) {
        for ((entity, struckAt) in lastStrike.entries.toList()) {
            if (now - struckAt > CONFIRM_MS) {
                doubtful[entity] = now
                lastStrike.remove(entity)
            }
        }
    }

    private fun clearDoubtFor(runtimeEntityId: Long) {
        doubtful.entries.removeIf { it.key.runtimeEntityId == runtimeEntityId }
    }

    // ── Weapon swap ───────────────────────────────────────────────────────

    /**
     * Best sword in the hotbar (netherite first) when we are not already holding one; null =
     * attack with whatever is held. Returns null when mappings are missing (protocol without
     * state assets) so the module degrades to normal attacks instead of mis-swapping.
     */
    private fun pickWeaponSwap(): Pair<Int, ItemData>? {
        if (!useWeapon || !session.mappingsLoaded) return null
        val inventory = session.localPlayer.inventory
        val heldIdentifier = identifierOf(inventory.content[inventory.heldItemSlot])
        if (heldIdentifier != null && heldIdentifier in WEAPON_IDENTIFIERS) return null
        return bestWeaponSlot()
    }

    private fun bestWeaponSlot(): Pair<Int, ItemData>? {
        val inventory = session.localPlayer.inventory
        for (identifier in WEAPON_PRIORITY) {
            val slot = inventory.searchForItemInHotbar { item ->
                item.netId != 0 &&
                    runCatching { session.itemMapping.getDefinition(item.netId).identifier }.getOrNull() == identifier
            } ?: continue
            return slot to inventory.content[slot]
        }
        return null
    }

    private fun identifierOf(item: ItemData): String? =
        if (item.netId == 0) null else
            runCatching { session.itemMapping.getDefinition(item.netId).identifier }.getOrNull()

    /** Server-bound only: the real client's screen and hotbar never change. */
    private fun equipServerSide(slot: Int, item: ItemData) {
        val packet = MobEquipmentPacket()
        packet.runtimeEntityId = session.localPlayer.runtimeEntityId
        packet.item = item
        packet.inventorySlot = slot
        packet.hotbarSlot = slot
        packet.containerId = ContainerId.INVENTORY
        session.serverBound(packet)
    }

    /** Like [LocalPlayer.attack], but with an explicit slot. */
    private fun attackWith(player: LocalPlayer, target: Entity, slot: Int, item: ItemData) {
        player.swing()
        val packet = InventoryTransactionPacket()
        packet.transactionType = InventoryTransactionType.ITEM_USE_ON_ENTITY
        packet.actionType = 1
        packet.runtimeEntityId = target.runtimeEntityId
        packet.hotbarSlot = slot
        packet.itemInHand = item
        packet.playerPosition = player.vec3Position
        packet.clickPosition = Vector3f.ZERO
        session.serverBound(packet)
        session.hitTracker.onAttack(target.runtimeEntityId)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    override fun onDisabled() {
        super.onDisabled()
        resetState()
    }

    override fun onDisconnect(reason: String) {
        super.onDisconnect(reason)
        resetState()
    }

    private fun resetState() {
        predictor.reset()
        lastWindowTime = 0L
        burstLeft = 0
        burstPauseUntil = 0L
        firstHitPending.clear()
        lastStrike.clear()
        doubtful.clear()
    }

    companion object {
        private const val EYE_HEIGHT = 1.62f

        /** Entity position is the bottom of the crystal's box; centre is ~0.45 above. */
        private const val CRYSTAL_CENTER = 0.45f

        private const val END_CRYSTAL = "minecraft:end_crystal"

        /** Wait this long for server knockback after a strike before calling it void. */
        private const val CONFIRM_MS = 150L

        /** Max time a crystal is set aside after voided hits before we retry it. */
        private const val DOUBT_MAX_MS = 1000L

        /** cos(60°) — multi-target cone half-angle around the aim direction. */
        private const val CONE_COS = 0.5f

        private val WEAPON_PRIORITY = listOf(
            "minecraft:netherite_sword",
            "minecraft:diamond_sword",
            "minecraft:iron_sword",
            "minecraft:stone_sword",
            "minecraft:golden_sword",
            "minecraft:wooden_sword",
        )

        private val WEAPON_IDENTIFIERS: Set<String> = WEAPON_PRIORITY.toSet()
    }
}
