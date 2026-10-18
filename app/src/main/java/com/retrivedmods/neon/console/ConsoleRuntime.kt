package com.retrivedmods.neon.console

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.Build
import com.retrivedmods.neon.BuildConfig
import com.retrivedmods.neon.application.AppContext
import com.retrivedmods.neon.service.Services
import com.retrivedmods.neon.trace.WPacketStats
import com.retrivedmods.neon.trace.WTrace
import com.retrivedmods.neon.trace.WTraceDiagnostics
import com.retrivedmods.neon.trace.WTraceHooks
import com.retrivedmods.neon.trace.WTraceReport
import com.retrivedmods.neon.trace.WTraceStreams
import com.retrivedmods.neon.overlay.OverlayManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/** Optional diagnostics runtime. Preferences are loaded before capture hooks are enabled.
 * Capture starts only with recording on; the watchdog idles while off. Settings are observable
 * so the Home panel and Console page always share the same recording state.
 */
object ConsoleRuntime {

    private const val STALL_AFTER_MS = 8_000L
    private const val STATUS_PERIOD_MS = 15_000L
    private const val NO_TRAFFIC_ALERT_MS = 20_000L

    @Volatile
    private var installed = false

    private var watchdog: Thread? = null

    private val _settings = MutableStateFlow(ConsoleSettings())
    val settingsFlow = _settings.asStateFlow()
    var settings: ConsoleSettings
        get() = _settings.value
        private set(value) { _settings.value = value }

    fun install(context: Context): ConsoleSettings {
        if (installed) {
            return settings
        }
        installed = true

        // Load the master switch before installing any capture hooks.
        settings = ConsoleSettings.load(context).also { it.applyToTrace() }
        WTraceHooks.install()
        if (settings.recording) startRecording(context.applicationContext)
        return settings
    }

    fun updateSettings(context: Context, newSettings: ConsoleSettings) {
        val wasRecording = settings.recording
        val updated = if (newSettings.recording) newSettings else newSettings.copy(overlayOpen = false)
        settings = updated
        ConsoleSettings.save(context, updated)
        if (!updated.recording) WTraceStreams.discardPending()
        if (!wasRecording && updated.recording) startRecording(context.applicationContext)
        Handler(Looper.getMainLooper()).post { OverlayManager.refreshConsoleOverlays() }
        WTrace.info(
            "console",
            "trace settings: packets=${newSettings.packetTrace} net=${newSettings.netTrace} " +
                "datagrams=${newSettings.datagramTrace} code=${newSettings.codeTrace} " +
                "minLevel=${newSettings.minLevel} buffer=${newSettings.capacity}"
        )
    }

    fun setRecording(context: Context, recording: Boolean) {
        updateSettings(context, settings.copy(recording = recording))
    }

    private fun startRecording(context: Context) {
        WTrace.stats.clear() // Fresh capture counters; keep previous log lines available to export.
        WTraceStreams.install()
        logBootBanner(context)
        logPlatform(context)
        logAndroidNetwork(context)
        startWatchdog()
        thread(name = "WConsoleDiagnostics", isDaemon = true) {
            if (settings.recording) logBootDiagnostics(context)
        }
    }

    // ---------------------------------------------------------------- boot info

    private fun logBootBanner(context: Context) {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

        WTrace.info(
            "console",
            "Neon console ready - v$versionName (${BuildConfig.VERSION_NAME}), package=${context.packageName}, " +
                "pid=${android.os.Process.myPid()}"
        )
        WTrace.info(
            "console",
            "use the Console entry in the sidebar, the floating console button over the game, or " +
                "'save report' to keep this log"
        )
    }

    private fun logPlatform(context: Context) {
        WTrace.info(
            "device",
            "${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE}, board=${Build.BOARD}) - " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}, ${Build.VERSION.INCREMENTAL})"
        )
        WTrace.info(
            "device",
            "abis=${Build.SUPPORTED_ABIS.joinToString(", ")} hardware=${Build.HARDWARE} " +
                "heapMaxMB=${Runtime.getRuntime().maxMemory() / (1024 * 1024)} cores=${Runtime.getRuntime().availableProcessors()}"
        )
        val vpnHint = runCatching {
            val hasVpn = context.getSystemService(ConnectivityManager::class.java)
                ?.allNetworks
                ?.any { network ->
                    context.getSystemService(ConnectivityManager::class.java)
                        ?.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                } ?: false
            hasVpn
        }.getOrDefault(false)
        if (vpnHint) {
            WTrace.warn(
                "net",
                "a VPN is active on this device: it changes the MTU and the public IP of every datagram the " +
                    "relay sends, which is a common cause of failed RakNet handshakes and post-join freezes"
            )
        }
    }

    private fun logAndroidNetwork(context: Context) {
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
                ?: return
            val network = manager.activeNetwork
            if (network == null) {
                WTrace.error("net", "Android reports NO active network - the relay cannot reach anything")
                return
            }
            val capabilities = manager.getNetworkCapabilities(network)
            val link: LinkProperties? = manager.getLinkProperties(network)
            val transports = buildList {
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ethernet")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("bluetooth")
            }
            WTrace.info(
                "net",
                "active network: ${transports.joinToString("+").ifEmpty { "unknown" }}" +
                    (capabilities?.let { ", downstream=${it.linkDownstreamBandwidthKbps / 1000}Mbps" +
                        ", upstream=${it.linkUpstreamBandwidthKbps / 1000}Mbps" } ?: "") +
                    (link?.let { ", interfaceMtu=${it.mtu}, dns=${it.dnsServers.joinToString(",") { dns -> dns.hostAddress ?: "?" }}" } ?: ""),
                com.retrivedmods.neon.trace.WKind.NET
            )
            link?.linkAddresses?.forEach { address ->
                WTrace.info(
                    "net",
                    "link address ${address.address.hostAddress}/${address.prefixLength} " +
                        "(${if (address.address is java.net.Inet6Address) "IPv6" else "IPv4"})",
                    com.retrivedmods.neon.trace.WKind.NET
                )
            }
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == false) {
                WTrace.warn(
                    "net",
                    "the active network is NOT validated (no internet reachability confirmed by Android); " +
                        "a captive portal or a half-broken Wi-Fi link looks exactly like this"
                )
            }
        }.onFailure {
            WTrace.warn("net", "could not read the Android network state: ${it.message}")
        }
    }

    private fun logBootDiagnostics(context: Context) {
        WTrace.info("boot", "collecting network diagnostics...")
        WTrace.info("console", "console reports are written to ${ConsoleReport.reportDirectory(context).absolutePath}")
        WTraceDiagnostics.logInterfaces()
        WTraceDiagnostics.checkLocalPort(19132)

        val target = runCatching {
            com.retrivedmods.neon.model.CaptureModeModel.from(
                context.getSharedPreferences("game_settings", Context.MODE_PRIVATE)
            )
        }.getOrNull()

        if (target != null) {
            WTrace.info("boot", "configured target: ${target.serverHostName}:${target.serverPort}")
            WTraceDiagnostics.resolve(target.serverHostName, target.serverPort)
            WTraceDiagnostics.reachability(target.serverHostName, target.serverPort)
        } else {
            WTrace.warn("boot", "no configured server yet (Settings/Server page); DNS probe skipped")
        }
        WTrace.info("boot", "diagnostics done")
    }

    // ---------------------------------------------------------------- watchdog

    /**
     * Watches the packet counters while the relay runs.
     *
     * The interesting failures are asymmetric, and only counters show them: when the game keeps
     * sending `PlayerAuthInputPacket` but nothing comes back, the relay's upstream link is stalled (the
     * "everyone freezes but I still have internet" symptom). When neither direction moves, the link is
     * dead. When only the game link is quiet, the player is simply standing still.
     */
    private fun startWatchdog() {
        if (watchdog != null) {
            return
        }
        watchdog = thread(name = "WConsoleWatchdog", isDaemon = true) {
            var stallReported = false
            var lastStatusAt = 0L
            var sessionSince = 0L

            while (true) {
                try {
                    Thread.sleep(1_000)
                    if (!settings.recording || !Services.isActive) {
                        stallReported = false
                        sessionSince = 0L
                        continue
                    }

                    val now = System.currentTimeMillis()
                    if (sessionSince == 0L) {
                        sessionSince = now
                    }

                    val lastToClient = WTrace.stats.lastToClientMs()
                    val lastToServer = WTrace.stats.lastToServerMs()
                    val downAge = now - maxOf(lastToClient, sessionSince)
                    val upAge = now - maxOf(lastToServer, sessionSince)

                    when {
                        // Nothing at all for a long time while a session exists.
                        lastToClient == 0L && lastToServer == 0L -> {
                            if (!stallReported && now - sessionSince > NO_TRAFFIC_ALERT_MS) {
                                stallReported = true
                                WTrace.error(
                                    "watchdog",
                                    "relay has been running for ${(now - sessionSince) / 1000}s and not a single packet " +
                                        "crossed it: the game never reached the relay. ${Services.linkSnapshot() ?: ""}"
                                )
                            }
                        }
                        downAge > STALL_AFTER_MS && upAge > STALL_AFTER_MS -> {
                            if (!stallReported) {
                                stallReported = true
                                WTrace.error(
                                    "watchdog",
                                    "LINK STALLED: no packets in either direction for " +
                                        "${upAge / 1000}s (up) / ${downAge / 1000}s (down). " +
                                        "${Services.linkSnapshot() ?: ""} ${upstreamPingText()}"
                                )
                            }
                        }
                        downAge > STALL_AFTER_MS -> {
                            if (!stallReported) {
                                stallReported = true
                                WTrace.error(
                                    "watchdog",
                                    "DOWNSTREAM STARVED: the game client is still sending (last packet ${upAge / 1000}s ago) " +
                                        "but the server has sent nothing for ${downAge / 1000}s - this is the freeze " +
                                        "where everyone stands still. ${Services.linkSnapshot() ?: ""} ${upstreamPingText()}"
                                )
                            }
                        }
                        else -> {
                            if (stallReported) {
                                stallReported = false
                                WTrace.info("watchdog", "traffic resumed")
                            }
                        }
                    }

                    if (now - lastStatusAt >= STATUS_PERIOD_MS) {
                        lastStatusAt = now
                        WTrace.debug(
                            "status",
                            "${Services.linkSnapshot() ?: "relay idle"} ${upstreamPingText()} " +
                                "↑${WTrace.stats.totalToServer()} ↓${WTrace.stats.totalToClient()} " +
                                "heap=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)}MB"
                        )
                    }
                } catch (_: InterruptedException) {
                    return@thread
                } catch (t: Throwable) {
                    WTrace.warn("watchdog", "watchdog iteration failed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }
    }

    private fun upstreamPingText(): String {
        val ping = Services.upstreamPingMs()
        return if (ping == null) "upstreamRtt=?" else "upstreamRtt=${ping}ms"
    }

    // ---------------------------------------------------------------- reports

    /** Writes a text report of the current buffer and returns the file, or null on failure. */
    fun saveReport(context: Context): File? = ConsoleReport.save(context)

    fun reportText(context: Context): String = ConsoleReport.buildText(context)

    fun dumpStatsToConsole() {
        val stats = WTrace.stats.snapshot()
        if (stats.isEmpty()) {
            WTrace.info("stats", "no packets recorded yet (start the relay first)")
            return
        }
        val now = System.currentTimeMillis()
        val lines = stats.joinToString("\n") { stat ->
            val dir = if (stat.direction == WPacketStats.TO_CLIENT) "↓ S→C" else "↑ C→S"
            "%s %-42s %8d pkts %10s last %s ago".format(
                dir,
                stat.name,
                stat.count,
                WTraceReport.formatBytes(stat.bytes),
                WTraceReport.formatDuration(now - stat.lastSeenMs)
            )
        }
        WTrace.info(
            "stats",
            "${stats.size} packet type(s): ↑${WTrace.stats.totalToServer()} " +
                "(${WTraceReport.formatBytes(WTrace.stats.totalBytesToServer())}) / " +
                "↓${WTrace.stats.totalToClient()} (${WTraceReport.formatBytes(WTrace.stats.totalBytesToClient())})",
            detail = lines
        )
    }

    fun timestampForFileName(): String =
        SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

    fun packageVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"

    @Suppress("unused")
    private fun hasPermission(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("unused")
    fun appContextOrNull(): Context? = if (AppContext.isInitialized) AppContext.instance else null
}
