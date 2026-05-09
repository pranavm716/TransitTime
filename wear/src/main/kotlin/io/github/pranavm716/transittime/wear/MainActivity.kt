package io.github.pranavm716.transittime.wear

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.wear.widget.BoxInsetLayout

class MainActivity : ComponentActivity() {

    private lateinit var notifButton: Button
    private lateinit var permissionManager: PermissionManager
    private var isOnboarding = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionManager = PermissionManager(this)
        isOnboarding = intent.getBooleanExtra("EXTRA_FROM_TILE_ADD", false)

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(40), 0, dp(40))
        }
        scrollView.addView(content)

        content.addView(TextView(this).apply {
            text = "TransitTime"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        content.addView(TextView(this).apply {
            text = "PERMISSIONS"
            textSize = 9f
            letterSpacing = 0.1f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(150, 150, 150))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        })

        notifButton = Button(this).apply {
            textSize = 10f
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (!permissionManager.hasNotificationPermission()) {
                    permissionManager.requestNotificationPermission(requestPermissionLauncher)
                } else {
                    Toast.makeText(this@MainActivity, "Notifications are ON", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }
        content.addView(notifButton, matchWidth(bottomDp = 8, heightDp = 40))

        val root = BoxInsetLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            scrollView, BoxInsetLayout.LayoutParams(
                BoxInsetLayout.LayoutParams.MATCH_PARENT,
                BoxInsetLayout.LayoutParams.MATCH_PARENT
            ).apply {
                boxedEdges = BoxInsetLayout.LayoutParams.BOX_LEFT or
                        BoxInsetLayout.LayoutParams.BOX_RIGHT
                gravity = Gravity.CENTER
            })

        setContentView(root)

        permissionManager.requestPermissionsOnFirstRun(
            requestPermissionLauncher,
            force = isOnboarding
        )
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun updateUI() {
        val hasNotif = permissionManager.hasNotificationPermission()
        notifButton.text = if (hasNotif) "✓  Notifications On" else "Enable Notifications"
        notifButton.backgroundTintList = null
        notifButton.background =
            if (hasNotif) pillFilled(Color.rgb(35, 134, 54)) else pillFilled(Color.rgb(180, 100, 0))
        notifButton.setTextColor(if (hasNotif) Color.rgb(200, 255, 200) else Color.WHITE)
        notifButton.alpha = if (hasNotif) 0.8f else 1f
    }

    private fun pillFilled(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 100f
        setColor(color)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchWidth(bottomDp: Int = 0, heightDp: Int = -2) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        if (heightDp == -2) LinearLayout.LayoutParams.WRAP_CONTENT else dp(heightDp)
    ).apply { setMargins(0, 0, 0, dp(bottomDp)) }
}
