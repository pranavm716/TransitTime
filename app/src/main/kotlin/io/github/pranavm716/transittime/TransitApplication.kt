package io.github.pranavm716.transittime

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.worker.FetchWorker
import java.util.concurrent.TimeUnit

class TransitApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleFetchWorker()
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
    }
}