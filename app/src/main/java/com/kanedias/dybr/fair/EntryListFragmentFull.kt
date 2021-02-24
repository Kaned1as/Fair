package com.kanedias.dybr.fair

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.kanedias.dybr.fair.databinding.FragmentEntryListFullscreenBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.themes.*

/**
 * Fragment that shows list of posts in someone's blog. This is the extension of tab-viewed [EntryListFragment]
 * but in fullscreen with its own floating action button and app bar. Used for opening custom links to
 * dybr.ru from other applications or Fair itself.
 *
 * @author Kanedias
 *
 * Created on 23.06.18
 */
open class EntryListFragmentFull: EntryListFragment() {

    protected lateinit var fullscreenBinding: FragmentEntryListFullscreenBinding

    override fun bindLayout(inflater: LayoutInflater, container: ViewGroup?): FragmentEntryListFullscreenBinding {
        return FragmentEntryListFullscreenBinding.inflate(inflater, container, false)
    }

    override fun setupUI() {
        super.setupUI()

        fullscreenBinding = binding as FragmentEntryListFullscreenBinding

        // setup toolbar
        fullscreenBinding.entryListToolbar.title = profile?.blogTitle
        fullscreenBinding.entryListToolbar.navigationIcon = DrawerArrowDrawable(activity).apply { progress = 1.0f }
        fullscreenBinding.entryListToolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        // setup FAB
        if (isBlogWritable(profile)) {
            fullscreenBinding.addEntryButton.show()
            fullscreenBinding.addEntryButton.setOnClickListener { addCreateNewEntryForm() }
        }
    }

    override fun setupTheming() {
        // this is a fullscreen fragment, add new style
        styleLevel = Scoop.getInstance().addStyleLevel()

        styleLevel.bind(BACKGROUND, fullscreenBinding.entryRibbonArea, DefaultColorAdapter())
        styleLevel.bindBgDrawable(BACKGROUND, fullscreenBinding.entryRibbon)

        styleLevel.bind(TOOLBAR, fullscreenBinding.entryListToolbar, DefaultColorAdapter())
        styleLevel.bind(TOOLBAR_TEXT, fullscreenBinding.entryListToolbar, ToolbarTextAdapter())
        styleLevel.bind(TOOLBAR_TEXT, fullscreenBinding.entryListToolbar, ToolbarIconsAdapter())

        styleLevel.bind(ACCENT, fullscreenBinding.addEntryButton, BackgroundTintColorAdapter())
        styleLevel.bind(ACCENT_TEXT, fullscreenBinding.addEntryButton, FabIconAdapter())

        styleLevel.bind(ACCENT, fullscreenBinding.fastJumpButton, BackgroundTintColorAdapter())
        styleLevel.bind(ACCENT_TEXT, fullscreenBinding.fastJumpButton, FabIconAdapter())

        styleLevel.bindStatusBar(activity, STATUS_BAR)

        profile?.let { applyTheme(activity, it, styleLevel) }
    }
}