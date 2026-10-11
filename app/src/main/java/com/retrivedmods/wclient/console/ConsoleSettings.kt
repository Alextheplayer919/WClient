package com.retrivedmods.wclient.console

import android.content.Context
import androidx.core.content.edit
import com.retrivedmods.wtrace.WLevel
import com.retrivedmods.wtrace.WTrace

/**
 * Console switches, persisted in their own `console_settings` preferences and applied to [WTrace] on
 * every change (the trace hooks read the flags live, so no restart is needed).
 */
data class ConsoleSettings(
    /** Show the boot console before the main UI when the app starts. */
    val showOnBoot: Boolean = true,
    /** Leave the boot console automatically once it has finished, so a normal launch still feels normal. */
    val autoContinueOnBoot: Boolean = true,
    /** How long the boot console stays up before continuing by itself. */
    val bootHoldSeconds: Int = 6,
    /** One console line per decoded Bedrock packet (chatty types are rate limited). */
    val packetTrace: Boolean = true,
    /** RakNet session events: handshake, MTU, splits, acks, resends, disconnects. */
    val netTrace: Boolean = true,
    /** Every single datagram. Very noisy, for "what is actually on the wire" investigations. */
    val datagramTrace: Boolean = false,
    /** Mirror android.util.Log and println output into the console. */
    val codeTrace: Boolean = true,
    val minLevel: WLevel = WLevel.TRACE,
    val capacity: Int = 20_000,
    /** Floating console button over the game while the relay runs. */
    val overlayButton: Boolean = true,
    /** Console window itself (the button toggles it). */
    val overlayOpen: Boolean = false
) {

    fun applyToTrace() {
        WTrace.packetTrace = packetTrace
        WTrace.netTrace = netTrace
        WTrace.datagramTrace = datagramTrace
        WTrace.codeTrace = codeTrace
        WTrace.minLevel = minLevel
        WTrace.capacity = capacity.coerceIn(500, 200_000)
        WTrace.enabled = true
    }

    companion object {

        private const val PREFS = "console_settings"

        fun load(context: Context): ConsoleSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return ConsoleSettings(
                showOnBoot = prefs.getBoolean("show_on_boot", true),
                autoContinueOnBoot = prefs.getBoolean("auto_continue", true),
                bootHoldSeconds = prefs.getInt("boot_hold_seconds", 6),
                packetTrace = prefs.getBoolean("packet_trace", true),
                netTrace = prefs.getBoolean("net_trace", true),
                datagramTrace = prefs.getBoolean("datagram_trace", false),
                codeTrace = prefs.getBoolean("code_trace", true),
                minLevel = runCatching {
                    WLevel.valueOf(prefs.getString("min_level", WLevel.TRACE.name) ?: WLevel.TRACE.name)
                }.getOrDefault(WLevel.TRACE),
                capacity = prefs.getInt("capacity", 20_000),
                overlayButton = prefs.getBoolean("overlay_button", true),
                overlayOpen = prefs.getBoolean("overlay_open", false)
            )
        }

        fun save(context: Context, settings: ConsoleSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putBoolean("show_on_boot", settings.showOnBoot)
                putBoolean("auto_continue", settings.autoContinueOnBoot)
                putInt("boot_hold_seconds", settings.bootHoldSeconds)
                putBoolean("packet_trace", settings.packetTrace)
                putBoolean("net_trace", settings.netTrace)
                putBoolean("datagram_trace", settings.datagramTrace)
                putBoolean("code_trace", settings.codeTrace)
                putString("min_level", settings.minLevel.name)
                putInt("capacity", settings.capacity)
                putBoolean("overlay_button", settings.overlayButton)
                putBoolean("overlay_open", settings.overlayOpen)
            }
            settings.applyToTrace()
        }
    }
}
