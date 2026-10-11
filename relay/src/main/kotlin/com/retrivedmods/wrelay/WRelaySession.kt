package com.retrivedmods.wrelay

import com.retrivedmods.wrelay.listener.WRelayPacketListener
import com.retrivedmods.wtrace.WPacketStats
import com.retrivedmods.wtrace.WTrace
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.util.internal.PlatformDependent
import org.cloudburstmc.netty.channel.raknet.RakDisconnectReason
import org.cloudburstmc.netty.handler.codec.raknet.common.RakSessionCodec
import org.cloudburstmc.protocol.bedrock.BedrockClientSession
import org.cloudburstmc.protocol.bedrock.BedrockPeer
import org.cloudburstmc.protocol.bedrock.BedrockServerSession
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacketHandler
import org.cloudburstmc.protocol.bedrock.packet.UnknownPacket
import java.util.*


class WRelaySession internal constructor(
    peer: BedrockPeer,
    subClientId: Int,
    val wRelay: WRelay
) {

    @Volatile
    private var lastUpstreamClose: String? = null

    @Volatile
    private var lastGameClose: String? = null

    @Volatile
    var handshakeCompletedWithServer: Boolean = false
        internal set

    @Volatile
    private var upstreamContextHandoffNeedsRetry = false

    @Volatile
    private var upstreamContextHandoffRetried = false

    private val upstreamLock = Any()

    val server = ServerSession(peer, subClientId)

    @Volatile
    var client: ClientSession? = null
        internal set(value) {
            if (value == null) {
                synchronized(upstreamLock) {
                    field = null
                }
                return
            }

            // The upstream channel can be visible before its packet pipeline has finished installing
            // the Bedrock codec. Try every piece independently: one unavailable registry must not
            // prevent the queue from being drained or the remaining context from being copied.
            upstreamContextHandoffNeedsRetry = carryServerContextToUpstream(value)
            upstreamContextHandoffRetried = false
            synchronized(upstreamLock) {
                field = value
                drainQueue(value)
            }
        }

    val listeners: MutableList<WRelayPacketListener> = ArrayList()

    private val packetQueue: Queue<Pair<BedrockPacket, Boolean>> = PlatformDependent.newMpscQueue()
    private val maxQueueSize = 1000

    private fun carryServerContextToUpstream(upstream: ClientSession): Boolean {
        var failed = false

        fun copy(what: String, action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failed = true
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                val message = "could not carry $what to the upstream session ($reason); " +
                    "the handoff will be retried when the server sends its network settings."
                println(message)
                WTrace.error("relay", message, error.stackTraceToString())
            }
        }

        copy("codec") { upstream.codec = server.codec }
        copy("blockDefinitions") {
            upstream.peer.codecHelper.blockDefinitions = server.peer.codecHelper.blockDefinitions
        }
        copy("itemDefinitions") {
            upstream.peer.codecHelper.itemDefinitions = server.peer.codecHelper.itemDefinitions
        }
        copy("cameraPresetDefinitions") {
            upstream.peer.codecHelper.cameraPresetDefinitions = server.peer.codecHelper.cameraPresetDefinitions
        }
        copy("encodingSettings") {
            upstream.peer.codecHelper.encodingSettings = server.peer.codecHelper.encodingSettings
        }

        return failed
    }

    /** Retry a failed codec/definition handoff once the upstream Bedrock pipeline is fully installed. */
    fun retryUpstreamContextHandoffAfterNetworkSettings() {
        if (!upstreamContextHandoffNeedsRetry || upstreamContextHandoffRetried) {
            return
        }
        val upstream = client ?: return

        upstreamContextHandoffRetried = true
        WTrace.info("relay", "server sent NetworkSettingsPacket; retrying the upstream codec/definition handoff")
        upstreamContextHandoffNeedsRetry = carryServerContextToUpstream(upstream)
    }

    /** Queue flushing stays independent of the codec handoff so a copy failure can never skip it. */
    private fun drainQueue(upstream: ClientSession) {
        var flushedCount = 0
        while (true) {
            val queued = packetQueue.poll() ?: break
            try {
                if (queued.second) {
                    upstream.sendPacketImmediately(queued.first)
                } else {
                    upstream.sendPacket(queued.first)
                }
                flushedCount++
            } catch (error: Throwable) {
                val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
                WTrace.error(
                    "relay",
                    "failed to flush queued ${queued.first.javaClass.simpleName} to the server ($reason)",
                    error.stackTraceToString()
                )
            }
        }

        if (flushedCount > 0) {
            println("Processed $flushedCount queued packets")
        }
        WTrace.info("relay", "upstream link up: flushed $flushedCount queued packet(s) to the server")
    }

    /**
     * Round trip time (ms) of the upstream RakNet link as measured by the transport itself
     * (ConnectedPing is sent every 2 seconds by [RakSessionCodec]).
     *
     * Null until the first pong arrives. This is the fallback RTT source for application protocols
     * that no longer ship an in-band probe: TickSync was deregistered in protocol 685, so writing it
     * on a modern server would fail inside the encoder.
     */
    val upstreamPingMs: Long?
        get() = runCatching {
            rakCodec(client?.peer)?.ping?.takeIf { it > 0L }
        }.getOrNull()

    /** Search through proxy wrappers to the channel that owns the RakNet transport pipeline. */
    internal fun rakCodec(peer: BedrockPeer?): RakSessionCodec? =
        peerChannels(peer).firstNotNullOfOrNull { channel ->
            runCatching { channel.pipeline().get(RakSessionCodec::class.java) }.getOrNull()
        }

    /**
     * Return the channel that owns the RakNet codec. Proxy channels keep their pipeline on a parent,
     * so inspecting only [BedrockPeer.channel] misses both the session codec and the real transport.
     */
    internal fun rakChannel(peer: BedrockPeer?): Channel? {
        val channels = peerChannels(peer)
        return channels.firstOrNull { channel ->
            runCatching { channel.pipeline().get(RakSessionCodec::class.java) != null }.getOrDefault(false)
        } ?: channels.firstOrNull()
    }

    private fun peerChannels(peer: BedrockPeer?): List<Channel> {
        val channels = ArrayList<Channel>(4)
        var channel: Channel? = peer?.channel
        var depth = 0
        while (depth < 4) {
            val current = channel ?: break
            channels.add(current)
            channel = runCatching { current.parent() }.getOrNull()
            depth++
        }
        return channels
    }

    private fun linkIsOpen(peer: BedrockPeer?): Boolean {
        val peerChannel = peer?.channel ?: return false
        val transportChannel = rakChannel(peer) ?: return false
        return peerChannel.isActive && transportChannel.isActive
    }

    /**
     * MTU and split budget of the RakNet link on [peer], for the log.
     *
     * Both links cap a single message at a fixed number of split parts, so how much payload a link can carry
     * is a function of its negotiated MTU. A batch over that limit is dropped by the transport (never sent half
     * way), because a Bedrock client acknowledges every part of an oversized message and then throws the message
     * away, which stalls that ordering channel for good - the post-join freeze. Logging the real numbers turns
     * "the world never loads" into "the link can only carry N bytes per message".
     */
    fun describeLink(name: String, peer: BedrockPeer?): String {
        val codec = rakCodec(peer) ?: return "$name: RakNet session not ready"
        return runCatching {
            "$name: mtu=${codec.mtu} maxMessageBytes=${codec.maxMessageSize}"
        }.getOrElse { "$name: RakNet session not measurable (${it.javaClass.simpleName})" }
    }

    /**
     * One-line state of both links, for the console's periodic status line and the stall watchdog.
     *
     * "Upstream connected? / game client connected? / MTU of each / RTT / packets each way / how long
     * since the last packet" is exactly the information needed to tell a dead upstream link from a
     * stalled game link.
     */
    fun linkSnapshot(): String {
        val upstream = runCatching {
            val session = client ?: return@runCatching "upstream=connecting"
            if (!linkIsOpen(session.peer)) {
                "upstream=CLOSED(lastClose=${lastUpstreamClose ?: "UNKNOWN"})"
            } else {
                val mtu = rakCodec(session.peer)?.mtu
                "upstream=connected(mtu=$mtu, rtt=${upstreamPingMs ?: "?"}ms)"
            }
        }.getOrElse { "upstream=unknown(${it.javaClass.simpleName})" }

        val game = runCatching {
            if (!linkIsOpen(server.peer)) {
                "client=CLOSED(lastClose=${lastGameClose ?: "UNKNOWN"})"
            } else {
                val mtu = rakCodec(server.peer)?.mtu
                "client=connected(mtu=$mtu)"
            }
        }.getOrElse { "client=unknown(${it.javaClass.simpleName})" }

        val now = System.currentTimeMillis()
        val lastToClient = stats.lastToClientMs().takeIf { it > 0L }?.let { "${now - it}ms ago" } ?: "never"
        val lastToServer = stats.lastToServerMs().takeIf { it > 0L }?.let { "${now - it}ms ago" } ?: "never"

        return "$upstream $game linkPackets=${stats.totalToServer()}↑/${stats.totalToClient()}↓ " +
            "lastUp=$lastToServer lastDown=$lastToClient queued=${packetQueue.size} " +
            "lastUpstreamClose=${lastUpstreamClose ?: "none"} lastGameClose=${lastGameClose ?: "none"}"
    }

    private fun observeCloseReason(peer: BedrockPeer, upstream: Boolean) {
        val pipeline = runCatching { peer.channel.pipeline() }.getOrNull() ?: return
        if (pipeline.context(BedrockPeer.NAME) == null) {
            return
        }
        val linkName = if (upstream) "upstream" else "game"
        val handlerName = "wrelay-close-tracker-$linkName-${System.identityHashCode(this)}"
        if (pipeline.context(handlerName) != null) {
            return
        }

        try {
            pipeline.addBefore(BedrockPeer.NAME, handlerName, object : ChannelInboundHandlerAdapter() {
                private var observedReason: String? = null

                override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
                    if (evt is RakDisconnectReason) {
                        observedReason = evt.name
                        rememberCloseReason(upstream, evt.name)
                    }
                    ctx.fireUserEventTriggered(evt)
                }

                override fun channelInactive(ctx: ChannelHandlerContext) {
                    if (observedReason == null) {
                        rememberCloseReason(upstream, "CHANNEL_INACTIVE")
                    }
                    ctx.fireChannelInactive()
                }
            })
        } catch (error: Throwable) {
            val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            WTrace.warn("relay", "could not observe the $linkName channel close reason ($reason)")
        }
    }

    private fun rememberCloseReason(upstream: Boolean, reason: String) {
        if (upstream) {
            lastUpstreamClose = reason
        } else {
            lastGameClose = reason
        }
        val linkName = if (upstream) "upstream" else "game-client"
        WTrace.warn("relay", "$linkName RakNet channel closed: $reason")
    }

    /** Packet counters of this session, shared with the console's packet panel. */
    val stats: WPacketStats
        get() = WTrace.stats

    /**
     * True when the negotiated codec can encode [packet]. Pre-encoded [UnknownPacket]s are always
     * accepted. Packets missing from the codec (for example TickSync on protocol >= 685) would make
     * the Netty encoder throw, so callers should drop them instead of writing them.
     */
    @Suppress("UNCHECKED_CAST")
    fun isEncodable(packet: BedrockPacket): Boolean {
        if (packet is UnknownPacket) {
            return true
        }
        val codec = client?.codec ?: server.codec
        return runCatching {
            codec.getPacketDefinition(packet.javaClass as Class<BedrockPacket>) != null
        }.getOrDefault(true)
    }

    private fun dropUnsupported(packet: BedrockPacket) {
        println("Dropping unsupported packet ${packet.javaClass.simpleName}: not present in the negotiated codec")
        WTrace.warn(
            "relay",
            "dropped ${packet.javaClass.simpleName}: the negotiated codec (protocol " +
                "${client?.codec?.protocolVersion ?: server.codec.protocolVersion}) has no id for it"
        )
    }

    fun clientBound(packet: BedrockPacket) {
        try {
            server.sendPacket(packet)
        } catch (e: Exception) {
            println("Failed to send packet to client: ${e.message}")
            WTrace.error("relay", "failed to send ${packet.javaClass.simpleName} to the game client: ${e.message}")
        }
    }

    fun clientBoundImmediately(packet: BedrockPacket) {
        try {
            server.sendPacketImmediately(packet)
        } catch (e: Exception) {
            println("Failed to send packet immediately to client: ${e.message}")
            WTrace.error("relay", "failed to send ${packet.javaClass.simpleName} to the game client immediately: ${e.message}")
        }
    }

    fun serverBound(packet: BedrockPacket) = sendUpstreamOrQueue(packet, immediately = false)

    fun serverBoundImmediately(packet: BedrockPacket) = sendUpstreamOrQueue(packet, immediately = true)

    private fun sendUpstreamOrQueue(packet: BedrockPacket, immediately: Boolean) {
        if (!isEncodable(packet)) {
            dropUnsupported(packet)
            return
        }

        val upstream = synchronized(upstreamLock) {
            val current = client
            if (current == null) {
                if (packetQueue.size < maxQueueSize) {
                    packetQueue.add(packet to immediately)
                    // Packets queued before the upstream session exists are the join handshake: if this
                    // number grows while the console shows nothing else, the upstream connect is the stall.
                    WTrace.debug(
                        "relay",
                        "queued ${packet.javaClass.simpleName} until the upstream link is up (queue=${packetQueue.size})"
                    )
                } else {
                    println("Packet queue full, dropping packet")
                    WTrace.error("relay", "packet queue full ($maxQueueSize), dropping ${packet.javaClass.simpleName}")
                }
                null
            } else {
                current
            }
        } ?: return

        try {
            if (immediately) {
                upstream.sendPacketImmediately(packet)
            } else {
                upstream.sendPacket(packet)
            }
        } catch (error: Exception) {
            println("Failed to send ${if (immediately) "immediate " else ""}packet to server: ${error.message}")
            WTrace.error("relay", "failed to send ${packet.javaClass.simpleName} upstream: ${error.message}")
        }
    }

    inner class ServerSession(peer: BedrockPeer, subClientId: Int) :
        BedrockServerSession(peer, subClientId) {

        init {
            observeCloseReason(peer, upstream = false)
            packetHandler = object : BedrockPacketHandler {
                override fun onDisconnect(reason: CharSequence) {
                    println("Client disconnect: $reason")
                    WTrace.warn("relay", "the game client disconnected from the relay: $reason")
                    runCatching {
                        client?.disconnect()
                    }
                    listeners.forEach {
                        runCatching {
                            it.onDisconnect(reason.toString())
                        }
                    }
                    wRelay.connectionManager?.cleanup()
                }
            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                listeners.forEach { listener ->
                    try {
                        if (listener.beforeClientBound(wrapper.packet)) {
                            return
                        }
                    } catch (e: Throwable) {
                        println("Before client bound error: ${e.message}")
                        e.printStackTrace()
                    }
                }

                // This packet passed every intercepting listener, so it is what actually crosses the
                // relay: hand the raw wrapper to the trace listeners (exact size, packet id).
                listeners.forEach { listener ->
                    runCatching { listener.onPacketForwarded(wrapper, true) }
                }

                val buffer = wrapper.packetBuffer
                    .retainedSlice()
                    .skipBytes(wrapper.headerLength)

                val unknownPacket = UnknownPacket()
                unknownPacket.payload = buffer
                unknownPacket.packetId = wrapper.packetId
                serverBound(unknownPacket)

                listeners.forEach { listener ->
                    try {
                        listener.afterClientBound(wrapper.packet)
                    } catch (e: Throwable) {
                        println("After client bound error: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                println("Error processing client packet: ${e.message}")
                e.printStackTrace()
            }
        }

    }

    inner class ClientSession(peer: BedrockPeer, subClientId: Int) :
        BedrockClientSession(peer, subClientId) {

        init {
            observeCloseReason(peer, upstream = true)
            packetHandler = object : BedrockPacketHandler {

                override fun onDisconnect(reason: CharSequence) {
                    println("Server disconnect: $reason")
                    WTrace.error("relay", "the upstream server closed the link: $reason")
                    runCatching {
                        server.disconnect(reason.toString())
                    }
                    listeners.forEach {
                        runCatching {
                            it.onDisconnect(reason.toString())
                        }
                    }
                    wRelay.connectionManager?.cleanup()
                }

            }
        }

        override fun onPacket(wrapper: BedrockPacketWrapper) {
            try {
                listeners.forEach { listener ->
                    try {
                        if (listener.beforeServerBound(wrapper.packet)) {
                            return
                        }
                    } catch (e: Throwable) {
                        println("Before server bound error: ${e.message}")
                        e.printStackTrace()
                    }
                }

                // Server -> game client, i.e. the traffic the player sees.
                listeners.forEach { listener ->
                    runCatching { listener.onPacketForwarded(wrapper, false) }
                }

                val buffer = wrapper.packetBuffer
                    .retainedSlice()
                    .skipBytes(wrapper.headerLength)

                val unknownPacket = UnknownPacket()
                unknownPacket.payload = buffer
                unknownPacket.packetId = wrapper.packetId
                clientBound(unknownPacket)

                listeners.forEach { listener ->
                    try {
                        listener.afterServerBound(wrapper.packet)
                    } catch (e: Throwable) {
                        println("After server bound error: ${e.message}")
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                println("Error processing server packet: ${e.message}")
                e.printStackTrace()
            }
        }

    }

}