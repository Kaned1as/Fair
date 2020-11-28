package com.kanedias.dybr.fair

import android.animation.ValueAnimator
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.ftinc.scoop.binding.ViewDrawableBinding
import com.kanedias.dybr.fair.databinding.FragmentCommentListBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.misc.showFullscreenFragment
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.misc.getTopFragment
import com.kanedias.dybr.fair.scheduling.SyncNotificationsWorker
import com.kanedias.dybr.fair.service.Network
import kotlinx.coroutines.*
import moe.banana.jsonapi2.ArrayDocument

/**
 * Fragment which displays selected entry and its comments below.
 *
 * @author Kanedias
 *
 * Created on 01.04.18
 */
class CommentListFragment : UserContentListFragment() {

    companion object {
        const val ENTRY_ARG = "entry"
        const val COMMENT_ID_ARG = "comment"
    }

    override fun getRibbonView() = binding.commentsRibbon
    override fun getRefresher() = binding.commentsRefresher
    override fun getRibbonAdapter() = commentAdapter
    override fun retrieveData(pageNum: Int, starter: Long): () -> ArrayDocument<Comment> = {
        // comments go from new to last, no need for a limiter
        Network.loadComments(entry = this.entry, pageNum = pageNum)
    }

    lateinit var entry: Entry
    private lateinit var commentAdapter: LoadMoreAdapter

    private lateinit var binding: FragmentCommentListBinding
    private lateinit var activity: MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = FragmentCommentListBinding.inflate(inflater, container, false)
        activity = context as MainActivity

        entry = requireArguments().get(ENTRY_ARG) as Entry
        commentAdapter = CommentListAdapter()

        setupUI()
        setupTheming()
        loadMore()

        return binding.root
    }

    private fun setupUI() {
        binding.commentsToolbar.title = entry.title
        binding.commentsToolbar.navigationIcon = DrawerArrowDrawable(activity).apply { progress = 1.0f }
        binding.commentsToolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        binding.commentsRefresher.setOnRefreshListener { loadMore(reset = true) }
        binding.commentsRibbon.adapter = commentAdapter

        binding.addCommentButton.setOnClickListener { addComment() }
        if (!isEntryCommentable(entry))
            binding.addCommentButton.visibility = View.GONE
    }

    private fun setupTheming() {
        // this is a fullscreen fragment, add new style
        styleLevel = Scoop.getInstance().addStyleLevel()
        lifecycle.addObserver(styleLevel)

        styleLevel.bind(TOOLBAR, binding.commentsToolbar, DefaultColorAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.commentsToolbar, ToolbarTextAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.commentsToolbar, ToolbarIconsAdapter())

        styleLevel.bind(ACCENT, binding.addCommentButton, BackgroundTintColorAdapter())
        styleLevel.bind(ACCENT_TEXT, binding.addCommentButton, FabIconAdapter())

        styleLevel.bind(BACKGROUND, binding.commentsRibbon, NoRewriteBgPicAdapter())
        styleLevel.bindBgDrawable(BACKGROUND, binding.commentsRibbon)

        styleLevel.bindStatusBar(activity, STATUS_BAR)

        (entry.community ?: entry.profile).get(entry.document)?.let { applyTheme(activity, it, styleLevel) }
    }

    /**
     * Refresh comments displayed in fragment. Entry is not touched but as recycler view is refreshed
     * its views are reloaded too.
     *
     * @param reset if true, reset page counting and start from page one
     */
    override fun loadMore(reset: Boolean) {
        // update entry comment meta counters and mark notifications as read for current entry
        lifecycleScope.launch {
            try {
                entry = withContext(Dispatchers.IO) { Network.loadEntry(entry.id) }
                getRibbonAdapter().replaceHeader(0, entry)

                // mark related notifications read
                if (Auth.profile == null)
                    return@launch // nothing to mark, we're nobody

                val markedRead = withContext(Dispatchers.IO) { Network.markNotificationsReadFor(entry) }
                if (markedRead) {
                    // we changed notifications, update fragment with them if present
                    val notifFragment = activity.getTopFragment(NotificationListFragment::class)
                    notifFragment?.loadMore(reset = true)
                }

                // update android notifications
                SyncNotificationsWorker.markReadFor(activity, entry.id)
            } catch (ex: Exception) {
                Network.reportErrors(context, ex)
            }
        }

        // load the actual comments
        if (!requireArguments().containsKey(COMMENT_ID_ARG)) {
            super.loadMore(reset)
            return
        }

        // we're first-loading till comment is visible
        val hlCommentId = requireArguments().getString(COMMENT_ID_ARG)
        lifecycleScope.launchWhenResumed {
            try {
                getRefresher().isRefreshing = true
                var page = 0

                while (true) {
                    val data = withContext(Dispatchers.IO) {
                        retrieveData(pageNum = ++page, starter = pageStarter).invoke()
                    }
                    onMoreDataLoaded(data)

                    val commentIdx = getRibbonAdapter().items.indexOfFirst { it.id == hlCommentId }
                    if (commentIdx != -1) {
                        val commentPos = commentIdx + getRibbonAdapter().headers.size
                        highlightItem(commentPos)
                        break
                    }

                    // should be lower than comment index search, or last comments won't be found
                    if (allLoaded) {
                        break
                    }
                }

            } catch (ex: Exception) {
                Network.reportErrors(context, ex)
            } finally {
                // we don't need to highlight the comment every time
                requireArguments().remove(COMMENT_ID_ARG)
            }

            getRefresher().isRefreshing = false
        }
    }

    private fun highlightItem(position: Int) {
        // the problem with highlighting is that in our recycler views all messages are of different height
        // when recycler view is asked to scroll to some position, it doesn't know their height in advance
        // so we have to scroll continually till all the messages have been laid out and parsed
        lifecycleScope.launchWhenResumed {
            delay(300)
            getRibbonView().scrollToPosition(position)

            var limit = 20 // 2 sec
            while(getRibbonView().findViewHolderForAdapterPosition(position) == null) {
                // holder view hasn't been laid out yet
                delay(100)
                limit -= 1


                if (limit == 0) {
                    // strange, we waited for message for too long to be viewable
                    Log.e("[TopicContent]", "Couldn't find holder for comment $position, this shouldn't happen!")
                    return@launchWhenResumed
                }
            }

            // highlight message by tinting background
            val holder = getRibbonView().findViewHolderForAdapterPosition(position) ?: return@launchWhenResumed
            val card = holder.itemView as CardView
            ValueAnimator.ofArgb(Color.RED, card.cardBackgroundColor.defaultColor).apply {
                addUpdateListener {
                    card.setCardBackgroundColor(it.animatedValue as Int)
                }
                duration = 1500
                start()
            }
        }
    }

    fun addComment() {
        val commentAdd = CreateNewCommentFragment().apply {
            arguments = Bundle().apply {
                putString(CreateNewCommentFragment.ENTRY_ID, this@CommentListFragment.entry.id)
            }
        }

        requireActivity().showFullscreenFragment(commentAdd)
    }

    /**
     * Adapter for comments list. Top item is an entry being viewed.
     * All views below represent comments to this entry.
     *
     * Scrolling to the bottom calls polling of next page or stops in case it's the last one
     */
    inner class CommentListAdapter : UserContentListFragment.LoadMoreAdapter() {

        init {
            headers.add(entry)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (getItemViewType(position)) {
                ITEM_HEADER -> {
                    val entry = headers[position] as Entry
                    (holder as EntryViewHolder).apply {
                        setup(entry)
                        itemView.isClickable = false
                    }
                }
                ITEM_REGULAR -> {
                    val comment = items[position - headers.size] as Comment
                    (holder as CommentViewHolder).setup(comment)
                }
                else -> super.onBindViewHolder(holder, position)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(activity)
            return when (viewType) {
                ITEM_HEADER -> {
                    val view = inflater.inflate(R.layout.fragment_entry_list_item, parent, false)
                    EntryViewHolder(view, this@CommentListFragment, allowSelection = true)
                }
                ITEM_REGULAR -> {
                    val view = inflater.inflate(R.layout.fragment_comment_list_item, parent, false)
                    CommentViewHolder(view, this@CommentListFragment)
                }
                else -> return super.onCreateViewHolder(parent, viewType)
            }
        }
    }

}