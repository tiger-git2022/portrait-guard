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
import android.graphics.drawable.GradientDrawable
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
    private var messageView: View? = null
    private var actionButton: View? = null
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
        actionButton?.let { view -> runCatching { windowManager.removeView(view) } }
        actionButton = null
        messageView?.let { view -> runCatching { windowManager.removeView(view) } }
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
            (actionButton as? ImageView)?.apply {
                alpha = 0.45f
                background = circularBackground(Color.argb(220, 51, 65, 85))
            }
            actionButton?.let { button -> scheduleActionButtonAutoHide(button) }
        }
    }

    fun showMessage(message: String) {
        mainHandler.post {
            messageView?.let { view -> runCatching { windowManager.removeView(view) } }
            val view = TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(28, 16, 28, 16)
                setBackgroundColor(Color.argb(220, 15, 23, 42))
                alpha = 0.96f
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
            runCatching {
                windowManager.addView(view, params)
                messageView = view
                mainHandler.postDelayed({
                    if (messageView === view) {
                        runCatching { windowManager.removeView(view) }
                        messageView = null
                    }
                }, 2200L)
            }
        }
    }

    fun close() = unlock()

    private fun showActionButton(onManualUnlock: () -> Unit) {
        actionButton?.let { view -> runCatching { windowManager.removeView(view) } }
        val button = ImageView(context).apply {
            setImageResource(com.personal.portraitguard.R.drawable.ic_unlock_rotation)
            contentDescription = "解除竖屏"
            setPadding(22, 22, 22, 22)
            background = circularBackground(Color.argb(235, 8, 145, 178))
            elevation = 12f
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
                        runCatching { windowManager.updateViewLayout(button, params) }
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
        runCatching {
            windowManager.addView(button, params)
            actionButton = button
            scheduleActionButtonAutoHide(button)
        }
    }

    private fun scheduleActionButtonAutoHide(button: View) {
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

    private fun circularBackground(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(2, Color.argb(180, 255, 255, 255))
    }

    private fun removeActionButton() {
        actionButton?.let { view -> runCatching { windowManager.removeView(view) } }
        actionButton = null
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

        // Auto-rotate off, locked to portrait: what the guard writes while it is on.
        val FORCED_ROTATION_STATE = RotationState(
            accelerometerRotation = 0,
            userRotation = android.view.Surface.ROTATION_0,
        )
    }
}
