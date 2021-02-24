package com.kanedias.dybr.fair

import android.content.Intent
import android.view.View
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

        editor.setupTheming()
    }

    /**
     * Override deprecated function, see comment at [EditorViews.uploadImage]
     */
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (EditorViews.EDITOR_REQUEST_LIST.contains(requestCode)) {
            editor.onActivityResult(requestCode, data)
            return
        }

        super.onActivityResult(requestCode, resultCode, data)
    }
}