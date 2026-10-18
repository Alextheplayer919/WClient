package com.retrivedmods.wclient.game.module.combat.rots

import com.retrivedmods.wclient.game.GameSession
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/** Tick-based prediction and target locking. Output is consumed via LocalPlayer.silentRotation.
 * Never sends client-bound packets or changes the camera/position.
 */
class PredictiveRotator {

    class Settings(
        val predTime: Float,
        val predStrength: Float,
        val smoothing: Float,
        val maxSpeed: Float,
        val accel: Float,
        val vertOffset: Float
    )

    private val aim = PredictiveAim()
    private var lockedId = -1L
    private var lockedSinceTick = 0L

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
            aim.release(packet.rotation.y, packet.rotation.x, tick)
            publish()
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
        return true
    }

    private fun publish() {
        pitch = aim.pitch
        yaw = aim.yaw
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
