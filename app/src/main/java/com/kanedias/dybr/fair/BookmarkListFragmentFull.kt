package com.kanedias.dybr.fair

import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.*
import moe.banana.jsonapi2.ArrayDocument

/**
 * Fragment that shows list of bookmarks of current profile.
 *
 * This is the extension of tab-viewed [EntryListFragment]
 * but in fullscreen with its own floating action button and app bar.
 *
 * @author Kanedias
 *
 * Created on 24.03.19
 */
open class BookmarkListFragmentFull: EntryListFragmentFull() {

    override fun setupUI() {
        super.setupUI()

        // setup toolbar
        fullscreenBinding.entryListToolbar.title = getString(R.string.my_bookmarks)
        fullscreenBinding.entryListToolbar.navigationIcon = DrawerArrowDrawable(activity).apply { progress = 1.0f }
        fullscreenBinding.entryListToolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }
    }

    override fun retrieveData(pageNum: Int, starter: Long): () -> ArrayDocument<Entry> = {
        val bookmarks = Network.loadBookmarks(pageNum)
        val entries = bookmarks.mapNotNull { it.entry?.get(it.document) }

        ArrayDocument<Entry>(bookmarks).apply { addAll(entries) }
    }

    /**
     * We don't rely on profile, never skip loading entries
     */
    override fun handleLoadSkip() = false
}