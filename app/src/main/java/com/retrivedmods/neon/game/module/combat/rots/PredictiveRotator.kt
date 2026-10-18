package com.retrivedmods.neon.game.module.combat.rots

import com.retrivedmods.neon.game.GameSession
import com.retrivedmods.neon.game.entity.Entity
import com.retrivedmods.neon.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs

/** Tick-based prediction and target locking. The caller writes [pitch]/[yaw] into its input packet.
 * Optional Camera Sync sends a guarded client-bound rotation packet; it does not alter aim math.
 */
class PredictiveRotator {

    class Settings(
        val predTime: Float,
        val predStrength: Float,
        val smoothing: Float,
        val maxSpeed: Float,
        val accel: Float,
        val cameraSync: Boolean,
        val vertOffset: Float
    )

    private val aim = PredictiveAim()
    private var lockedId = -1L
    private var lockedSinceTick = 0L
    private var lastSyncYaw = Float.NaN
    private var lastSyncPitch = Float.NaN

    var pitch = 0f
        private set
    var yaw = 0f
        private set

    /** Whether an outgoing server-bound rotation is requested. */
    val isOverriding: Boolean get() = aim.isOverriding

    fun reset() {
        aim.reset()
        lockedId = -1L
        lockedSinceTick = 0L
        lastSyncYaw = Float.NaN
        lastSyncPitch = Float.NaN
        pitch = 0f
        yaw = 0f
    }

    /**
     * @param targets candidatos ordenados por prioridad (targets[0] = preferido). Se reordena en
     * situ para que el objetivo bloqueado quede en el indice 0.
     * @return true si KillAura debe sobreescribir la rotacion en este tick.
     */
    fun update(
        session: GameSession,
        packet: PlayerAuthInputPacket,
        targets: MutableList<Entity>,
        s: Settings
    ): Boolean {
        val localPlayer = session.localPlayer
        val tick = packet.tick
        aim.config = PredictiveAim.Config(
            predictionTicks = s.predTime,
            predictionStrength = s.predStrength,
            smoothing = s.smoothing,
            maxYawSpeed = s.maxSpeed,
            turnAccel = s.accel
        )

        for (e in targets) aim.observe(e.runtimeEntityId, e.posX, feetY(e), e.posZ, tick)
        aim.retain(targets.map { it.runtimeEntityId })

        if (targets.isEmpty()) {
            lockedId = -1L
            if (!aim.isOverriding) return false
            if (s.cameraSync) {
                // Keep the camera where the last synced rotation left it while releasing.
                aim.release(aim.yaw, aim.pitch, tick)
            } else {
                // Without camera sync, ease back toward the real input rotation.
                aim.release(packet.rotation.y, packet.rotation.x, tick)
            }
            publish()
            syncCamera(session, packet, s)
            // Publish the final release step too, then stop overriding on the next tick.
            return true
        }

        selectTarget(targets, localPlayer, tick)
        val handler = targets[0]

        // Al adquirir objetivo se parte de la rotacion real de la camara (sin salto inicial).
        if (!aim.isOverriding) aim.seed(packet.rotation.y, packet.rotation.x, tick)

        aim.step(
            localPlayer.posX, localPlayer.posY, localPlayer.posZ,
            handler.runtimeEntityId,
            handler.posX, feetY(handler), handler.posZ,
            width(handler), height(handler),
            s.vertOffset,
            tick
        )
        publish()
        syncCamera(session, packet, s)
        return true
    }

    private fun publish() {
        pitch = aim.pitch
        yaw = aim.yaw
    }

    /** Synchronize the visible client rotation when the imported Camera Sync option is enabled. */
    private fun syncCamera(session: GameSession, packet: PlayerAuthInputPacket, s: Settings) {
        if (!s.cameraSync) return
        if (!lastSyncYaw.isNaN()) {
            var dYaw = yaw - lastSyncYaw
            while (dYaw > 180f) dYaw -= 360f
            while (dYaw < -180f) dYaw += 360f
            if (abs(dYaw) < SYNC_EPS && abs(pitch - lastSyncPitch) < SYNC_EPS) return
        }
        lastSyncYaw = yaw
        lastSyncPitch = pitch

        val localPlayer = session.localPlayer
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId = localPlayer.runtimeEntityId
            position = Vector3f.from(
                packet.position.x + localPlayer.motionX.coerceIn(-MAX_SYNC_STEP, MAX_SYNC_STEP),
                packet.position.y,
                packet.position.z + localPlayer.motionZ.coerceIn(-MAX_SYNC_STEP, MAX_SYNC_STEP)
            )
            rotation = Vector3f.from(pitch, yaw, yaw)
            mode = MovePlayerPacket.Mode.NORMAL
            onGround = packet.inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION) && localPlayer.motionY <= 0f
            ridingRuntimeEntityId = 0
            tick = packet.tick
        })
    }

    /**
     * Mantiene el objetivo actual salvo que otro este claramente mas cerca y el actual lleve un
     * rato bloqueado. Evita que la mira salte entre jugadores cuyas distancias son casi iguales.
     */
    private fun applyTargetLock(targets: MutableList<Entity>, lp: Entity, tick: Long) {
        val nearest = targets[0]
        val current = if (lockedId == -1L) null else targets.firstOrNull { it.runtimeEntityId == lockedId }

        val chosen = when {
            current == null -> nearest
            nearest === current -> current
            else -> {
                val dCur = current.distance(lp)
                val dNear = nearest.distance(lp)
                val clearlyCloser = dNear < dCur * LOCK_SWITCH_RATIO && dCur - dNear > LOCK_SWITCH_MIN_GAIN
                if (clearlyCloser && tick - lockedSinceTick >= LOCK_MIN_HOLD_TICKS) nearest else current
            }
        }
        if (chosen !== targets[0]) {
            targets.remove(chosen)
            targets.add(0, chosen)
        }
    }

    /** Reused by every rotation mode so orbit and aim use the same stable target. */
    fun selectTarget(targets: MutableList<Entity>, localPlayer: Entity, tick: Long) {
        if (targets.isEmpty()) {
            lockedId = -1L
            return
        }
        if (tick < lockedSinceTick) lockedId = -1L
        applyTargetLock(targets, localPlayer, tick)
        if (targets[0].runtimeEntityId != lockedId) {
            lockedId = targets[0].runtimeEntityId
            lockedSinceTick = tick
        }
    }

    private companion object {
        const val SYNC_EPS = 0.01f
        const val MAX_SYNC_STEP = 0.6f

        // Cambio de objetivo: solo si el nuevo esta >25% mas cerca y al menos 0.75 bloques mas
        // cerca, y el actual lleva al menos 6 ticks bloqueado.
        const val LOCK_SWITCH_RATIO = 0.75f
        const val LOCK_SWITCH_MIN_GAIN = 0.75f
        const val LOCK_MIN_HOLD_TICKS = 6L
    }
}

/** Bedrock player network Y is eye-level; mob Y is feet-level. */
internal fun feetY(entity: Entity): Float = entity.posY - if (entity is Player) 1.62f else 0f
internal fun width(entity: Entity): Float =
    (entity.metadata[EntityDataTypes.WIDTH] as? Float)?.takeIf { it.isFinite() && it > 0f } ?: 0.6f
internal fun height(entity: Entity): Float =
    (entity.metadata[EntityDataTypes.HEIGHT] as? Float)?.takeIf { it.isFinite() && it > 0f } ?: 1.8f
