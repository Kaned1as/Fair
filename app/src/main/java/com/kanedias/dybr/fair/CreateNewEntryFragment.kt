package com.kanedias.dybr.fair

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.graphics.drawable.DrawableCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnCheckedChanged
import butterknife.OnClick
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.customListAdapter
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.StyleLevel
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.ftinc.scoop.util.Utils
import com.google.android.material.textfield.TextInputLayout
import com.hootsuite.nachos.NachoTextView
import com.hootsuite.nachos.terminator.ChipTerminatorHandler
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.database.entities.OfflineDraft
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.markdown.handleMarkdownRaw
import com.kanedias.dybr.fair.markdown.markdownToHtml
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.*
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
class CreateNewEntryFragment : Fragment() {

    /**
     * Title of future diary entry
     */
    @BindView(R.id.entry_title_text)
    lateinit var titleInput: EditText

    @BindView(R.id.entry_title_text_layout)
    lateinit var titleInputLayout: TextInputLayout

    /**
     * Content of entry
     */
    @BindView(R.id.source_text)
    lateinit var contentInput: EditText

    /**
     * Entry tag editor
     */
    @BindView(R.id.tags_text)
    lateinit var tagsInput: NachoTextView

    /**
     * Permission type of an entry: visible for all, for registered
     * or something else.
     */
    @BindView(R.id.entry_permission_selector)
    lateinit var permissionSpinner: AppCompatSpinner

    /**
     * Markdown preview
     */
    @BindView(R.id.entry_markdown_preview)
    lateinit var preview: TextView

    /**
     * View switcher between preview and editor
     */
    @BindView(R.id.entry_preview_switcher)
    lateinit var previewSwitcher: ViewSwitcher

    /**
     * Switch between publication and draft.
     * Draft entries are only visible to the owner and have "draft" attribute set.
     */
    @BindView(R.id.entry_draft_switch)
    lateinit var draftSwitch: CheckBox

    /**
     * Switch between "pinned" and "unpinned" state of entry.
     * Pinned entries are always on top of the blog.
     */
    @BindView(R.id.entry_pinned_switch)
    lateinit var pinSwitch: CheckBox

    /**
     * Switch between input and preview
     */
    @BindView(R.id.entry_preview)
    lateinit var previewButton: Button

    /**
     * Submit new/edited entry
     */
    @BindView(R.id.entry_submit)
    lateinit var submitButton: Button

    private lateinit var styleLevel: StyleLevel

    var editMode = false // create new by default

    /**
     * Entry that is being edited. Only set if [editMode] is `true`
     */
    lateinit var editEntry: Entry

    /**
     * Blog this entry belongs to. Should be always set.
     */
    lateinit var profile: OwnProfile

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        savedInstanceState?.getBoolean("editMode")?.let { editMode = it }
        savedInstanceState?.getSerializable("editEntry")?.let { editEntry = it as Entry }
        savedInstanceState?.getSerializable("profile")?.let { profile = it as OwnProfile }

        val view = inflater.inflate(R.layout.fragment_create_entry, container, false)
        ButterKnife.bind(this, view)

        setupUI()
        setupTheming(view)

        if (editMode) {
            // we're editing existing entry, populate UI with its contents and settings
            populateEditUI()
        } else {
            // It's new entry, if we canceled it previously, it may be saved to offline draft
            loadDraft()
        }

        return view
    }

    private fun setupUI() {
        permissionSpinner.adapter = PermissionSpinnerAdapter(listOf(
                RecordAccessItem("private"),
                RecordAccessItem("registered"),
                RecordAccessItem("favorites"),
                RecordAccessItem("subscribers"),
                null /* visible for all */))
        permissionSpinner.setSelection(4) // select "Visible for all" by default

        // tags autocompletion
        lifecycleScope.launch {
            Network.perform(
                    networkAction = { Network.loadProfile(profile.id) },
                    uiAction = { prof ->
                        val tags = prof.tags.map { it.name }
                        val adapter = TagsAdapter(requireContext(), tags)
                        tagsInput.setAdapter(adapter)
                    })
        }
        tagsInput.addChipTerminator('\n', ChipTerminatorHandler.BEHAVIOR_CHIPIFY_ALL)
        tagsInput.addChipTerminator(',', ChipTerminatorHandler.BEHAVIOR_CHIPIFY_ALL)
        tagsInput.onFocusChangeListener = View.OnFocusChangeListener { _, focused ->
            if (focused && tagsInput.text.isNullOrBlank()) {
                tagsInput.showDropDown()
            }
        }
    }

    private fun setupTheming(view: View) {
        styleLevel = Scoop.getInstance().addStyleLevel()
        lifecycle.addObserver(styleLevel)

        styleLevel.bind(TEXT_BLOCK, view, BackgroundNoAlphaAdapter())
        styleLevel.bind(TEXT, titleInput, EditTextAdapter())
        styleLevel.bind(TEXT_LINKS, titleInput, EditTextLineAdapter())
        styleLevel.bind(TEXT, titleInputLayout, EditTextLayoutHintAdapter())
        styleLevel.bind(TEXT_LINKS, titleInputLayout, EditTextLayoutBoxStrokeAdapter())

        styleLevel.bind(TEXT, tagsInput, EditTextAdapter())
        styleLevel.bind(TEXT_LINKS, tagsInput, EditTextLineAdapter())
        styleLevel.bind(TEXT_BLOCK, tagsInput, AutocompleteDropdownNoAlphaAdapter())
        styleLevel.bind(ACCENT_TEXT, tagsInput, NachosChipTextColorAdapter())
        styleLevel.bind(ACCENT, tagsInput, NachosChipBgColorAdapter())

        styleLevel.bind(TEXT, preview, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, preview, TextViewLinksAdapter())

        styleLevel.bind(TEXT_LINKS, permissionSpinner, SpinnerDropdownColorAdapter())
        styleLevel.bind(TEXT_BLOCK, permissionSpinner, SpinnerDropdownBackgroundColorAdapter())

        styleLevel.bind(TEXT, draftSwitch, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, draftSwitch, CheckBoxAdapter())

        styleLevel.bind(TEXT, pinSwitch, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, pinSwitch, CheckBoxAdapter())

        styleLevel.bind(TEXT_LINKS, previewButton, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, submitButton, TextViewColorAdapter())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        outState.putBoolean("editMode", editMode)
        when (editMode) {
            false -> outState.putSerializable("profile", profile)
            true -> outState.putSerializable("editEntry", editEntry)
        }
    }

    /**
     * Populates UI elements from entry that is edited.
     * Call once when fragment is initialized.
     */
    private fun populateEditUI() {
        titleInput.setText(editEntry.title)
        draftSwitch.isChecked = editEntry.state == "published"
        pinSwitch.isChecked = profile.settings.pinnedEntries.contains(editEntry.id)
        // need to convert entry content (html) to Markdown somehow...
        val markdown = Html2Markdown().parseExtended(editEntry.content)
        contentInput.setText(markdown)
        tagsInput.setText(editEntry.tags)

        // permission settings, if exist
        when (editEntry.settings?.permissions?.access?.firstOrNull()) {
            RecordAccessItem("private") -> permissionSpinner.setSelection(0)
            RecordAccessItem("registered") -> permissionSpinner.setSelection(1)
            RecordAccessItem("favorites") -> permissionSpinner.setSelection(2)
            RecordAccessItem("subscribers") -> permissionSpinner.setSelection(3)
            else -> permissionSpinner.setSelection(4) // visible for all
        }
    }

    /**
     * Handler for clicking on "Preview" button
     */
    @OnClick(R.id.entry_preview)
    fun togglePreview() {
        if (previewSwitcher.displayedChild == 0) {
            preview.handleMarkdownRaw(contentInput.text.toString())
            previewSwitcher.displayedChild = 1
        } else {
            previewSwitcher.displayedChild = 0
        }
    }

    /**
     * Change text on draft-publish checkbox based on what's currently selected
     */
    @OnCheckedChanged(R.id.entry_draft_switch)
    fun toggleDraftState(publish: Boolean) {
        if (publish) {
            draftSwitch.setText(R.string.publish_entry)
        } else {
            draftSwitch.setText(R.string.make_draft_entry)
        }
    }

    /**
     * Change text on pin-unpin checkbox based on what's currently selected
     */
    @OnCheckedChanged(R.id.entry_pinned_switch)
    fun togglePinnedState(pinned: Boolean) {
        if (pinned) {
            pinSwitch.setText(R.string.pinned_entry)
        } else {
            pinSwitch.setText(R.string.non_pinned_entry)
        }
    }

    /**
     * Cancel this item editing (with confirmation)
     */
    @Suppress("DEPRECATION") // getColor doesn't work up to API level 23
    @OnClick(R.id.entry_cancel)
    fun cancel() {
        if (editMode || titleInput.text.isNullOrEmpty() && contentInput.text.isNullOrEmpty() && tagsInput.text.isNullOrEmpty()) {
            // entry has empty title and content, canceling right away
            requireFragmentManager().popBackStack()
            return
        }

        // persist draft
        DbProvider.helper.draftDao.create(OfflineDraft(
                key = "entry,blog=${profile.id}",
                title = titleInput.text.toString(),
                base = contentInput.text.toString(),
                tags = tagsInput.chipValues.joinToString()
        ))
        Toast.makeText(activity, R.string.offline_draft_saved, Toast.LENGTH_SHORT).show()
        requireFragmentManager().popBackStack()
    }

    /**
     * Assemble entry creation request and submit it to the server
     */
    @OnClick(R.id.entry_submit)
    fun submit() {
        // hide keyboard
        val imm = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(requireView().windowToken, 0)

        val access = when(permissionSpinner.selectedItemPosition) {
            0 -> RecordAccessItem("private")
            1 -> RecordAccessItem("registered")
            2 -> RecordAccessItem("favorites")
            3 -> RecordAccessItem("subscribers")
            else -> null // visible for all
        }

        val entry = EntryCreateRequest().apply {
            title = titleInput.text.toString()
            state = if (draftSwitch.isChecked) { "published" } else { "draft" }
            content = markdownToHtml(contentInput.text.toString())
            tags = tagsInput.chipValues
            settings = RecordSettings(permissions = RecordPermissions(listOfNotNull(access)))
        }

        val progressDialog = MaterialDialog(requireContext())
                .title(R.string.please_wait)
                .message(R.string.submitting)

        // make http request
        lifecycleScope.launch {
            progressDialog.showThemed(styleLevel)

            try {
                if (editMode) {
                    // alter existing entry
                    entry.id = editEntry.id
                    withContext(Dispatchers.IO) { Network.updateEntry(entry) }
                    Toast.makeText(activity, R.string.entry_updated, Toast.LENGTH_SHORT).show()
                } else {
                    // create new
                    entry.profile = HasOne(Auth.profile!!)
                    entry.community = HasOne(this@CreateNewEntryFragment.profile)
                    entry.id = withContext(Dispatchers.IO) { Network.createEntry(entry) }.id
                    Toast.makeText(activity, R.string.entry_created, Toast.LENGTH_SHORT).show()
                }

                // pin if needed
                val settings = profile.settings
                val pinnedAlready = settings.pinnedEntries
                when {
                    pinSwitch.isChecked && !pinnedAlready.contains(entry.id) -> {
                        val req = ProfileCreateRequest().apply {
                            this.id = profile.id
                            this.settings = settings.copy(pinnedEntries = pinnedAlready.apply { add(entry.id) })
                        }
                        withContext(Dispatchers.IO) { Network.updateProfile(req) }
                    }
                    !pinSwitch.isChecked && pinnedAlready.contains(entry.id) -> {
                        val req = ProfileCreateRequest().apply {
                            this.id = profile.id
                            this.settings = settings.copy(pinnedEntries = pinnedAlready.apply { remove(entry.id) })
                        }
                        withContext(Dispatchers.IO) { Network.updateProfile(req) }
                    }
                }
                parentFragmentManager.popBackStack()

                // if we have current tab set, refresh it
                val frgPredicate = { it: Fragment -> it is UserContentListFragment && it.lifecycle.currentState == Lifecycle.State.RESUMED }
                val currentFrg = parentFragmentManager.fragments.reversed().find(frgPredicate) as UserContentListFragment?
                currentFrg?.loadMore(reset = true)
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
        if (titleInput.text.isNullOrEmpty() && contentInput.text.isNullOrEmpty())
            return

        // persist new draft
        DbProvider.helper.draftDao.create(OfflineDraft(
                title = titleInput.text.toString(),
                base = contentInput.text.toString(),
                tags = tagsInput.chipValues.joinToString()
        ))

        // clear the context and show notification
        titleInput.setText("")
        contentInput.setText("")
        Toast.makeText(context, R.string.offline_draft_saved, Toast.LENGTH_SHORT).show()
    }

    /**
     * Loads a list of drafts from database and shows a dialog with list items to be selected.
     * After offline draft item is selected, this offline draft is deleted from the database and its contents
     * are applied to content of the editor.
     */
    private fun loadDraft() {
        val drafts = DbProvider.helper.draftDao.queryBuilder()
                .apply {
                    where()
                            .eq("key", "entry,blog=${profile.id}")
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
        titleInput.setText(draft.title)
        contentInput.setText(draft.content)
        tagsInput.setText(draft.tags?.split(Regex(",\\s+")))
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
            val v = inflater.inflate(R.layout.fragment_edit_form_draft_selection_row, parent, false)
            return DraftEntryViewHolder(v)
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
        inner class DraftEntryViewHolder(v: View): RecyclerView.ViewHolder(v) {

            private var pos: Int = 0
            private lateinit var draft: OfflineDraft

            @BindView(R.id.draft_date)
            lateinit var draftDate: TextView

            @BindView(R.id.draft_delete)
            lateinit var draftDelete: ImageView

            @BindView(R.id.draft_title)
            lateinit var draftTitle: TextView

            @BindView(R.id.draft_content)
            lateinit var draftContent: TextView

            init {
                ButterKnife.bind(this, v)
                v.setOnClickListener {
                    popDraftUI(draft)
                    toDismiss.dismiss()
                }

                draftDelete.setOnClickListener {
                    DbProvider.helper.draftDao.deleteById(draft.id)
                    Toast.makeText(context, R.string.offline_draft_deleted, Toast.LENGTH_SHORT).show()
                    removeItem(pos)
                }
            }

            fun setup(position: Int) {
                pos = position
                draft = drafts[position]
                draftDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(draft.createdAt)
                draftContent.text = draft.content
                draftTitle.text = draft.title
            }
        }
    }

    inner class TagsAdapter : ArrayAdapter<String> {

        constructor(context: Context, objects: List<String>)
                : super(context, R.layout.fragment_edit_form_tags_item, R.id.tag_text_label, objects)

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.fragment_edit_form_tags_item, parent, false)

            val tagName = view.findViewById<TextView>(R.id.tag_text_label)
            tagName.text = getItem(position)

            styleLevel.bind(TEXT, tagName, TextViewColorAdapter())

            return view
        }
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

                @Suppress("DEPRECATION") // minSDK == 14 for now
                val accessDrawable = DrawableCompat.wrap(it.context.resources.getDrawable(accessDrawableRes))
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