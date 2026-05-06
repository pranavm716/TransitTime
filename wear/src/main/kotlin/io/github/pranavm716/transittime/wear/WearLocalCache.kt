package io.github.pranavm716.transittime.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.pranavm716.transittime.model.TileSnapshot

class WearLocalCache(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveSnapshot(snapshot: TileSnapshot, pushedAt: Long) {
        prefs.edit {
            putString(snapshotKey(snapshot.stopId), gson.toJson(snapshot))
            putLong(pushedAtKey(snapshot.stopId), pushedAt)
        }
    }

    fun getSnapshot(stopId: String): TileSnapshot? {
        val json = prefs.getString(snapshotKey(stopId), null)
        val result = if (json != null) gson.fromJson(json, TileSnapshot::class.java) else null
        Log.d(TAG, "getSnapshot: stopId=$stopId, ${if (result != null) "hit (fetchedAt=${result.fetchedAt}, rows=${result.rows.size})" else "miss"}")
        return result
    }

    fun getPushedAt(stopId: String): Long = prefs.getLong(pushedAtKey(stopId), 0L)

    fun saveStopIds(stopIds: List<String>, pushedAt: Long) {
        prefs.edit {
            putString(KEY_STOP_IDS, gson.toJson(stopIds))
            putLong(KEY_STOP_IDS_PUSHED_AT, pushedAt)
        }
    }

    fun getStopIds(): List<String> {
        val json = prefs.getString(KEY_STOP_IDS, null)
        val result: List<String> = if (json != null) {
            val type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } else emptyList()
        Log.d(TAG, "getStopIds: ${if (result.isNotEmpty()) "hit, stopIds=$result" else "miss"}")
        return result
    }

    fun getStopIdsPushedAt(): Long = prefs.getLong(KEY_STOP_IDS_PUSHED_AT, 0L)

    fun saveCurrentIndex(index: Int) = prefs.edit { putInt(KEY_CURRENT_INDEX, index) }
    fun getCurrentIndex(): Int = prefs.getInt(KEY_CURRENT_INDEX, 0)

    fun saveCurrentStopId(stopId: String) = prefs.edit { putString(KEY_CURRENT_STOP_ID, stopId) }
    fun getCurrentStopId(): String? = prefs.getString(KEY_CURRENT_STOP_ID, null)

    fun setRefreshing(stopId: String, refreshing: Boolean) {
        prefs.edit {
            if (refreshing) {
                putLong(refreshingKey(stopId), System.currentTimeMillis())
            } else {
                remove(refreshingKey(stopId))
            }
        }
    }

    fun getRefreshingStartTime(stopId: String): Long = prefs.getLong(refreshingKey(stopId), 0L)

    // null = use snapshot value, true = force green dot, false = force refresh icon
    fun setLocalGoModeOverride(value: Boolean?) {
        prefs.edit {
            if (value == null) remove(KEY_LOCAL_GO_MODE_OVERRIDE)
            else putInt(KEY_LOCAL_GO_MODE_OVERRIDE, if (value) 1 else 0)
        }
    }

    fun getLocalGoModeOverride(): Boolean? {
        val v = prefs.getInt(KEY_LOCAL_GO_MODE_OVERRIDE, -1)
        return if (v == -1) null else v == 1
    }

    private fun snapshotKey(stopId: String) = "snapshot_$stopId"
    private fun pushedAtKey(stopId: String) = "pushed_at_$stopId"
    private fun refreshingKey(stopId: String) = "refreshing_$stopId"

    companion object {
        private const val TAG = "TransitWear"
        private const val PREFS_NAME = "wear_local_cache"
        private const val KEY_STOP_IDS = "stop_ids"
        private const val KEY_STOP_IDS_PUSHED_AT = "stop_ids_pushed_at"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_CURRENT_STOP_ID = "current_stop_id"
        private const val KEY_LOCAL_GO_MODE_OVERRIDE = "local_go_mode_override"
    }
}
