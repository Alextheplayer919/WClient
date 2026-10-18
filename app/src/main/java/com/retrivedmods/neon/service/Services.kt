package com.retrivedmods.neon.service

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.retrivedmods.neon.util.WLog as Log
import android.view.WindowManager
import android.graphics.PixelFormat
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retrivedmods.neon.game.AccountManager
import com.retrivedmods.neon.game.GameSession
import com.retrivedmods.neon.game.ModuleManager
import com.retrivedmods.neon.game.module.visual.ESPModule
import com.retrivedmods.neon.model.CaptureModeModel
import com.retrivedmods.neon.overlay.OverlayManager
import com.retrivedmods.neon.render.RenderOverlayView
import com.retrivedmods.neon.relay.WRelay
import com.retrivedmods.neon.relay.WRelaySession
import com.retrivedmods.neon.relay.address.WAddress
import com.retrivedmods.neon.relay.config.EnhancedServerConfig
import com.retrivedmods.neon.relay.definition.Definitions
import com.retrivedmods.neon.relay.listener.AutoCodecPacketListener
import com.retrivedmods.neon.relay.listener.GamingPacketHandler
import com.retrivedmods.neon.relay.listener.OnlineLoginPacketListener
import com.retrivedmods.neon.relay.util.captureGamePacket
import com.retrivedmods.neon.util.ServerCompatUtils
import com.retrivedmods.neon.trace.WTrace
import java.io.File
import kotlin.concurrent.thread

@Suppress("MemberVisibilityCanBePrivate")
object Services {

    private val handler = Handler(Looper.getMainLooper())

    private var wRelay: WRelay? = null
    private var thread: Thread? = null

    private var renderView: RenderOverlayView? = null
    private var windowManager: WindowManager? = null

    var isActive by mutableStateOf(false)
    var detectedProtocolVersion by mutableStateOf<Int?>(null)
    var detectedMinecraftVersion by mutableStateOf<String?>(null)

    fun toggle(context: Context, captureModeModel: CaptureModeModel) {
        if (!isActive) {
            on(context, captureModeModel)
            return
        }

        off()
    }

    private fun clearNetworkCaches() {
        try {
            val inetAddressClass = Class.forName("java.net.InetAddress")
            val cacheField = inetAddressClass.getDeclaredField("addressCache")
            cacheField.isAccessible = true
            val cache = cacheField.get(null)
            val cacheMapField = cache.javaClass.getDeclaredField("cache")
            cacheMapField.isAccessible = true
            val cacheMap = cacheMapField.get(cache) as? java.util.Map<*, *>
            cacheMap?.clear()

            val negativeCacheField = inetAddressClass.getDeclaredField("negativeCache")
            negativeCacheField.isAccessible = true
            val negativeCache = negativeCacheField.get(null)
            val negativeCacheMapField = negativeCache.javaClass.getDeclaredField("cache")
            negativeCacheMapField.isAccessible = true
            val negativeCacheMap = negativeCacheMapField.get(negativeCache) as? java.util.Map<*, *>
            negativeCacheMap?.clear()

            System.gc()
            System.runFinalization()

            Log.d("Services", "Network caches cleared")
        } catch (e: Exception) {
            Log.e("Services", "Error clearing network caches: ${e.message}")
        }
    }

    private fun on(context: Context, captureModeModel: CaptureModeModel) {
        if (thread != null) {
            return
        }

        wRelay?.let { relay ->
            try {
                if (relay.javaClass.methods.any { it.name == "stop" }) {
                    relay.javaClass.getMethod("stop").invoke(relay)
                }
            } catch (e: Exception) {
                Log.e("Services", "Error stopping existing WRelay: ${e.message}")
            }
        }
        wRelay = null

        File(context.cacheDir, "token_cache.json")

        isActive = true

        // Session marker: everything after this line belongs to this capture, and this is the line the
        // player can point at when they say "the freeze happened on this attempt".
        WTrace.info(
            "session",
            "=== relay START: ${captureModeModel.serverHostName}:${captureModeModel.serverPort} " +
                "(mode=${captureModeModel.serverConfigType}, optimize=${captureModeModel.enableServerOptimizations}, " +
                "protected=${captureModeModel.isProtectedServer()}, " +
                "account=${AccountManager.selectedAccount?.mcChain?.displayName ?: "none"}) ==="
        )

        handler.post {
            OverlayManager.show(context)
        }

        setupOverlay(context)

        thread = thread(
            name = "WRelayThread",
            priority = Thread.MAX_PRIORITY
        ) {
            runCatching {
                ModuleManager.loadConfig()
            }.exceptionOrNull()?.let {
                it.printStackTrace()
                context.toast("Load configuration error: ${it.message}")
            }

            runCatching {
                Definitions.loadBlockPalette()
            }.exceptionOrNull()?.let {
                it.printStackTrace()
                context.toast("Load block palette error: ${it.message}")
            }

            val selectedAccount = AccountManager.selectedAccount

            runCatching {
                clearNetworkCaches()

                val remoteAddress = WAddress(
                    captureModeModel.serverHostName,
                    captureModeModel.serverPort
                )

                val serverConfig = getServerConfig(captureModeModel)
                wRelay = if (captureModeModel.isProtectedServer() && captureModeModel.enableServerOptimizations) {
                    WRelay(
                        localAddress = WAddress("0.0.0.0", 19132),
                        serverConfig = serverConfig
                    ).capture(remoteAddress = remoteAddress) {
                        initModules(this)
                        listeners.add(AutoCodecPacketListener(this))
                        selectedAccount?.let { OnlineLoginPacketListener(this, it) }
                            ?.let { listeners.add(it) }
                        listeners.add(GamingPacketHandler(this))
                    }
                } else {
                    captureGamePacket(
                        localAddress = WAddress("0.0.0.0", 19132),
                        remoteAddress = remoteAddress
                    ) {
                        initModules(this)
                        listeners.add(AutoCodecPacketListener(this))
                        selectedAccount?.let { OnlineLoginPacketListener(this, it) }
                            ?.let { listeners.add(it) }
                        listeners.add(GamingPacketHandler(this))
                    }
                }
            }.exceptionOrNull()?.let {
                it.printStackTrace()
                context.toast("Start WRelay error: ${it.message}")
            }
        }
    }

    private fun off() {
        WTrace.info("session", "=== relay STOP requested (was forwarding until now) ===")
        thread(name = "WRelayThread") {
            ModuleManager.saveConfig()

            wRelay?.let { relay ->
                try {
                    relay.wRelaySession?.client?.disconnect()
                    relay.wRelaySession?.server?.disconnect()

                    if (relay.javaClass.methods.any { it.name == "stop" }) {
                        relay.javaClass.getMethod("stop").invoke(relay)
                        Log.d("Services", "WRelay connection stopped successfully")
                    }
                } catch (e: Exception) {
                    Log.e("Services", "Error stopping WRelay: ${e.message}")
                    e.printStackTrace()
                }
            }
            wRelay = null

            clearNetworkCaches()

            try {
                Thread.sleep(500)
            } catch (e: Exception) {
                Log.e("Services", "Error during cleanup delay: ${e.message}")
            }

            handler.post {
                OverlayManager.dismiss()
            }
            removeOverlay()
            isActive = false
            thread?.interrupt()
            thread = null

            Log.d("Services", "WRelay service stopped and cleaned up")
        }
    }

    private fun Context.toast(message: String) {
        handler.post {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ console

    /** One-line state of both relay links, or null when no session exists yet. */
    fun linkSnapshot(): String? = runCatching { wRelay?.wRelaySession?.linkSnapshot() }.getOrNull()

    /** RakNet RTT (ms) of the relay to server link, or null before it was measured. */
    fun upstreamPingMs(): Long? = runCatching { wRelay?.wRelaySession?.upstreamPingMs }.getOrNull()

    /** True once the relay has a session object, even if the upstream link is still connecting. */
    fun hasSession(): Boolean = runCatching { wRelay?.wRelaySession != null }.getOrDefault(false)

    private fun initModules(wRelaySession: WRelaySession) {
        val session = GameSession(wRelaySession)
        wRelaySession.listeners.add(session)

        // Raw packet trace first, so a line is written even for packets a later listener swallows.
        wRelaySession.listeners.add(com.retrivedmods.neon.relay.listener.PacketTraceListener())

        wRelaySession.listeners.add(com.retrivedmods.neon.relay.listener.VersionTrackingListener() { protocol, version ->
            detectedProtocolVersion = protocol
            detectedMinecraftVersion = version
            Log.i("Services", "Client version: Minecraft $version (Protocol $protocol)")
            WTrace.info("version", "the game client identified itself as Minecraft $version (protocol $protocol)")
        })

        for (module in ModuleManager.modules) {
            module.session = session
        }
        Log.e("Services", "Init session")
    }

    private fun setupOverlay(context: Context) {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha = 0.8f
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
            }
        }

        renderView = RenderOverlayView(context)
        ESPModule.setRenderView(renderView!!)



        handler.post {
            try {
                windowManager?.addView(renderView, params)
            } catch (e: Exception) {
                e.printStackTrace()
                context.toast("Failed to add overlay view: ${e.message}")
            }
        }
    }

    private fun removeOverlay() {
        renderView?.let { view ->
            windowManager?.removeView(view)
            renderView = null
        }
    }

    private fun getServerConfig(captureModeModel: CaptureModeModel): EnhancedServerConfig {
        return when (captureModeModel.serverConfigType) {
            ServerCompatUtils.ServerConfigType.FAST -> EnhancedServerConfig.FAST
            ServerCompatUtils.ServerConfigType.DEFAULT -> EnhancedServerConfig.DEFAULT
            ServerCompatUtils.ServerConfigType.AGGRESSIVE -> EnhancedServerConfig.AGGRESSIVE
            ServerCompatUtils.ServerConfigType.STANDARD -> EnhancedServerConfig.DEFAULT
        }
    }
}