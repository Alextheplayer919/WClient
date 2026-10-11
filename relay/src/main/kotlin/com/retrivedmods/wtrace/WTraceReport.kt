package com.retrivedmods.wtrace

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders the console buffer into a plain text report that can be pulled off the device, pasted into
 * a chat or compared between two runs.
 */
object WTraceReport {

    private val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun suggestedFileName(): String = "wclient-console_" + fileStamp.format(Date()) + ".txt"

    fun build(
        entries: List<WEntry> = WTrace.snapshot(),
        stats: List<WPacketStat> = WTrace.stats.snapshot(),
        header: String? = null,
        filterDescription: String? = null
    ): String = buildString {
        appendLine(header ?: WTraceDiagnostics.headerLine())
        appendLine("generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("uptime: ${formatDuration(WTrace.uptimeMs())}")
        appendLine("entries: ${entries.size} (buffer ${WTrace.bufferSize()}, dropped ${WTrace.droppedCount()})")
        appendLine(
            "settings: minLevel=${WTrace.minLevel} packetTrace=${WTrace.packetTrace} " +
                "netTrace=${WTrace.netTrace} datagramTrace=${WTrace.datagramTrace} codeTrace=${WTrace.codeTrace} " +
                "capacity=${WTrace.capacity}"
        )
        if (filterDescription != null) {
            appendLine("filter: $filterDescription")
        }
        appendLine("packets: ${WPacketStats.TO_SERVER}=${WTrace.stats.totalToServer()} " +
            "(${formatBytes(WTrace.stats.totalBytesToServer())}), " +
            "${WPacketStats.TO_CLIENT}=${WTrace.stats.totalToClient()} " +
            "(${formatBytes(WTrace.stats.totalBytesToClient())})")
        val lastPacketAt = WTrace.stats.lastSeenMs()
        if (lastPacketAt > 0L) {
            appendLine("last packet: ${formatDuration(WTrace.nowWallMs() - lastPacketAt)} ago")
        }
        appendLine()

        if (stats.isNotEmpty()) {
            appendLine("=== packet types (count, bytes, last seen age) ===")
            val now = WTrace.nowWallMs()
            for (stat in stats) {
                appendLine(
                    pad(stat.direction, 4) + " " +
                        pad(stat.name, 46) + " " +
                        pad(stat.count.toString(), 9) + " " +
                        pad(formatBytes(stat.bytes), 11) + " " +
                        "avg ${pad(formatBytes(stat.averageBytes.toLong()), 9)} " +
                        "last ${formatDuration(now - stat.lastSeenMs)} ago"
                )
            }
            appendLine()
        }

        appendLine("=== console ===")
        for (entry in entries) {
            appendLine(format(entry))
        }
    }

    fun format(entry: WEntry): String = buildString {
        append('[')
        append(formatDuration(entry.uptimeMs))
        append("] ")
        append(levelChar(entry.level))
        append(' ')
        append(pad(entry.kind.name, 6))
        append(' ')
        append(pad(entry.tag, 18))
        append(' ')
        append(entry.message)
        if (entry.count > 1) {
            append("   x").append(entry.count)
        }
        if (entry.detail != null) {
            append('\n')
            append(entry.detail.lineSequence().joinToString("\n") { "        " + it })
        }
    }

    private fun levelChar(level: WLevel): Char = when (level) {
        WLevel.TRACE -> '·'
        WLevel.DEBUG -> 'D'
        WLevel.INFO -> 'I'
        WLevel.WARN -> 'W'
        WLevel.ERROR -> 'E'
    }

    fun formatDuration(millis: Long): String {
        if (millis < 0) {
            return "-" + formatDuration(-millis)
        }
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millisPart = millis % 1000
        return when {
            hours > 0 -> String.format(Locale.US, "%d:%02d:%02d.%03d", hours, minutes, seconds, millisPart)
            minutes > 0 -> String.format(Locale.US, "%02d:%02d.%03d", minutes, seconds, millisPart)
            else -> String.format(Locale.US, "%02d.%03d", seconds, millisPart)
        }
    }

    fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1fKiB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.2fMiB", bytes / (1024.0 * 1024.0))
    }

    private fun pad(text: String, width: Int): String =
        if (text.length >= width) text else text + " ".repeat(width - text.length)
}
