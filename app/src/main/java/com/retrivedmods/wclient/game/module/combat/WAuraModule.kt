package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.ModuleManager
import com.retrivedmods.wclient.game.entity.*
import com.retrivedmods.wclient.game.friend.FriendManager
import com.retrivedmods.wclient.game.utils.math.RotationUtils
import com.retrivedmods.wclient.game.utils.math.TargetPredictor
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class WAuraModule : Module("WAura", ModuleCategory.Combat) {

    private var playersOnly by boolValue("players_only", true)
    private var mobsOnly by boolValue("mobs_only", false)
    private var rotations by boolValue("rotations", true)
    private var prediction by boolValue("prediction", true)
    private var autoPrediction by boolValue("auto_prediction", true)
    private var predictionTicks by intValue("prediction_ticks", 2, 0..10)
    private var hitboxAim by boolValue("hitbox_aim", true)

    private var rangeValue by floatValue("range", 50f, 2f..50f)
    private var cpsValue by intValue("cps", 25, 1..50)
    private var boost by intValue("packets", 2, 1..10)

    private var targetMode by intValue("Target Mode", 2, 0..2)
    private var switchDelay by intValue("Switch Delay", 100, 20..100)

    private var strafe by boolValue("strafe", false)
    private val strafeRadius by floatValue("strafe_radius", 2.5f, 1f..6f)

    /**
     * Which way the player travels around the target while strafing. There is deliberately no
     * speed setting: the fly module sets the pace and the orbit only bends that movement.
     */
    private var spinDirection by enumValue("spin_direction", RotationUtils.SpinDirection.LEFT, RotationUtils.SpinDirection::class.java)

    private var lastAttackNanoTime = 0L
    private var lastSwitchTime = 0L
    private var switchIndex = 0
    private var currentTarget: Entity? = null
    private val predictor = TargetPredictor()
    private val orbit = RotationUtils.Orbit()

    /**
     * Shared prediction entry point for Killaura while WAura is enabled. This lets Killaura aim
     * at its own attack target using WAura's predictor history and the same prediction settings.
     */
    internal fun predictedAimPoint(observer: Entity, target: Entity, tick: Long): Vector3f? {
        if (!rotations) return null
        predictor.record(target, tick)

        val lookahead = if (autoPrediction) {
            session.latency.lookaheadTicks + session.hitTracker.predictionOffsetTicks
        } else {
            predictionTicks.toFloat()
        }
        val predicted = if (prediction) {
            predictor.predict(observer, target, lookahead.coerceAtLeast(0f), tick)
        } else {
            target.vec3Position
        }
        return if (hitboxAim) RotationUtils.hitboxAimPoint(observer, target, predicted) else predicted
    }

    override fun onDisabled() {
        super.onDisabled()
        predictor.reset()
        orbit.reset()
        currentTarget = null
    }

    override fun onDisconnect(reason: String) {
        predictor.reset()
        orbit.reset()
        currentTarget = null
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        val packet = interceptablePacket.packet

        // Server teleports and Bedrock movement-prediction corrections invalidate the orbit's
        // anchor. Do not immediately replay movement after a correction.
        if (packet is CorrectPlayerMovePredictionPacket ||
            (packet is MovePlayerPacket &&
                packet.runtimeEntityId == session.localPlayer.runtimeEntityId &&
                (packet.mode == MovePlayerPacket.Mode.TELEPORT || packet.mode == MovePlayerPacket.Mode.RESPAWN))
        ) {
            orbit.onServerCorrection()
            return
        }

        if (packet !is PlayerAuthInputPacket) return

        val localPlayer = session.localPlayer
        val tick = packet.tick
        val candidates = session.level.entityMap.values
            .filter { it.isTarget() && it.distance(localPlayer) <= rangeValue }
        candidates.forEach { predictor.record(it, tick) }

        val aimTarget = currentTarget?.takeIf { it.distance(localPlayer) <= rangeValue }
            ?: candidates.minByOrNull { it.distance(localPlayer) }

        // Silent, instant aim at the current/closest target every tick - no rotation speed of its
        // own and nothing is sent to the client, so the camera never moves.
        if (rotations) {
            aimTarget?.let { target ->
                predictedAimPoint(localPlayer, target, tick)?.let { aimPoint ->
                    RotationUtils.aimSilently(localPlayer, aimPoint)
                }
            }
        }

        // Spin around the target using the player's own movement (the fly). Killaura owns the orbit
        // whenever it is strafing too, so two spin sources never fight over the same tick.
        if (strafe && aimTarget != null && !killauraIsStrafing()) {
            session.orbitAround(localPlayer, aimTarget, strafeRadius, tick, spinDirection, orbit)
        } else {
            orbit.reset()
        }

        val now = System.nanoTime()
        val nowMillis = System.currentTimeMillis()
        val attackDelay = 1_000_000_000L / cpsValue

        if ((now - lastAttackNanoTime) < attackDelay) return

        val targets = searchForTargets()
        if (targets.isEmpty()) {
            currentTarget = null
            return
        }

        val player = session.localPlayer

        when (targetMode) {
            0 -> {
                val target = currentTarget ?: targets.first()
                if (target.distance(player) <= rangeValue) {
                    repeat(boost) { player.attack(target) }
                    currentTarget = target
                    lastAttackNanoTime = now
                } else {
                    currentTarget = null
                }
            }

            1 -> {
                if ((nowMillis - lastSwitchTime) >= switchDelay) {
                    switchIndex = (switchIndex + 1) % targets.size
                    currentTarget = targets[switchIndex]
                    lastSwitchTime = nowMillis
                }
                currentTarget?.let { target ->
                    if (target.distance(player) <= rangeValue) {
                        repeat(boost) { player.attack(target) }
                        lastAttackNanoTime = now
                    }
                }
            }

            2 -> {
                for (entity in targets) {
                    if (entity.distance(player) <= rangeValue) {
                        repeat(boost) { player.attack(entity) }
                    }
                }
                lastAttackNanoTime = now
            }
        }
    }

    /** Killaura spins too: when it does, it is the one that moves the player. */
    private fun killauraIsStrafing(): Boolean {
        val killaura = ModuleManager.modules
            .firstOrNull { it is KillauraModule && it.isEnabled } as? KillauraModule
        return killaura?.isStrafing == true
    }

    private fun searchForTargets(): List<Entity> {
        val player = session.localPlayer
        return session.level.entityMap.values
            .filter { it.distance(player) <= rangeValue }
            .filter { it.isTarget() }
            .filterNot { it is Player && FriendManager.isFriend(it.uuid) }
            .sortedBy { it.distance(player) }
    }

    private fun Entity.isTarget(): Boolean = when (this) {
        is LocalPlayer -> false
        is Player -> !mobsOnly && !isBot()
        is EntityUnknown -> {
            if (mobsOnly) identifier in MobList.mobTypes
            else !playersOnly
        }
        else -> false
    }

    private fun Player.isBot(): Boolean {
        if (this is LocalPlayer) return false
        val playerList = session.level.playerMap[this.uuid] ?: return false
        return playerList.name.isBlank()
    }
}
