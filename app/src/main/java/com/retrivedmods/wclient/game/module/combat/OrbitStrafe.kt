package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.GameSession
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.utils.math.RotationUtils
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket

/**
 * Asks [orbit] for the next spin position around [target] and, if it produced one, commands it to
 * the game client so the player travels around the target instead of past them.
 *
 * Shared by Killaura and WAura so both spin exactly the same way. The orbit itself decides the
 * pace from the player's own movement (see [RotationUtils.Orbit]); this only ships the packet.
 *
 * @return true when a position was commanded.
 */
internal fun GameSession.orbitAround(
    player: LocalPlayer,
    target: Entity,
    radius: Float,
    tick: Long,
    direction: RotationUtils.SpinDirection,
    orbit: RotationUtils.Orbit
): Boolean {
    val next = orbit.next(player, target, radius, tick, direction) ?: return false

    clientBound(
        MovePlayerPacket().apply {
            runtimeEntityId = player.runtimeEntityId
            position = next
            rotation = player.vec3Rotation
            mode = MovePlayerPacket.Mode.NORMAL
            // Spinning happens mid air: never claim ground contact.
            onGround = false
            // Qualify the packet field - the `tick` parameter would otherwise hide it.
            this.tick = player.tickExists
        }
    )
    return true
}
