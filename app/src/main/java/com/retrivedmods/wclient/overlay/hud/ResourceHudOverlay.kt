package com.retrivedmods.wclient.overlay.hud

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrivedmods.wclient.R
import com.retrivedmods.wclient.game.module.misc.ResourceHudModule
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.overlay.OverlayWindow
import com.retrivedmods.wclient.ui.theme.WColors

/**
 * Small text card listing how many of each PvP resource (totems, strength
 * potions, ...) are in the inventory. The rows are computed by
 * [ResourceHudModule]; this class only draws them.
 *
 * Text only: the app ships no item textures.
 */
class ResourceHudOverlay : OverlayWindow() {

    /** One line of the card. */
    data class ResourceRow(
        val label: String,
        val count: Int,
        /** Extra text after the count, e.g. the remaining effect time. */
        val extra: String = "",
        /** Highlight the row (count at or below the configured threshold). */
        val low: Boolean = false
    )

    private val _layoutParams by lazy {
        super.layoutParams.apply {
            // Untouchable while playing; HUD edit mode clears the flag so the
            // card can be dragged.
            flags = flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            x = DEFAULT_OFFSET_X
            y = DEFAULT_OFFSET_Y
        }
    }

    override val layoutParams: WindowManager.LayoutParams
        get() = _layoutParams

    override val isHudElement: Boolean = true

    private var rows by mutableStateOf(listOf<ResourceRow>())
    private var position by mutableStateOf(ResourceHudModule.Position.CENTER_LEFT)
    private var offsetX by mutableStateOf(DEFAULT_OFFSET_X)
    private var offsetY by mutableStateOf(DEFAULT_OFFSET_Y)
    private var fontSize by mutableStateOf(14)
    private var compact by mutableStateOf(false)
    private var showBackground by mutableStateOf(true)
    private var backgroundOpacity by mutableStateOf(0.7f)
    private var useMinecraftFont by mutableStateOf(true)

    init {
        onHudMoved = { x, y ->
            offsetX = x
            offsetY = y
            onPositionChanged?.invoke(position, x, y)
        }
    }

    companion object {
        private const val DEFAULT_OFFSET_X = 20
        private const val DEFAULT_OFFSET_Y = 0

        val overlayInstance by lazy { ResourceHudOverlay() }
        private var shouldShowOverlay = false

        /** Called after a drag so ResourceHudModule can persist the new offset. */
        var onPositionChanged: ((ResourceHudModule.Position, Int, Int) -> Unit)? = null

        fun setOverlayEnabled(enabled: Boolean) {
            shouldShowOverlay = enabled
            try {
                if (enabled) OverlayManager.showOverlayWindow(overlayInstance)
                else OverlayManager.dismissOverlayWindow(overlayInstance)
            } catch (_: Exception) {
            }
        }

        fun isOverlayEnabled(): Boolean = shouldShowOverlay

        fun setRows(newRows: List<ResourceRow>) {
            if (overlayInstance.rows != newRows) overlayInstance.rows = newRows
        }

        fun setPosition(pos: ResourceHudModule.Position, x: Int, y: Int) {
            val instance = overlayInstance
            // Called from the module's update loop; only re-layout on change.
            if (instance.position == pos && instance.offsetX == x && instance.offsetY == y) return
            instance.position = pos
            instance.offsetX = x
            instance.offsetY = y
            instance.updateLayoutParams()
        }

        fun setFontSize(size: Int) {
            overlayInstance.fontSize = size
        }

        fun setCompact(value: Boolean) {
            overlayInstance.compact = value
        }

        fun setShowBackground(value: Boolean) {
            overlayInstance.showBackground = value
        }

        fun setBackgroundOpacity(value: Float) {
            overlayInstance.backgroundOpacity = value
        }

        fun setUseMinecraftFont(value: Boolean) {
            overlayInstance.useMinecraftFont = value
        }
    }

    private fun updateLayoutParams() {
        _layoutParams.gravity = when (position) {
            ResourceHudModule.Position.TOP_LEFT -> Gravity.TOP or Gravity.START
            ResourceHudModule.Position.TOP_CENTER -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ResourceHudModule.Position.TOP_RIGHT -> Gravity.TOP or Gravity.END
            ResourceHudModule.Position.CENTER_LEFT -> Gravity.CENTER_VERTICAL or Gravity.START
            ResourceHudModule.Position.CENTER_RIGHT -> Gravity.CENTER_VERTICAL or Gravity.END
            ResourceHudModule.Position.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            ResourceHudModule.Position.BOTTOM_CENTER -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ResourceHudModule.Position.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
        }
        _layoutParams.x = offsetX
        _layoutParams.y = offsetY

        try {
            windowManager.updateViewLayout(composeView, _layoutParams)
        } catch (_: Exception) {
        }
    }

    @Composable
    override fun Content() {
        if (!isOverlayEnabled()) return

        val fontFamily = if (useMinecraftFont) FontFamily(Font(R.font.minecraft)) else FontFamily.Default
        val visibleRows = rows
        // Keep a placeholder while editing so there is always something to grab.
        val displayRows = if (visibleRows.isEmpty() && OverlayManager.isHudEditMode) {
            listOf(ResourceRow("Resources", 0))
        } else {
            visibleRows
        }

        HudEditBox(window = this, label = "Resources") {
            if (displayRows.isEmpty()) return@HudEditBox

            Column(
                modifier = Modifier
                    .let { modifier ->
                        if (showBackground) {
                            modifier
                                .background(
                                    WColors.Surface.copy(alpha = backgroundOpacity),
                                    RoundedCornerShape(8.dp)
                                )
                                .border(
                                    1.dp,
                                    WColors.Border.copy(alpha = backgroundOpacity),
                                    RoundedCornerShape(8.dp)
                                )
                        } else modifier
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (compact) {
                    CompactLine(displayRows, fontFamily)
                } else {
                    displayRows.forEach { row -> DetailedRow(row, fontFamily) }
                }
            }
        }
    }

    @Composable
    private fun DetailedRow(row: ResourceRow, fontFamily: FontFamily) {
        val countColor = if (row.low) WColors.Error else WColors.OnBackground
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.label,
                color = WColors.OnSurfaceVariant,
                fontSize = fontSize.sp,
                fontFamily = fontFamily
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = row.count.toString(),
                color = countColor,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = fontFamily
            )
            if (row.extra.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = row.extra,
                    color = WColors.Accent,
                    fontSize = (fontSize - 2).coerceAtLeast(8).sp,
                    fontFamily = fontFamily
                )
            }
        }
    }

    @Composable
    private fun CompactLine(rows: List<ResourceRow>, fontFamily: FontFamily) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            rows.forEachIndexed { index, row ->
                if (index > 0) Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${row.label.take(1)}:",
                    color = WColors.OnSurfaceVariant,
                    fontSize = fontSize.sp,
                    fontFamily = fontFamily
                )
                Text(
                    text = row.count.toString(),
                    color = if (row.low) WColors.Error else WColors.OnBackground,
                    fontSize = fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = fontFamily
                )
                if (row.extra.isNotEmpty()) {
                    Text(
                        text = " ${row.extra}",
                        color = WColors.Accent,
                        fontSize = (fontSize - 2).coerceAtLeast(8).sp,
                        fontFamily = fontFamily
                    )
                }
            }
        }
    }
}
