package io.github.pranavm716.transittime.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.api.bart.BartParser
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.WidgetConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransitWidgetConfig : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedStopId: String? = null
    private var selectedStopName: String? = null

    // Full stop list loaded from static GTFS, filtered as user types
    private var allStops: List<Pair<String, String>> = emptyList() // (baseId, name)
    private lateinit var resultsAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        widgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        // Agency spinner
        val spinner = findViewById<Spinner>(R.id.spinnerAgency)
        val agencies = Agency.entries.map { it.name }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            agencies
        )

        // Results list setup
        val lvResults = findViewById<ListView>(R.id.lvStopResults)
        resultsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvResults.adapter = resultsAdapter

        // Load stops when agency changes
        spinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>,
                    view: android.view.View?,
                    position: Int,
                    id: Long
                ) {
                    val agency = Agency.entries[position]
                    selectedStopId = null
                    selectedStopName = null
                    findViewById<TextView>(R.id.tvSelectedStop).visibility = View.GONE
                    findViewById<EditText>(R.id.etStopSearch).setText("")
                    resultsAdapter.clear()
                    allStops = emptyList()

                    CoroutineScope(Dispatchers.IO).launch {
                        when (agency) {
                            Agency.BART -> {
                                BartParser.loadStaticGtfs(applicationContext)
                                val stops = BartParser.getStopNames()
                                    .entries
                                    .map { Pair(it.key, it.value) }
                                    .sortedBy { it.second }
                                withContext(Dispatchers.Main) { allStops = stops }
                            }

                            Agency.MUNI_METRO, Agency.MUNI_BUS -> {
                                // TODO: load MUNI stops
                            }
                        }
                    }
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }

        // Filter results as user types
        findViewById<EditText>(R.id.etStopSearch).addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int
                ) {
                }

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val query = s.toString().trim().lowercase()
                    if (query.isEmpty()) {
                        lvResults.visibility = View.GONE
                        resultsAdapter.clear()
                        return
                    }
                    val filtered = allStops.filter { it.second.lowercase().contains(query) }
                    resultsAdapter.clear()
                    resultsAdapter.addAll(filtered.map { it.second })
                    lvResults.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

                    // Store filtered list so tap knows which id to select
                    lvResults.tag = filtered
                }
            }
        )

        // Handle stop selection from list
        lvResults.setOnItemClickListener { _, _, position, _ ->
            @Suppress("UNCHECKED_CAST")
            val filtered =
                lvResults.tag as? List<Pair<String, String>> ?: return@setOnItemClickListener
            val selected = filtered[position]
            selectedStopId = selected.first
            selectedStopName = selected.second

            findViewById<EditText>(R.id.etStopSearch).setText(selected.second)
            lvResults.visibility = View.GONE

            val tvSelected = findViewById<TextView>(R.id.tvSelectedStop)
            tvSelected.text = "Selected: ${selected.second} (${selected.first})"
            tvSelected.visibility = View.VISIBLE
        }

        // Save
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val stopId = selectedStopId
            val stopName = selectedStopName
            val maxArrivals = findViewById<EditText>(R.id.etMaxArrivals)
                .text.toString().trim().toIntOrNull() ?: 3
            val agency = Agency.entries[spinner.selectedItemPosition]

            if (stopId == null || stopName == null) {
                Toast.makeText(this, "Please select a stop", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (maxArrivals !in 1..3) {
                Toast.makeText(this, "Max arrivals must be between 1 and 3", Toast.LENGTH_SHORT)
                    .show()
                return@setOnClickListener
            }

            val config = WidgetConfig(
                widgetId = widgetId,
                stopId = stopId,
                stopName = stopName,
                agency = agency,
                filteredHeadsigns = emptyList(),
                maxArrivals = maxArrivals
            )

            CoroutineScope(Dispatchers.IO).launch {
                TransitDatabase.getInstance(applicationContext)
                    .widgetConfigDao()
                    .upsertConfig(config)

                withContext(Dispatchers.Main) {
                    TransitWidget.triggerFetch(applicationContext)
                    TransitWidget.updateWidget(
                        applicationContext,
                        AppWidgetManager.getInstance(applicationContext),
                        widgetId
                    )
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