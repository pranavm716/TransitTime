package io.github.pranavm716.transittime.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

class PermissionManager(private val context: Context) {

    fun hasNotificationPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 36) { // Android 16
            permissions.add("android.permission.POST_PROMOTED_NOTIFICATIONS")
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestNotificationPermissions(launcher: ActivityResultLauncher<Array<String>>) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 36) { // Android 16
            permissions.add("android.permission.POST_PROMOTED_NOTIFICATIONS")
        }
        if (permissions.isNotEmpty()) {
            launcher.launch(permissions.toTypedArray())
        }
    }

    /**
     * Requests notification permissions once on first run.
     * Returns true if the dialog was launched (so callers can chain battery prompt in the result
     * callback), false if it was a no-op (caller should prompt battery immediately instead).
     */
    fun requestPermissionsOnFirstRun(launcher: ActivityResultLauncher<Array<String>>): Boolean {
        val prefs = context.getSharedPreferences("transit_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("has_prompted_permissions", false)) {
            prefs.edit().putBoolean("has_prompted_permissions", true).apply()
            requestNotificationPermissions(launcher)
            return true
        }
        return false
    }

    /** Prompts to disable battery optimization once on first run, if not already ignored. */
    fun requestBatteryOptimizationOnFirstRun(activity: Activity) {
        if (isBatteryOptimizationIgnored()) return
        val prefs = context.getSharedPreferences("transit_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("has_prompted_battery", false)) {
            prefs.edit().putBoolean("has_prompted_battery", true).apply()
            try { activity.startActivity(createBatteryOptimizationIntent()) } catch (e: Exception) { }
        }
    }

    fun isBatteryOptimizationIgnored(): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun createBatteryOptimizationIntent(): Intent {
        return try {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        } catch (e: Exception) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }
}
