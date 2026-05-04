package io.github.pranavm716.transittime.wear

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.github.pranavm716.transittime.model.WatchDeparture
import io.github.pranavm716.transittime.model.WatchStopConfig

class WearLocalCache(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveDepartures(stopId: String, departures: List<WatchDeparture>, fetchedAt: Long) {
        prefs.edit {
            putString(departuresKey(stopId), gson.toJson(departures))
            putLong(fetchedAtKey(stopId), fetchedAt)
        }
    }

    fun getDepartures(stopId: String): List<WatchDeparture> {
        val json = prefs.getString(departuresKey(stopId), null) ?: return emptyList()
        val type = object : TypeToken<List<WatchDeparture>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun getFetchedAt(stopId: String): Long = prefs.getLong(fetchedAtKey(stopId), 0L)

    fun saveStopConfigs(configs: List<WatchStopConfig>) {
        Log.d(TAG, "saveStopConfigs: writing ${configs.size} configs: ${configs.map { it.stopName }}")
        prefs.edit { putString(KEY_STOP_CONFIGS, gson.toJson(configs)) }
    }

    fun getStopConfigs(): List<WatchStopConfig> {
        val json = prefs.getString(KEY_STOP_CONFIGS, null) ?: run {
            Log.d(TAG, "getStopConfigs: cache miss (no value stored)")
            return emptyList()
        }
        val type = object : TypeToken<List<WatchStopConfig>>() {}.type
        val result = gson.fromJson<List<WatchStopConfig>>(json, type) ?: emptyList()
        Log.d(TAG, "getStopConfigs: cache hit, ${result.size} configs: ${result.map { it.stopName }}")
        return result
    }

    fun saveGoModeExpiresAt(expiresAt: Long) {
        prefs.edit { putLong(KEY_GO_MODE_EXPIRES_AT, expiresAt) }
    }

    fun getGoModeExpiresAt(): Long = prefs.getLong(KEY_GO_MODE_EXPIRES_AT, 0L)

    private fun departuresKey(stopId: String) = "departures_$stopId"
    private fun fetchedAtKey(stopId: String) = "fetched_at_$stopId"

    companion object {
        private const val TAG = "TransitWear"
        private const val PREFS_NAME = "wear_local_cache"
        private const val KEY_STOP_CONFIGS = "stop_configs"
        private const val KEY_GO_MODE_EXPIRES_AT = "go_mode_expires_at"
    }
}
