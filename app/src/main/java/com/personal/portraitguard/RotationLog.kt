package com.personal.portraitguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last few rotation-setting events (what the guard saw, wrote and put back), shown in the
 * app so a lost rotation setting can be traced to what happened.
 */
object RotationLog {
    private const val PREFERENCES = "rotation_log"
    private const val KEY_LINES = "lines"
    private const val MAX_LINES = 60

    fun add(context: Context, message: String) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val lines = read(context) + "$time $message"
        preferences.edit().putString(KEY_LINES, lines.takeLast(MAX_LINES).joinToString("\n")).apply()
    }

    fun read(context: Context): List<String> =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(KEY_LINES, null)
            ?.lines()
            ?.filter { it.isNotBlank() }
            .orEmpty()

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY_LINES).apply()
    }
}
