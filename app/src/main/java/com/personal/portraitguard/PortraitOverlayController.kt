package com.personal.portraitguard

import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.MotionEvent
import android.widget.TextView
import android.widget.ImageView

class PortraitOverlayController(private val context: Context) {
    private data class RotationState(
        val accelerometerRotation: Int,
        val userRotation: Int,
    )

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var overlay: View? = null
    private var messageView: GlassOverlayWindow? = null
    private var actionButton: GlassOverlayWindow? = null
    private var actionButtonGlass: LiquidGlassDrawable? = null
    private var overlayToken: IBinder? = null
    private val density = context.resources.displayMetrics.density
    private var rotationStateBeforeGuard: RotationState? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val rotationPreferences =
        context.getSharedPreferences(ROTATION_STATE_PREFERENCES, Context.MODE_PRIVATE)

    val isPortraitLocked: Boolean
        get() = overlay != null

    val hasPendingRotationState: Boolean
        get() = rotationStateBeforeGuard != null || readPersistedRotationState() != null

    fun showControl(onAction: () -> Unit): Boolean {
        if (actionButton != null) return true
        showActionButton(onAction)
        return actionButton != null
    }

    /**
     * Shows the button once the app is in landscape. When returning from the launcher or recents
     * the screen is still portrait at this point, so keep checking while the app rotates back.
     */
    fun showLandscapeControl(onAction: () -> Unit) {
        mainHandler.removeCallbacksAndMessages(LANDSCAPE_CONTROL_TOKEN)
        val deadline = android.os.SystemClock.uptimeMillis() + LANDSCAPE_CONTROL_WAIT_MS
        val check = object : Runnable {
            override fun run() {
                if (overlay != null) return
                val now = android.os.SystemClock.uptimeMillis()
                if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    showControl(onAction)
                } else if (now < deadline) {
                    mainHandler.postAtTime(this, LANDSCAPE_CONTROL_TOKEN, now + LANDSCAPE_CONTROL_RECHECK_MS)
                }
            }
        }
        check.run()
    }

    fun lockPortrait(onManualUnlock: () -> Unit): Boolean {
        if (overlay != null) {
            forcePortraitRotation()
            showActionButton(onManualUnlock)
            return true
        }
        return runCatching {
            val view = View(context).apply { alpha = 0f; isClickable = false; isFocusable = false }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSPARENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                title = "竖屏守护"
            }
            windowManager.addView(view, params)
            overlay = view
            showActionButton(onManualUnlock)
            rememberRotationState()
            forcePortraitRotation()
            true
        }.getOrDefault(false)
    }

    fun unlock() {
        mainHandler.removeCallbacksAndMessages(null)
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        removeActionButton()
        messageView?.remove()
        messageView = null
        restoreRotationState()
    }

    fun unlockPortrait(hideFloating: Boolean) {
        mainHandler.removeCallbacksAndMessages(null)
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        restoreRotationState()
        if (hideFloating) {
            removeActionButton()
        } else {
            // Off: gray glass, the icon faded.
            actionButtonGlass?.glassTint = INACTIVE_TINT
            actionButton?.content?.alpha = 0.55f
            actionButton?.let { button -> scheduleActionButtonAutoHide(button) }
        }
    }

    fun showMessage(message: String) {
        mainHandler.post {
            messageView?.remove()
            messageView = null
            val glass = LiquidGlassDrawable(MESSAGE_TINT, blurred = false, density = density)
            val view = TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(40, 20, 40, 20)
                setShadowLayer(4f, 0f, 1f, Color.argb(120, 0, 0, 0))
                background = glass
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 52
                title = "竖屏守护状态"
            }
            // A fixed size, so the blurred area is exactly the pill.
            view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            params.width = view.measuredWidth
            params.height = view.measuredHeight
            val window = glassWindow(view, params)
            if (window.show()) {
                glass.blurred = window.isBlurred
                messageView = window
                mainHandler.postDelayed({
                    if (messageView === window) {
                        window.remove()
                        messageView = null
                    }
                }, 2200L)
            }
        }
    }

    fun close() = unlock()

    private fun showActionButton(onManualUnlock: () -> Unit) {
        removeActionButton()
        val glass = LiquidGlassDrawable(ACTIVE_TINT, blurred = false, density = density)
        val button = ImageView(context).apply {
            setImageResource(com.personal.portraitguard.R.drawable.ic_unlock_rotation)
            contentDescription = "解除竖屏"
            setPadding(26, 26, 26, 26)
            background = glass
        }
        val params = WindowManager.LayoutParams(
            116,
            116,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 26
            y = 180
            title = "竖屏守护按钮"
        }
        val window = glassWindow(button, params)
        var downX = 0f
        var downY = 0f
        var startX = params.x
        var startY = params.y
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = params.x
                    startY = params.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moveX = (event.rawX - downX).toInt()
                    val moveY = (event.rawY - downY).toInt()
                    if (kotlin.math.abs(moveX) > 6 || kotlin.math.abs(moveY) > 6) {
                        params.x = (startX - moveX).coerceAtLeast(0)
                        params.y = (startY + moveY).coerceAtLeast(0)
                        window.updatePosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - downX) > 12 || kotlin.math.abs(event.rawY - downY) > 12
                    if (!moved) onManualUnlock()
                    true
                }
                else -> true
            }
        }
        if (window.show()) {
            glass.blurred = window.isBlurred
            actionButton = window
            actionButtonGlass = glass
            scheduleActionButtonAutoHide(window)
        }
    }

    private fun glassWindow(view: View, params: WindowManager.LayoutParams) = GlassOverlayWindow(
        context = context,
        windowManager = windowManager,
        content = view,
        params = params,
        // Fully rounded: a circle for the button, a pill for the message.
        cornerRadius = params.height / 2f,
        tokenSource = {
            overlayToken ?: GlassOverlayWindow.probeToken(context, windowManager).also { overlayToken = it }
        },
    )

    private fun scheduleActionButtonAutoHide(button: GlassOverlayWindow) {
        val shownInLandscape =
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        var hasBeenPortrait = !shownInLandscape
        val landscapeEntryAt = android.os.SystemClock.uptimeMillis()
        val hideCheck = object : Runnable {
            override fun run() {
                if (actionButton !== button) return
                val now = android.os.SystemClock.uptimeMillis()
                if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    val shouldHide = hasBeenPortrait ||
                        now - landscapeEntryAt >= LANDSCAPE_BUTTON_VISIBLE_MS
                    if (shouldHide) {
                        removeActionButton()
                        return
                    }
                } else {
                    hasBeenPortrait = true
                }
                mainHandler.postDelayed(this, FLOATING_BUTTON_RECHECK_MS)
            }
        }
        mainHandler.post(hideCheck)
    }

    private fun removeActionButton() {
        actionButton?.remove()
        actionButton = null
        actionButtonGlass = null
    }

    private fun rememberRotationState() {
        if (rotationStateBeforeGuard != null) return
        if (!Settings.System.canWrite(context)) return
        val currentState = readCurrentRotationState()
        readPersistedRotationState()?.let { persisted ->
            // Left behind by a guard that ended without restoring (for example when the process
            // was killed). If the settings still hold the portrait values written then, the saved
            // state is still the user's own; otherwise the user has chosen a rotation since.
            if (currentState == FORCED_ROTATION_STATE) {
                rotationStateBeforeGuard = persisted
                return
            }
            clearRotationState()
        }
        // Already locked to portrait: nothing needs writing, so nothing is restored later either.
        if (currentState == FORCED_ROTATION_STATE) return
        val saved = rotationPreferences.edit()
            .putInt(KEY_ACCELEROMETER_ROTATION, currentState.accelerometerRotation)
            .putInt(KEY_USER_ROTATION, currentState.userRotation)
            .commit()
        if (saved) rotationStateBeforeGuard = currentState
    }

    private fun restoreRotationState() {
        val previousState = rotationStateBeforeGuard ?: readPersistedRotationState() ?: return
        if (!Settings.System.canWrite(context)) return
        val currentState = readCurrentRotationState()
        // Only undo values this app wrote. A setting that no longer holds the forced value was
        // changed by the user (or the system) during the guard, and that choice stays.
        val restored = runCatching {
            val userRotationRestored =
                currentState.userRotation != FORCED_ROTATION_STATE.userRotation ||
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.USER_ROTATION,
                        previousState.userRotation,
                    )
            val accelerometerRotationRestored =
                currentState.accelerometerRotation != FORCED_ROTATION_STATE.accelerometerRotation ||
                    Settings.System.putInt(
                        context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION,
                        previousState.accelerometerRotation,
                    )
            userRotationRestored && accelerometerRotationRestored
        }.getOrDefault(false)
        if (restored) clearRotationState()
    }

    private fun clearRotationState() {
        rotationStateBeforeGuard = null
        rotationPreferences.edit().clear().commit()
    }

    private fun readCurrentRotationState() = RotationState(
        accelerometerRotation = Settings.System.getInt(
            context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION,
            1,
        ),
        userRotation = Settings.System.getInt(
            context.contentResolver,
            Settings.System.USER_ROTATION,
            android.view.Surface.ROTATION_0,
        ),
    )

    private fun readPersistedRotationState(): RotationState? {
        if (
            !rotationPreferences.contains(KEY_ACCELEROMETER_ROTATION) ||
            !rotationPreferences.contains(KEY_USER_ROTATION)
        ) {
            return null
        }
        return RotationState(
            accelerometerRotation = rotationPreferences.getInt(KEY_ACCELEROMETER_ROTATION, 1),
            userRotation = rotationPreferences.getInt(
                KEY_USER_ROTATION,
                android.view.Surface.ROTATION_0,
            ),
        )
    }

    private fun forcePortraitRotation() {
        if (!Settings.System.canWrite(context)) return
        // Only write when the original state was saved, so it can be put back on unlock.
        if (rotationStateBeforeGuard == null) return
        runCatching {
            // The overlay requests portrait, while these settings make the
            // current display rotation agree immediately on vendor ROMs.
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                FORCED_ROTATION_STATE.accelerometerRotation,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.USER_ROTATION,
                FORCED_ROTATION_STATE.userRotation,
            )
        }
    }

    private companion object {
        const val ROTATION_STATE_PREFERENCES = "rotation_state_backup"
        const val KEY_ACCELEROMETER_ROTATION = "accelerometer_rotation"
        const val KEY_USER_ROTATION = "user_rotation"
        const val LANDSCAPE_BUTTON_VISIBLE_MS = 6_000L
        const val FLOATING_BUTTON_RECHECK_MS = 1_000L
        const val LANDSCAPE_CONTROL_WAIT_MS = 10_000L
        const val LANDSCAPE_CONTROL_RECHECK_MS = 200L
        val LANDSCAPE_CONTROL_TOKEN = Any()

        // The glass tints: cyan while the guard is on, gray while it is off, dark slate behind messages.
        val ACTIVE_TINT = Color.argb(110, 8, 145, 178)
        val INACTIVE_TINT = Color.argb(110, 51, 65, 85)
        val MESSAGE_TINT = Color.argb(130, 15, 23, 42)

        // Auto-rotate off, locked to portrait: what the guard writes while it is on.
        val FORCED_ROTATION_STATE = RotationState(
            accelerometerRotation = 0,
            userRotation = android.view.Surface.ROTATION_0,
        )
    }
}
