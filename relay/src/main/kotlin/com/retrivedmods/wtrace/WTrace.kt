package com.retrivedmods.wtrace

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Severity of a [WEntry].
 *
 * The values are also the wire format of the Java-side trace hooks (`RakTrace` / `BedrockTrace`),
 * which live in Java 8 modules and therefore cannot reference this enum directly: they pass the
 * ordinal through [fromOrdinal].
 */
enum class WLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR;

    companion object {
        fun fromOrdinal(value: Int): WLevel = entries.getOrElse(value) { TRACE }
    }
}

/**
 * Which layer produced an entry. The console UI filters on this, and it is what makes the difference
 * between "the relay never sent the packet" and "RakNet never put it on the wire" readable.
 */
enum class WKind {
    /** Application / module / UI code (android.util.Log, println, module messages). */
    CODE,

    /** A decoded Bedrock packet crossing the relay in either direction. */
    PACKET,

    /** RakNet transport: datagrams, splits, acks/nacks, connect handshake, MTU. */
    NET,

    /** Session, listener and connection-manager events that are not per-packet. */
    RELAY
}

/**
 * One console line.
 *
 * [count] > 1 means an identical line repeated and the console collapsed it into this entry
 * (`message  x12`). That keeps a movement-packet flood readable without dropping anything: the
 * per-packet detail lives in [WTrace.stats].
 */
data class WEntry(
    val id: Long,
    val wallMs: Long,
    val uptimeMs: Long,
    val level: WLevel,
    val kind: WKind,
    val tag: String,
    val message: String,
    val detail: String?,
    val thread: String,
    var count: Int = 1
) {
    val label: String get() = tag
}

/**
 * The WClient console: one process-wide ring buffer that every layer writes to.
 *
 * Design notes, because this runs on Netty event loops that must not stall:
 *  - writes take one short lock and never allocate more than the entry itself;
 *  - callers must guard expensive string building behind [enabled] / [packetTrace] checks
 *    (see the Java hooks);
 *  - readers ([snapshot]) copy the list, so the UI can never see a torn buffer;
 *  - [revision] is bumped at most every [REVISION_INTERVAL_MS] so a packet flood cannot turn into a
 *    recomposition flood.
 */
object WTrace {

    /** Master switch. False disables every hook that checks it before building a message. */
    @Volatile
    var enabled: Boolean = true

    /** Entries below this level are dropped. */
    @Volatile
    var minLevel: WLevel = WLevel.TRACE

    /** Emit one line per decoded Bedrock packet (chatty packet types are rate limited). */
    @Volatile
    var packetTrace: Boolean = true

    /** Emit RakNet session events: handshake, MTU, splits, acks, resends, disconnects. */
    @Volatile
    var netTrace: Boolean = true

    /** Emit every single datagram. Very noisy (dozens per second); off by default. */
    @Volatile
    var datagramTrace: Boolean = false

    /** Mirror android.util.Log / println output into the console. */
    @Volatile
    var codeTrace: Boolean = true

    /** Console lines kept in memory. */
    @Volatile
    var capacity: Int = 20_000

    /** Longest detail block (stack trace) kept per entry. */
    @Volatile
    var detailLimit: Int = 4_000

    /** Per-packet-type emission is limited to this many lines per [PACKET_BURST_WINDOW_MS]. */
    @Volatile
    var packetLinesPerSecond: Int = 30

    private const val REVISION_INTERVAL_MS = 100L
    private const val COLLAPSE_WINDOW_MS = 1_500L
    private const val PACKET_BURST_WINDOW_MS = 1_000L
    private const val FULL_DETAIL = true

    private val lock = Any()
    private val entries = ArrayDeque<WEntry>()

    private var nextId = 1L
    private var dropped = 0L
    private var lastRevision = 0L

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    private val startNanos = System.nanoTime()
    private val startWallMs = System.currentTimeMillis()

    /** Per-packet-type counters, shared with the console's packet panel. */
    val stats = WPacketStats()

    /** Set while a burst summary line is pending, so a flood leaves one line, not thousands. */
    private val packetThrottle = HashMap<String, Long>()

    fun uptimeMs(): Long = (System.nanoTime() - startNanos) / 1_000_000L

    fun startWallClockMs(): Long = startWallMs

    fun nowWallMs(): Long = System.currentTimeMillis()

    fun bufferSize(): Int = synchronized(lock) { entries.size }

    fun droppedCount(): Long = synchronized(lock) { dropped }

    /**
     * True when an entry at [level] would be kept. Callers use it to skip building messages that
     * would only be dropped.
     */
    fun accepts(level: WLevel): Boolean = enabled && level.ordinal >= minLevel.ordinal

    fun accepts(level: WLevel, kind: WKind): Boolean {
        if (!accepts(level)) {
            return false
        }
        return when (kind) {
            WKind.PACKET -> packetTrace
            WKind.NET -> netTrace
            WKind.CODE -> codeTrace
            WKind.RELAY -> true
        }
    }

    fun log(
        level: WLevel,
        kind: WKind,
        tag: String,
        message: String,
        detail: String? = null
    ) {
        if (!accepts(level, kind)) {
            return
        }
        val trimmedDetail = detail?.let { if (it.length > detailLimit) it.substring(0, detailLimit) + "\n… truncated" else it }
        val now = System.currentTimeMillis()
        val threadName = Thread.currentThread().name

        val bump: Boolean
        synchronized(lock) {
            val last = entries.lastOrNull()
            if (trimmedDetail == null && last != null &&
                last.detail == null &&
                last.message == message &&
                last.tag == tag &&
                last.level == level &&
                last.kind == kind &&
                now - last.wallMs <= COLLAPSE_WINDOW_MS
            ) {
                last.count++
                bump = true
            } else {
                entries.addLast(
                    WEntry(
                        id = nextId++,
                        wallMs = now,
                        uptimeMs = uptimeMs(),
                        level = level,
                        kind = kind,
                        tag = tag,
                        message = message,
                        detail = trimmedDetail,
                        thread = threadName
                    )
                )
                val max = capacity.coerceAtLeast(200)
                while (entries.size > max) {
                    entries.removeFirst()
                    dropped++
                }
                bump = true
            }
        }
        if (bump) {
            bumpRevision(now)
        }
    }

    fun log(level: WLevel, tag: String, message: String, detail: String? = null) =
        log(level, WKind.CODE, tag, message, detail)

    fun trace(tag: String, message: String, kind: WKind = WKind.RELAY, detail: String? = null) =
        log(WLevel.TRACE, kind, tag, message, detail)

    fun debug(tag: String, message: String, kind: WKind = WKind.RELAY, detail: String? = null) =
        log(WLevel.DEBUG, kind, tag, message, detail)

    fun info(tag: String, message: String, kind: WKind = WKind.RELAY, detail: String? = null) =
        log(WLevel.INFO, kind, tag, message, detail)

    fun warn(tag: String, message: String, kind: WKind = WKind.RELAY, detail: String? = null) =
        log(WLevel.WARN, kind, tag, message, detail)

    fun error(tag: String, message: String, detail: String? = null, kind: WKind = WKind.RELAY) =
        log(WLevel.ERROR, kind, tag, message, detail)

    fun error(tag: String, message: String, throwable: Throwable?) =
        log(WLevel.ERROR, WKind.CODE, tag, message, throwable?.stackTraceToString())

    /** Network-layer line (RakNet, sockets, connection manager). */
    fun net(tag: String, message: String, level: WLevel = WLevel.DEBUG) =
        log(level, WKind.NET, tag, message)

    /**
     * One decoded Bedrock packet.
     *
     * [chatty] marks packets that flow constantly (movement, entity data, chunk data). Those still
     * update [stats] and a "last seen" timestamp, but only produce a limited number of console lines
     * per second each, so a 30 player server does not drown the console.
     */
    fun packet(
        direction: String,
        name: String,
        sizeBytes: Int,
        detail: String? = null,
        chatty: Boolean = false,
        level: WLevel = WLevel.DEBUG,
        packetId: Int? = null
    ) {
        val key = "$direction|$name"
        stats.record(direction, name, sizeBytes)
        if (!enabled || !packetTrace || !accepts(level)) {
            return
        }

        val now = System.currentTimeMillis()
        if (chatty) {
            val lastLine = synchronized(lock) { packetThrottle[key] }
            if (lastLine != null && now - lastLine < PACKET_BURST_WINDOW_MS) {
                return
            }
            synchronized(lock) { packetThrottle[key] = now }
        }

        val suffix = buildString {
            packetId?.let { append(" id=").append(it) }
            if (sizeBytes >= 0) {
                append(" (${sizeBytes}B)")
            }
        }
        log(level, WKind.PACKET, direction, "$name$suffix", detail)
    }

    /** True when a per-datagram line should be built. */
    fun verboseDatagrams(): Boolean = enabled && netTrace && datagramTrace

    fun clear() {
        synchronized(lock) {
            entries.clear()
            dropped = 0
            packetThrottle.clear()
        }
        stats.clear()
        bumpRevision(System.currentTimeMillis())
    }

    fun snapshot(): List<WEntry> = synchronized(lock) { entries.toList() }

    /** Last [count] entries, oldest first. */
    fun tail(count: Int): List<WEntry> = synchronized(lock) {
        if (entries.size <= count) entries.toList() else entries.toList().takeLast(count)
    }

    /** Most recent [kind] entry, used by the watchdog and the status bar. */
    fun lastOf(kind: WKind): WEntry? = synchronized(lock) {
        entries.lastOrNull { it.kind == kind }
    }

    fun lastMessageOf(kind: WKind): String? = lastOf(kind)?.message

    /** Newest entry timestamp (wall clock), or null when nothing was logged yet. */
    fun lastTimestampMs(): Long? = synchronized(lock) { entries.lastOrNull()?.wallMs }

    private fun bumpRevision(now: Long) {
        if (now - lastRevision >= REVISION_INTERVAL_MS) {
            lastRevision = now
            _revision.value = nextId
        }
    }

    /** Force a revision bump, for the last line of a burst. */
    fun flushRevision() {
        lastRevision = 0L
        bumpRevision(System.currentTimeMillis())
    }

    /** Logs a Throwable with its stack trace, collapsing repeated frames. */
    fun throwable(tag: String, message: String, throwable: Throwable) {
        log(WLevel.ERROR, WKind.CODE, tag, "$message: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable.stackTraceToString())
    }

    @Suppress("unused")
    fun detailOf(entry: WEntry): String = if (FULL_DETAIL) entry.detail ?: "" else ""
}
