package com.retrivedmods.neon.ui.console

import androidx.compose.ui.graphics.Color
import com.retrivedmods.neon.trace.WKind
import com.retrivedmods.neon.trace.WLevel
import com.retrivedmods.neon.trace.WTraceReport

/** Colors and labels shared by the console surfaces (startup screen, sidebar page, overlay). */

internal object ConsoleColors {
    val Background = Color(0xFF07070A)
    val Panel = Color(0xFF0E0E13)
    val RowAlt = Color(0x14FFFFFF)
    val Border = Color(0xFF26232E)

    val Trace = Color(0xFF6B7280)
    val Debug = Color(0xFF9AA3B2)
    val Info = Color(0xFFE2E8F0)
    val Warn = Color(0xFFFBBF24)
    val Error = Color(0xFFFF6B6B)

    val Packet = Color(0xFF67E8F9)
    val Net = Color(0xFFA78BFA)
    val Relay = Color(0xFF86EFAC)
    val Code = Color(0xFFE2E8F0)

    val Up = Color(0xFF67E8F9)
    val Down = Color(0xFF86EFAC)
    val Muted = Color(0xFF6B7280)
}

internal fun WLevel.color(): Color = when (this) {
    WLevel.TRACE -> ConsoleColors.Trace
    WLevel.DEBUG -> ConsoleColors.Debug
    WLevel.INFO -> ConsoleColors.Info
    WLevel.WARN -> ConsoleColors.Warn
    WLevel.ERROR -> ConsoleColors.Error
}

internal fun WLevel.shortLabel(): String = when (this) {
    WLevel.TRACE -> "TRC"
    WLevel.DEBUG -> "DBG"
    WLevel.INFO -> "INF"
    WLevel.WARN -> "WRN"
    WLevel.ERROR -> "ERR"
}

internal fun WKind.label(): String = when (this) {
    WKind.CODE -> "code"
    WKind.PACKET -> "packet"
    WKind.NET -> "net"
    WKind.RELAY -> "relay"
}

internal fun WKind.color(): Color = when (this) {
    WKind.CODE -> ConsoleColors.Code
    WKind.PACKET -> ConsoleColors.Packet
    WKind.NET -> ConsoleColors.Net
    WKind.RELAY -> ConsoleColors.Relay
}

/** `12.345` style uptime, right aligned by the caller. */
internal fun uptimeLabel(millis: Long): String = WTraceReport.formatDuration(millis)

internal fun bytesLabel(bytes: Long): String = WTraceReport.formatBytes(bytes)

internal fun ageLabel(now: Long, then: Long): String =
    if (then <= 0L) "never" else WTraceReport.formatDuration(now - then) + " ago"
