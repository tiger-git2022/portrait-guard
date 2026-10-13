package com.personal.portraitguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PortraitGuardScreen(this) }
        lifecycleScope.launch {
            setHideFromRecentTasks(AppConfigStore.hideRecentTask.first())
        }
    }

    fun setHideFromRecentTasks(hide: Boolean) {
        getSystemService(ActivityManager::class.java).appTasks
            .firstOrNull { appTask -> appTask.taskInfo.taskId == taskId }
            ?.setExcludeFromRecents(hide)
    }
}

private data class PermissionState(
    val accessibility: Boolean,
    val overlay: Boolean,
    val writeSettings: Boolean,
    val usageAccess: Boolean,
    val ignoringBatteryOptimizations: Boolean,
) {
    val canEnableApps get() = accessibility && overlay && writeSettings && usageAccess
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortraitGuardScreen(activity: MainActivity) {
    val apps by AppConfigStore.apps.collectAsState(initial = emptyList())
    val hideRecentTask by AppConfigStore.hideRecentTask.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var permissions by remember { mutableStateOf(activity.permissionState()) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(750)
            permissions = activity.permissionState()
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = { CenterAlignedTopAppBar(title = { Text("竖屏守护") }) },
            floatingActionButton = {
                FloatingActionButton(onClick = { showPicker = true }) {
                    Icon(Icons.Default.Add, contentDescription = "添加应用")
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "进入已启用的应用后固定正向竖屏，点击游戏内悬浮按钮手动恢复自动旋转。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                item { PermissionsCard(permissions, activity) }
                item {
                    RecentTaskSetting(
                        checked = hideRecentTask,
                        onCheckedChange = { hide ->
                            scope.launch {
                                AppConfigStore.setHideRecentTask(hide)
                                activity.setHideFromRecentTasks(hide)
                            }
                        },
                    )
                }
                item {
                    Text("已配置应用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                }
                if (apps.isEmpty()) {
                    item {
                        Text(
                            "还没有应用。点击右下角加号选择游戏或其他应用。",
                            modifier = Modifier.padding(vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    GuardedAppCard(
                        app = app,
                        canEnable = permissions.canEnableApps,
                        onEnabledChanged = { enabled -> scope.launch { AppConfigStore.setEnabled(app.packageName, enabled) } },
                        onHideFloatingChanged = { hide ->
                            scope.launch { AppConfigStore.setHideFloatingAfterUnlock(app.packageName, hide) }
                        },
                        onDelete = { scope.launch { AppConfigStore.remove(app.packageName) } },
                    )
                }
                item { Spacer(Modifier.height(88.dp)) }
            }
        }
    }

    if (showPicker) {
        AppPickerDialog(
            activity = activity,
            configuredPackages = apps.map { it.packageName }.toSet(),
            onDismiss = { showPicker = false },
            onSelected = { installed ->
                scope.launch { AppConfigStore.addOrUpdate(GuardedApp(installed.packageName, installed.label)) }
                showPicker = false
            },
        )
    }
}

@Composable
private fun RecentTaskSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("从最近任务中隐藏", style = MaterialTheme.typography.titleMedium)
                Text("开启后设置页不会出现在最近任务中，应用不会自动退出；再次打开仍可修改。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun PermissionsCard(state: PermissionState, context: Context) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("所需权限", style = MaterialTheme.typography.titleMedium)
            PermissionRow("无障碍服务", state.accessibility) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            PermissionRow("显示在其他应用上层", state.overlay) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
            PermissionRow("修改系统设置", state.writeSettings) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            PermissionRow("使用情况访问", state.usageAccess) {
                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            PermissionRow("忽略电池优化", state.ignoringBatteryOptimizations) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
            if (!state.canEnableApps) {
                Text("四个权限均允许后，才能启用应用规则；使用情况访问用于识别游戏是否已从最近任务划掉。", style = MaterialTheme.typography.bodySmall)
            }
            if (!state.ignoringBatteryOptimizations) {
                Text("建议允许后台运行，避免省电策略停止无障碍服务。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Text(if (granted) "已允许" else "未允许", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(8.dp))
        FilledTonalButton(onClick = onOpenSettings) { Text(if (granted) "查看" else "授权") }
    }
}

@Composable
private fun GuardedAppCard(
    app: GuardedApp,
    canEnable: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    onHideFloatingChanged: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.titleMedium)
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                Text("进入后手动解除", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("解除后隐藏悬浮按钮", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(
                        checked = app.hideFloatingAfterUnlock,
                        onCheckedChange = onHideFloatingChanged,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Switch(
                    checked = app.enabled,
                    enabled = canEnable || app.enabled,
                    onCheckedChange = onEnabledChanged,
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除 ${app.label}")
                }
            }
        }
    }
}

@Composable
private fun AppPickerDialog(
    activity: MainActivity,
    configuredPackages: Set<String>,
    onDismiss: () -> Unit,
    onSelected: (InstalledApp) -> Unit,
) {
    val allApps by produceState<List<InstalledApp>?>(initialValue = null, activity) {
        value = withContext(Dispatchers.Default) { loadLaunchableApps(activity) }
    }
    var query by remember { mutableStateOf("") }
    val availableApps = remember(allApps, configuredPackages, query) {
        allApps.orEmpty()
            .asSequence()
            .filterNot { it.packageName in configuredPackages }
            .filter { app ->
                query.isBlank() ||
                    app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .toList()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("搜索应用") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                )
                when {
                    allApps == null -> Text("正在读取应用列表...")
                    availableApps.isEmpty() -> Text("没有匹配的应用")
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(availableApps, key = { it.packageName }) { app ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onSelected(app) }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.packageName)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1)
                                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AppIcon(packageName: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    val loadedBitmap = bitmap
    loadedBitmap?.let {
        Image(bitmap = it, contentDescription = null, modifier = Modifier.width(44.dp).height(44.dp))
    } ?: Box(Modifier.width(44.dp).height(44.dp))
}

private fun Context.permissionState(): PermissionState = PermissionState(
    accessibility = isPortraitAccessibilityServiceEnabled(),
    overlay = Settings.canDrawOverlays(this),
    writeSettings = Settings.System.canWrite(this),
    usageAccess = hasUsageStatsAccess(),
    ignoringBatteryOptimizations = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName),
)

private fun Context.hasUsageStatsAccess(): Boolean {
    val appOps = getSystemService(AppOpsManager::class.java)
    return appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        packageName,
    ) == AppOpsManager.MODE_ALLOWED
}

private fun Context.isPortraitAccessibilityServiceEnabled(): Boolean {
    val component = ComponentName(this, PortraitAccessibilityService::class.java)
    val manager = getSystemService(AccessibilityManager::class.java)
    return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { service -> ComponentName.unflattenFromString(service.id) == component }
}
