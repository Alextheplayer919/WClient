package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.*
import com.retrivedmods.wclient.game.friend.FriendManager
import com.retrivedmods.wclient.game.utils.math.RotationUtils
import com.retrivedmods.wclient.game.module.combat.rots.*
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.cos
import kotlin.math.sin

class KillauraModule : Module("killaura", ModuleCategory.Combat) {

    private var rangeValue by floatValue("range", 7f, 2f..10f)
    private var cpsValue by intValue("cps", 20, 5..30)
    private var packets by intValue("packets", 1, 1..10)
    private var playersOnly by boolValue("players_only", true)
    private var mobsOnly by boolValue("mobs_only", false)
    private var antiBot by boolValue("anti_bot", true)
    private var rotations by boolValue("rotations", true)
    private var prediction by boolValue("prediction", true)
    private var autoPrediction by boolValue("auto_prediction", true)
    private var predictionTicks by intValue("prediction_ticks", 2, 0..10)
    enum class RotationMode { PREDICTIVE, ABYSM, AUTO, SMOOTH, NEMESIS, VORTEX, FRONT_STRAFE, AIR_HVH_PRO, FRONTS_X, ASTRAL, ATOMIC, SYNTAX, CORTEX, ALPHA }

    private var rotationMode by enumValue("rotation_mode", RotationMode.PREDICTIVE, RotationMode::class.java)
    private var verticalOffset by floatValue("vertical_offset", 0.3f, -1f..2f)

    private var predictionStrength by floatValue("prediction_strength", 1f, 0f..1.5f)
    private var rotationSmoothing by floatValue("rotation_smoothing", 0.35f, 0.05f..1f)
    private var rotationMaxSpeed by floatValue("rotation_max_speed", 30f, 5f..90f)
    private var rotationAcceleration by floatValue("rotation_acceleration", 12f, 2f..60f)

    private var tpAuraEnabled by boolValue("tp_aura", false)
    private var teleportBehind by boolValue("tp_behind", false)
    private var tpSpeed by intValue("tp_speed", 100, 10..500)
    private var tpYOffset by intValue("tp_y_offset", 1, -10..10)
    private var keepDistance by floatValue("keep_distance", 1.2f, 0.5f..10f)


    private var strafe by boolValue("strafe", false)
    private val strafeRadius by floatValue("strafe_radius", 2.5f, 1f..6f)

    /**
     * Which way the player travels around the target while strafing. There is deliberately no
     * speed setting: the fly module sets the pace and the orbit only bends that movement.
     */
    private var spinDirection by enumValue("spin_direction", RotationUtils.SpinDirection.LEFT, RotationUtils.SpinDirection::class.java)





    private var lastAttackTime = 0L
    private var tpCooldown = 0L
    private val orbit = RotationUtils.Orbit()
    private val rotationEngine = KillauraRots()
    private val predictiveRotator = PredictiveRotator()
    private val abysm = AbysmRot()
    private val styleSelector = HvHGod()
    private var effectiveMode: RotationMode? = null
    private var lastRotationTick = Long.MIN_VALUE
    private var rotationContext: KillauraRots.Ctx? = null
    private var rotationTarget: Long? = null
    private var previousRotationMode: RotationMode? = null

    private fun resetRotations() {
        rotationEngine.resetState()
        predictiveRotator.reset()
        abysm.reset()
        styleSelector.reset()
        effectiveMode = null
        lastRotationTick = Long.MIN_VALUE
        rotationContext = null
        rotationTarget = null
        previousRotationMode = null
    }

    private var orbitCommanded = false

    /**
     * True while this module is actually moving the player around a target. WAura checks this so
     * two spin sources never fight over the player's position on the same tick.
     */
    internal val isStrafing: Boolean
        get() = strafe && orbitCommanded



    private fun Player.isBot(): Boolean {

        if (this is LocalPlayer) return false


        val playerListEntry = session.level.playerMap[this.uuid] ?: return true


        val name = playerListEntry.name?.toString() ?: ""
        if (name.isBlank()) return true


        val xuid = playerListEntry.xuid ?: ""
        if (xuid.isEmpty() || xuid == "0") return true

        if (name.trim().isEmpty()) return true

        return false
    }



    override fun onEnabled() {
        super.onEnabled()
        lastAttackTime = 0L
        tpCooldown = 0L
        orbit.reset()
        orbitCommanded = false
        resetRotations()
    }

    override fun onDisabled() {
        super.onDisabled()
        orbit.reset()
        orbitCommanded = false
        resetRotations()
    }

    override fun onDisconnect(reason: String) {
        orbit.reset()
        orbitCommanded = false
        resetRotations()
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        val packet = interceptablePacket.packet

        // Server teleports and Bedrock movement-prediction corrections invalidate the orbit's
        // previous phase and speed estimate. Do not immediately replay movement after a correction.
        if (packet is CorrectPlayerMovePredictionPacket ||
            (packet is MovePlayerPacket &&
                packet.runtimeEntityId == session.localPlayer.runtimeEntityId &&
                (packet.mode == MovePlayerPacket.Mode.TELEPORT || packet.mode == MovePlayerPacket.Mode.RESPAWN))
        ) {
            resetRotations()
            orbit.onServerCorrection()
            return
        }

        if (packet !is PlayerAuthInputPacket) return

        val targets = searchForTargets()
            .filterNot { it is Player && FriendManager.isFriend(it.uuid) }.toMutableList()
        if (previousRotationMode != rotationMode || packet.tick < lastRotationTick) {
            resetRotations()
            previousRotationMode = rotationMode
        }
        lastRotationTick = packet.tick
        if (!rotations) resetRotations()
        if (targets.isEmpty()) {
            orbit.reset()
            if (rotations && rotationMode == RotationMode.PREDICTIVE) {
                applyPredictiveRotation(targets, packet)
            } else resetRotations()
            orbitCommanded = false
            return
        }

        // Rotations and strafing run every tick, independently of attack CPS. Orbit speed comes
        // from the player's own movement (the fly), never from a value of ours.
        val player = session.localPlayer
        val tick = packet.tick
        predictiveRotator.selectTarget(targets, player, tick)
        val primary = targets.first()
        if (rotations && (rotationMode == RotationMode.ABYSM || rotationMode == RotationMode.AUTO)) {
            targets.forEach { styleSelector.observe(it.runtimeEntityId, it.rotationYaw, it.posX, feetY(it), it.posZ, tick) }
            styleSelector.retain(targets.map { it.runtimeEntityId })
        }
        if (rotations) {
            if (rotationMode == RotationMode.PREDICTIVE) applyPredictiveRotation(targets, packet)
            else applyRotations(player, primary, packet)
        } else {
            resetRotations()
        }
        if (strafe) {
            orbitCommanded = strafeAroundTarget(primary, tick)
        } else {
            orbitCommanded = false
            orbit.reset()
        }

        val now = System.currentTimeMillis()
        val delay = 1000L / cpsValue
        if (now - lastAttackTime < delay) return

        for (target in targets) {

            if (tpAuraEnabled && now - tpCooldown >= tpSpeed) {
                teleportTo(target)
                tpCooldown = now
            }


            repeat(packets) {
                session.localPlayer.attack(target)
            }
        }

        lastAttackTime = now
    }


    /** Adapt Bedrock eye-level player positions and packet deltas to the uploaded engine. */
    private fun applyRotations(player: LocalPlayer, target: Entity, packet: PlayerAuthInputPacket) {
        val selected = if (rotationMode == RotationMode.AUTO) {
            when (styleSelector.pick(target.runtimeEntityId, player.posX, player.posZ, packet.tick)) {
                "Atomic" -> RotationMode.ATOMIC
                "Cortex" -> RotationMode.CORTEX
                "Nemesis" -> RotationMode.NEMESIS
                "AirHvH Pro" -> RotationMode.AIR_HVH_PRO
                "FrontStrafe" -> RotationMode.FRONT_STRAFE
                "Astral" -> RotationMode.ASTRAL
                "Lynx Smooth" -> RotationMode.SMOOTH
                else -> RotationMode.FRONTS_X
            }
        } else rotationMode
        if (rotationTarget != target.runtimeEntityId || effectiveMode != selected) {
            rotationEngine.resetState()
            abysm.reset()
            abysm.seed(player.serverRotation.y, player.serverRotation.x)
            rotationContext = null
            rotationTarget = target.runtimeEntityId
            effectiveMode = selected
        }
        if (selected == RotationMode.ABYSM) {
            styleSelector.pick(target.runtimeEntityId, player.posX, player.posZ, packet.tick)
            abysm.maxSpeed = rotationMaxSpeed
            // The existing prediction switch/time applies to Abysm too; disable its built-in
            // fixed lead and feed a single, explicitly controlled predicted position.
            val lead = lookaheadTicks()
            val (pitch, yaw) = abysm.aim(
                target.posX + target.motionX * lead,
                feetY(target) + height(target) * 0.55f + verticalOffset + target.motionY * lead,
                target.posZ + target.motionZ * lead,
                0f, 0f, 0f, player.posX, player.posY, player.posZ, styleSelector.lastStyle
            )
            publishRotation(pitch, yaw, yaw)
            return
        }
        val lookahead = lookaheadTicks()
        val last = rotationContext
        val ctx = KillauraRots.Ctx(
            pred50 = lookahead * 50f, c158 = 0.9f, c15C = 8f, c160 = 1f,
            c164 = 3f, c20C = 0.7f, c214 = 1.5f, c120 = 0.1f,
            c124 = 0.1f, c11C = 8, c130 = 20, vertOffset = verticalOffset
        ).apply {
            rotPitch = last?.rotPitch ?: player.serverRotation.x
            rotYaw = last?.rotYaw ?: player.serverRotation.y
            headYaw = last?.headYaw ?: player.serverRotation.z
        }
        val snapshot = KillauraRots.Target(
            target.posX, feetY(target), target.posZ,
            target.motionX, target.motionY, target.motionZ, target.rotationYaw,
            width(target),
            height(target)
        )
        val keys = packet.inputData
        val env = KillauraRots.Env(
            player.posX, player.posY, player.posZ, player.motionX, player.motionY, player.motionZ,
            PlayerAuthInputData.UP in keys || PlayerAuthInputData.UP_LEFT in keys || PlayerAuthInputData.UP_RIGHT in keys,
            PlayerAuthInputData.LEFT in keys || PlayerAuthInputData.UP_LEFT in keys || PlayerAuthInputData.DOWN_LEFT in keys,
            PlayerAuthInputData.DOWN in keys || PlayerAuthInputData.DOWN_LEFT in keys || PlayerAuthInputData.DOWN_RIGHT in keys,
            PlayerAuthInputData.RIGHT in keys || PlayerAuthInputData.UP_RIGHT in keys || PlayerAuthInputData.DOWN_RIGHT in keys,
            PlayerAuthInputData.JUMPING in keys || PlayerAuthInputData.JUMP_DOWN in keys,
            (packet.tick % 72000L).toFloat() / 20f
        )
        when (selected) {
            RotationMode.SMOOTH -> rotationEngine.smooth(ctx, snapshot, env)
            RotationMode.NEMESIS -> rotationEngine.nemesis(ctx, snapshot, env)
            RotationMode.VORTEX -> rotationEngine.vortex(ctx, snapshot, env)
            RotationMode.FRONT_STRAFE -> rotationEngine.frontStrafe(ctx, snapshot, env, target.runtimeEntityId)
            RotationMode.AIR_HVH_PRO -> rotationEngine.airHvHPro(ctx, snapshot, env)
            RotationMode.FRONTS_X -> rotationEngine.frontsX(ctx, snapshot, env)
            RotationMode.ASTRAL -> rotationEngine.astral(ctx, snapshot, env)
            RotationMode.ATOMIC -> rotationEngine.atomic(ctx, snapshot, env)
            RotationMode.SYNTAX -> rotationEngine.syntax(ctx, snapshot, env)
            RotationMode.CORTEX -> rotationEngine.cortex(ctx, snapshot, env)
            RotationMode.ALPHA -> rotationEngine.alpha(ctx, snapshot, env)
            else -> return // Predictive and Abysm are handled above; AUTO resolves to a handler.
        }
        // These source handlers only output pitch/body yaw; keep the head aligned rather
        // than retaining an unrelated head yaw from the last packet.
        if (selected in setOf(RotationMode.SMOOTH, RotationMode.NEMESIS,
                RotationMode.VORTEX, RotationMode.FRONT_STRAFE)) {
            ctx.headYaw = ctx.rotYaw
        }
        if (ctx.rotPitch.isFinite() && ctx.rotYaw.isFinite() && ctx.headYaw.isFinite()) {
            rotationContext = ctx
            player.silentRotation = Vector3f.from(ctx.rotPitch.coerceIn(-90f, 90f), ctx.rotYaw, ctx.headYaw)
        } else {
            resetRotations()
        }
    }

    private fun lookaheadTicks(): Float = if (!prediction) 0f else if (autoPrediction) {
        (session.latency.lookaheadTicks + session.hitTracker.predictionOffsetTicks).coerceIn(0f, 10f)
    } else predictionTicks.toFloat()

    private fun applyPredictiveRotation(targets: MutableList<Entity>, packet: PlayerAuthInputPacket) {
        val settings = PredictiveRotator.Settings(
            predTime = lookaheadTicks(), predStrength = predictionStrength,
            smoothing = rotationSmoothing, maxSpeed = rotationMaxSpeed,
            accel = rotationAcceleration, vertOffset = verticalOffset
        )
        if (predictiveRotator.update(session, packet, targets, settings)) {
            publishRotation(predictiveRotator.pitch, predictiveRotator.yaw, predictiveRotator.yaw)
        }
    }

    private fun publishRotation(pitch: Float, yaw: Float, headYaw: Float) {
        if (pitch.isFinite() && yaw.isFinite() && headYaw.isFinite()) {
            session.localPlayer.silentRotation = Vector3f.from(pitch.coerceIn(-90f, 90f), yaw, headYaw)
        } else resetRotations()
    }

    private fun searchForTargets(): List<Entity> {
        val player = session.localPlayer
        return session.level.entityMap.values
            .filter { it.distance(player) <= rangeValue }
            .filter { it.isTarget() }
            .sortedBy { it.distance(player) }
    }

    private fun Entity.isTarget(): Boolean {
        return when (this) {
            is LocalPlayer -> false
            is Player -> {
                if (!playersOnly) return false


                if (antiBot && isBot()) return false

                true
            }
            is EntityUnknown -> mobsOnly && isMob()
            else -> false
        }
    }


    private fun teleportTo(entity: Entity) {
        val player = session.localPlayer
        val pos = entity.vec3Position

        val yawRad = Math.toRadians(entity.vec3Rotation.y.toDouble()).toFloat()
        val behind = Vector3f.from(sin(yawRad), 0f, -cos(yawRad)).normalize()

        val tpPos = if (teleportBehind) {
            Vector3f.from(
                pos.x + behind.x * keepDistance,
                pos.y + tpYOffset,
                pos.z + behind.z * keepDistance
            )
        } else {
            val dir = pos.sub(player.vec3Position).normalize()
            Vector3f.from(
                pos.x - dir.x * keepDistance,
                pos.y + tpYOffset,
                pos.z - dir.z * keepDistance
            )
        }

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = tpPos
                rotation = player.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                onGround = false
                tick = player.tickExists
            }
        )
    }


    /**
     * Spins the player around the target using nothing but their own movement: the orbit advances
     * by the distance the fly carried the player this tick and re-places them on the radius, so the
     * player circles the target instead of flying past it. Standing still means no spin.
     */
    private fun strafeAroundTarget(entity: Entity, tick: Long): Boolean =
        session.orbitAround(session.localPlayer, entity, strafeRadius, tick, spinDirection, orbit)


    private fun EntityUnknown.isMob(): Boolean {
        return this.identifier in MobList.mobTypes
    }
}