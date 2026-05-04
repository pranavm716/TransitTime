package io.github.pranavm716.transittime

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.model.WatchStopConfig
import io.github.pranavm716.transittime.wear.WearDataPusher
import io.github.pranavm716.transittime.worker.FetchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class TransitApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleFetchWorker()
        pushStopConfigsToWatch()
    }

    private fun pushStopConfigsToWatch() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val configs = TransitDatabase.getInstance(this@TransitApplication)
                    .widgetConfigDao()
                    .getAllConfigs()
                    .map { WatchStopConfig(it.stopId, it.stopName, it.agency, it.delayColorMode) }
                WearDataPusher(this@TransitApplication).pushStopConfigs(configs)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun scheduleFetchWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<FetchWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            FETCH_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val FETCH_WORK_NAME = "transit_fetch"
        const val FETCH_WORK_NAME_MANUAL = "transit_fetch_manual"
    }
}