package com.retrivedmods.neon.trace

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Boot-time network diagnostics for the console.
 *
 * Everything here answers a question that used to require a second device and a packet capture:
 * which interface does the phone actually have, does the host resolve, how long does it take, can a
 * UDP socket be bound on 19132 at all, and what is the address family of the server. A relay that is
 * never reached and a relay that is reached but then starved look identical in the game client; these
 * lines separate them before the first join attempt.
 */
object WTraceDiagnostics {

    fun logInterfaces() {
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            if (interfaces.isEmpty()) {
                WTrace.warn("net", "no network interfaces reported")
                return
            }
            for (nif in interfaces) {
                val addresses = nif.inetAddresses.toList().filter { !it.isLoopbackAddress }
                if (addresses.isEmpty()) {
                    continue
                }
                val detail = buildString {
                    append("name=").append(nif.name)
                    append(" up=").append(nif.isUp)
                    append(" virtual=").append(nif.isVirtual)
                    runCatching { append(" mtu=").append(nif.mtu) }
                    append('\n')
                    for (address in addresses) {
                        append("  ").append(address.hostAddress)
                        append("  family=").append(if (address is Inet6Address) "IPv6" else "IPv4")
                        runCatching { append(" reachable=").append(address.isReachable(120)) }
                        append('\n')
                    }
                }
                val label = addresses.joinToString(", ") { it.hostAddress ?: "?" }
                WTrace.info("net", "iface ${nif.name}: $label", WKind.NET, detail)
            }
            val active = Interfaces.active()
            WTrace.info("net", "active route candidate: ${active ?: "none"} (network interfaces with a non-loopback address)")
        }.onFailure {
            WTrace.warn("net", "interface enumeration failed: ${it.message}")
        }
    }

    /**
     * Resolves [host] and reports every address plus the time the lookup took. A resolver that stalls
     * for seconds is the difference between a clean RakNet handshake and a first-join timeout.
     */
    fun resolve(host: String, port: Int = 19132) {
        val started = System.nanoTime()
        try {
            val addresses = InetAddress.getAllByName(host)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            val detail = addresses.joinToString("\n") { address ->
                val family = when (address) {
                    is Inet4Address -> "IPv4"
                    is Inet6Address -> "IPv6"
                    else -> "?"
                }
                "$family ${address.hostAddress}"
            }
            WTrace.info(
                "dns",
                "resolved $host -> ${addresses.size} address(es) in ${elapsed}ms " +
                    "[${addresses.joinToString(", ") { it.hostAddress }}]",
                WKind.NET,
                detail
            )
        } catch (e: Throwable) {
            val elapsed = (System.nanoTime() - started) / 1_000_000
            WTrace.error("dns", "failed to resolve $host after ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Concurrent ping-style reachability probes; failures are informational, not fatal. */
    fun reachability(host: String, port: Int, timeoutMs: Int = 1500) {
        runCatching {
            val address = InetAddress.getByName(host)
            val socket = DatagramSocket()
            socket.soTimeout = timeoutMs
            try {
                socket.connect(InetSocketAddress(address, port))
                WTrace.info(
                    "net",
                    "udp probe to $host:$port armed (local ${socket.localAddress?.hostAddress}:${socket.localPort})",
                    WKind.NET
                )
            } finally {
                runCatching { socket.close() }
            }
        }.onFailure {
            WTrace.warn("net", "udp probe to $host:$port failed: ${it.message}", WKind.NET)
        }
    }

    /** Can a UDP socket be bound to [port] on this device right now? */
    fun checkLocalPort(port: Int = 19132) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket(null).apply {
                reuseAddress = false
                bind(InetSocketAddress(port))
            }
            WTrace.info("net", "local UDP port $port is free (relay can bind it)", WKind.NET)
        } catch (e: Throwable) {
            WTrace.warn(
                "net",
                "local UDP port $port is NOT bindable: ${e.javaClass.simpleName}: ${e.message} " +
                    "(another app - often a second client or a VPN - is holding it)",
                WKind.NET
            )
        } finally {
            runCatching { socket?.close() }
        }
    }

    /** Summarises the device/build for the report header. */
    fun headerLine(): String = buildString {
        append("Neon console report")
        append(" | os=").append(System.getProperty("os.name"))
        append(" | arch=").append(System.getProperty("os.arch"))
        append(" | java=").append(System.getProperty("java.version"))
        append(" | cores=").append(Runtime.getRuntime().availableProcessors())
        append(" | heapMaxMB=").append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
    }

    private object Interfaces {
        fun active(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .mapNotNull { nif ->
                    nif.inetAddresses.toList().firstOrNull { it is Inet4Address }?.let { "$nif=${it.hostAddress}" }
                }
                .firstOrNull()
        }.getOrNull()
    }
}
