package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.ftinc.scoop.adapters.ImageViewColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.kanedias.dybr.fair.databinding.FragmentCommentListItemBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.misc.showFullscreenFragment
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.markdown.handleMarkdown
import com.kanedias.dybr.fair.service.Network
import kotlinx.coroutines.*


/**
 * View holder for showing comments in entry view. Has quick buttons for editing actions if this
 * comment is yours.
 *
 * @param parentFragment fragment that this view holder belongs to. Needed for styling and
 *                       proper async job lifecycle
 * @param iv inflated view
 * @see CommentListFragment
 * @author Kanedias
 */
class CommentViewHolder(iv: View, parentFragment: UserContentListFragment) : UserContentViewHolder<Comment>(iv, parentFragment) {

    private val binding = FragmentCommentListItemBinding.bind(iv)
    private val buttons = listOf(binding.commentEdit, binding.commentDelete)

    private lateinit var comment: Comment

    init {
        binding.commentEdit.setOnClickListener { editComment() }
        binding.commentDelete.setOnClickListener { deleteComment() }

        setupTheming()
    }

    override fun getCreationDateView() = binding.commentDate
    override fun getProfileAvatarView() = binding.commentAvatar
    override fun getAuthorNameView() = binding.commentAuthor
    override fun getContentView() = binding.commentMessage

    private fun setupTheming() {
        val styleLevel = parentFragment.styleLevel

        styleLevel.bind(TEXT_BLOCK, itemView, CardViewColorAdapter())
        styleLevel.bind(TEXT, binding.commentAuthor, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.commentDate, TextViewColorAdapter())
        styleLevel.bind(TEXT, binding.commentMessage, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.commentMessage, TextViewLinksAdapter())
        buttons.forEach { styleLevel.bind(TEXT_LINKS, it, ImageViewColorAdapter()) }
    }

    fun editComment() {
        val activity = itemView.context as AppCompatActivity
        val parentEntry = comment.entry.get(comment.document)
        val commentEdit = CreateNewCommentFragment().apply {
            arguments = Bundle().apply {
                putBoolean(CreateNewCommentFragment.EDIT_MODE, true)
                putString(CreateNewCommentFragment.ENTRY_ID, parentEntry.id)
                putString(CreateNewCommentFragment.EDIT_COMMENT_ID, this@CommentViewHolder.comment.id)
                putString(CreateNewCommentFragment.EDIT_COMMENT_HTML, this@CommentViewHolder.comment.content)
            }
        }

        activity.showFullscreenFragment(commentEdit)
    }

    fun deleteComment() {
        val activity = itemView.context as AppCompatActivity

        // delete callback
        val delete = {
            parentFragment.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { Network.deleteComment(comment) }
                    Toast.makeText(activity, R.string.comment_deleted, Toast.LENGTH_SHORT).show()

                    // if we have current tab, refresh it
                    val clPredicate = { it: Fragment -> it is CommentListFragment && it.lifecycle.currentState == Lifecycle.State.RESUMED }
                    val currentTab = activity.supportFragmentManager.fragments.find(clPredicate) as CommentListFragment?
                    currentTab?.loadMore(reset = true)
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

    /**
     * Show or hide entry editing buttons depending on circumstances
     */
    private fun toggleEditButtons(show: Boolean) {
        val visibility = when (show) {
            true -> View.VISIBLE
            false -> View.GONE
        }
        val editTag = itemView.context.getString(R.string.edit_tag)
        buttons.filter { it.tag == editTag }.forEach { it.visibility = visibility }
    }

    /**
     * Called when this holder should be refreshed based on what it must show now
     */
    override fun setup(entity: Comment) {
        super.setup(entity)

        this.comment = entity

        binding.commentMessage.handleMarkdown(comment.content)

        val profile = comment.profile.get(comment.document)
        toggleEditButtons(isBlogWritable(profile))

        // make text selectable
        // XXX: this is MAGIC: see https://stackoverflow.com/a/56224791/1696844
        binding.commentMessage.setTextIsSelectable(false)
        binding.commentMessage.measure(-1, -1)
        binding.commentMessage.setTextIsSelectable(true)
    }


}