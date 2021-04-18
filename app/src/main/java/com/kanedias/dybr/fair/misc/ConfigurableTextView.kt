package com.kanedias.dybr.fair.misc

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import com.google.android.material.textview.MaterialTextView
import com.kanedias.dybr.fair.service.UserPrefs

open class ConfigurableTextView: MaterialTextView {

    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    init {
        @Suppress("LeakingThis")
        setTextSize(TypedValue.COMPLEX_UNIT_SP, UserPrefs.userPreferredTextSize.toFloat())
    }

}