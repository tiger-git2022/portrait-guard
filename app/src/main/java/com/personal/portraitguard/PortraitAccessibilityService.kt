package com.personal.portraitguard

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PortraitAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var overlayController: PortraitOverlayController
    private var activePackage: String? = null
    private var hideFloatingAfterUnlock = false
    private var foregroundPackage: String? = null
    private var lastForegroundUsageCheckAt = System.currentTimeMillis()
    private val homePackageName: String? by lazy {
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .resolveActivity(packageManager)
            ?.packageName
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlayController = PortraitOverlayController(this)
        serviceScope.launch {
            AppConfigStore.apps.collectLatest { apps ->
                val active = activePackage ?: return@collectLatest
                if (apps.none { it.packageName == active && it.enabled }) {
                    stopSession("规则已关闭")
                }
            }
        }
        serviceScope.launch {
            while (isActive) {
                pollForegroundPackage()
                delay(500L)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        if (
            packageName == applicationContext.packageName &&
            event.className?.toString() != MainActivity::class.java.name
        ) {
            return
        }
        handleForegroundPackage(
            packageName = packageName,
            confirmedForeground = packageName == homePackageName ||
                packageName == applicationContext.packageName,
        )
    }

    private fun handleForegroundPackage(
        packageName: String,
        confirmedForeground: Boolean,
    ) {
        serviceScope.launch {
            if (packageName == applicationContext.packageName) {
                foregroundPackage = packageName
                if (activePackage != null) {
                    stopSession("已恢复原旋转设置")
                } else if (overlayController.hasPendingRotationState) {
                    leaveTargetForeground()
                }
                return@launch
            }

            val target = AppConfigStore.apps.first()
                .firstOrNull { it.packageName == packageName && it.enabled }
            if (target == null) {
                if (confirmedForeground) {
                    foregroundPackage = packageName
                    if (activePackage != null || overlayController.hasPendingRotationState) {
                        leaveTargetForeground()
                    }
                }
                return@launch
            }

            foregroundPackage = packageName
            if (activePackage == packageName) {
                overlayController.showLandscapeControl { togglePortrait() }
                return@launch
            }
            stopSession()
            activePackage = packageName
            hideFloatingAfterUnlock = target.hideFloatingAfterUnlock
            if (!overlayController.lockPortrait { togglePortrait() }) {
                activePackage = null
                hideFloatingAfterUnlock = false
                overlayController.showMessage("无法创建方向浮层")
            }
        }
    }

    private fun pollForegroundPackage() {
        val now = System.currentTimeMillis()
        val usageStats = getSystemService(UsageStatsManager::class.java)
        val events = runCatching {
            usageStats.queryEvents(lastForegroundUsageCheckAt, now)
        }.getOrNull() ?: run {
            lastForegroundUsageCheckAt = now
            return
        }
        lastForegroundUsageCheckAt = now
        val event = UsageEvents.Event()
        var latestResumedPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == USAGE_EVENT_ACTIVITY_RESUMED) {
                latestResumedPackage = event.packageName
            }
        }
        latestResumedPackage?.let { packageName ->
            handleForegroundPackage(
                packageName = packageName,
                confirmedForeground = true,
            )
        }
    }

    private fun togglePortrait() {
        if (activePackage == null) return
        if (overlayController.isPortraitLocked) {
            overlayController.unlockPortrait(hideFloating = hideFloatingAfterUnlock)
            overlayController.showMessage("已恢复原旋转设置")
        } else if (overlayController.lockPortrait { togglePortrait() }) {
            overlayController.showMessage("已固定竖屏")
        } else {
            overlayController.showMessage("无法创建方向浮层")
        }
    }

    private fun stopSession(message: String? = null) {
        activePackage = null
        hideFloatingAfterUnlock = false
        if (::overlayController.isInitialized) overlayController.unlock()
        if (message != null && ::overlayController.isInitialized) {
            overlayController.showMessage(message)
        }
    }

    private fun leaveTargetForeground() {
        if (::overlayController.isInitialized) overlayController.unlock()
    }

    override fun onInterrupt() = stopSession()

    override fun onDestroy() {
        stopSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val USAGE_EVENT_ACTIVITY_RESUMED = 1
    }
}
