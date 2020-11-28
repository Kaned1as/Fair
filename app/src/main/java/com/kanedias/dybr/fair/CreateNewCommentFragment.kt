package com.kanedias.dybr.fair

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.customListAdapter
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.StyleLevel
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.ftinc.scoop.adapters.ImageViewColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.database.entities.OfflineDraft
import com.kanedias.dybr.fair.databinding.FragmentCreateCommentBinding
import com.kanedias.dybr.fair.databinding.FragmentEditFormDraftSelectionRowBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.ui.EditorViews
import com.kanedias.dybr.fair.markdown.handleMarkdownRaw
import com.kanedias.dybr.fair.markdown.markdownToHtml
import com.kanedias.dybr.fair.service.Network
import com.kanedias.html2md.Html2Markdown
import kotlinx.coroutines.*
import moe.banana.jsonapi2.HasOne
import java.text.SimpleDateFormat
import java.util.*


/**
 * Fragment responsible for creating/updating comments.
 *
 * @author Kanedias
 *
 * Created on 01.04.18
 */
class CreateNewCommentFragment : Fragment() {

    companion object {
        const val EDIT_MODE = "edit-mode"
        const val ENTRY_ID = "entry-id"
        const val EDIT_COMMENT_ID = "edit-comment-id"
        const val EDIT_COMMENT_HTML = "edit-comment-text"
        const val AUTHOR_LINK_TEXT = "author-link-text"
        const val REPLY_TEXT = "reply-text"
    }

    private lateinit var styleLevel: StyleLevel
    private lateinit var binding: FragmentCreateCommentBinding
    private lateinit var editor: EditorViews

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentCreateCommentBinding.inflate(inflater, container, false)
        editor = EditorViews(this, binding.commentEditor)

        binding.commentPreview.setOnClickListener { togglePreview() }
        binding.commentCancel.setOnClickListener { cancel() }
        binding.commentCancel.setOnLongClickListener { forceCancel() }
        binding.commentSubmit.setOnClickListener { submit() }

        val editMode = requireArguments().getBoolean(EDIT_MODE)
        if (editMode) {
            populateUI()
        } else {
            loadDraft()
            handleMisc()
        }

        setupTheming()
        return binding.root
    }

    private fun setupTheming() {
        styleLevel = Scoop.getInstance().addStyleLevel()
        //lifecycle.addObserver(styleLevel) // only auto-bind without animations

        styleLevel.bind(BACKGROUND, binding.dialogArea, NoRewriteBgPicAdapter())
        styleLevel.bindBgDrawable(BACKGROUND, binding.dialogArea)

        styleLevel.bind(TEXT_BLOCK, binding.commentAddArea, DefaultColorAdapter())

        styleLevel.bind(TEXT, binding.commentMarkdownPreview, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.commentMarkdownPreview, TextViewLinksAdapter())
        styleLevel.bind(TEXT_LINKS, binding.commentPreview, ImageViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.commentSubmit, ImageViewColorAdapter())
    }

    /**
     * Populates UI elements from entry that is edited.
     * Call once when fragment is initialized.
     */
    private fun populateUI() {
        // need to convert entry content (html) to Markdown somehow...
        val html = requireArguments().getString(EDIT_COMMENT_HTML)!!
        val markdown = Html2Markdown().parseExtended(html)
        binding.commentEditor.sourceText.setText(markdown)
    }

    /**
     * Handler for clicking on "Preview" button
     */
    fun togglePreview() {
        if (binding.commentPreviewSwitcher.displayedChild == 0) {
            binding.commentPreviewSwitcher.displayedChild = 1
            binding.commentMarkdownPreview.handleMarkdownRaw(binding.commentEditor.sourceText.text.toString())
        } else {
            binding.commentPreviewSwitcher.displayedChild = 0
        }
    }

    /**
     * Cancel this item editing (with confirmation)
     */
    fun cancel() {
        val entryId = requireArguments().getString(ENTRY_ID)
        val editMode = requireArguments().getBoolean(EDIT_MODE)

        if (editMode || binding.commentEditor.sourceText.text.isNullOrEmpty()) {
            // entry has empty title and content, canceling right away
            parentFragmentManager.popBackStack()
            return
        }

        // persist draft
        DbProvider.helper.draftDao.create(OfflineDraft(
                key = "comment,entry=${entryId}",
                base = binding.commentEditor.sourceText.text.toString()
        ))

        Toast.makeText(requireContext(), R.string.offline_draft_saved, Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
    }

    /**
     * Nobody knows about this feature.
     * Cancels dialog and doesn't create any offline draft.
     * @return always true
     */
    fun forceCancel(): Boolean {
        parentFragmentManager.popBackStack()
        return true
    }

    /**
     * Assemble comment creation request and submit it to the server
     */
    fun submit() {
        // hide keyboard
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)

        val comment = CreateCommentRequest().apply {
            content = markdownToHtml(binding.commentEditor.sourceText.text.toString())
        }

        val progressDialog = MaterialDialog(requireContext())
                .title(R.string.please_wait)
                .message(R.string.submitting)

        // make http request
        lifecycleScope.launch {
            progressDialog.showThemed(styleLevel)

            try {
                // if we have current comment list, refresh it
                val frgPredicate = { it: Fragment -> it is UserContentListFragment }
                val curFrg = parentFragmentManager.fragments.reversed().find(frgPredicate) as UserContentListFragment?

                val editMode = requireArguments().getBoolean(EDIT_MODE)
                if (editMode) {
                    // alter existing comment
                    comment.id = requireArguments().getString(EDIT_COMMENT_ID)
                    withContext(Dispatchers.IO) { Network.updateComment(comment) }
                    Toast.makeText(activity, R.string.comment_updated, Toast.LENGTH_SHORT).show()
                    curFrg?.loadMore(reset = true)
                } else {
                    // create new
                    comment.entry = HasOne("entries", requireArguments().getString(ENTRY_ID))
                    comment.profile = HasOne(Auth.profile)
                    withContext(Dispatchers.IO) { Network.createComment(comment) }
                    Toast.makeText(activity, R.string.comment_created, Toast.LENGTH_SHORT).show()
                    curFrg?.loadMore()
                }
                parentFragmentManager.popBackStack()
            } catch (ex: Exception) {
                // don't close the fragment, just report errors
                if (isActive) {
                    Network.reportErrors(context, ex)
                }
            }

            progressDialog.dismiss()
        }
    }

    fun saveDraft() {
        if (binding.commentEditor.sourceText.text.isNullOrEmpty())
            return

        // persist new draft
        DbProvider.helper.draftDao.create(OfflineDraft(base = binding.commentEditor.sourceText.text.toString()))

        // clear the context and show notification
        binding.commentEditor.sourceText.setText("")
        Toast.makeText(context, R.string.offline_draft_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Loads a list of drafts from database and shows a dialog with list items to be selected.
     * After offline draft item is selected, this offline draft is deleted from the database and its contents
     * are applied to content of the editor.
     */
    private fun loadDraft() {
        val entryId = requireArguments().getString(ENTRY_ID)
        val drafts = DbProvider.helper.draftDao.queryBuilder()
                .apply {
                    where()
                            .eq("key", "comment,entry=${entryId}")
                            .or()
                            .isNull("key")
                }
                .orderBy("createdAt", false)
                .query()

        if (drafts.isEmpty())
            return

        if (!drafts[0].key.isNullOrBlank()) {
            // was invoked on fragment creation and
            // probably user saved entry previously by clicking "cancel", load it
            popDraftUI(drafts[0])
            return
        }

        val adapter = DraftCommentViewAdapter(drafts)
        val dialog = MaterialDialog(requireContext())
                .title(R.string.select_offline_draft)
                .customListAdapter(adapter)
        adapter.toDismiss = dialog
        dialog.show()
    }

    /**
     * Loads draft and deletes it from local database
     * @param draft draft to load into UI forms and delete
     */
    private fun popDraftUI(draft: OfflineDraft) {
        binding.commentEditor.sourceText.setText(draft.content)
        Toast.makeText(context, R.string.offline_draft_loaded, Toast.LENGTH_SHORT).show()

        DbProvider.helper.draftDao.deleteById(draft.id)
    }

    /**
     * Handles miscellaneous conditions, such as:
     * * This fragment was shown due to click on author's nickname
     * * This fragment was shown due to quoting
     *
     */
    private fun handleMisc() {
        // handle click on reply in text selection menu
        arguments?.get(REPLY_TEXT)?.let {
            val selectedText = it as String
            val replyQuoted = selectedText.replace(EditorViews.LINE_START, "> ")
            val withQuote = "${binding.commentEditor.sourceText.text}$replyQuoted\n\n"

            binding.commentEditor.sourceText.setText(withQuote)
            binding.commentEditor.sourceText.setSelection(withQuote.length)
        }

        // handle click on author nickname in comments field
        arguments?.get(AUTHOR_LINK_TEXT)?.let { linkText ->
            val withAuthorLink = "${binding.commentEditor.sourceText.text}${linkText}"
            binding.commentEditor.sourceText.setText(withAuthorLink)
            binding.commentEditor.sourceText.setSelection(withAuthorLink.length)
        }
    }

    /**
     * Recycler adapter to hold list of offline drafts that user saved
     */
    inner class DraftCommentViewAdapter(private val drafts: MutableList<OfflineDraft>) : RecyclerView.Adapter<DraftCommentViewAdapter.DraftCommentViewHolder>() {

        /**
         * Dismiss this if item is selected
         */
        lateinit var toDismiss: MaterialDialog

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftCommentViewHolder {
            val inflater = LayoutInflater.from(context)
            val v = inflater.inflate(R.layout.fragment_edit_form_draft_selection_row, parent, false)
            return DraftCommentViewHolder(v)
        }

        override fun getItemCount() = drafts.size

        override fun onBindViewHolder(holder: DraftCommentViewHolder, position: Int) = holder.setup(position)

        fun removeItem(pos: Int) {
            drafts.removeAt(pos)
            notifyItemRemoved(pos)

            if (drafts.isEmpty()) {
                toDismiss.dismiss()
            }
        }

        /**
         * View holder to show one draft as a recycler view item
         */
        inner class DraftCommentViewHolder(v: View): RecyclerView.ViewHolder(v) {

            private var pos: Int = 0
            private lateinit var draft: OfflineDraft

            private val draftItem = FragmentEditFormDraftSelectionRowBinding.bind(v)

            init {
                v.setOnClickListener {
                    popDraftUI(draft)
                    toDismiss.dismiss()
                }

                draftItem.draftDelete.setOnClickListener {
                    DbProvider.helper.draftDao.deleteById(draft.id)
                    Toast.makeText(context, R.string.offline_draft_deleted, Toast.LENGTH_SHORT).show()
                    removeItem(pos)
                }
            }

            fun setup(position: Int) {
                pos = position
                draft = drafts[position]
                draftItem.draftDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(draft.createdAt)
                draftItem.draftContent.text = draft.content
            }
        }
    }
}