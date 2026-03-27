package io.github.pranavm716.transittime.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.WidgetConfig
import io.github.pranavm716.transittime.transit.AgencyRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TransitWidgetConfig : AppCompatActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedStopId: String? = null
    private var selectedStopName: String? = null
    private var allStops: List<Pair<String, String>> = emptyList()
    private var currentRoutes: Map<String, List<String>> = emptyMap()

    private lateinit var resultsAdapter: ArrayAdapter<String>
    private val checkedHeadsigns = mutableSetOf<String>()
    private var routeAdapter: RouteHeadsignAdapter? = null

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

        val spinner = findViewById<Spinner>(R.id.spinnerAgency)
        val lvResults = findViewById<ListView>(R.id.lvStopResults)
        val elvRoutes = findViewById<ExpandableListView>(R.id.elvRoutes)
        val tvRoutesLabel = findViewById<TextView>(R.id.tvRoutesLabel)

        resultsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvResults.adapter = resultsAdapter

        val agencies = Agency.entries.map { it.name }
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            agencies
        )

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val agency = Agency.entries[position]
                selectedStopId = null
                selectedStopName = null
                currentRoutes = emptyMap()
                checkedHeadsigns.clear()
                findViewById<TextView>(R.id.tvSelectedStop).visibility = View.GONE
                findViewById<EditText>(R.id.etStopSearch).setText("")
                resultsAdapter.clear()
                allStops = emptyList()
                elvRoutes.visibility = View.GONE
                tvRoutesLabel.visibility = View.GONE

                CoroutineScope(Dispatchers.IO).launch {
                    val handler = AgencyRegistry.get(agency)
                    handler.loadStaticData(applicationContext)
                    val stops = handler.getStopNames()
                        .entries
                        .map { Pair(it.key, it.value) }
                        .sortedBy { it.second }
                    withContext(Dispatchers.Main) { allStops = stops }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

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
                    lvResults.tag = filtered
                    lvResults.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        )

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

            checkedHeadsigns.clear()
            CoroutineScope(Dispatchers.IO).launch {
                val agency = Agency.entries[spinner.selectedItemPosition]
                val routes = AgencyRegistry.get(agency).fetchRoutesForStop(selected.first)

                withContext(Dispatchers.Main) {
                    val imm =
                        getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    imm.hideSoftInputFromWindow(
                        findViewById<EditText>(R.id.etStopSearch).windowToken,
                        0
                    )

                    currentRoutes = routes
                    if (routes.isEmpty()) {
                        elvRoutes.visibility = View.GONE
                        tvRoutesLabel.visibility = View.GONE
                        Toast.makeText(
                            this@TransitWidgetConfig,
                            "⚠ No routes available right now. Try configuring at a different time.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        routes.entries.forEach { (routeName, headsigns) ->
                            headsigns.forEach { headsign ->
                                checkedHeadsigns.add("$routeName|$headsign")
                            }
                        }
                        routeAdapter = RouteHeadsignAdapter(
                            this@TransitWidgetConfig,
                            routes,
                            checkedHeadsigns
                        )
                        elvRoutes.setAdapter(routeAdapter)
                        for (i in routes.keys.indices) elvRoutes.expandGroup(i)
                        elvRoutes.visibility = View.VISIBLE
                        tvRoutesLabel.visibility = View.VISIBLE
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val stopId = selectedStopId
            val stopName = selectedStopName
            val maxArrivals = findViewById<EditText>(R.id.etMaxArrivals)
                .text.toString().trim().toIntOrNull() ?: 2
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

            val allRouteHeadsigns = currentRoutes
                .entries.flatMap { (r, hs) -> hs.map { "$r|$it" } }.toSet()
            val filtered = if (checkedHeadsigns == allRouteHeadsigns) emptyList()
            else checkedHeadsigns.map { it.substringAfter("|") }.toList()

            val config = WidgetConfig(
                widgetId = widgetId,
                stopId = stopId,
                stopName = stopName,
                agency = agency,
                filteredHeadsigns = filtered,
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
