package com.retrivedmods.wtrace

/** One row of the console's packet panel. */
data class WPacketStat(
    val direction: String,
    val name: String,
    val count: Long,
    val bytes: Long,
    val lastSeenMs: Long,
    val firstSeenMs: Long,
    val lastSizeBytes: Int
) {
    val averageBytes: Int get() = if (count <= 0L) 0 else (bytes / count).toInt()
}

/**
 * Counters behind the console's packet panel: how many packets of each type crossed the relay, in
 * which direction, how large they were and when one was last seen.
 *
 * This is what turns "the world froze" into "`LevelChunkPacket` server→client: 412 received, last one
 * 8.2 s ago, while `PlayerAuthInputPacket` keeps flowing" - a lost stream and a dead link look
 * completely different here.
 *
 * Directions are named after the traffic, not after the relay's internal session names: [TO_SERVER] is
 * what the player's game client sent and the relay forwarded upstream, [TO_CLIENT] is what came back
 * and was handed to the game.
 */
class WPacketStats {

    private val lock = Any()
    private val counters = LinkedHashMap<String, MutableCounter>()

    private var lastToClientMs = 0L
    private var lastToServerMs = 0L
    private var totalToClient = 0L
    private var totalToServer = 0L
    private var totalBytesToClient = 0L
    private var totalBytesToServer = 0L

    companion object {
        /** Game client -> relay -> upstream server (what this device sends). */
        const val TO_SERVER = "client->server"

        /** Upstream server -> relay -> game client (what this device receives). */
        const val TO_CLIENT = "server->client"
    }

    private class MutableCounter(
        val direction: String,
        val name: String,
        var count: Long = 0,
        var bytes: Long = 0,
        var firstSeenMs: Long = 0,
        var lastSeenMs: Long = 0,
        var lastSizeBytes: Int = 0
    )

    fun record(direction: String, name: String, sizeBytes: Int) {
        val now = System.currentTimeMillis()
        val key = if (direction == TO_CLIENT) "d|$name" else "u|$name"
        synchronized(lock) {
            val counter = counters.getOrPut(key) { MutableCounter(direction, name) }
            counter.count++
            if (sizeBytes > 0) {
                counter.bytes += sizeBytes
            }
            if (counter.firstSeenMs == 0L) {
                counter.firstSeenMs = now
            }
            counter.lastSeenMs = now
            counter.lastSizeBytes = sizeBytes

            if (direction == TO_CLIENT) {
                lastToClientMs = now
                totalToClient++
                if (sizeBytes > 0) totalBytesToClient += sizeBytes
            } else {
                lastToServerMs = now
                totalToServer++
                if (sizeBytes > 0) totalBytesToServer += sizeBytes
            }
        }
    }

    fun clear() = synchronized(lock) {
        counters.clear()
        lastToClientMs = 0
        lastToServerMs = 0
        totalToClient = 0
        totalToServer = 0
        totalBytesToClient = 0
        totalBytesToServer = 0
    }

    fun snapshot(): List<WPacketStat> = synchronized(lock) {
        counters.values.map {
            WPacketStat(
                direction = it.direction,
                name = it.name,
                count = it.count,
                bytes = it.bytes,
                lastSeenMs = it.lastSeenMs,
                firstSeenMs = it.firstSeenMs,
                lastSizeBytes = it.lastSizeBytes
            )
        }.sortedWith(compareByDescending<WPacketStat> { it.count }.thenBy { it.name })
    }

    fun totalToServer(): Long = synchronized(lock) { totalToServer }

    fun totalToClient(): Long = synchronized(lock) { totalToClient }

    fun totalBytesToServer(): Long = synchronized(lock) { totalBytesToServer }

    fun totalBytesToClient(): Long = synchronized(lock) { totalBytesToClient }

    /** Wall clock of the last packet in either direction, 0 when none was seen yet. */
    fun lastSeenMs(): Long = synchronized(lock) { maxOf(lastToClientMs, lastToServerMs) }

    fun lastToServerMs(): Long = synchronized(lock) { lastToServerMs }

    fun lastToClientMs(): Long = synchronized(lock) { lastToClientMs }

    fun hasData(): Boolean = synchronized(lock) { counters.isNotEmpty() }

    /**
     * Packet types whose last arrival is [ageMs] or older, oldest first. That ordering is the point:
     * the first entry is the stream that stopped, the last one is still alive.
     */
    fun staleOlderThan(now: Long, ageMs: Long): List<WPacketStat> =
        snapshot().filter { now - it.lastSeenMs >= ageMs }.sortedBy { it.lastSeenMs }
}
