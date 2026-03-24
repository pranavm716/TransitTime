package io.github.pranavm716.transittime

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.receiver.ScreenUnlockReceiver
import io.github.pranavm716.transittime.worker.FetchWorker
import java.util.concurrent.TimeUnit

class TransitApplication : Application() {

    private val screenUnlockReceiver = ScreenUnlockReceiver()

    override fun onCreate() {
        super.onCreate()
        scheduleFetchWorker()
        registerReceiver(
            screenUnlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT)
        )
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