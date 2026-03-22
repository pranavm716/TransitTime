package io.github.pranavm716.transittime.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.WidgetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransitWidgetConfig : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android requires you set the result to CANCELED until the user
        // explicitly confirms — if they back out, no widget gets added
        setResult(RESULT_CANCELED)

        setContentView(R.layout.activity_widget_config)

        // Retrieve the widget ID Android passed in — if missing, bail out
        widgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Populate agency spinner
        val spinner = findViewById<Spinner>(R.id.spinnerAgency)
        val agencies = Agency.entries.map { it.name }
        spinner.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, agencies)

        // Save button
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val stopId = findViewById<EditText>(R.id.etStopId).text.toString().trim()
            val maxArrivals = findViewById<EditText>(R.id.etMaxArrivals).text
                .toString().trim().toIntOrNull() ?: 3
            val agency = Agency.entries[spinner.selectedItemPosition]

            if (stopId.isBlank()) {
                Toast.makeText(this, "Please enter a stop ID", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (maxArrivals !in 1..5) {
                Toast.makeText(this, "Max arrivals must be between 1 and 5", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            val config = WidgetConfig(
                widgetId = widgetId,
                stopId = stopId,
                stopName = stopId,       // placeholder — ideally resolved from static GTFS
                agency = agency,
                filteredHeadsigns = emptyList(),
                maxArrivals = maxArrivals
            )

            CoroutineScope(Dispatchers.IO).launch {
                TransitDatabase.getInstance(applicationContext)
                    .widgetConfigDao()
                    .upsertConfig(config)

                withContext(Dispatchers.Main) {
                    // Trigger an immediate fetch and draw
                    TransitWidget.triggerFetch(applicationContext)
                    TransitWidget.updateWidget(
                        applicationContext,
                        AppWidgetManager.getInstance(applicationContext),
                        widgetId
                    )

                    // Tell Android the widget was configured successfully
                    val resultIntent = Intent().apply {
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    }
                    setResult(RESULT_OK, resultIntent)
                    finish()
                }
            }
        }
    }
}