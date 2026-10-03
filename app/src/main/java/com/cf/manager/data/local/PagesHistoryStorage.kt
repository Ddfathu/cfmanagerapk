package com.cf.manager.data.local

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PagesDeploySnapshot(
    val projectName: String,
    val htmlContent: String,
    val workerScript: String,
    val compatDate: String = "2024-01-01",
    val enableNodeCompat: Boolean = true,
    val deployedAt: String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
)

class PagesHistoryStorage(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("cf_pages_history_pref", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveSnapshot(snapshot: PagesDeploySnapshot) {
        val map = getAllSnapshots().toMutableMap()
        map[snapshot.projectName] = snapshot
        prefs.edit().putString("pages_snapshots_map", gson.toJson(map)).apply()
    }

    fun getSnapshot(projectName: String): PagesDeploySnapshot? {
        return getAllSnapshots()[projectName]
    }

    fun getAllSnapshots(): Map<String, PagesDeploySnapshot> {
        val json = prefs.getString("pages_snapshots_map", null) ?: return emptyMap()
        val type = object : TypeToken<Map<String, PagesDeploySnapshot>>() {}.type
        return try {
            gson.fromJson(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
