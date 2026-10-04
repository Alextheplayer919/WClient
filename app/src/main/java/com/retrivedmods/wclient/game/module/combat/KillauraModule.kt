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
    private var hitboxAim by boolValue("hitbox_aim", true)

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
    private val predictor = TargetPredictor()

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



    override fun onDisabled() {
        super.onDisabled()
        orbit.reset()
        predictor.reset()
    }

    override fun onDisconnect(reason: String) {
        orbit.reset()
        predictor.reset()
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
            orbit.onServerCorrection()
            return
        }

        if (packet !is PlayerAuthInputPacket) return

        val targets = searchForTargets()
            .filterNot { it is Player && FriendManager.isFriend(it.uuid) }
        if (targets.isEmpty()) {
            orbit.reset()
            predictor.reset()
            orbitCommanded = false
            return
        }

        // Rotations and strafing run every tick, independently of attack CPS. Orbit speed comes
        // from the player's own movement (the fly), never from a value of ours.
        val player = session.localPlayer
        val primary = targets.first()
        val tick = packet.tick
        targets.forEach { predictor.record(it, tick) }

        if (rotations) {
            // If WAura is active, use its predictor history and settings for Killaura's actual
            // attack target. Killaura remains independently usable with its own settings otherwise.
            val activeWAura = ModuleManager.modules
                .firstOrNull { it is WAuraModule && it.isEnabled } as? WAuraModule
            val sharedAimPoint = activeWAura?.predictedAimPoint(player, primary, tick)
            val aimPoint = sharedAimPoint ?: run {
                val lookahead = if (autoPrediction) {
                    session.latency.lookaheadTicks + session.hitTracker.predictionOffsetTicks
                } else {
                    predictionTicks.toFloat()
                }
                val predicted = if (prediction) {
                    predictor.predict(player, primary, lookahead.coerceAtLeast(0f), tick)
                } else {
                    primary.vec3Position
                }
                if (hitboxAim) RotationUtils.hitboxAimPoint(player, primary, predicted) else predicted
            }
            // Silent: only the server-bound rotation changes, the camera is never moved.
            RotationUtils.aimSilently(player, aimPoint)
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