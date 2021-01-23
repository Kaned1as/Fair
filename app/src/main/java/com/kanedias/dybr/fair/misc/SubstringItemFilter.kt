package com.kanedias.dybr.fair.misc

import android.widget.ArrayAdapter
import android.widget.Filter
import java.util.*

class SubstringItemFilter<T>(private val adapter: ArrayAdapter<T>, items: List<T>) : Filter() {

    // clone list so adapter won't reduce it on clear
    private val origItems = items.toList()
    private val filteredItems = mutableListOf<T>()
    private val skipChars = mutableListOf<Char>()

    fun addSkipChar(item: Char) = skipChars.add(item)

    override fun performFiltering(constraint: CharSequence?): FilterResults {
        if (constraint == null) {
            return FilterResults()
        }

        filteredItems.clear()
        origItems.forEach {
            val lcItem = it.toString().toLowerCase(Locale.getDefault())
            var lcConstraint = constraint.toString().toLowerCase(Locale.getDefault())

            if (skipChars.isNotEmpty()) {
                // remove skip chars from search query
                // this is needed because tokens @username should search for "username", not full token
                lcConstraint = lcConstraint.replace(Regex("[${skipChars.joinToString(separator = "")}]"), "")
            }

            if (lcItem.contains(lcConstraint)) {
                filteredItems.add(it)
            }
        }

        val filterResults = FilterResults()
        filterResults.values = filteredItems
        filterResults.count = filteredItems.size
        return filterResults
    }

    @Suppress("UNCHECKED_CAST") // FilterResults constraint, can't change
    override fun publishResults(constraint: CharSequence?, results: FilterResults) {
        if (results.values == null) {
            return
        }
        val filterList = results.values as List<T>
        if (results.count > 0) {
            adapter.clear()
            adapter.addAll(filterList)
            adapter.notifyDataSetChanged()
        }
    }
}