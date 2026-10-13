package com.personal.portraitguard

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState
    data class NeedsInstallPermission(val apk: File) : UpdateState
    data class Message(val title: String, val text: String) : UpdateState
}

class UpdateController(private val context: Context, private val scope: CoroutineScope) {
    var state by mutableStateOf<UpdateState>(UpdateState.Idle)
        private set
    private var downloadJob: Job? = null

    val currentVersionName = AppUpdater.currentVersionName(context)
    private val currentVersionCode = AppUpdater.currentVersionCode(context)

    /** Runs at most once a day and stays silent on failure or when there is nothing to show. */
    suspend fun autoCheck() {
        val now = System.currentTimeMillis()
        if (now - AppConfigStore.lastUpdateCheck.first() in 0 until AppUpdater.AUTO_CHECK_INTERVAL_MS) return
        AppConfigStore.setLastUpdateCheck(now)
        val release = runCatching { AppUpdater.fetchLatest() }.getOrNull() ?: return
        val ignored = AppConfigStore.ignoredUpdateVersionCode.first()
        if (release.versionCode > currentVersionCode && release.versionCode != ignored && state == UpdateState.Idle) {
            state = UpdateState.Available(release)
        }
    }

    fun manualCheck() {
        if (state != UpdateState.Idle) return
        state = UpdateState.Checking
        scope.launch {
            state = runCatching { AppUpdater.fetchLatest() }.fold(
                onSuccess = { release ->
                    if (release.versionCode > currentVersionCode) {
                        UpdateState.Available(release)
                    } else {
                        UpdateState.Message("已是最新版本", "当前版本 $currentVersionName 已是最新。")
                    }
                },
                onFailure = { error -> UpdateState.Message("检查更新失败", error.message ?: "网络连接失败，请稍后重试。") },
            )
        }
    }

    fun ignore(release: ReleaseInfo) {
        scope.launch { AppConfigStore.setIgnoredUpdateVersionCode(release.versionCode) }
        dismiss()
    }

    fun dismiss() {
        state = UpdateState.Idle
    }

    fun download(release: ReleaseInfo) {
        state = UpdateState.Downloading(release, null)
        downloadJob = scope.launch {
            try {
                val apk = AppUpdater.download(context, release) { progress ->
                    if (isActive) state = UpdateState.Downloading(release, progress)
                }
                install(apk)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = UpdateState.Message("下载失败", error.message ?: "网络连接失败，请稍后重试。")
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        dismiss()
    }

    fun install(apk: File) {
        if (!AppUpdater.canInstallPackages(context)) {
            state = UpdateState.NeedsInstallPermission(apk)
            return
        }
        state = try {
            AppUpdater.install(context, apk)
            UpdateState.Idle
        } catch (_: ActivityNotFoundException) {
            UpdateState.Message("无法安装", "系统中没有可用的安装程序。")
        }
    }

    fun openInstallPermissionSettings() {
        runCatching { AppUpdater.openInstallPermissionSettings(context) }
    }
}

@Composable
fun rememberUpdateController(): UpdateController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope) { UpdateController(context, scope) }
}

@Composable
fun UpdateCard(controller: UpdateController) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("应用更新", style = MaterialTheme.typography.titleMedium)
                Text("当前版本 ${controller.currentVersionName}", style = MaterialTheme.typography.bodySmall)
            }
            val checking = controller.state == UpdateState.Checking
            FilledTonalButton(onClick = controller::manualCheck, enabled = !checking) {
                Text(if (checking) "检查中…" else "检查更新")
            }
        }
    }
}

@Composable
fun UpdateDialogs(controller: UpdateController) {
    when (val state = controller.state) {
        UpdateState.Idle, UpdateState.Checking -> Unit

        is UpdateState.Available -> AlertDialog(
            onDismissRequest = controller::dismiss,
            title = { Text("发现新版本 ${state.release.versionName}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("当前版本：${controller.currentVersionName}", style = MaterialTheme.typography.bodySmall)
                    Text(state.release.notes.ifBlank { "暂无更新说明" }, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { controller.download(state.release) }) { Text("更新") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { controller.ignore(state.release) }) { Text("忽略此版本") }
                    TextButton(onClick = controller::dismiss) { Text("稍后") }
                }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载 ${state.release.versionName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val progress = state.progress
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在连接…")
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text("${(progress * 100).toInt()}%")
                    }
                }
            },
            confirmButton = { TextButton(onClick = controller::cancelDownload) { Text("取消") } },
        )

        is UpdateState.NeedsInstallPermission -> {
            // Continue automatically once the user comes back from the system settings page with the permission granted.
            LifecycleResumeEffect(state.apk) {
                controller.install(state.apk)
                onPauseOrDispose {}
            }
            AlertDialog(
                onDismissRequest = controller::dismiss,
                title = { Text("需要安装权限") },
                text = { Text("安装更新需要允许「竖屏守护」安装未知应用。请在设置页中开启，返回后会自动继续安装。") },
                confirmButton = { TextButton(onClick = controller::openInstallPermissionSettings) { Text("去开启") } },
                dismissButton = { TextButton(onClick = controller::dismiss) { Text("取消") } },
            )
        }

        is UpdateState.Message -> AlertDialog(
            onDismissRequest = controller::dismiss,
            title = { Text(state.title) },
            text = { Text(state.text) },
            confirmButton = { TextButton(onClick = controller::dismiss) { Text("确定") } },
        )
    }
}
