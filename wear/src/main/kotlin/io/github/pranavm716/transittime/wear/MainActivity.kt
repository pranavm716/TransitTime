package io.github.pranavm716.transittime.wear

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.wear.tiles.TileService

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView
    private lateinit var permissionButton: Button
    private lateinit var batteryButton: Button
    private lateinit var stopButton: Button
    private lateinit var permissionManager: PermissionManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(20, 20, 20, 20)
        }

        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        root.addView(statusText)

        permissionButton = Button(this).apply {
            text = "Enable Notifications"
            setOnClickListener {
                permissionManager.requestNotificationPermission(requestPermissionLauncher)
            }
        }
        root.addView(permissionButton)

        batteryButton = Button(this).apply {
            text = "Unrestrict Battery"
            setOnClickListener {
                startActivity(permissionManager.createBatteryOptimizationIntent())
            }
        }
        root.addView(batteryButton)

        stopButton = Button(this).apply {
            text = "Stop Go Mode"
            setOnClickListener {
                val cache = WearLocalCache(this@MainActivity)
                cache.setLocalGoModeOverride(false)
                GoModeNotificationService.update(this@MainActivity)
                TileService.getUpdater(this@MainActivity).requestUpdate(TransitTileService::class.java)
                updateUI()
            }
        }
        root.addView(stopButton)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val cache = WearLocalCache(this)
        val currentStopId = cache.getCurrentStopId()
        val snapshot = currentStopId?.let { cache.getSnapshot(it) }
        val localOverride = cache.getLocalGoModeOverride()
        val isActive = localOverride ?: (snapshot?.goModeActive ?: false)

        statusText.text = if (isActive) {
            "Go Mode: Active\n${snapshot?.stopName ?: "Tracking..."}"
        } else {
            "Go Mode: Inactive\nStart from Tile"
        }

        permissionButton.visibility = if (permissionManager.hasNotificationPermission()) View.GONE else View.VISIBLE
        batteryButton.visibility = if (permissionManager.isBatteryOptimizationIgnored()) View.GONE else View.VISIBLE

        stopButton.visibility = if (isActive) View.VISIBLE else View.GONE
    }
}
