package io.github.pranavm716.transittime

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.pranavm716.transittime.util.PermissionManager

class MainActivity : AppCompatActivity() {

    private lateinit var notifButton: Button
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

        val bgMain = ContextCompat.getColor(this, R.color.bg_main)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.text_secondary)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgMain)
            setPadding(dp(24), dp(40), dp(24), dp(40))
        }

        root.addView(TextView(this).apply {
            text = "TransitTime"
            textSize = 28f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textPrimary)
            setPadding(0, 0, 0, dp(32))
        })

        root.addView(sectionLabel("PERMISSIONS", textSecondary))

        notifButton = Button(this).apply {
            textSize = 14f
            setTextColor(textPrimary)
            setOnClickListener {
                if (!permissionManager.hasNotificationPermissions()) {
                    permissionManager.requestNotificationPermissions(requestPermissionLauncher)
                } else {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    }
                    try {
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    }
                }
            }
        }
        root.addView(notifButton, matchWidth(bottomDp = 12))

        batteryButton = Button(this).apply {
            textSize = 14f
            setTextColor(textPrimary)
            setOnClickListener {
                startActivity(permissionManager.createBatteryOptimizationIntent())
            }
        }
        root.addView(batteryButton, matchWidth())

        setContentView(ScrollView(this).apply {
            setBackgroundColor(bgMain)
            addView(root)
        })

        val launchedNotif = permissionManager.requestPermissionsOnFirstRun(requestPermissionLauncher)
        if (!launchedNotif) permissionManager.requestBatteryOptimizationOnFirstRun(this)
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val accent = ContextCompat.getColor(this, R.color.accent_color)
        val bgContainer = ContextCompat.getColor(this, R.color.bg_container)
        val textPrimary = ContextCompat.getColor(this, R.color.text_primary)

        val hasNotif = permissionManager.hasNotificationPermissions()
        notifButton.text = if (hasNotif) "✓  Notifications Granted" else "Grant Notification Permissions"
        notifButton.backgroundTintList = null
        notifButton.background = if (hasNotif) outlined(bgContainer, accent) else filled(accent)
        notifButton.setTextColor(if (hasNotif) accent else textPrimary)
        notifButton.alpha = if (hasNotif) 0.7f else 1f

        val hasBattery = permissionManager.isBatteryOptimizationIgnored()
        batteryButton.text = if (hasBattery) "✓  Battery Unrestricted" else "Set Battery to Unrestricted"
        batteryButton.backgroundTintList = null
        batteryButton.background = if (hasBattery) outlined(bgContainer, accent) else filled(accent)
        batteryButton.setTextColor(if (hasBattery) accent else textPrimary)
        batteryButton.alpha = if (hasBattery) 0.7f else 1f
    }

    private fun sectionLabel(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 11f
        letterSpacing = 0.12f
        setTypeface(null, Typeface.BOLD)
        setTextColor(color)
        setPadding(0, dp(8), 0, dp(12))
    }

    private fun filled(color: Int) = ripple(GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(color)
    })

    private fun outlined(fill: Int, stroke: Int) = ripple(GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    })

    private fun ripple(content: Drawable) = RippleDrawable(
        ColorStateList.valueOf(0x20FFFFFF),
        content,
        content
    )

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(topDp: Int = 0, bottomDp: Int = 0) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(topDp), 0, dp(bottomDp)) }
}
