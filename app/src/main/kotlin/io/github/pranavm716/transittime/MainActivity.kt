package io.github.pranavm716.transittime

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.pranavm716.transittime.service.GoModeNotificationService
import io.github.pranavm716.transittime.util.PermissionManager
import io.github.pranavm716.transittime.widget.TransitWidget

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var stopButton: Button
    private lateinit var batteryButton: Button
    private lateinit var permissionManager: PermissionManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = "TransitTime"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.BLACK)
        }
        root.addView(title)

        statusText = TextView(this).apply {
            textSize = 18f
            setPadding(0, 48, 0, 16)
        }
        root.addView(statusText)

        stopButton = Button(this).apply {
            text = "Stop Go Mode"
            visibility = View.GONE
            setOnClickListener {
                val goModeManager = GoModeManager(this@MainActivity)
                goModeManager.goModeExpiresAt = 0
                GoModeNotificationService.update(this@MainActivity)
                
                val widgetId = goModeManager.goModeWidgetId
                if (widgetId != -1) {
                    val intent = Intent(TransitWidget.ACTION_REFRESH).apply {
                        setPackage(packageName)
                        putExtra(TransitWidget.EXTRA_WIDGET_ID, widgetId)
                    }
                    sendBroadcast(intent)
                }
                updateUI()
            }
        }
        root.addView(stopButton)

        val sectionTitle = TextView(this).apply {
            text = "Optimization & Permissions"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 64, 0, 16)
            setTextColor(Color.BLACK)
        }
        root.addView(sectionTitle)

        val description = TextView(this).apply {
            text = "For reliable background updates and real-time Go Mode notifications, please ensure the following are enabled:"
            textSize = 14f
            setPadding(0, 0, 0, 32)
            setTextColor(Color.DKGRAY)
        }
        root.addView(description)

        val permissionButton = Button(this).apply {
            text = "Grant Notification Permissions"
            setOnClickListener {
                if (permissionManager.hasNotificationPermissions()) {
                    Toast.makeText(this@MainActivity, "Permissions already granted", Toast.LENGTH_SHORT).show()
                } else {
                    permissionManager.requestNotificationPermissions(requestPermissionLauncher)
                }
            }
        }
        root.addView(permissionButton)

        batteryButton = Button(this).apply {
            text = "Disable Battery Optimization"
            setOnClickListener {
                startActivity(permissionManager.createBatteryOptimizationIntent())
            }
        }
        root.addView(batteryButton)

        val helpText = TextView(this).apply {
            text = "\nTip: Add a TransitTime widget to your home screen to start tracking departures."
            textSize = 14f
            alpha = 0.7f
            setTextColor(Color.GRAY)
        }
        root.addView(helpText)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val goModeManager = GoModeManager(this)
        if (goModeManager.isGoModeActive) {
            statusText.text = "Go Mode is ACTIVE"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            stopButton.visibility = View.VISIBLE
        } else {
            statusText.text = "Go Mode is Inactive"
            statusText.setTextColor(Color.GRAY)
            stopButton.visibility = View.GONE
        }

        if (permissionManager.isBatteryOptimizationIgnored()) {
            batteryButton.text = "Battery: Unrestricted (Good)"
            batteryButton.isEnabled = false
        } else {
            batteryButton.text = "Set Battery to Unrestricted"
            batteryButton.isEnabled = true
        }
    }
}
