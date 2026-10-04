package com.retrivedmods.wclient.game.utils.combat

import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

/**
 * Closed-loop feedback for the aim system.
 *
 * Every attack we send is remembered; when the server answers with a HURT event (or knockback
 * motion) for that entity within [HIT_WINDOW_MS] it counts as a hit and the attack->hurt delay
 * becomes a passive latency sample. Attacks that never get an answer count as misses.
 *
 * The hit ratio drives [predictionOffsetTicks], a small correction added to the latency-derived
 * lookahead: it hill-climbs between [MIN_OFFSET] and [MAX_OFFSET], keeping whichever direction
 * improved the hit rate.
 */
class HitTracker(private val latency: LatencyTracker) {

    companion object {
        private const val HIT_WINDOW_MS = 600L
        private const val WINDOW = 20
        private const val MIN_OFFSET = -2
        private const val MAX_OFFSET = 3
    }

    private class PendingAttack(val entityId: Long, val sentAt: Long)

    private val pending = ArrayDeque<PendingAttack>()
    private val recentResults = ArrayDeque<Boolean>()

    @Volatile
    var hitRate = 1f
        private set

    @Volatile
    var predictionOffsetTicks = 0
        private set

    private var lastRateAtAdjust = -1f
    private var lastDirection = 1
    private var resultsSinceAdjust = 0

    @Synchronized
    fun onAttack(entityId: Long, now: Long = System.currentTimeMillis()) {
        expire(now)
        // One pending entry per entity per swing burst - multiple packets per swing are one attempt.
        if (pending.any { it.entityId == entityId && now - it.sentAt < 60 }) return
        pending.addLast(PendingAttack(entityId, now))
    }

    @Synchronized
    fun onPacket(packet: BedrockPacket) {
        val now = System.currentTimeMillis()
        val entityId = when (packet) {
            is EntityEventPacket -> if (packet.type == EntityEventType.HURT) packet.runtimeEntityId else return
            is SetEntityMotionPacket -> packet.runtimeEntityId
            else -> return
        }
        val attack = pending.firstOrNull { it.entityId == entityId } ?: return
        pending.remove(attack)
        latency.addSample(now - attack.sentAt)
        record(true)
        expire(now)
    }

    private fun expire(now: Long) {
        while (pending.isNotEmpty() && now - pending.first().sentAt > HIT_WINDOW_MS) {
            pending.removeFirst()
            record(false)
        }
    }

    private fun record(hit: Boolean) {
        recentResults.addLast(hit)
        while (recentResults.size > WINDOW) recentResults.removeFirst()
        hitRate = recentResults.count { it }.toFloat() / recentResults.size
        resultsSinceAdjust++
        if (resultsSinceAdjust >= WINDOW / 2 && recentResults.size >= WINDOW / 2) {
            adjust()
            resultsSinceAdjust = 0
        }
    }

    /** Simple hill-climb: keep moving the offset in the direction that improved the hit rate. */
    private fun adjust() {
        if (hitRate >= 0.85f) {
            lastRateAtAdjust = hitRate
            return
        }
        if (lastRateAtAdjust >= 0f && hitRate < lastRateAtAdjust) lastDirection = -lastDirection
        lastRateAtAdjust = hitRate

        val next = predictionOffsetTicks + lastDirection
        predictionOffsetTicks = when {
            next > MAX_OFFSET -> { lastDirection = -1; MAX_OFFSET - 1 }
            next < MIN_OFFSET -> { lastDirection = 1; MIN_OFFSET + 1 }
            else -> next
        }
    }

    @Synchronized
    fun reset() {
        pending.clear()
        recentResults.clear()
        hitRate = 1f
        predictionOffsetTicks = 0
        lastRateAtAdjust = -1f
        lastDirection = 1
        resultsSinceAdjust = 0
    }
}
