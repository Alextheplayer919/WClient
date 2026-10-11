package com.retrivedmods.wclient.console

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import com.retrivedmods.wclient.BuildConfig
import com.retrivedmods.wclient.application.AppContext
import com.retrivedmods.wclient.service.Services
import com.retrivedmods.wtrace.WPacketStats
import com.retrivedmods.wtrace.WTrace
import com.retrivedmods.wtrace.WTraceDiagnostics
import com.retrivedmods.wtrace.WTraceHooks
import com.retrivedmods.wtrace.WTraceReport
import com.retrivedmods.wtrace.WTraceStreams
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Boots the console and keeps it fed.
 *
 * Three things happen here, all of them before the relay starts:
 *  1. the trace hooks are installed (stdout/stderr tee + the RakNet/Bedrock hooks of the vendored
 *     modules), so nothing the relay does from now on can happen off the record;
 *  2. the device, the network links and the target server are probed and written to the console - the
 *     "no internet" feeling of a broken relay is usually a phone that is on a VPN, a link with a tiny
 *     MTU or a server that does not resolve;
 *  3. a watchdog watches the packet counters while the relay is running and reports the exact kind of
 *     stall ("the game is still sending, the server stopped answering") instead of leaving the player
 *     to guess.
 */
object ConsoleRuntime {

    private const val STALL_AFTER_MS = 8_000L
    private const val STATUS_PERIOD_MS = 15_000L
    private const val NO_TRAFFIC_ALERT_MS = 20_000L

    @Volatile
    private var installed = false

    private var watchdog: Thread? = null

    @Volatile
    var settings: ConsoleSettings = ConsoleSettings()

    fun install(context: Context): ConsoleSettings {
        if (installed) {
            return settings
        }
        installed = true

        // Streams first: everything from here on - including a failure below - is on the record.
        WTraceStreams.install()
        WTraceHooks.install()

        settings = ConsoleSettings.load(context).also { it.applyToTrace() }

        logBootBanner(context)
        logPlatform(context)
        logAndroidNetwork(context)

        startWatchdog()

        thread(name = "WConsoleDiagnostics", isDaemon = true) {
            logBootDiagnostics(context)
        }

        return settings
    }

    fun updateSettings(context: Context, newSettings: ConsoleSettings) {
        settings = newSettings
        ConsoleSettings.save(context, newSettings)
        WTrace.info(
            "console",
            "trace settings: packets=${newSettings.packetTrace} net=${newSettings.netTrace} " +
                "datagrams=${newSettings.datagramTrace} code=${newSettings.codeTrace} " +
                "minLevel=${newSettings.minLevel} buffer=${newSettings.capacity}"
        )
    }

    // ---------------------------------------------------------------- boot info

    private fun logBootBanner(context: Context) {
        val versionName = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"

        WTrace.info(
            "console",
            "WClient console ready - v$versionName (${BuildConfig.VERSION_NAME}), package=${context.packageName}, " +
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
                com.retrivedmods.wtrace.WKind.NET
            )
            link?.linkAddresses?.forEach { address ->
                WTrace.info(
                    "net",
                    "link address ${address.address.hostAddress}/${address.prefixLength} " +
                        "(${if (address.address is java.net.Inet6Address) "IPv6" else "IPv4"})",
                    com.retrivedmods.wtrace.WKind.NET
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
            com.retrivedmods.wclient.model.CaptureModeModel.from(
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
                    if (!Services.isActive) {
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
                    val downAge = lastToClient.takeIf { it > 0L }?.let { now - it }
                    val upAge = lastToServer.takeIf { it > 0L }?.let { now - it }
                    val noPacketsSeenYet = downAge == null && upAge == null

                    when {
                        // Do not treat an unseen direction as an age of Long.MAX_VALUE. A brand-new
                        // relay gets a full 20 seconds before we decide the game never reached it.
                        noPacketsSeenYet -> {
                            if (now - sessionSince >= NO_TRAFFIC_ALERT_MS && !stallReported) {
                                stallReported = true
                                WTrace.error(
                                    "watchdog",
                                    "relay has been running for ${(now - sessionSince) / 1000}s and not a single packet " +
                                        "crossed it: the game client never reached it (wrong port, another app on 19132, " +
                                        "or the game was not restarted after pressing Start). " +
                                        (Services.linkSnapshot() ?: "")
                                )
                            }
                        }
                        downAge != null && upAge != null &&
                            downAge > STALL_AFTER_MS && upAge > STALL_AFTER_MS -> {
                            if (!stallReported) {
                                stallReported = true
                                WTrace.error(
                                    "watchdog",
                                    "LINK STALLED: no packets in either direction for " +
                                        "${upAge / 1000}s (client to server) / ${downAge / 1000}s (server to client). " +
                                        "${Services.linkSnapshot() ?: ""} ${upstreamPingText()}"
                                )
                            }
                        }
                        upAge != null && upAge > STALL_AFTER_MS &&
                            (downAge == null || downAge > STALL_AFTER_MS) -> {
                            if (!stallReported) {
                                stallReported = true
                                val downDescription = downAge?.let { "${it / 1000}s ago" } ?: "never"
                                WTrace.error(
                                    "watchdog",
                                    "LINK STALLED: the game client last sent a packet ${upAge / 1000}s ago, " +
                                        "but the server has sent nothing since $downDescription. " +
                                        "${Services.linkSnapshot() ?: ""} ${upstreamPingText()}"
                                )
                            }
                        }
                        downAge != null && downAge > STALL_AFTER_MS && upAge != null -> {
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
