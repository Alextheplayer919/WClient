package com.retrivedmods.wclient.game.module.motion

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * TestFly - port of the LeHu-style "MotionFly (Testfly port)".
 *
 * Differences from the source it was ported from:
 *  - `EntityTracker.self*`      -> `session.localPlayer` (position fed by PlayerAuthInputPacket).
 *  - `CollisionGuard`           -> not available: WClient keeps no block/chunk data, so there is
 *                                  nothing to clamp motion against. Left out on purpose.
 *  - `MovementCompliance`       -> replaced by a small built-in adaptive governor (see below) that
 *                                  backs horizontal/vertical speed off after a server correction
 *                                  and lets it recover gradually.
 *  - "Shortcut" value           -> WClient already has per-module shortcut buttons
 *                                  (Module.isShortcutDisplayed), so no extra setting is needed.
 */
class TestFlyModule : Module("test_fly", ModuleCategory.Motion) {

    // ── LeHu settings ──────────────────────────────────────────────────────────────────────
    private val hSpeedBPS by floatValue("h_speed_bps", 46.0f, 1.0f..100.0f)
    private val upSpeedBPS by floatValue("up_speed_bps", 19.8f, 1.0f..60.0f)
    private val downSpeedBPS by floatValue("down_speed_bps", 46.0f, 1.0f..60.0f)
    private val glide by floatValue("glide", -0.02f, -0.3f..0.0f)
    private val upHFactor by floatValue("up_h_factor", 0.55f, 0.1f..1.0f)
    private val downHFactor by floatValue("down_h_factor", 0.55f, 0.1f..1.0f)

    // ── Adaptive governor (stand-in for MovementCompliance) ───────────────────────────────
    private val adaptive by boolValue("adaptive", true)
    private val lagbackBackoff by floatValue("lagback_backoff", 0.5f, 0.1f..0.9f)
    private val recoveryPerTick by floatValue("recovery_per_tick", 0.01f, 0.001f..0.1f)

    // ── State ──────────────────────────────────────────────────────────────────────────────
    private var lastPos = Vector3f.ZERO

    /** 0..1 multiplier applied to requested speeds; drops on a lagback, recovers slowly. */
    private var governor = 1f
    private var settleVerticalTicks = 0

    companion object {
        /** Vanilla max horizontal speed per tick squared, matches sqrt(5.99) in the original. */
        private val MAX_HORIZ_PER_TICK = sqrt(5.99).toFloat()
        private const val RUBBERBAND_DISTANCE = 4.5f
        private const val SETTLE_TICKS_AFTER_LAGBACK = 10
    }

    override fun onEnabled() {
        super.onEnabled()
        if (isSessionCreated) {
            lastPos = session.localPlayer.vec3Position
        }
        governor = 1f
        settleVerticalTicks = 0
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return
        val packet = interceptablePacket.packet

        // Server-side correction of our own position = lagback. Feed the governor.
        if (packet is MovePlayerPacket &&
            packet.runtimeEntityId == session.localPlayer.runtimeEntityId &&
            (packet.mode == MovePlayerPacket.Mode.TELEPORT || packet.mode == MovePlayerPacket.Mode.RESPAWN)
        ) {
            onLagback()
            return
        }

        if (packet !is PlayerAuthInputPacket) return

        val player = session.localPlayer
        val selfPos = player.vec3Position

        // ── Anti-rubberband: distance check ───────────────────────────────────────────────
        val dx = selfPos.x - lastPos.x
        val dy = selfPos.y - lastPos.y
        val dz = selfPos.z - lastPos.z
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist > RUBBERBAND_DISTANCE) {
            onLagback()
            lastPos = selfPos
            return
        }

        // ── Read input ────────────────────────────────────────────────────────────────────
        val wantUp = packet.inputData.contains(PlayerAuthInputData.WANT_UP) ||
                packet.inputData.contains(PlayerAuthInputData.JUMPING)
        val wantDown = packet.inputData.contains(PlayerAuthInputData.WANT_DOWN) ||
                packet.inputData.contains(PlayerAuthInputData.SNEAKING)

        val motion = packet.motion
        if (!wantUp && !wantDown && motion.x == 0f && motion.y == 0f && glide == 0f) {
            lastPos = selfPos
            return
        }

        // ── Horizontal speed ──────────────────────────────────────────────────────────────
        var horizSpeed = min(hSpeedBPS / 20f, MAX_HORIZ_PER_TICK)
        if (wantUp) horizSpeed *= upHFactor
        if (wantDown) horizSpeed *= downHFactor

        // ── Vertical speed ────────────────────────────────────────────────────────────────
        var vertSpeed = when {
            wantUp -> upSpeedBPS / 20f
            wantDown -> -downSpeedBPS / 20f + glide
            else -> glide
        }

        // ── Adaptive governor ─────────────────────────────────────────────────────────────
        if (adaptive) {
            horizSpeed = governedSpeed(horizSpeed)
            if (shouldSettleVertical() && vertSpeed > glide) {
                vertSpeed = glide
            }
            vertSpeed = governedVertical(vertSpeed)
            tickGovernor()
        }

        // ── Input rotation ────────────────────────────────────────────────────────────────
        val yawRad = Math.toRadians(packet.rotation.y.toDouble()).toFloat()
        val sinYaw = sin(yawRad)
        val cosYaw = cos(yawRad)

        val strafe = motion.x
        val forward = motion.y

        var motionX = strafe * cosYaw - forward * sinYaw
        var motionZ = forward * cosYaw + strafe * sinYaw

        val len = sqrt(motionX * motionX + motionZ * motionZ)
        if (len > 0.001f) {
            motionX = motionX / len * horizSpeed
            motionZ = motionZ / len * horizSpeed
        } else {
            motionX = 0f
            motionZ = 0f
        }

        // ── Send motion packet ────────────────────────────────────────────────────────────
        val motionPacket = SetEntityMotionPacket().apply {
            runtimeEntityId = player.runtimeEntityId
            this.motion = Vector3f.from(motionX, vertSpeed, motionZ)
        }
        session.clientBound(motionPacket)

        lastPos = selfPos
    }

    // ── Governor helpers ──────────────────────────────────────────────────────────────────

    private fun onLagback() {
        if (!adaptive) return
        governor = (governor * lagbackBackoff).coerceAtLeast(0.1f)
        settleVerticalTicks = SETTLE_TICKS_AFTER_LAGBACK
    }

    private fun governedSpeed(speed: Float) = speed * governor

    /** Vertical gets a milder cut than horizontal so the player does not drop out of the sky. */
    private fun governedVertical(speed: Float) = if (speed > 0f) speed * (0.5f + governor * 0.5f) else speed

    private fun shouldSettleVertical() = settleVerticalTicks > 0

    private fun tickGovernor() {
        if (settleVerticalTicks > 0) settleVerticalTicks--
        if (governor < 1f) governor = min(1f, governor + recoveryPerTick)
    }
}
