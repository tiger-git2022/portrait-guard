package com.personal.portraitguard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

data class InstalledApp(
    val packageName: String,
    val label: String,
)

fun loadLaunchableApps(context: Context): List<InstalledApp> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        .map { info ->
            val applicationInfo = info.activityInfo.applicationInfo
            InstalledApp(
                packageName = applicationInfo.packageName,
                label = applicationInfo.loadLabel(context.packageManager).toString(),
            )
        }
        .filter { it.packageName != context.packageName }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}
