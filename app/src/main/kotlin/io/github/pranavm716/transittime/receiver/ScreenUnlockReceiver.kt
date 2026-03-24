package io.github.pranavm716.transittime.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import io.github.pranavm716.transittime.worker.FetchWorker

class ScreenUnlockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_PRESENT) return

        // Trigger a fresh fetch
        val request = OneTimeWorkRequestBuilder<FetchWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "transit_fetch_screen_on",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}