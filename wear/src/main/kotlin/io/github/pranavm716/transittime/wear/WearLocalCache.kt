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

    fun saveSnapshot(snapshot: TileSnapshot) {
        prefs.edit {
            putString(snapshotKey(snapshot.stopId), gson.toJson(snapshot))
            putLong(fetchedAtKey(snapshot.stopId), snapshot.fetchedAt)
        }
    }

    fun getSnapshot(stopId: String): TileSnapshot? {
        val json = prefs.getString(snapshotKey(stopId), null)
        val result = if (json != null) gson.fromJson(json, TileSnapshot::class.java) else null
        Log.d(TAG, "getSnapshot: stopId=$stopId, ${if (result != null) "hit (fetchedAt=${result.fetchedAt}, rows=${result.rows.size})" else "miss"}")
        return result
    }

    fun getFetchedAt(stopId: String): Long = prefs.getLong(fetchedAtKey(stopId), 0L)

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

    private fun snapshotKey(stopId: String) = "snapshot_$stopId"
    private fun fetchedAtKey(stopId: String) = "fetched_at_$stopId"

    companion object {
        private const val TAG = "TransitWear"
        private const val PREFS_NAME = "wear_local_cache"
        private const val KEY_STOP_IDS = "stop_ids"
        private const val KEY_STOP_IDS_PUSHED_AT = "stop_ids_pushed_at"
        private const val KEY_CURRENT_INDEX = "current_index"
        private const val KEY_CURRENT_STOP_ID = "current_stop_id"
    }
}
