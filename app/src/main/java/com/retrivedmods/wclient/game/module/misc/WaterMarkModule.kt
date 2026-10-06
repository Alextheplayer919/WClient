package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.overlay.hud.WaterMarkOverlay
import kotlinx.coroutines.*

class WaterMarkModule : Module("watermark", ModuleCategory.Misc) {

    private var scope: CoroutineScope? = null

    private val customText by stringValue("Text", "WClient", listOf())
    private val showVersion by boolValue("Show Version", true)
    private var position by enumValue("Position", Position.TOP_LEFT, Position::class.java)
    // Signed because the CENTER anchors measure their offsets from the middle of
    // the screen. Written back by the overlay when you drag it in HUD edit mode.
    private var offsetX by intValue("Offset X", 20, -2000..2000)
    private var offsetY by intValue("Offset Y", 20, -2000..2000)
    private val fontSize by intValue("Font Size", 24, 12..36)
    private val mode by enumValue("Mode", WatermarkMode.RGB, WatermarkMode::class.java)
    private val fontStyle by enumValue("Font", FontStyle.MINECRAFT, FontStyle::class.java)

    override fun onEnabled() {
        super.onEnabled()
        if (!isSessionCreated) return

        WaterMarkOverlay.setOverlayEnabled(true)
        WaterMarkOverlay.onPositionChanged = { newPosition, x, y ->
            if (position != newPosition) position = newPosition
            if (offsetX != x) offsetX = x
            if (offsetY != y) offsetY = y
        }
        applySettings()

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()).apply {
            launch {
                while (isActive && isEnabled && isSessionCreated) {
                    applySettings()
                    delay(500L)
                }
            }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        scope?.cancel()
        scope = null
        WaterMarkOverlay.onPositionChanged = null

        if (isSessionCreated) {
            WaterMarkOverlay.setOverlayEnabled(false)
        }
    }

    override fun onDisconnect(reason: String) {
        scope?.cancel()
        scope = null
        WaterMarkOverlay.onPositionChanged = null
        WaterMarkOverlay.setOverlayEnabled(false)
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {

    }

    private fun applySettings() {
        WaterMarkOverlay.setCustomText(customText)
        WaterMarkOverlay.setShowVersion(showVersion)
        WaterMarkOverlay.setPosition(position, offsetX, offsetY)
        WaterMarkOverlay.setFontSize(fontSize)
        WaterMarkOverlay.setMode(mode)
        WaterMarkOverlay.setFontStyle(fontStyle)
    }

    enum class Position {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        CENTER_LEFT, CENTER, CENTER_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    enum class WatermarkMode {
        RGB

    }

    enum class FontStyle {
        DEFAULT,
        MINECRAFT
    }
}