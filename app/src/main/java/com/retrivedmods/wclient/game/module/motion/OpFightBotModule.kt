package com.retrivedmods.wclient.game.module.motion

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.ModuleManager
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.Item
import com.retrivedmods.wclient.game.entity.MobList
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * OPFightBot — port of ProtoHax's ModuleOpFightBot, adapted to WClient.
 *
 * The bot is a direction chooser, not a speed authority. It writes fractional
 * inputs into TestFlyModule's external control surface. TestFlyModule owns
 * the actual motion packets, horizontal/vertical speed, adaptive governor,
 * and lagback recovery.
 *
 * Phases:
 *   Approaching (horizDist > range, passive = false) — fly toward the target.
 *   Orbiting    (horizDist < range)                  — hold a ring at radius = range.
 *
 * Modes:
 *   RANDOM — pick a random yaw to strafe on each tick.
 *   STRAFE — advance yaw by strafe_step degrees each tick (circling).
 *   BEHIND — hold position directly behind the target's body yaw.
 *
 * Requires TestFlyModule to expose:
 *   var externalForward:  Float?   — overrides PlayerAuthInput.motion.y when set
 *   var externalStrafe:   Float?   — overrides PlayerAuthInput.motion.x when set
 *   var externalWantUp:   Boolean? — overrides WANT_UP/JUMPING when set
 *   var externalWantDown: Boolean? — overrides WANT_DOWN/SNEAKING when set
 */
class OpFightBotModule : Module("op_fight_bot", ModuleCategory.Motion) {

    // ── Settings ─────────────────────────────────────────────────────────────

    /** Orbit mode used while inside [range]. */
    private val mode by enumValue("mode", Mode.STRAFE, Mode::class.java)

    /** Radius (in blocks) at which the bot holds position around the target. */
    private val range by floatValue("range", 1.5f, 1.5f..4.0f)

    /** If true, the bot never approaches targets outside [range]; it only orbits. */
    private val passive by boolValue("passive", false)

    /** If true, mobs from MobList.mobTypes are also treated as valid targets. */
    private val targetMobs by boolValue("target_mobs", false)

    /**
     * How aggressively the bot corrects radial error while orbiting.
     * 0.0 = never correct, 1.0 = correct at TestFly's full horizontal speed.
     * This is a fraction of fly's speed, not an independent speed.
     */
    private val orbitStrength by floatValue("orbit_strength", 0.5f, 0.0f..1.0f)

    /** Degrees added to the orbit angle per tick while in STRAFE mode. */
    private val strafeStep by intValue("strafe_step", 20, 10..90)

    /** Vertical dead-band — ignore vertical error smaller than this to avoid jitter. */
    private val verticalDeadzone by floatValue("vertical_deadzone", 0.15f, 0.05f..1.0f)

    // ── State ────────────────────────────────────────────────────────────────

    /**
     * Persistent orbit angle. Kept in module state instead of reading
     * tickExists, so it survives world changes and doesn't depend on any
     * entity's tick counter.
     */
    private var orbitAngle = 0.0

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onDisabled() {
        super.onDisabled()
        releaseFly()
        orbitAngle = 0.0
    }

    // ── Packet hook ──────────────────────────────────────────────────────────

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        if (!isSessionCreated) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val fly = findFly() ?: return
        if (!fly.isEnabled || !fly.isSessionCreated) {
            releaseFly()
            return
        }

        val target = pickTarget()
        if (target == null) {
            releaseFly()
            return
        }

        val player = session.localPlayer
        val dx = target.posX - player.posX
        val dz = target.posZ - player.posZ
        val dy = target.posY - player.posY
        val horizDist = sqrt(dx * dx + dz * dz)

        // Degenerate case: standing on top of the target. Nothing meaningful to orbit.
        if (horizDist < 0.001f) return

        // Yaw from player toward target. Bedrock convention: 0 = +Z, 90 = -X.
        val yawToTarget = Math.toDegrees(atan2(-dx.toDouble(), dz.toDouble())).toFloat()

        if (horizDist < range) {
            // ── Orbit phase ──────────────────────────────────────────────
            val desiredYaw = when (mode) {
                Mode.RANDOM -> (Math.random() * 360.0).toFloat()
                Mode.STRAFE -> {
                    orbitAngle = (orbitAngle + strafeStep) % 360.0
                    orbitAngle.toFloat()
                }
                Mode.BEHIND -> target.rotationYaw + 180f
            }

            // Radial correction: pull back toward ring radius = range.
            // Clamped to orbitStrength, which is a fraction of fly's max speed.
            val radialError = (horizDist - range).coerceIn(-orbitStrength, orbitStrength)

            // Radial component points along the line target -> player (outwardYaw).
            val outwardRad = Math.toRadians((yawToTarget + 180f).toDouble())
            val radialWorldX = -sin(outwardRad).toFloat() * radialError
            val radialWorldZ = cos(outwardRad).toFloat() * radialError

            // Tangential component gives the circling motion.
            // Half-strength so the radial correction dominates when they conflict.
            val tangentRad = Math.toRadians(desiredYaw.toDouble())
            val tangentMag = orbitStrength * 0.5f
            val tangentWorldX = -sin(tangentRad).toFloat() * tangentMag
            val tangentWorldZ = cos(tangentRad).toFloat() * tangentMag

            // Sum the two world-space vectors, then decompose into fly's local axes.
            val combinedWorldX = radialWorldX + tangentWorldX
            val combinedWorldZ = radialWorldZ + tangentWorldZ
            val (fwd, str) = worldVecToLocalInput(combinedWorldX, combinedWorldZ, yawToTarget)

            fly.externalForward = fwd
            fly.externalStrafe = str

            // Match the target's Y level. Dead-band prevents vertical jitter.
            fly.externalWantUp = dy > verticalDeadzone
            fly.externalWantDown = dy < -verticalDeadzone

        } else if (!passive) {
            // ── Approach phase ───────────────────────────────────────────
            // Point straight at the target. Unit direction — fly's own speed
            // settings decide how fast we actually close the gap.
            val (fwd, str) = worldVecToLocalInput(dx / horizDist, dz / horizDist, yawToTarget)

            fly.externalForward = fwd
            fly.externalStrafe = str

            fly.externalWantUp = dy > verticalDeadzone
            fly.externalWantDown = dy < -verticalDeadzone

        } else {
            // Passive + out of range: do nothing, let fly fall through to its own inputs.
            releaseFly()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Resolve TestFlyModule from ModuleManager. Singleton lookup — cheap. */
    private fun findFly(): TestFlyModule? =
        ModuleManager.modules.firstOrNull { it is TestFlyModule } as? TestFlyModule

    /**
     * Convert a world-space (x, z) direction into fly's local (forward, strafe) frame.
     *
     * TestFlyModule rotates input as:
     *   motionX = strafe * cosY - forward * sinY
     *   motionZ = forward * cosY + strafe * sinY
     * where Y is the player's yaw (packet.rotation.y, in degrees).
     *
     * So the inverse is:
     *   forward =  worldZ * cosY + worldX * sinY
     *   strafe  = -worldZ * sinY + worldX * cosY
     */
    private fun worldVecToLocalInput(worldX: Float, worldZ: Float, yawDeg: Float): Pair<Float, Float> {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val sinY = sin(yaw).toFloat()
        val cosY = cos(yaw).toFloat()
        val forward = worldZ * cosY + worldX * sinY
        val strafe = -worldZ * sinY + worldX * cosY
        return forward to strafe
    }

    /**
     * Nearest valid target within the entity map.
     *
     * Valid = other players, plus mobs when targetMobs is on.
     * Excluded = the local player, dropped items, and unknown non-mob entities
     * (XP orbs, arrows, boats, minecarts, armor stands, etc.).
     */
    private fun pickTarget(): Entity? {
        val self = session.localPlayer
        return session.level.entityMap.values.asSequence()
            // Self-exclusion — three independent checks so a mismatched ID
            // in one place can't cause the bot to target itself.
            .filter { it !== self }
            .filter { it.runtimeEntityId != self.runtimeEntityId }
            .filter { it.uniqueEntityId != self.uniqueEntityId }
            // Dropped items are never valid targets.
            .filter { it !is Item }
            // Whitelist by type: players always; mobs only if enabled and recognized.
            .filter { entity ->
                when (entity) {
                    is Player -> true
                    is EntityUnknown -> targetMobs && entity.identifier in MobList.mobTypes
                    else -> false
                }
            }
            .minByOrNull { it.distanceSq(self) }
    }

    /** Clear fly's external inputs so its own packet-driven defaults take over. */
    private fun releaseFly() {
        val fly = findFly() ?: return
        fly.externalForward = null
        fly.externalStrafe = null
        fly.externalWantUp = null
        fly.externalWantDown = null
    }

    // ── Enum ─────────────────────────────────────────────────────────────────

    enum class Mode { RANDOM, STRAFE, BEHIND }
}
