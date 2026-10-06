package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.ModuleManager
import com.retrivedmods.wclient.game.config.ConfigManager
import com.retrivedmods.wclient.game.module.visual.TargetHudModule
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.overlay.hud.TargetHudOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lay the HUD out by dragging it.
 *
 * Enabling this module closes the ClickGUI (a full-screen window stacked above
 * every HUD element that would otherwise swallow all touches), makes the HUD
 * elements touchable and outlined, and lets you position them with your finger —
 * including the ArrayList, which flips its corner and its name-length direction
 * as you drag it around.
 *
 * Disable it again to lock the layout: every element writes its position back
 * into its own module values, so the layout is saved with the config / profile.
 * Tip: tick this module's **Shortcut** box and you get a floating toggle button
 * that exits edit mode without reopening the ClickGUI.
 */
class HudEditorModule : Module("hud_editor", ModuleCategory.Misc) {

    private var scope: CoroutineScope? = null
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val snapToEdges by boolValue("Snap To Edges", true)
    private val snapDistance by intValue("Snap Distance", 24, 4..80)
    private val gridSize by intValue("Grid Size", 0, 0..80)
    private val showOutlines by boolValue("Show Outlines", true)
    private val showLabels by boolValue("Show Labels", true)
    private val saveOnExit by boolValue("Save Layout On Exit", true)

    override fun onEnabled() {
        super.onEnabled()
        applySettings()
        OverlayManager.applyHudEditMode(true)
        OverlayManager.dismissClickGui()
        showTargetHudPreview()

        // Values can be edited from the app UI while edit mode is running, so keep
        // pushing them (same approach as WaterMarkModule).
        scope = CoroutineScope(Dispatchers.Main + SupervisorJob()).apply {
            launch {
                while (isActive && isEnabled) {
                    applySettings()
                    delay(300L)
                }
            }
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        scope?.cancel()
        scope = null
        TargetHudOverlay.hidePreview()
        OverlayManager.applyHudEditMode(false)
        if (saveOnExit) saveLayout()
    }

    override fun onDisconnect(reason: String) {
        scope?.cancel()
        scope = null
        TargetHudOverlay.hidePreview()
        OverlayManager.applyHudEditMode(false)
    }

    /**
     * Edit mode is a transient tool: never come back from a restart or a profile
     * switch with it switched on.
     */
    override fun toJson() = buildJsonObject {
        super.toJson().forEach { (key, element) ->
            if (key != "state") put(key, element)
        }
        put("state", false)
    }

    override fun fromJson(jsonElement: JsonElement) {
        if (jsonElement is JsonObject) {
            super.fromJson(JsonObject(jsonElement.filterKeys { it != "state" }))
        } else {
            super.fromJson(jsonElement)
        }
    }

    /**
     * The Target HUD window only exists while a target is tracked, so it could
     * never be dragged otherwise. Show its sample card while editing, but only
     * when the user actually has the module on.
     */
    private fun showTargetHudPreview() {
        if (!isSessionCreated) return
        val targetHudEnabled = ModuleManager.modules
            .filterIsInstance<TargetHudModule>()
            .any { it.isEnabled }
        if (targetHudEnabled) {
            try {
                TargetHudOverlay.showPreview()
            } catch (_: Exception) {
            }
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
    }

    private fun applySettings() {
        OverlayManager.hudSnapToEdges = snapToEdges
        OverlayManager.hudSnapThresholdPx = snapDistance
        OverlayManager.hudGridSizePx = gridSize
        OverlayManager.hudShowOutlines = showOutlines
        OverlayManager.hudShowLabels = showLabels
    }

    /**
     * HUD positions live in each module's `Value`s, so saving the config (and the
     * active profile, when there is one) is all it takes to make a layout stick.
     */
    private fun saveLayout() {
        ioScope.launch {
            try {
                val profile = ConfigManager.activeProfileName.value
                if (profile != null) {
                    ConfigManager.saveProfile(profile) // also writes UserConfig.json
                } else {
                    ModuleManager.saveConfig()
                }
            } catch (_: Exception) {
            }
        }
    }
}
