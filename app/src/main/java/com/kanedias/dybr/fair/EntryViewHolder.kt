package com.kanedias.dybr.fair

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.afollestad.materialdialogs.MaterialDialog
import com.kanedias.dybr.fair.markdown.handleMarkdown
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.listItems
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.ftinc.scoop.adapters.ImageViewColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.kanedias.dybr.fair.databinding.FragmentEntryListItemBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.misc.idMatches
import com.kanedias.dybr.fair.misc.onClickSingleOnly
import com.kanedias.dybr.fair.misc.showFullscreenFragment
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.ui.openUrlExternally
import com.kanedias.dybr.fair.ui.showToastAtView
import kotlinx.coroutines.*
import moe.banana.jsonapi2.HasOne

/**
 * View holder for showing regular entries in blog view.
 *
 * @param iv inflated view to be used by this holder
 * @param allowSelection whether text in this view can be selected and copied
 * @see EntryListFragment.entryRibbon
 * @author Kanedias
 */
class EntryViewHolder(iv: View, parentFragment: UserContentListFragment, private val allowSelection: Boolean = false)
    : UserContentViewHolder<Entry>(iv, parentFragment) {

    /**
     * Entry that this holder represents
     */
    private lateinit var entry: Entry

    /**
     * Optional metadata associated with current entry
     */
    private var metadata: EntryMeta? = null
    private var reactions: MutableList<Reaction> = mutableListOf()

    /**
     * Profile this entry belongs to
     */
    private lateinit var profile: OwnProfile

    /**
     * Community this entry belongs to, if exists
     */
    private var community: OwnProfile? = null

    override fun getCreationDateView() = binding.entryDate
    override fun getProfileAvatarView() = binding.entryAvatar
    override fun getAuthorNameView() = binding.entryAuthor
    override fun getContentView() = binding.entryMessage

    /**
     * Listener to show comments of this entry
     */
    private val commentShow = View.OnClickListener {
        val activity = it.context as AppCompatActivity
        val commentsPage = CommentListFragment().apply {
            arguments = Bundle().apply { putSerializable(CommentListFragment.ENTRY_ARG, this@EntryViewHolder.entry) }
        }
        activity.showFullscreenFragment(commentsPage)
    }

    private val binding: FragmentEntryListItemBinding = FragmentEntryListItemBinding.bind(iv)

    private val buttons = listOf(
            binding.entryBookmark,
            binding.entryWatch,
            binding.entryEdit,
            binding.entryDelete,
            binding.entryMoreOptions,
            binding.entryRepost,
            binding.entryAddReaction
    )

    private val indicatorImages = listOf(
            binding.entryCommentsIndicator,
            binding.entryParticipantsIndicator
    )

    private val indicatorTexts = listOf(
            binding.entryCommentsIndicatorText,
            binding.entryParticipantsIndicatorText
    )

    init {
        setupTheming()

        binding.root.setOnClickListener(commentShow)
        binding.entryTags.movementMethod = LinkMovementMethod()
        binding.entryEdit.setOnClickListener { editEntry() }
        binding.entryDelete.setOnClickListener { deleteEntry() }
        binding.entryMoreOptions.setOnClickListener { showOverflowMenu() }
        binding.entryWatch.setOnClickListener { subscribeToEntry(binding.entryWatch) }
        binding.entryBookmark.setOnClickListener { bookmarkEntry(binding.entryBookmark) }
        binding.entryRepost.setOnClickListener { repostEntry() }
        binding.entryAddReaction.setOnClickListener { openReactionMenu(binding.entryAddReaction) }
    }

    private fun setupTheming() {
        val styleLevel = parentFragment.styleLevel

        styleLevel.bind(TEXT_BLOCK, binding.root, CardViewColorAdapter())
        styleLevel.bind(TEXT_HEADERS, binding.entryTitle, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.entryDate, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.entryAuthor, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.entryAuthorSubtext, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.entryCommunityProfile, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.entryCommunityProfile, TextViewDrawableAdapter())
        styleLevel.bind(TEXT, binding.entryCommunityProfileSubtext, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.entryMessage, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryMessage, TextViewLinksAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryTags, TextViewLinksAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryLockIcon, ImageViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryPinIcon, ImageViewColorAdapter())
        styleLevel.bind(DIVIDER, binding.entryMetaDivider, DefaultColorAdapter())

        (buttons + indicatorImages).forEach { styleLevel.bind(TEXT_LINKS, it, ImageViewColorAdapter()) }
        indicatorTexts.forEach { styleLevel.bind(TEXT_LINKS, it, TextViewColorAdapter()) }
    }

    private fun editEntry() {
        val activity = itemView.context as AppCompatActivity
        val entryEdit = CreateNewEntryFragment().apply {
            arguments = Bundle().apply {
                // edit entry
                putBoolean(CreateNewEntryFragment.EDIT_MODE, true)
                putString(CreateNewEntryFragment.EDIT_ENTRY_ID, entry.id)
                putString(CreateNewEntryFragment.EDIT_ENTRY_STATE, entry.state)
                putString(CreateNewEntryFragment.EDIT_ENTRY_TITLE, entry.title)
                putSerializable(CreateNewEntryFragment.EDIT_ENTRY_SETTINGS, entry.settings)
                putStringArray(CreateNewEntryFragment.EDIT_ENTRY_TAGS, entry.tags.toTypedArray())
                putString(CreateNewEntryFragment.EDIT_ENTRY_CONTENT_HTML, entry.content)

                // parent profile
                putString(CreateNewEntryFragment.PARENT_BLOG_PROFILE_ID, profile.id)
                putSerializable(CreateNewEntryFragment.PARENT_BLOG_PROFILE_SETTINGS, profile.settings)
            }
        }

        entryEdit.show(parentFragment.parentFragmentManager, "entry edit fragment")
    }

    private fun subscribeToEntry(subButton: ImageView) {
        val subscribe = !(metadata?.subscribed ?: false)

        val toastText = when (subscribe) {
            true -> R.string.subscribed_to_entry
            false -> R.string.unsubscribed_from_entry
        }

        parentFragment.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Network.updateSubscription(entry, subscribe) }
                showToastAtView(subButton, itemView.context.getString(toastText))

                metadata?.subscribed = subscribe
                when(subscribe) {
                    true -> subButton.setImageResource(R.drawable.watch_added)
                    false -> subButton.setImageResource(R.drawable.watch_removed)
                }
            } catch (ex: Exception) {
                Network.reportErrors(itemView.context, ex)
            }
        }
    }

    private fun bookmarkEntry(button: ImageView) {
        val bookmark = !(metadata?.bookmark ?: false)

        val toastText = when (bookmark) {
            true -> R.string.entry_bookmarked
            false -> R.string.entry_removed_from_bookmarks
        }

        parentFragment.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Network.updateBookmark(entry, bookmark) }
                showToastAtView(button, itemView.context.getString(toastText))

                metadata?.bookmark = bookmark
                setupButtons()
            } catch (ex: Exception) {
                Network.reportErrors(itemView.context, ex)
            }
        }
    }

    private fun repostEntry() {
        val repostHeader = itemView.context.getString(R.string.repost_header, profile.id, profile.nickname, profile.blogSlug, entry.id)
        val repostFooter = itemView.context.getString(R.string.repost_footer, profile.blogSlug, entry.id)
        val repostText = "$repostHeader <blockquote>${entry.content}</blockquote> $repostFooter"

        val repostView = TextView(itemView.context)

        repostView.handleMarkdown(repostText)

        val dialog = MaterialDialog(itemView.context)
                .title(R.string.confirm_action)
                .customView(view = repostView, scrollable = true, horizontalPadding = true)
                .negativeButton(android.R.string.no)
                .positiveButton(R.string.repost, click = { dialog -> doRepostEntry(repostText) })

        parentFragment.styleLevel.bindBgDrawable(BACKGROUND, dialog.view)
        parentFragment.styleLevel.bind(TEXT_BLOCK, dialog.view.titleLayout, DefaultColorAdapter())
        parentFragment.styleLevel.bind(TEXT_BLOCK, dialog.view.contentLayout, DefaultColorAdapter())
        parentFragment.styleLevel.bind(TEXT_BLOCK, dialog.view.buttonsLayout, DefaultColorAdapter())

        parentFragment.styleLevel.bind(TEXT, repostView, TextViewColorAdapter())
        parentFragment.styleLevel.bind(TEXT_LINKS, repostView, TextViewLinksAdapter())

        dialog.showThemed(parentFragment.styleLevel)
    }

    private fun doRepostEntry(repostText: String) {

        val dialog = MaterialDialog(itemView.context)
                .title(R.string.please_wait)
                .message(R.string.submitting)

        val repostedEntry = EntryCreateRequest().apply {
            title = entry.title
            state = "published"
            content = repostText

            profile = HasOne(Auth.profile!!)
        }

        parentFragment.lifecycleScope.launch {
            dialog.showThemed(parentFragment.styleLevel)

            Network.perform(networkAction = { Network.createEntry(repostedEntry) },
            uiAction = { Toast.makeText(itemView.context, R.string.entry_created, Toast.LENGTH_SHORT).show() })

            dialog.dismiss()
        }
    }

    private fun openReactionMenu(button: ImageView) {
        parentFragment.lifecycleScope.launch {
            try {
                val reactionSets = withContext(Dispatchers.IO) { Network.loadReactionSets() }
                if (!reactionSets.isNullOrEmpty()) {
                    showReactionMenu(button, reactionSets.first())
                }
            } catch (ex: Exception) {
                Network.reportErrors(itemView.context, ex)
            }
        }
    }

    private fun showReactionMenu(view: View, reactionSet: ReactionSet) {
        val reactionTypes = reactionSet.reactionTypes?.get(reactionSet.document).orEmpty()

        val emojiTable = View.inflate(view.context, R.layout.view_emoji_panel, null) as GridLayout
        val pw = PopupWindow().apply {
            height = WindowManager.LayoutParams.WRAP_CONTENT
            width = WindowManager.LayoutParams.WRAP_CONTENT
            contentView = emojiTable
            isOutsideTouchable = true
        }

        parentFragment.styleLevel.bind(TEXT_BLOCK, pw.contentView, DefaultColorAdapter())

        for (type in reactionTypes.sortedBy { it.id }) {
            emojiTable.addView(TextView(view.context).apply {
                text = type.emoji
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setOnClickListener {
                    parentFragment.lifecycleScope.launch { toggleReaction(view, type) }
                    pw.dismiss()
                }
            })
        }
        pw.showAsDropDown(view, 0, 0, Gravity.TOP)
    }

    private fun deleteEntry() {
        val activity = itemView.context as AppCompatActivity

        // delete callback
        val delete = {
            parentFragment.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { Network.deleteEntry(entry) }
                    Toast.makeText(activity, R.string.entry_deleted, Toast.LENGTH_SHORT).show()
                    activity.supportFragmentManager.popBackStack()

                    parentFragment.loadMore(reset = true)
                } catch (ex: Exception) {
                    Network.reportErrors(itemView.context, ex)
                }
            }
        }

        // show confirmation dialog
        MaterialDialog(itemView.context)
                .title(R.string.confirm_action)
                .message(R.string.are_you_sure)
                .negativeButton(android.R.string.no)
                .positiveButton(android.R.string.yes, click = { delete() })
                .showThemed(parentFragment.styleLevel)
    }

    private fun showOverflowMenu() {
        val ctx = itemView.context
        val items = mutableListOf(
                ctx.getString(R.string.open_in_browser),
                ctx.getString(R.string.share)
        )

        if (parentFragment is EntryListFragment
                && Auth.user != Auth.guest
                && parentFragment.profile === Auth.worldMarker) {
            // show hide-from-feed option
            items.add(ctx.getString(R.string.hide_author_from_feed))
        }

        MaterialDialog(itemView.context)
                .title(R.string.entry_menu)
                .listItems(items = items, selection = { _, index, _ ->
                    when (index) {
                        0 -> showInWebView()
                        1 -> sharePost()
                        2 -> hideFromFeed()
                    }
                }).show()
    }

    private fun hideFromFeed() {
        // hide callback
        val hide = { reason: String ->
            val activity = itemView.context as AppCompatActivity

            val listItem = ActionListRequest().apply {
                kind = "profile"
                action = "hide"
                scope = "feed"
                name = reason
                profiles.add(profile)
            }

            parentFragment.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { Network.addToActionList(listItem) }
                    Toast.makeText(activity, R.string.author_hidden_from_feed, Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    Network.reportErrors(itemView.context, ex)
                }
            }
        }

        MaterialDialog(itemView.context)
                .title(R.string.confirm_action)
                .input(hintRes = R.string.reason)
                .negativeButton(android.R.string.cancel)
                .positiveButton(R.string.submit, click = { md -> hide(md.getInputField().text.toString()) })
                .show()
    }

    private fun sharePost() {
        val ctx = itemView.context

        // if entry belongs to the community, use community link instead
        val prof = community ?: profile

        try {
            val intent = Intent(Intent.ACTION_SEND)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_TEXT, "https://dybr.ru/blog/${prof.blogSlug}/${entry.id}")
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_link_using)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.no_browser_found, Toast.LENGTH_SHORT).show()
        }

    }

    private fun showInWebView() {
        val uri = Uri.Builder()
                .scheme("https").authority("dybr.ru")
                .appendPath("blog").appendPath(profile.blogSlug)
                .appendPath(entry.id)
                .build()

        openUrlExternally(itemView.context, uri)
    }

    /**
     * Show or hide entry editing buttons depending on circumstances
     */
    private fun setupButtons() {
        // setup edit buttons
        val editVisibility = when (isEditable(entry)) {
            true -> View.VISIBLE
            false -> View.GONE
        }
        val editTag = itemView.context.getString(R.string.edit_tag)
        buttons.filter { it.tag == editTag }.forEach { it.visibility = editVisibility }

        // setup repost button
        // don't show it on our own entries or if we don't have blog
        val repostBtn = buttons.first { it.id == R.id.entry_repost }
        when (profile.idMatches(Auth.profile) || Auth.profile?.blogSlug.isNullOrEmpty()) {
            true -> repostBtn.visibility = View.GONE
            false -> repostBtn.visibility = View.VISIBLE
        }

        // setup subscription button
        val subButton = buttons.first { it.id == R.id.entry_watch }
        when (metadata?.subscribed) {
            true -> subButton.apply { visibility = View.VISIBLE; setImageResource(R.drawable.watch_remove) }
            false -> subButton.apply { visibility = View.VISIBLE; setImageResource(R.drawable.watch_add) }
            null -> subButton.visibility = View.GONE
        }

        // setup bookmark button
        val bookmarkButton = buttons.first { it.id == R.id.entry_bookmark }
        when (metadata?.bookmark) {
            true -> bookmarkButton.apply { visibility = View.VISIBLE; setImageResource(R.drawable.bookmark_filled) }
            false -> bookmarkButton.apply { visibility = View.VISIBLE; setImageResource(R.drawable.bookmark_add) }
            null -> bookmarkButton.visibility = View.GONE
        }

        // setup reactions button
        val reactionButton = buttons.first { it.id == R.id.entry_add_reaction }
        when {
            // disabled globally by current user
            Auth.profile?.settings?.reactions?.disable == true -> reactionButton.visibility = View.GONE
            // disabled in current blog by owner
            profile.settings.reactions?.disableInBlog == true -> reactionButton.visibility = View.GONE
            // not authorized, can't add reactions
            Auth.profile == null -> reactionButton.visibility = View.GONE
            // enabled, show the button
            else -> reactionButton.visibility = View.VISIBLE
        }
    }

    /**
     * Called when this holder should be refreshed based on what it must show now
     */
    override fun setup(entity: Entry) {
        super.setup(entity)

        // bind variables
        this.entry = entity
        this.metadata = Network.bufferToObject(entry.meta, EntryMeta::class.java)
        this.profile = entity.profile.get(entity.document)
        this.community = entity.community?.get(entity.document)
        this.reactions = entity.reactions?.get(entity.document) ?: mutableListOf()

        // setup profile info
        binding.entryAuthorSubtext.text = profile.settings.subtext

        // setup community info
        if (community == null || community!!.idMatches(profile)) {
            // default, no community row
            binding.communityRow.visibility = View.GONE
        } else {
            // community exists
            binding.communityRow.visibility = View.VISIBLE
            binding.entryCommunityProfile.text = community!!.nickname
            binding.entryCommunityProfile.setOnClickListener { showProfile(community!!) }

            val avatar = Network.resolve(community?.settings?.avatar) ?: Network.defaultAvatar()
            Glide.with(binding.entryCommunityAvatar).load(avatar.toString())
                    .apply(RequestOptions().centerInside().circleCrop())
                    .into(binding.entryCommunityAvatar)
            binding.entryCommunityAvatar.setOnClickListener { showProfile(community!!) }

            val communitySubtext = community!!.settings.subtext
            if (communitySubtext.isNullOrEmpty()) {
                binding.entryCommunityProfileSubtext.visibility = View.GONE
            } else {
                binding.entryCommunityProfileSubtext.visibility = View.VISIBLE
                binding.entryCommunityProfileSubtext.text = communitySubtext
            }
        }

        // setup text views from entry data
        binding.entryTitle.text = entry.title
        binding.entryTitle.visibility = if (entry.title.isNullOrEmpty()) { View.GONE } else { View.VISIBLE }
        binding.entryDraftState.visibility = if (entry.state == "published") { View.GONE } else { View.VISIBLE }

        // setup permission icon
        val accessItem = entry.settings?.permissions?.access?.firstOrNull()
        if (accessItem == null) {
            binding.entryLockIcon.visibility = View.GONE
        } else {
            binding.entryLockIcon.visibility = View.VISIBLE
            binding.entryLockIcon.setOnClickListener { showToastAtView(binding.entryLockIcon, accessItem.toDescription(it.context)) }
        }

        // setup pin icon
        val pinned = profile.settings.pinnedEntries?.contains(entry.id) ?: false
        if (pinned) {
            binding.entryPinIcon.visibility = View.VISIBLE
            binding.entryPinIcon.setOnClickListener { showToastAtView(binding.entryPinIcon, it.context.getString(R.string.pinned_entry)) }
        } else {
            binding.entryPinIcon.visibility = View.GONE
        }

        // show tags if they are present
        setupTags(entry)

        // setup bottom row of metadata buttons
        metadata?.let { binding.entryCommentsIndicatorText.text = it.comments.toString() }
        metadata?.let { binding.entryParticipantsIndicatorText.text = it.commenters.toString() }

        // setup bottom row of buttons
        setupButtons()

        // setup reaction row
        setupReactions()

        // don't show subscribe button if we can't subscribe
        // guests can't do anything
        if (Auth.profile == null) {
            buttons.first { it.id == R.id.entry_watch }.visibility = View.GONE
            buttons.first { it.id == R.id.entry_bookmark }.visibility = View.GONE
        }

        binding.entryMessage.handleMarkdown(entry.content)

        if (allowSelection) {
            // make text selectable
            // XXX: this is MAGIC: see https://stackoverflow.com/a/56224791/1696844
            binding.entryMessage.setTextIsSelectable(false)
            binding.entryMessage.measure(-1, -1)
            binding.entryMessage.setTextIsSelectable(true)
        }
    }

    /**
     * Setup reactions row, to show reactions which were attached to this entry
     */
    private fun setupReactions() {
        binding.entryReactionsRow.removeAllViews()

        val reactionsDisabled = Auth.profile?.settings?.reactions?.disable == true
        val reactionsDisabledInThisBlog = profile.settings.reactions?.disableInBlog == true

        if (reactions.isEmpty() || reactionsDisabled || reactionsDisabledInThisBlog) {
            // no reactions for this entry or reactions disabled
            binding.entryReactionsRow.visibility = View.GONE
            return
        } else {
            binding.entryReactionsRow.visibility = View.VISIBLE
        }

        // there are some reactions, display them
        val styleLevel = parentFragment.styleLevel
        val counts = reactions.groupBy { it.reactionType.get().id }
        val types = reactions.map { it.reactionType.get(it.document) }.associateBy { it.id }
        for (reactionTypeId in counts.keys) {
            // for each reaction type get reaction counts and authors
            val reactionType = types[reactionTypeId] ?: continue
            val postedWithThisType = counts[reactionTypeId] ?: continue
            val includingMe = postedWithThisType.any { Auth.profile?.idMatches(it.author.get()) == true }

            val reactionView = LayoutInflater.from(itemView.context).inflate(R.layout.view_reaction, binding.entryReactionsRow, false)

            reactionView.onClickSingleOnly { toggleReaction(it, reactionType) }
            if (includingMe) {
                reactionView.isSelected = true
            }

            val emojiTxt = reactionView.findViewById<TextView>(R.id.reaction_emoji)
            val emojiCount = reactionView.findViewById<TextView>(R.id.reaction_count)

            emojiTxt.text = reactionType.emoji
            emojiCount.text = postedWithThisType.size.toString()

            styleLevel.bind(TEXT_LINKS, reactionView, BackgroundTintColorAdapter())
            styleLevel.bind(TEXT, emojiCount, TextViewColorAdapter())

            binding.entryReactionsRow.addView(reactionView)
        }
    }

    private suspend fun toggleReaction(view: View, reactionType: ReactionType) {
        // find reaction with this type
        val myReaction = reactions
                .filter { reactionType.idMatches(it.reactionType.get()) }
                .find { Auth.profile?.idMatches(it.author.get()) == true }

        if (myReaction != null) {
            // it's there, delete it
            try {
                withContext(Dispatchers.IO) { Network.deleteReaction(myReaction) }
                showToastAtView(view, view.context.getString(R.string.reaction_deleted))

                reactions.remove(myReaction)
                setupReactions()
            } catch (ex: Exception) {
                Network.reportErrors(view.context, ex)
            }
        } else {
            // add it
            try {
                val newReaction = withContext(Dispatchers.IO) { Network.createReaction(entry, reactionType) }
                showToastAtView(view, view.context.getString(R.string.reaction_added))

                reactions.add(newReaction)
                setupReactions()
            } catch (ex: Exception) {
                Network.reportErrors(view.context, ex)
            }
        }
    }

    /**
     * Show tags below the message, with divider.
     * Make tags below the entry message clickable.
     */
    private fun setupTags(entry: Entry) {
        if (entry.tags.isEmpty()) {
            binding.entryTags.visibility = View.GONE
        } else {
            binding.entryTags.visibility = View.VISIBLE

            val clickTags = SpannableStringBuilder()
            for (tag in entry.tags) {
                clickTags.append("#").append(tag)
                clickTags.setSpan(ClickableTag(tag), clickTags.length - 1 - tag.length, clickTags.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                clickTags.append(" ")
            }
            binding.entryTags.text = clickTags
        }
    }

    /**
     * Clickable tag span. Don't make it look like a URL link but make it clickable nevertheless.
     */
    inner class ClickableTag(private val tagValue: String) : ClickableSpan() {

        override fun onClick(widget: View) {
            val activity = itemView.context as AppCompatActivity
            val filters = hashMapOf("tag" to tagValue)

            val insideBlog = parentFragment is EntryListFragmentFull
            val insideEntry = parentFragment is CommentListFragment
            val tagInOurBlog = parentFragment is EntryListFragment && parentFragment.profile == Auth.profile
            if (insideBlog || insideEntry || tagInOurBlog) {
                // we're browsing one person's blog, show only their entries
                filters["profile_id"] = this@EntryViewHolder.profile.id
            }

            val searchType = parentFragment.getString(R.string.search_by, parentFragment.getString(R.string.dative_case_tag))
            val searchFragment = EntryListSearchFragmentFull().apply {
                arguments = Bundle().apply {
                    putString("title", "#${tagValue}")
                    putString("subtitle", searchType)
                    putSerializable("filters", filters)
                }
            }
            activity.showFullscreenFragment(searchFragment)
        }

        override fun updateDrawState(ds: TextPaint) {
            super.updateDrawState(ds)
            ds.isUnderlineText = false
        }
    }

}