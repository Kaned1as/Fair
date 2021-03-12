package com.kanedias.dybr.fair

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.customListAdapter
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.ftinc.scoop.adapters.ImageViewColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.ftinc.scoop.util.Utils
import com.hootsuite.nachos.terminator.ChipTerminatorHandler
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.database.entities.OfflineDraft
import com.kanedias.dybr.fair.databinding.FragmentCreateEntryBinding
import com.kanedias.dybr.fair.databinding.FragmentEditFormDraftSelectionRowBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.markdown.handleMarkdownRaw
import com.kanedias.dybr.fair.markdown.markdownToHtml
import com.kanedias.dybr.fair.misc.SubstringItemFilter
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.ui.EditorViews
import com.kanedias.html2md.Html2Markdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.banana.jsonapi2.HasOne
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fragment responsible for showing create entry/edit entry form.
 *
 * @see EntryListFragment.entryRibbon
 * @author Kanedias
 */
class CreateNewEntryFragment : EditorFragment() {

    companion object {
        /// True if edit mode is enabled, false if it's new entry.
        const val EDIT_MODE = "edit-mode"

        /// Edit entry is set as series of fields instead of one serializable object
        /// because if passed as serializable it contains JSON-API whole document which can be very big
        const val EDIT_ENTRY_ID = "edit-entry-id"
        const val EDIT_ENTRY_TAGS = "edit-entry-tags"
        const val EDIT_ENTRY_STATE = "edit-entry-state"
        const val EDIT_ENTRY_TITLE = "edit-entry-title"
        const val EDIT_ENTRY_SETTINGS = "edit-entry-settings"
        const val EDIT_ENTRY_CONTENT_HTML = "edit-entry-content-html"

        /// Parent blog profile fields
        const val PARENT_BLOG_PROFILE_ID = "parent-blog-profile-id"
        const val PARENT_BLOG_PROFILE_SETTINGS = "parent-blog-profile-settings"
    }

    private lateinit var binding: FragmentCreateEntryBinding
    private var skipDraft = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentCreateEntryBinding.inflate(inflater, container, false)
        editor = EditorViews(this, binding.entryEditor)

        setupUI()
        setupTheming()

        val editMode = requireArguments().getBoolean(EDIT_MODE, false)
        if (editMode) {
            // we're editing existing entry, populate UI with its contents and settings
            populateEditUI()
        } else {
            // It's new entry, if we canceled it previously, it may be saved to offline draft
            loadDraft()
        }

        return binding.root
    }

    private fun setupUI() {
        binding.entryPreview.setOnClickListener { togglePreview() }
        binding.entryDraftSwitch.setOnCheckedChangeListener { _, publish ->  toggleDraftState(publish) }
        binding.entryPinnedSwitch.setOnCheckedChangeListener { _, pin ->  togglePinnedState(pin) }
        binding.entryCancel.setOnLongClickListener { skipDraft = true; dismiss(); true }
        binding.entryCancel.setOnClickListener { dismiss() }
        binding.entrySubmit.setOnClickListener { submit() }

        binding.entryPermissionSelector.adapter = PermissionSpinnerAdapter(listOf(
                RecordAccessItem("private"),
                RecordAccessItem("registered"),
                RecordAccessItem("favorites"),
                RecordAccessItem("subscribers"),
                null /* visible for all */))
        binding.entryPermissionSelector.setSelection(4) // select "Visible for all" by default

        // tags autocompletion
        val parentProfileId = requireArguments().getString(PARENT_BLOG_PROFILE_ID)!!
        lifecycleScope.launch {
            Network.perform(
                    networkAction = { Network.loadProfileTags(parentProfileId) },
                    uiAction = { tagDoc ->
                        val tags = tagDoc.map { it.name }
                        val adapter = TagsAdapter(requireContext(), tags)
                        binding.tagsText.setAdapter(adapter)
                    })
        }
        binding.tagsText.addChipTerminator('\n', ChipTerminatorHandler.BEHAVIOR_CHIPIFY_ALL)
        binding.tagsText.addChipTerminator(',', ChipTerminatorHandler.BEHAVIOR_CHIPIFY_ALL)
        binding.tagsText.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused && binding.tagsText.text.isNullOrBlank()) {
                binding.tagsText.showDropDown()
            }
        }
    }

    override fun setupTheming() {
        super.setupTheming()

        styleLevel.bind(BACKGROUND, binding.dialogArea, DefaultColorAdapter())
        styleLevel.bindImageDrawable(BACKGROUND, binding.blogBgArea)

        styleLevel.bind(TEXT_BLOCK, binding.entryAddArea, DefaultColorAdapter())

        styleLevel.bind(TEXT, binding.entryTitleText, EditTextAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryTitleText, EditTextLineAdapter())
        styleLevel.bind(TEXT, binding.entryTitleTextLayout, EditTextLayoutHintAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryTitleTextLayout, EditTextLayoutBoxStrokeAdapter())

        styleLevel.bind(TEXT, binding.tagsText, EditTextAdapter())
        styleLevel.bind(TEXT_LINKS, binding.tagsText, EditTextLineAdapter())
        styleLevel.bind(TEXT_BLOCK, binding.tagsText, AutocompleteDropdownNoAlphaAdapter())
        styleLevel.bind(ACCENT_TEXT, binding.tagsText, NachosChipTextColorAdapter())
        styleLevel.bind(ACCENT, binding.tagsText, NachosChipBgColorAdapter())
        styleLevel.bind(TEXT, binding.tagsTextLayout, EditTextLayoutHintAdapter())
        styleLevel.bind(TEXT_LINKS, binding.tagsTextLayout, EditTextLayoutBoxStrokeAdapter())

        styleLevel.bind(TEXT, binding.entryMarkdownPreview, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryMarkdownPreview, TextViewLinksAdapter())

        styleLevel.bind(TEXT_LINKS, binding.entryPermissionSelector, SpinnerDropdownColorAdapter())
        styleLevel.bind(TEXT_BLOCK, binding.entryPermissionSelector, SpinnerDropdownBackgroundColorAdapter())

        styleLevel.bind(TEXT, binding.entryDraftSwitch, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryDraftSwitch, CheckBoxAdapter())

        styleLevel.bind(TEXT, binding.entryPinnedSwitch, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entryPinnedSwitch, CheckBoxAdapter())

        styleLevel.bind(TEXT_LINKS, binding.entryPreview, ImageViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.entrySubmit, ImageViewColorAdapter())
    }

    /**
     * Populates UI elements from entry that is edited.
     * Call once when fragment is initialized.
     */
    private fun populateEditUI() {
        val editEntryId = requireArguments().getString(EDIT_ENTRY_ID)!!
        val editEntryTitle = requireArguments().getString(EDIT_ENTRY_TITLE)
        val editEntryTags = requireArguments().getStringArray(EDIT_ENTRY_TAGS)!!
        val editEntryContentHtml = requireArguments().getString(EDIT_ENTRY_CONTENT_HTML)!!
        val profSettings = requireArguments().getSerializable(PARENT_BLOG_PROFILE_SETTINGS) as ProfileSettings
        val editEntrySettings = requireArguments().getSerializable(EDIT_ENTRY_SETTINGS) as? RecordSettings

        binding.entryTitleText.setText(editEntryTitle)
        binding.entryDraftSwitch.isChecked = requireArguments().getString(EDIT_ENTRY_STATE) == "published"
        binding.entryPinnedSwitch.isChecked = profSettings.pinnedEntries.contains(editEntryId)
        // need to convert entry content (html) to Markdown somehow...
        val markdown = Html2Markdown().parseExtended(editEntryContentHtml)
        editor.setText(markdown)
        binding.tagsText.setText(editEntryTags.asList())

        // permission settings, if exist
        when (editEntrySettings?.permissions?.access?.firstOrNull()) {
            RecordAccessItem("private") -> binding.entryPermissionSelector.setSelection(0)
            RecordAccessItem("registered") -> binding.entryPermissionSelector.setSelection(1)
            RecordAccessItem("favorites") -> binding.entryPermissionSelector.setSelection(2)
            RecordAccessItem("subscribers") -> binding.entryPermissionSelector.setSelection(3)
            else -> binding.entryPermissionSelector.setSelection(4) // visible for all
        }
    }

    /**
     * Handler for clicking on "Preview" button
     */
    fun togglePreview() {
        if (binding.entryPreviewSwitcher.displayedChild == 0) {
            binding.entryPreviewSwitcher.displayedChild = 1
            binding.entryMarkdownPreview.handleMarkdownRaw(binding.entryEditor.sourceText.text.toString())
        } else {
            binding.entryPreviewSwitcher.displayedChild = 0
        }
    }

    /**
     * Change text on draft-publish checkbox based on what's currently selected
     */
    fun toggleDraftState(publish: Boolean) {
        if (publish) {
            binding.entryDraftSwitch.setText(R.string.publish_entry)
        } else {
            binding.entryDraftSwitch.setText(R.string.make_draft_entry)
        }
    }

    /**
     * Change text on pin-unpin checkbox based on what's currently selected
     */
    fun togglePinnedState(pinned: Boolean) {
        if (pinned) {
            binding.entryPinnedSwitch.setText(R.string.pinned_entry)
        } else {
            binding.entryPinnedSwitch.setText(R.string.non_pinned_entry)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        saveDraftEdit()
    }

    /**
     * Cancel this item editing (with confirmation)
     */
    private fun saveDraftEdit() {
        if (skipDraft) {
            return
        }

        val editMode = requireArguments().getBoolean(EDIT_MODE, false)
        if (editMode ||
                binding.entryTitleText.text.isNullOrEmpty() &&
                binding.entryEditor.sourceText.text.isNullOrEmpty() &&
                binding.tagsText.text.isNullOrEmpty()) {
            // entry has empty title and content, canceling right away
            return
        }

        // persist draft
        val parentProfileId = requireArguments().getString(PARENT_BLOG_PROFILE_ID)!!
        DbProvider.helper.draftDao.create(OfflineDraft(
                key = "entry,blog=${parentProfileId}",
                title = binding.entryTitleText.text.toString(),
                base = binding.entryEditor.sourceText.text.toString(),
                tags = binding.tagsText.chipValues.joinToString()
        ))
        Toast.makeText(activity, R.string.offline_draft_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Assemble entry creation request and submit it to the server
     */
    fun submit() {
        // hide keyboard
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)

        val access = when(binding.entryPermissionSelector.selectedItemPosition) {
            0 -> RecordAccessItem("private")
            1 -> RecordAccessItem("registered")
            2 -> RecordAccessItem("favorites")
            3 -> RecordAccessItem("subscribers")
            else -> null // visible for all
        }

        val entry = EntryCreateRequest().apply {
            title = binding.entryTitleText.text.toString()
            state = if (binding.entryDraftSwitch.isChecked) { "published" } else { "draft" }
            content = markdownToHtml(binding.entryEditor.sourceText.text.toString())
            tags = binding.tagsText.chipValues
            settings = RecordSettings(permissions = RecordPermissions(listOfNotNull(access)))
        }

        val progressDialog = MaterialDialog(requireContext())
                .title(R.string.please_wait)
                .message(R.string.submitting)

        // make http request
        lifecycleScope.launch {
            progressDialog.showThemed(styleLevel)

            try {
                val parentProfileId = requireArguments().getString(PARENT_BLOG_PROFILE_ID)!!
                val editMode = requireArguments().getBoolean(EDIT_MODE, false)
                if (editMode) {
                    // alter existing entry
                    entry.id = requireArguments().getString(EDIT_ENTRY_ID)!!
                    withContext(Dispatchers.IO) { Network.updateEntry(entry) }
                    Toast.makeText(activity, R.string.entry_updated, Toast.LENGTH_SHORT).show()
                } else {
                    // create new
                    entry.profile = HasOne(Auth.profile!!)
                    entry.community = HasOne("profiles", parentProfileId)
                    entry.id = withContext(Dispatchers.IO) { Network.createEntry(entry) }.id
                    Toast.makeText(activity, R.string.entry_created, Toast.LENGTH_SHORT).show()
                }

                // pin if needed
                val settings = requireArguments().getSerializable(PARENT_BLOG_PROFILE_SETTINGS) as ProfileSettings
                val pinnedAlready = settings.pinnedEntries
                when {
                    binding.entryPinnedSwitch.isChecked && !pinnedAlready.contains(entry.id) -> {
                        val req = ProfileCreateRequest().apply {
                            this.id = parentProfileId
                            this.settings = settings.copy(pinnedEntries = pinnedAlready.apply { add(entry.id) })
                        }
                        withContext(Dispatchers.IO) { Network.updateProfile(req) }
                    }
                    !binding.entryPinnedSwitch.isChecked && pinnedAlready.contains(entry.id) -> {
                        val req = ProfileCreateRequest().apply {
                            this.id = parentProfileId
                            this.settings = settings.copy(pinnedEntries = pinnedAlready.apply { remove(entry.id) })
                        }
                        withContext(Dispatchers.IO) { Network.updateProfile(req) }
                    }
                }
                // if we have current tab set, refresh it
                val frgPredicate = { it: Fragment -> it is UserContentListFragment && it.lifecycle.currentState == Lifecycle.State.RESUMED }
                val currentFrg = parentFragmentManager.fragments.reversed().find(frgPredicate) as UserContentListFragment?
                currentFrg?.loadMore(reset = true)

                skipDraft = true
                dismiss()
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
        if (binding.entryTitleText.text.isNullOrEmpty() && binding.entryEditor.sourceText.text.isNullOrEmpty())
            return

        // persist new draft
        DbProvider.helper.draftDao.create(OfflineDraft(
                title = binding.entryTitleText.text.toString(),
                base = binding.entryEditor.sourceText.text.toString(),
                tags = binding.tagsText.chipValues.joinToString()
        ))

        // clear the context and show notification
        binding.entryTitleText.setText("")
        binding.entryEditor.sourceText.setText("")
        Toast.makeText(context, R.string.offline_draft_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Loads a list of drafts from database and shows a dialog with list items to be selected.
     * After offline draft item is selected, this offline draft is deleted from the database and its contents
     * are applied to content of the editor.
     */
    private fun loadDraft() {
        val parentProfileId = requireArguments().getString(PARENT_BLOG_PROFILE_ID)!!
        val drafts = DbProvider.helper.draftDao.queryBuilder()
                .apply {
                    where()
                            .eq("key", "entry,blog=${parentProfileId}")
                            .or()
                            .isNull("key")
                }
                .orderBy("createdAt", false)
                .query()

        if (drafts.isEmpty())
            return

        if (!drafts[0].key.isNullOrBlank()) {
            // probably user saved it by clicking "cancel", load it
            popDraftUI(drafts[0])
            return
        }

        val adapter = DraftEntryViewAdapter(drafts)
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
        binding.entryTitleText.setText(draft.title)
        editor.setText(draft.content)
        binding.tagsText.setText(draft.tags?.split(Regex(",\\s+")).orEmpty().filterNot { it.isBlank() })
        Toast.makeText(context, R.string.offline_draft_loaded, Toast.LENGTH_SHORT).show()

        DbProvider.helper.draftDao.deleteById(draft.id)
    }

    /**
     * Recycler adapter to hold list of offline drafts that user saved
     */
    inner class DraftEntryViewAdapter(private val drafts: MutableList<OfflineDraft>) : RecyclerView.Adapter<DraftEntryViewAdapter.DraftEntryViewHolder>() {

        /**
         * Dismiss this if item is selected
         */
        lateinit var toDismiss: MaterialDialog

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DraftEntryViewHolder {
            val inflater = LayoutInflater.from(context)
            val itemBinding = FragmentEditFormDraftSelectionRowBinding.inflate(inflater, parent, false)
            return DraftEntryViewHolder(itemBinding)
        }

        override fun getItemCount() = drafts.size

        override fun onBindViewHolder(holder: DraftEntryViewHolder, position: Int) = holder.setup(position)

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
        inner class DraftEntryViewHolder(private val itemBinding: FragmentEditFormDraftSelectionRowBinding)
            : RecyclerView.ViewHolder(itemBinding.root) {

            private var pos: Int = 0
            private lateinit var draft: OfflineDraft

            init {
                itemBinding.root.setOnClickListener {
                    popDraftUI(draft)
                    toDismiss.dismiss()
                }

                itemBinding.draftDelete.setOnClickListener {
                    DbProvider.helper.draftDao.deleteById(draft.id)
                    Toast.makeText(context, R.string.offline_draft_deleted, Toast.LENGTH_SHORT).show()
                    removeItem(pos)
                }
            }

            fun setup(position: Int) {
                pos = position
                draft = drafts[position]
                itemBinding.draftDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(draft.createdAt)
                itemBinding.draftContent.text = draft.content
                itemBinding.draftTitle.text = draft.title
            }
        }
    }

    inner class TagsAdapter(context: Context, objects: List<String>)
        : ArrayAdapter<String>(context, R.layout.fragment_edit_form_tags_item, R.id.tag_text_label, objects) {

        private val filter = SubstringItemFilter(this, objects)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.fragment_edit_form_tags_item, parent, false)

            val tagName = view.findViewById<TextView>(R.id.tag_text_label)
            tagName.text = getItem(position)

            styleLevel.bind(TEXT, tagName, TextViewColorAdapter())

            return view
        }

        override fun getFilter() = filter
    }

    /**
     * Adapter for the spinner below the content editor.
     * Allows to select permission setting for current entry.
     *
     * Values vary from "visible for all" to "visible only for me"
     */
    inner class PermissionSpinnerAdapter(items: List<RecordAccessItem?>): ArrayAdapter<RecordAccessItem>(requireContext(), 0, items) {

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return getDropDownView(position, convertView, parent)
        }

        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)
            val inflater = LayoutInflater.from(context)
            val view = convertView ?: inflater.inflate(android.R.layout.simple_dropdown_item_1line, parent, false)

            (view as TextView).let {
                val accessDrawableRes = when(item) {
                    RecordAccessItem("private") -> R.drawable.eye_crossed
                    RecordAccessItem("registered") -> R.drawable.portrait
                    RecordAccessItem("favorites") -> R.drawable.account_favorited
                    RecordAccessItem("subscribers") -> R.drawable.account_liked
                    else -> R.drawable.earth
                }

                val accessDrawable = DrawableCompat.wrap(ResourcesCompat.getDrawable(context.resources, accessDrawableRes, null)!!)
                it.setCompoundDrawablesWithIntrinsicBounds(accessDrawable, null, null, null)
                it.compoundDrawablePadding = Utils.dpToPx(it.context, 4F).toInt()
                it.text = item.toDescription(it.context)

                styleLevel.bind(TEXT, it, TextViewColorAdapter())
                styleLevel.bind(TEXT_LINKS, it, TextViewDrawableAdapter())
            }

            return view
        }
    }
}