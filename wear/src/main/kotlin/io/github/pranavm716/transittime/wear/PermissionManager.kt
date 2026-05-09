package io.github.pranavm716.transittime.wear

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher

class PermissionManager(private val context: Context) {

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun requestNotificationPermission(launcher: ActivityResultLauncher<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestPermissionsOnFirstRun(launcher: ActivityResultLauncher<String>, force: Boolean = false): Boolean {
        if (hasNotificationPermission()) return false
        val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)
        if (force || !prefs.getBoolean("has_prompted_permissions", false)) {
            prefs.edit().putBoolean("has_prompted_permissions", true).apply()
            requestNotificationPermission(launcher)
            return true
        }
        return false
    }
}
