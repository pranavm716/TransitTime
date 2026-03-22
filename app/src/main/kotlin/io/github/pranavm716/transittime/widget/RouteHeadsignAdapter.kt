package io.github.pranavm716.transittime.widget

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.CheckBox
import android.widget.TextView
import io.github.pranavm716.transittime.R

class RouteHeadsignAdapter(
    private val context: Context,
    // routeName → list of headsigns
    private val routes: Map<String, List<String>>,
    // which headsigns are currently checked — mutable so toggles persist
    private val checkedHeadsigns: MutableSet<String>
) : BaseExpandableListAdapter() {

    private val routeNames = routes.keys.toList().sorted()

    override fun getGroupCount() = routeNames.size
    override fun getGroup(groupPosition: Int) = routeNames[groupPosition]
    override fun getGroupId(groupPosition: Int) = groupPosition.toLong()
    override fun isChildSelectable(groupPosition: Int, childPosition: Int) = true
    override fun hasStableIds() = false

    override fun getChildrenCount(groupPosition: Int) =
        routes[routeNames[groupPosition]]?.size ?: 0

    override fun getChild(groupPosition: Int, childPosition: Int): String =
        routes[routeNames[groupPosition]]!![childPosition]

    override fun getChildId(groupPosition: Int, childPosition: Int) =
        childPosition.toLong()

    override fun getGroupView(
        groupPosition: Int,
        isExpanded: Boolean,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_route_group, parent, false)

        val routeName = routeNames[groupPosition]
        val headsigns = routes[routeName] ?: emptyList()
        val selectedCount = headsigns.count { "$routeName|$it" in checkedHeadsigns }

        view.findViewById<TextView>(R.id.tvRouteName).text = routeName
        view.findViewById<TextView>(R.id.tvRouteSelectedCount).text =
            if (selectedCount == headsigns.size) "All"
            else if (selectedCount == 0) "None"
            else "$selectedCount/${headsigns.size}"

        return view
    }

    override fun getChildView(
        groupPosition: Int,
        childPosition: Int,
        isLastChild: Boolean,
        convertView: View?,
        parent: ViewGroup
    ): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_headsign_child, parent, false)

        val routeName = routeNames[groupPosition]
        val headsign = getChild(groupPosition, childPosition)
        val key = "$routeName|$headsign"
        val cb = view.findViewById<CheckBox>(R.id.cbHeadsign)

        cb.setOnCheckedChangeListener(null)
        cb.text = headsign
        cb.isChecked = key in checkedHeadsigns

        cb.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) checkedHeadsigns.add(key)
            else checkedHeadsigns.remove(key)
            notifyDataSetChanged()
        }

        return view
    }
}