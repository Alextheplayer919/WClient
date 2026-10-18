package com.retrivedmods.neon.game.module.combat

import com.retrivedmods.neon.game.DisplayNamed
import com.retrivedmods.neon.game.InterceptablePacket
import com.retrivedmods.neon.game.Module
import com.retrivedmods.neon.game.ModuleCategory
import com.retrivedmods.neon.game.entity.*
import com.retrivedmods.neon.game.friend.FriendManager
import com.retrivedmods.neon.game.module.combat.rots.*
import com.retrivedmods.neon.game.utils.math.RotationUtils
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cloudburstmc.math.vector.Vector2f
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.CorrectPlayerMovePredictionPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One aura module hosting existing handlers and Kiwi modes, writing rotations to input packets. */
class KillauraModule : Module("d4c_aura", ModuleCategory.Combat) {

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
    enum class RotationMode(override val displayName: String) : DisplayNamed {
        PREDICTIVE("Predictive"),
        ABYSM("Abysm"),
        AUTO("Auto"),
        SMOOTH("Lynx Smooth"),
        NEMESIS("Nemesis"),
        VORTEX("Vortex"),
        FRONT_STRAFE("FrontStrafe"),
        AIR_HVH_PRO("AirHvH Pro"),
        FRONTS_X("FrontsX"),
        ASTRAL("Astral"),
        ATOMIC("Atomic"),
        SYNTAX("Syntax"),
        CORTEX("Cortex"),
        ALPHA("Alpha"),
        HVH_GOD("HvHGod"),
        GEO_CIRCLE("Circle"),
        GEO_SQUARE("Square"),
        GEO_ZIGZAG("Zigzag"),
        GEO_SPIRAL("Spiral"),
        KIWI_NONE("None"),
        KIWI_SNAP("Snap"),
        KIWI_NORMAL("Normal"),
        KIWI_SILENT("Silent"),
        KIWI_BODY("Body"),
        KIWI_PING_PREDICT("Ping Predict"),
        KIWI_LEAD_SHOT("Lead Shot"),
        KIWI_SMOOTH("Smooth"),
        KIWI_LEGIT("Legit"),
        KIWI_JITTER("Jitter"),
        KIWI_STRAFE("Strafe"),
        KIWI_ANTICIPATE("Anticipate")
    }

    private var rotationMode by enumValue("rotation_mode", RotationMode.PREDICTIVE, RotationMode::class.java)
    private var verticalOffset by floatValue("vertical_offset", 0.3f, -1f..2f)

    // Settings used by the imported Kiwi-style rotation handlers.
    private var randomizeRotations by boolValue("randomize", false)
    private var smoothSpeed by floatValue("smooth_speed", 15f, 1f..45f)
    private var strafeAmplitude by floatValue("strafe_amp", 8f, 0f..30f)
    private var cameraSync by boolValue("camera_sync", true)

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
    private var rotationTickCounter = 0L
    private var smoothYaw = 0f
    private var smoothPitch = 0f

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

    /** Import the title-cased Kiwi settings used by the merged rotation modes. */
    override fun fromJson(jsonElement: JsonElement) {
        val moduleObject = jsonElement as? JsonObject ?: return super.fromJson(jsonElement)
        val sourceValues = moduleObject["values"] as? JsonObject ?: return super.fromJson(jsonElement)
        val migratedValues = sourceValues.toMutableMap()

        fun copyValue(currentName: String, legacyName: String) {
            if (currentName !in migratedValues) {
                sourceValues[legacyName]?.let { migratedValues[currentName] = it }
            }
        }

        copyValue("range", "Range")
        copyValue("cps", "CPS")
        copyValue("packets", "Packets")
        copyValue("players_only", "Players Only")
        copyValue("anti_bot", "Anti Bot")
        copyValue("randomize", "Randomize")
        copyValue("smooth_speed", "Smooth Speed")
        copyValue("strafe_amp", "Strafe Amp")
        copyValue("camera_sync", "Camera Sync")
        copyValue("vertical_offset", "Vertical Offset")
        copyValue("prediction_strength", "Pred Strength")
        copyValue("rotation_smoothing", "Rot Smoothing")
        copyValue("rotation_max_speed", "Rot Max Speed")
        copyValue("rotation_acceleration", "Rot Accel")

        if ("prediction_ticks" !in migratedValues) {
            val predictionTime = (sourceValues["Pred Time"] as? JsonPrimitive)?.content?.toFloatOrNull()
            if (predictionTime != null) migratedValues["prediction_ticks"] = JsonPrimitive(predictionTime.toInt())
        }
        if ("rotation_mode" !in migratedValues) {
            val legacyMode = (sourceValues["Rot Mode"] as? JsonPrimitive)?.content
            val migratedMode = legacyMode?.let { mode ->
                LEGACY_ROTATION_MODES.entries.firstOrNull { it.key.equals(mode, ignoreCase = true) }?.value
            }
            if (migratedMode != null) migratedValues["rotation_mode"] = JsonPrimitive(migratedMode)
        }

        val migratedObject = moduleObject.toMutableMap()
        migratedObject["values"] = JsonObject(migratedValues)
        super.fromJson(JsonObject(migratedObject))
    }

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
        rotationTickCounter = 0L
        orbit.reset()
        orbitCommanded = false
        resetRotations()
    }

    override fun onDisabled() {
        super.onDisabled()
        rotationTickCounter = 0L
        orbit.reset()
        orbitCommanded = false
        resetRotations()
    }

    override fun onDisconnect(reason: String) {
        rotationTickCounter = 0L
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
        val tickRolledBack = packet.tick < lastRotationTick
        if (previousRotationMode != rotationMode || tickRolledBack) {
            resetRotations()
            previousRotationMode = rotationMode
            if (tickRolledBack) rotationTickCounter = 0L
        }
        lastRotationTick = packet.tick
        rotationTickCounter++
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
        if (rotations && (rotationMode == RotationMode.ABYSM || rotationMode == RotationMode.AUTO ||
                    rotationMode == RotationMode.HVH_GOD)) {
            targets.forEach { styleSelector.observe(it.runtimeEntityId, it.rotationYaw, it.posX, feetY(it), it.posZ, tick) }
            styleSelector.retain(targets.map { it.runtimeEntityId })
        }
        var shouldAttack = true
        if (rotations) {
            if (rotationMode == RotationMode.PREDICTIVE) applyPredictiveRotation(targets, packet)
            else shouldAttack = applyRotations(player, primary, packet)
        } else {
            resetRotations()
        }
        if (strafe) {
            orbitCommanded = strafeAroundTarget(primary, tick)
        } else {
            orbitCommanded = false
            orbit.reset()
        }

        if (!shouldAttack) return

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


    /** Adapt Bedrock eye-level player positions and packet deltas to the uploaded engines. */
    private fun applyRotations(player: LocalPlayer, target: Entity, packet: PlayerAuthInputPacket): Boolean {
        val selected = if (rotationMode == RotationMode.AUTO || rotationMode == RotationMode.HVH_GOD) {
            when (styleSelector.pick(target.runtimeEntityId, player.posX, player.posZ, packet.tick)) {
                "Atomic" -> RotationMode.ATOMIC
                "Cortex" -> RotationMode.CORTEX
                "Nemesis" -> RotationMode.NEMESIS
                "AirHvH Pro" -> RotationMode.AIR_HVH_PRO
                "FrontStrafe" -> RotationMode.FRONT_STRAFE
                "Astral" -> RotationMode.ASTRAL
                "Lynx Smooth" -> RotationMode.SMOOTH
                "FrontsX" -> RotationMode.FRONTS_X
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
            val targetChestY = feetY(target) + if (target is Player) height(target) * 0.6f else 0.9f
            val (pitch, yaw) = abysm.aim(
                target.posX, targetChestY, target.posZ,
                target.motionX, target.motionY, target.motionZ,
                player.posX, player.posY, player.posZ, styleSelector.lastStyle
            )
            applyPacketRotation(packet, pitch, yaw, yaw)
            return true
        }

        val geoPattern = when (selected) {
            RotationMode.GEO_CIRCLE -> "Circle"
            RotationMode.GEO_SQUARE -> "Square"
            RotationMode.GEO_ZIGZAG -> "Zigzag"
            RotationMode.GEO_SPIRAL -> "Spiral"
            else -> null
        }
        if (geoPattern != null) {
            val (offsetX, offsetY, offsetZ) = GeoRot.offset(geoPattern, packet.tick)
            val chestY = feetY(target) + if (target is Player) height(target) * 0.6f else 0.9f
            val (pitch, yaw) = abysm.aim(
                target.posX + offsetX, chestY + offsetY, target.posZ + offsetZ,
                0f, 0f, 0f, player.posX, player.posY, player.posZ, HvHGod.Style.UNKNOWN
            )
            applyPacketRotation(packet, pitch, yaw, yaw)
            return true
        }

        if (selected.isKiwiMode()) return applyKiwiRotation(selected, player, target, packet)

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
            else -> return true // Predictive, Abysm, geometric, and Kiwi modes are handled above.
        }
        // These source handlers only output pitch/body yaw; keep the head aligned rather
        // than retaining an unrelated head yaw from the last packet.
        if (selected in setOf(RotationMode.SMOOTH, RotationMode.NEMESIS,
                RotationMode.VORTEX, RotationMode.FRONT_STRAFE)) {
            ctx.headYaw = ctx.rotYaw
        }
        if (ctx.rotPitch.isFinite() && ctx.rotYaw.isFinite() && ctx.headYaw.isFinite()) {
            rotationContext = ctx
            applyPacketRotation(packet, ctx.rotPitch, ctx.rotYaw, ctx.headYaw)
        } else {
            resetRotations()
        }
        return true
    }

    private fun RotationMode.isKiwiMode(): Boolean = when (this) {
        RotationMode.KIWI_NONE,
        RotationMode.KIWI_SNAP,
        RotationMode.KIWI_NORMAL,
        RotationMode.KIWI_SILENT,
        RotationMode.KIWI_BODY,
        RotationMode.KIWI_PING_PREDICT,
        RotationMode.KIWI_LEAD_SHOT,
        RotationMode.KIWI_SMOOTH,
        RotationMode.KIWI_LEGIT,
        RotationMode.KIWI_JITTER,
        RotationMode.KIWI_STRAFE,
        RotationMode.KIWI_ANTICIPATE -> true
        else -> false
    }

    /** Kiwi handlers keep their original formulas and write directly to the current input packet. */
    private fun applyKiwiRotation(
        mode: RotationMode, player: LocalPlayer, target: Entity, packet: PlayerAuthInputPacket
    ): Boolean {
        if (mode == RotationMode.KIWI_NONE) return false

        val eye = Vector3f.from(player.posX, player.posY, player.posZ)
        val (pitch, yaw) = calcAngle(eye, kiwiChestPosition(target))
        val outPitch: Float
        val outYaw: Float

        when (mode) {
            RotationMode.KIWI_SNAP,
            RotationMode.KIWI_NORMAL,
            RotationMode.KIWI_SILENT,
            RotationMode.KIWI_BODY,
            RotationMode.KIWI_PING_PREDICT,
            RotationMode.KIWI_LEAD_SHOT -> {
                var adjustedPitch = pitch
                var adjustedYaw = yaw
                if (randomizeRotations) {
                    adjustedPitch += (kotlin.random.Random.nextFloat() - 0.5f) * 14f
                    adjustedYaw += (kotlin.random.Random.nextFloat() - 0.5f) * 14f
                }
                smoothPitch = adjustedPitch
                smoothYaw = adjustedYaw
                outPitch = adjustedPitch
                outYaw = adjustedYaw
            }
            RotationMode.KIWI_JITTER -> {
                outPitch = pitch + (kotlin.random.Random.nextFloat() - 0.5f) * 14f
                outYaw = yaw + (kotlin.random.Random.nextFloat() - 0.5f) * 14f
            }
            RotationMode.KIWI_SMOOTH,
            RotationMode.KIWI_LEGIT -> {
                var deltaYaw = yaw - smoothYaw
                while (deltaYaw > 180f) deltaYaw -= 360f
                while (deltaYaw < -180f) deltaYaw += 360f
                if (abs(deltaYaw) > smoothSpeed) {
                    deltaYaw = if (deltaYaw > 0f) smoothSpeed else -smoothSpeed
                }
                smoothYaw += deltaYaw
                smoothPitch += (pitch - smoothPitch) * 0.3f
                if (mode == RotationMode.KIWI_LEGIT && abs(deltaYaw) < 5f) {
                    smoothPitch += (kotlin.random.Random.nextFloat() - 0.5f) * 3f
                    smoothYaw += (kotlin.random.Random.nextFloat() - 0.5f) * 3f
                }
                outPitch = smoothPitch
                outYaw = smoothYaw
            }
            RotationMode.KIWI_STRAFE -> {
                val dx = target.posX - eye.x
                val dz = target.posZ - eye.z
                val scale = min(sqrt(dx * dx + dz * dz) / 6f, 1f)
                outYaw = sin(rotationTickCounter * 0.35f) * strafeAmplitude * scale + yaw
                outPitch = pitch + cos(rotationTickCounter * 0.22f) * strafeAmplitude * 0.25f * scale
            }
            RotationMode.KIWI_ANTICIPATE -> {
                val yawRadians = Math.toRadians(target.vec3Rotation.y.toDouble())
                val ahead = Vector3f.from(
                    target.posX - sin(yawRadians).toFloat() * ANTICIPATE_DEPTH,
                    kiwiChestPosition(target).y,
                    target.posZ + cos(yawRadians).toFloat() * ANTICIPATE_DEPTH
                )
                val (aheadPitch, aheadYaw) = calcAngle(eye, ahead)
                outPitch = aheadPitch
                outYaw = aheadYaw
            }
            else -> return false
        }

        applyPacketRotation(packet, outPitch, outYaw, outYaw)
        return true
    }

    /** (pitch, yaw) in degrees using the uploaded Kiwi angle convention. */
    private fun calcAngle(from: Vector3f, to: Vector3f): Pair<Float, Float> {
        val x = (to.x - from.x).toDouble()
        val y = (to.y - from.y).toDouble()
        val z = (to.z - from.z).toDouble()
        val distance = sqrt(x * x + y * y + z * z)
        val pitch = if (distance > 0.001) (-asin(y / distance) * RAD2DEG).toFloat() else 0f
        val yaw = (-atan2(x, z) * RAD2DEG).toFloat()
        return pitch to yaw
    }

    private fun kiwiChestPosition(target: Entity): Vector3f = Vector3f.from(
        target.posX,
        feetY(target) + if (target is Player) height(target) * 0.5f else 0.9f,
        target.posZ
    )

    private fun lookaheadTicks(): Float = if (!prediction) 0f else if (autoPrediction) {
        (session.latency.lookaheadTicks + session.hitTracker.predictionOffsetTicks).coerceIn(0f, 10f)
    } else predictionTicks.toFloat()

    private fun applyPredictiveRotation(targets: MutableList<Entity>, packet: PlayerAuthInputPacket) {
        val settings = PredictiveRotator.Settings(
            predTime = lookaheadTicks(), predStrength = predictionStrength,
            smoothing = rotationSmoothing, maxSpeed = rotationMaxSpeed,
            accel = rotationAcceleration, cameraSync = cameraSync, vertOffset = verticalOffset
        )
        if (predictiveRotator.update(session, packet, targets, settings)) {
            applyPacketRotation(packet, predictiveRotator.pitch, predictiveRotator.yaw, predictiveRotator.yaw)
        }
    }

    private fun applyPacketRotation(packet: PlayerAuthInputPacket, pitch: Float, yaw: Float, headYaw: Float) {
        if (pitch.isFinite() && yaw.isFinite() && headYaw.isFinite()) {
            val rotation = Vector3f.from(pitch.coerceIn(-90f, 90f), yaw, headYaw)
            packet.rotation = rotation
            if (packet.interactRotation != null) {
                packet.interactRotation = Vector2f.from(rotation.x, rotation.y)
            }
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

    private companion object {
        const val RAD2DEG = 57.29577951308232
        const val ANTICIPATE_DEPTH = 3f

        val LEGACY_ROTATION_MODES = mapOf(
            "Predictive" to RotationMode.PREDICTIVE.name,
            "Abysm" to RotationMode.ABYSM.name,
            "Auto" to RotationMode.AUTO.name,
            "HvHGod" to RotationMode.HVH_GOD.name,
            "Circle" to RotationMode.GEO_CIRCLE.name,
            "Square" to RotationMode.GEO_SQUARE.name,
            "Zigzag" to RotationMode.GEO_ZIGZAG.name,
            "Spiral" to RotationMode.GEO_SPIRAL.name,
            "None" to RotationMode.KIWI_NONE.name,
            "Snap" to RotationMode.KIWI_SNAP.name,
            "Normal" to RotationMode.KIWI_NORMAL.name,
            "Silent" to RotationMode.KIWI_SILENT.name,
            "Body" to RotationMode.KIWI_BODY.name,
            "Ping Predict" to RotationMode.KIWI_PING_PREDICT.name,
            "Lead Shot" to RotationMode.KIWI_LEAD_SHOT.name,
            "Smooth" to RotationMode.KIWI_SMOOTH.name,
            "Legit" to RotationMode.KIWI_LEGIT.name,
            "Jitter" to RotationMode.KIWI_JITTER.name,
            "Strafe" to RotationMode.KIWI_STRAFE.name,
            "Anticipate" to RotationMode.KIWI_ANTICIPATE.name,
            "Lynx Smooth" to RotationMode.SMOOTH.name,
            "Nemesis" to RotationMode.NEMESIS.name,
            "Vortex" to RotationMode.VORTEX.name,
            "FrontStrafe" to RotationMode.FRONT_STRAFE.name,
            "AirHvH Pro" to RotationMode.AIR_HVH_PRO.name,
            "FrontsX" to RotationMode.FRONTS_X.name,
            "Astral" to RotationMode.ASTRAL.name,
            "Atomic" to RotationMode.ATOMIC.name,
            "Syntax" to RotationMode.SYNTAX.name,
            "Cortex" to RotationMode.CORTEX.name,
            "Alpha" to RotationMode.ALPHA.name
        )
    }
}
