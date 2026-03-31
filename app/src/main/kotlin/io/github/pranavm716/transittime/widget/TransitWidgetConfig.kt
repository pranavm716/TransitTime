package io.github.pranavm716.transittime.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ExpandableListView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import io.github.pranavm716.transittime.R
import io.github.pranavm716.transittime.data.db.TransitDatabase
import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.data.model.DelayColorMode
import io.github.pranavm716.transittime.data.model.DisplayMode
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
    private var existingConfig: WidgetConfig? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        setContentView(R.layout.activity_widget_config)

        val scrollView = findViewById<NestedScrollView>(R.id.nestedScrollView)
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val sysInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                top = sysInsets.top,
                bottom = maxOf(imeInsets.bottom, sysInsets.bottom)
            )
            
            if (imeInsets.bottom > 0) {
                val focusedView = currentFocus
                if (focusedView != null) {
                    v.post {
                        val rect = Rect()
                        focusedView.getDrawingRect(rect)
                        (v as NestedScrollView).offsetDescendantRectToMyCoords(focusedView, rect)
                        v.smoothScrollTo(0, rect.top - 100)
                    }
                }
            }
            insets
        }

        widgetId = intent.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val spinner = findViewById<Spinner>(R.id.spinnerAgency)
        val flStopResults = findViewById<FrameLayout>(R.id.flStopResultsContainer)
        val lvResults = findViewById<ListView>(R.id.lvStopResults)
        val tvNoResults = findViewById<TextView>(R.id.tvNoResults)
        val flRoutes = findViewById<FrameLayout>(R.id.flRoutesContainer)
        val elvRoutes = findViewById<ExpandableListView>(R.id.elvRoutes)
        val tvRoutesLabel = findViewById<TextView>(R.id.tvRoutesLabel)
        val etStopSearch = findViewById<EditText>(R.id.etStopSearch)
        val btnClearSearch = findViewById<ImageButton>(R.id.btnClearSearch)
        val tvSelectedStop = findViewById<TextView>(R.id.tvSelectedStop)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val rgDisplayMode = findViewById<RadioGroup>(R.id.rgDisplayMode)
        val rbRelative = findViewById<RadioButton>(R.id.rbRelative)
        val rbAbsolute = findViewById<RadioButton>(R.id.rbAbsolute)
        val rbHybrid = findViewById<RadioButton>(R.id.rbHybrid)
        val llHybridThresholdRow = findViewById<LinearLayout>(R.id.llHybridThresholdRow)
        val etHybridThreshold = findViewById<EditText>(R.id.etHybridThreshold)
        val etMaxArrivals = findViewById<EditText>(R.id.etMaxArrivals)
        val rgDelayInfoMode = findViewById<RadioGroup>(R.id.rgDelayInfoMode)
        val rbNoDelay = findViewById<RadioButton>(R.id.rbNoDelay)
        val rbFlatDelay = findViewById<RadioButton>(R.id.rbFlatDelay)

        findViewById<TextView>(R.id.tvRelativeDesc).setOnClickListener { rbRelative.isChecked = true }
        findViewById<TextView>(R.id.tvAbsoluteDesc).setOnClickListener { rbAbsolute.isChecked = true }
        llHybridThresholdRow.setOnClickListener { rbHybrid.isChecked = true }
        etHybridThreshold.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) rbHybrid.isChecked = true }

        resultsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        lvResults.adapter = resultsAdapter

        etStopSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase()
                btnClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                
                val filtered = if (query.isEmpty()) allStops
                else allStops.filter { it.second.lowercase().contains(query) }
                resultsAdapter.clear()
                resultsAdapter.addAll(filtered.map { it.second })
                lvResults.tag = filtered
                val hasStopsLoaded = allStops.isNotEmpty()
                flStopResults.visibility = if (hasStopsLoaded) View.VISIBLE else View.GONE
                lvResults.visibility = if (hasStopsLoaded && filtered.isNotEmpty()) View.VISIBLE else View.GONE
                tvNoResults.visibility = if (hasStopsLoaded && filtered.isEmpty()) View.VISIBLE else View.GONE
                if (hasStopsLoaded) {
                    flRoutes.visibility = View.GONE
                    tvRoutesLabel.visibility = View.GONE
                    tvSelectedStop.visibility = View.GONE
                }
            }
        })

        btnClearSearch.setOnClickListener {
            etStopSearch.setText("")
        }

        lvResults.setOnItemClickListener { _, _, position, _ ->
            @Suppress("UNCHECKED_CAST")
            val filtered = lvResults.tag as? List<Pair<String, String>> ?: return@setOnItemClickListener
            val selected = filtered[position]
            selectedStopId = selected.first
            selectedStopName = selected.second

            etStopSearch.setText(selected.second)
            flStopResults.visibility = View.GONE

            tvSelectedStop.text = "Selected: ${selected.second} (${selected.first})"
            tvSelectedStop.visibility = View.VISIBLE

            fetchRoutes(selected.first, spinner, flRoutes, elvRoutes, tvRoutesLabel, etStopSearch)
        }

        CoroutineScope(Dispatchers.IO).launch {
            existingConfig = TransitDatabase.getInstance(applicationContext)
                .widgetConfigDao()
                .getConfig(widgetId)

            withContext(Dispatchers.Main) {
                val agencies = Agency.entries.map { it.name }
                spinner.adapter = ArrayAdapter(
                    this@TransitWidgetConfig,
                    android.R.layout.simple_spinner_dropdown_item,
                    agencies
                )

                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                        val agency = Agency.entries[position]
                        selectedStopId = null
                        selectedStopName = null
                        currentRoutes = emptyMap()
                        checkedHeadsigns.clear()
                        tvSelectedStop.visibility = View.GONE
                        etStopSearch.setText("")
                        resultsAdapter.clear()
                        allStops = emptyList()
                        flRoutes.visibility = View.GONE
                        tvRoutesLabel.visibility = View.GONE
                        flStopResults.visibility = View.GONE

                        CoroutineScope(Dispatchers.IO).launch {
                            val handler = AgencyRegistry.get(agency)
                            handler.loadStaticData(applicationContext)
                            val stops = handler.getStopNames()
                                .entries
                                .map { Pair(it.key, it.value) }
                                .sortedBy { it.second }
                            withContext(Dispatchers.Main) {
                                allStops = stops
                                resultsAdapter.clear()
                                resultsAdapter.addAll(stops.map { it.second })
                                lvResults.tag = stops
                                flStopResults.visibility = if (stops.isEmpty()) View.GONE else View.VISIBLE
                                lvResults.visibility = if (stops.isEmpty()) View.GONE else View.VISIBLE
                                tvNoResults.visibility = View.GONE

                                val config = existingConfig
                                if (config != null && Agency.entries[position] == config.agency) {
                                    selectedStopId = config.stopId
                                    selectedStopName = config.stopName
                                    etStopSearch.setText(config.stopName)
                                    flStopResults.visibility = View.GONE
                                    tvSelectedStop.text = "Selected: ${config.stopName} (${config.stopId})"
                                    tvSelectedStop.visibility = View.VISIBLE
                                    fetchRoutes(config.stopId, spinner, flRoutes, elvRoutes, tvRoutesLabel, etStopSearch, config)
                                }
                            }
                        }
                    }

                    override fun onNothingSelected(parent: AdapterView<*>) {}
                }

                existingConfig?.let { config ->
                    spinner.setSelection(config.agency.ordinal)
                    etMaxArrivals.setText(config.maxArrivals.toString())
                    etHybridThreshold.setText(config.hybridThresholdMinutes.toString())
                    when (config.displayMode) {
                        DisplayMode.ABSOLUTE -> rbAbsolute.isChecked = true
                        DisplayMode.HYBRID -> rbHybrid.isChecked = true
                        DisplayMode.RELATIVE -> rbRelative.isChecked = true
                    }
                    when (config.delayColorMode) {
                        DelayColorMode.NONE -> rbNoDelay.isChecked = true
                        DelayColorMode.FLAT -> rbFlatDelay.isChecked = true
                        DelayColorMode.GRADIENT -> { /* default checked in layout */ }
                    }
                    btnSave.setText(R.string.save_changes)
                }
            }
        }

        btnSave.setOnClickListener {
            val stopId = selectedStopId
            val stopName = selectedStopName
            val maxArrivals = etMaxArrivals.text.toString().trim().toIntOrNull() ?: 2
            val agency = Agency.entries[spinner.selectedItemPosition]

            if (stopId == null || stopName == null) {
                Toast.makeText(this, "Please select a stop", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (maxArrivals !in 1..3) {
                Toast.makeText(this, "Max arrivals must be between 1 and 3", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val displayMode = when (rgDisplayMode.checkedRadioButtonId) {
                R.id.rbAbsolute -> DisplayMode.ABSOLUTE
                R.id.rbHybrid -> DisplayMode.HYBRID
                else -> DisplayMode.RELATIVE
            }
            val hybridThresholdMinutes = etHybridThreshold.text.toString().trim().toIntOrNull() ?: 60
            val delayColorMode = when (rgDelayInfoMode.checkedRadioButtonId) {
                R.id.rbNoDelay -> DelayColorMode.NONE
                R.id.rbFlatDelay -> DelayColorMode.FLAT
                else -> DelayColorMode.GRADIENT
            }

            if (displayMode == DisplayMode.HYBRID && hybridThresholdMinutes !in 1..1440) {
                Toast.makeText(this, "Hybrid threshold must be between 1 and 1440 minutes", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val allRouteHeadsigns = currentRoutes
                .entries.flatMap { (r, hs) -> hs.map { "$r|$it" } }.toSet()
            val filtered = if (checkedHeadsigns == allRouteHeadsigns) emptyList()
            else checkedHeadsigns.toList()

            val config = WidgetConfig(
                widgetId = widgetId,
                stopId = stopId,
                stopName = stopName,
                agency = agency,
                filteredHeadsigns = filtered,
                maxArrivals = maxArrivals,
                displayMode = displayMode,
                hybridThresholdMinutes = hybridThresholdMinutes,
                delayColorMode = delayColorMode
            )

            CoroutineScope(Dispatchers.IO).launch {
                val db = TransitDatabase.getInstance(applicationContext)
                val configDao = db.widgetConfigDao()
                val departureDao = db.departureDao()

                val oldStopId = configDao.getConfig(widgetId)?.stopId
                if (oldStopId != null && oldStopId != stopId) {
                    val remaining = configDao.getAllConfigs()
                        .filter { it.stopId == oldStopId && it.widgetId != widgetId }
                    if (remaining.isEmpty()) {
                        departureDao.deleteDeparturesForStop(oldStopId)
                    }
                }

                configDao.upsertConfig(config)

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

    private fun fetchRoutes(
        stopId: String,
        spinner: Spinner,
        flRoutes: FrameLayout,
        elvRoutes: ExpandableListView,
        tvRoutesLabel: TextView,
        etStopSearch: EditText,
        configToRestore: WidgetConfig? = null
    ) {
        checkedHeadsigns.clear()
        CoroutineScope(Dispatchers.IO).launch {
            val agency = Agency.entries[spinner.selectedItemPosition]
            val routes = AgencyRegistry.get(agency).fetchRoutesForStop(stopId)

            withContext(Dispatchers.Main) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(etStopSearch.windowToken, 0)

                currentRoutes = routes
                if (routes.isEmpty()) {
                    flRoutes.visibility = View.GONE
                    tvRoutesLabel.visibility = View.GONE
                    Toast.makeText(
                        this@TransitWidgetConfig,
                        "⚠ No routes available right now. Try configuring at a different time.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    if (configToRestore?.filteredHeadsigns?.isNotEmpty() == true && configToRestore.stopId == stopId) {
                        checkedHeadsigns.addAll(configToRestore.filteredHeadsigns)
                    } else {
                        routes.entries.forEach { (routeName, headsigns) ->
                            headsigns.forEach { headsign ->
                                checkedHeadsigns.add("$routeName|$headsign")
                            }
                        }
                    }
                    routeAdapter = RouteHeadsignAdapter(
                        this@TransitWidgetConfig,
                        routes,
                        checkedHeadsigns
                    )
                    elvRoutes.setAdapter(routeAdapter)
                    for (i in routes.keys.indices) elvRoutes.expandGroup(i)
                    flRoutes.visibility = View.VISIBLE
                    tvRoutesLabel.visibility = View.VISIBLE
                }
            }
        }
    }
}
