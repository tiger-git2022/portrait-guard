package com.personal.portraitguard

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.RequiresApi

/*
 * Liquid glass for the floating button and the status message over the game.
 *
 * An overlay window cannot read the game's pixels, so the glass cannot bend the game like the glass of EhViewer and
 * Mihon. What the system offers is a blur of whatever lies behind a window (Android 12+, "cross-window blur"), and it
 * only does that for a window with a Window object. So the parts are shown in a small dialog window of the accessibility
 * overlay type with a background blur, and [LiquidGlassDrawable] draws the glass on it: a light tint, a lit rim brighter
 * at the top left and a sheen across the top, like the glass parts of the two apps. Without blur (older systems, blur
 * switched off by the system, e.g. in battery saver) the window is a plain overlay and the tint is nearly opaque, as
 * before.
 */

/** The glass surface: a pill or circle (fully rounded), a tint, a lit rim and a sheen. */
class LiquidGlassDrawable(glassTint: Int, blurred: Boolean, private val density: Float) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val rect = RectF()

    var glassTint: Int = glassTint
        set(value) {
            field = value
            invalidateSelf()
        }

    /** With blur behind, the tint stays light; without, it is nearly opaque so the white icon and text stay readable. */
    var blurred: Boolean = blurred
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val width = bounds.width().toFloat()
        val height = bounds.height().toFloat()
        // The rim catches the light at the top left and, fainter, at the bottom right, like a lens edge.
        rim.shader = LinearGradient(
            bounds.left.toFloat(), bounds.top.toFloat(), bounds.left + width, bounds.top + height,
            intArrayOf(Color.argb(210, 255, 255, 255), Color.argb(40, 255, 255, 255), Color.argb(120, 255, 255, 255)),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        // A soft sheen across the upper half.
        sheen.shader = LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.top + height * 0.6f,
            Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        val inset = rim.strokeWidth / 2f
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        val radius = minOf(rect.width(), rect.height()) / 2f
        val alpha = Color.alpha(glassTint)
        fill.color = if (blurred) glassTint else Color.argb(maxOf(alpha, OPAQUE_TINT_ALPHA), Color.red(glassTint), Color.green(glassTint), Color.blue(glassTint))
        canvas.drawRoundRect(rect, radius, radius, fill)
        canvas.drawRoundRect(rect, radius, radius, sheen)
        canvas.drawRoundRect(rect, radius, radius, rim)
    }

    override fun getOutline(outline: Outline) {
        val bounds = bounds
        outline.setRoundRect(bounds, minOf(bounds.width(), bounds.height()) / 2f)
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val OPAQUE_TINT_ALPHA = 225
    }
}

/**
 * [content] in an accessibility overlay window laid out by [params], with the system blur behind it when the system
 * allows it. [cornerRadius] is the rounding of the blurred area, in pixels.
 */
class GlassOverlayWindow(
    private val context: Context,
    private val windowManager: WindowManager,
    val content: View,
    val params: WindowManager.LayoutParams,
    private val cornerRadius: Float,
    private val tokenSource: () -> IBinder?,
) {
    private var dialog: Dialog? = null
    private var plain = false

    /** Whether the system blurs the game behind this window. */
    var isBlurred = false
        private set

    fun show(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val shown = runCatching { showBlurred() }.getOrElse { failure ->
                Log.w(TAG, "Blurred overlay failed, showing a plain one", failure)
                false
            }
            if (shown) {
                isBlurred = true
                return true
            }
            // Undo a half-made dialog, so the content can go into the plain window.
            dialog?.let { runCatching { it.dismiss() } }
            dialog = null
            (content.parent as? ViewGroup)?.removeView(content)
        }
        return runCatching {
            windowManager.addView(content, params)
            plain = true
            true
        }.getOrDefault(false)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun showBlurred(): Boolean {
        if (!windowManager.isCrossWindowBlurEnabled) return false
        // A dialog's window does not get the accessibility service's window token by itself; overlay windows need it.
        val token = tokenSource() ?: return false
        val overlayDialog = Dialog(context, R.style.GlassOverlayWindow)
        overlayDialog.setCancelable(false)
        dialog = overlayDialog
        overlayDialog.setContentView(
            content,
            ViewGroup.LayoutParams(
                if (params.width == WindowManager.LayoutParams.WRAP_CONTENT) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
                if (params.height == WindowManager.LayoutParams.WRAP_CONTENT) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        val window = overlayDialog.window ?: return false
        window.setType(params.type)
        // Clear, rounded like the glass: the blur takes the rounding of the window background.
        window.setBackgroundDrawable(
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = this@GlassOverlayWindow.cornerRadius
                setColor(Color.TRANSPARENT)
            },
        )
        window.setBackgroundBlurRadius((BLUR_RADIUS_DP * context.resources.displayMetrics.density).toInt())
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setWindowAnimations(0)
        val attributes = window.attributes
        attributes.type = params.type
        attributes.token = token
        attributes.width = params.width
        attributes.height = params.height
        attributes.gravity = params.gravity
        attributes.x = params.x
        attributes.y = params.y
        attributes.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        attributes.format = PixelFormat.TRANSLUCENT
        attributes.dimAmount = 0f
        attributes.title = params.title
        window.attributes = attributes
        overlayDialog.show()
        return true
    }

    /** Moves the window to [params]' position. */
    fun updatePosition() {
        val window = dialog?.window
        if (window != null) {
            val attributes = window.attributes
            attributes.x = params.x
            attributes.y = params.y
            runCatching { window.attributes = attributes }
        } else if (plain) {
            runCatching { windowManager.updateViewLayout(content, params) }
        }
    }

    fun remove() {
        dialog?.let { runCatching { it.dismiss() } }
        dialog = null
        if (plain) runCatching { windowManager.removeView(content) }
        plain = false
    }

    companion object {
        private const val TAG = "PortraitGuardGlass"
        private const val BLUR_RADIUS_DP = 12f

        /**
         * The accessibility service's window token, which its window manager adds to overlay windows: read from a
         * tiny window added and removed right away.
         */
        fun probeToken(context: Context, windowManager: WindowManager): IBinder? = runCatching {
            val probe = View(context)
            val probeParams = WindowManager.LayoutParams(
                1,
                1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            )
            windowManager.addView(probe, probeParams)
            val token = probeParams.token
            runCatching { windowManager.removeView(probe) }
            token
        }.getOrNull()
    }
}
