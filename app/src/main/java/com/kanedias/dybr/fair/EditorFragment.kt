package com.kanedias.dybr.fair

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.StyleLevel
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.kanedias.dybr.fair.ui.EditorViews

/**
 * Helper fragment representing edit dialog that pops from the bottom of the screen.
 *
 * @see AddMessageFragment
 *
 * @author Kanedias
 *
 * Created on 2019-12-29
 */
open class EditorFragment: BottomSheetDialogFragment() {

    lateinit var styleLevel: StyleLevel
    protected lateinit var editor: EditorViews

    override fun onStart() {
        super.onStart()

        view?.apply {
            val parent = parent as? View ?: return@apply

            // transparent bottom sheet background
            parent.setBackgroundResource(android.R.color.transparent)

            val params = parent.layoutParams as? CoordinatorLayout.LayoutParams ?: return@apply
            val bottomSheetBehavior = params.behavior as? BottomSheetBehavior ?: return@apply
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    protected open fun setupTheming() {
        styleLevel = Scoop.getInstance().addStyleLevel()
        lifecycle.addObserver(styleLevel)

        editor.setupTheming()
    }
}