package com.retrivedmods.wclient.overlay.hud

import android.view.Gravity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.overlay.OverlayWindow
import kotlin.math.abs
import kotlin.math.roundToInt

private val EditOutline = Color(0xFFE63946)
private val EditLabelBackground = Color(0xCC0A0A0A)

/**
 * Shared click-to-drag support for HUD overlay windows.
 *
 * HUD windows carry `FLAG_NOT_TOUCHABLE` during normal play so they never steal
 * touches from Minecraft. [OverlayManager.applyHudEditMode] clears that flag while
 * the HUD Editor module is enabled — the only time these gestures can run.
 *
 * Offsets in `WindowManager.LayoutParams` are relative to the window's `gravity`,
 * so the drag delta is mirrored on axes anchored to END / BOTTOM (a bottom-right
 * anchored list moves left when `x` grows), and axes anchored to CENTER accept
 * negative offsets.
 */
object HudDrag {

    /**
     * Moves [window] by the drag delta, keeps it on screen, applies edge/grid
     * snapping and reports the new offsets through [OverlayWindow.onHudMoved] so
     * the owning module can persist them.
     */
    fun drag(window: OverlayWindow, dx: Float, dy: Float) {
        val layoutParams = window.layoutParams
        val view = window.composeView
        val metrics = view.resources.displayMetrics
        val gravity = layoutParams.gravity

        val centeredX = (gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
        val centeredY = (gravity and Gravity.VERTICAL_GRAVITY_MASK) == Gravity.CENTER_VERTICAL
        val signX = if (!centeredX && isGravityEnd(gravity)) -1f else 1f
        val signY = if (!centeredY && isGravityBottom(gravity)) -1f else 1f

        val x = if (centeredX) {
            snapCentered(
                layoutParams.x + (dx * signX).roundToInt(),
                metrics.widthPixels,
                view.width
            )
        } else {
            snap(
                layoutParams.x + (dx * signX).roundToInt(),
                edgeMax(metrics.widthPixels, view.width)
            )
        }
        val y = if (centeredY) {
            snapCentered(
                layoutParams.y + (dy * signY).roundToInt(),
                metrics.heightPixels,
                view.height
            )
        } else {
            snap(
                layoutParams.y + (dy * signY).roundToInt(),
                edgeMax(metrics.heightPixels, view.height)
            )
        }

        layoutParams.x = x
        layoutParams.y = y
        try {
            window.windowManager.updateViewLayout(view, layoutParams)
        } catch (_: Exception) {
        }
        window.onHudMoved?.invoke(x, y)
    }

    /**
     * Grid + edge snapping for an edge-anchored axis, clamped to `0..maxOffset`.
     * A `maxOffset` of 0 or less means "size unknown" (the window has not been
     * measured yet), in which case only the grid is applied.
     */
    fun snap(offset: Int, maxOffset: Int): Int {
        var value = offset.coerceAtLeast(0)

        val grid = OverlayManager.hudGridSizePx
        if (grid > 0) {
            value = ((value + grid / 2) / grid) * grid
        }

        if (maxOffset > 0) {
            value = value.coerceAtMost(maxOffset)
            if (OverlayManager.hudSnapToEdges) {
                val threshold = OverlayManager.hudSnapThresholdPx
                if (value <= threshold) value = 0
                if (maxOffset - value <= threshold) value = maxOffset
            }
        }
        return value
    }

    /**
     * Same, for a CENTER-anchored axis where the offset is signed: snapping to 0
     * means "dead centre", snapping to the limit means flush with the screen edge.
     */
    private fun snapCentered(offset: Int, screen: Int, viewSize: Int): Int {
        val limit = if (viewSize > 0) {
            ((screen - viewSize) / 2).coerceAtLeast(0)
        } else {
            screen / 2
        }
        var value = offset.coerceIn(-limit, limit)

        val grid = OverlayManager.hudGridSizePx
        if (grid > 0) {
            val half = grid / 2
            value = ((value + if (value >= 0) half else -half) / grid) * grid
        }

        if (OverlayManager.hudSnapToEdges && limit > 0) {
            val threshold = OverlayManager.hudSnapThresholdPx
            if (abs(value) <= threshold) value = 0
            if (limit - abs(value) <= threshold) value = if (value >= 0) limit else -limit
        }
        return value.coerceIn(-limit, limit)
    }

    private fun edgeMax(screen: Int, viewSize: Int): Int =
        if (viewSize > 0) (screen - viewSize).coerceAtLeast(0) else -1

    fun isGravityEnd(gravity: Int): Boolean =
        (gravity and Gravity.END) == Gravity.END || (gravity and Gravity.RIGHT) == Gravity.RIGHT

    fun isGravityBottom(gravity: Int): Boolean =
        (gravity and Gravity.BOTTOM) == Gravity.BOTTOM
}

/**
 * Wraps a HUD element's content with the edit-mode drag gesture and outline.
 *
 * While [OverlayManager.isHudEditMode] is false this is a plain pass-through
 * [Box], so the element renders and behaves exactly as before.
 *
 * @param onDrag override for elements that manage their own anchor/offset model
 *   (the ArrayList re-picks its screen corner while dragging); defaults to
 *   [HudDrag.drag].
 */
@Composable
fun HudEditBox(
    window: OverlayWindow,
    label: String,
    modifier: Modifier = Modifier,
    onDrag: ((Float, Float) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val editing = OverlayManager.isHudEditMode

    Box(
        modifier = modifier.then(
            if (editing) {
                Modifier
                    .pointerInput(window) {
                        detectDragGestures { _, dragAmount ->
                            if (onDrag != null) {
                                onDrag(dragAmount.x, dragAmount.y)
                            } else {
                                HudDrag.drag(window, dragAmount.x, dragAmount.y)
                            }
                        }
                    }
                    .then(
                        if (OverlayManager.hudShowOutlines) {
                            Modifier.border(1.dp, EditOutline, RoundedCornerShape(6.dp))
                        } else {
                            Modifier
                        }
                    )
            } else {
                Modifier
            }
        ),
        contentAlignment = Alignment.TopStart
    ) {
        content()

        if (editing && OverlayManager.hudShowLabels) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 9.sp,
                modifier = Modifier
                    .background(EditLabelBackground, RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}
