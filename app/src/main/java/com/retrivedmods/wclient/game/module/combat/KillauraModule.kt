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
    private var strafeSpeed by floatValue("strafe_speed", 0.15f, 0.05f..0.30f)





    private var lastAttackTime = 0L
    private var tpCooldown = 0L
    private val orbit = RotationUtils.Orbit()
    private val predictor = TargetPredictor()



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
            return
        }

        // Rotations and strafing run every tick, independently of attack CPS. Orbit speed is a
        // configured constant, so knockback and changing air-control velocity do not change pace.
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
            strafeAroundTarget(primary, tick)
        } else {
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
     * Orbits at a configured constant speed. Positions are still bounded per packet and re-anchored
     * to the current target, avoiding a snap when the target or configured radius changes.
     */
    private fun strafeAroundTarget(entity: Entity, tick: Long) {
        val player = session.localPlayer
        val next = orbit.next(player, entity, strafeRadius, tick, strafeSpeed) ?: return

        session.clientBound(
            MovePlayerPacket().apply {
                runtimeEntityId = player.runtimeEntityId
                position = next
                rotation = player.vec3Rotation
                mode = MovePlayerPacket.Mode.NORMAL
                // Killaura strafing is primarily used airborne; do not claim ground contact.
                onGround = false
                // Qualify: the function parameter `tick` would otherwise hide this packet field.
                this.tick = player.tickExists
            }
        )
    }


    private fun EntityUnknown.isMob(): Boolean {
        return this.identifier in MobList.mobTypes
    }
}