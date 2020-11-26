package com.kanedias.dybr.fair.ui

import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.content.pm.PackageManager.PERMISSION_GRANTED
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import android.text.method.LinkMovementMethod
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.core.text.HtmlCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.list.listItems
import com.ftinc.scoop.adapters.ImageViewColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.R
import com.kanedias.dybr.fair.databinding.FragmentEditFormBinding
import com.kanedias.dybr.fair.misc.styleLevel
import com.kanedias.dybr.fair.themes.*
import kotlinx.coroutines.*

/**
 * Fragment to hold all editing-related functions in all edit views where possible.
 *
 * @author Kanedias
 *
 * Created on 07.04.18
 */
class EditorViews(private val parent: Fragment, private val binding: FragmentEditFormBinding) {

    companion object {
        val LINE_START = Regex("^", RegexOption.MULTILINE)
    }

    private val ctx = binding.root.context

    private val permissionCall = parent.registerForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            uploadImage(binding.editQuickImage)
        } else {
            showToastAtView(binding.editQuickImage, R.string.no_permissions)
        }
    }

    private val selectImageCall = parent.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        requestImageUpload(uri)
    }

    init {
        binding.editFormattingHelperLabel.text = HtmlCompat.fromHtml(ctx.getString(R.string.markdown_basics), 0)
        binding.editFormattingHelperLabel.movementMethod = LinkMovementMethod.getInstance()

        binding.editQuickImage.setOnClickListener { uploadImage(binding.editQuickImage) }
        listOf(
                binding.editQuickBold,
                binding.editQuickItalic,
                binding.editQuickUnderlined,
                binding.editQuickStrikethrough,
                binding.editQuickCode,
                binding.editQuickQuote,
                binding.editQuickNumberList,
                binding.editQuickBulletList,
                binding.editQuickLink,
                binding.editQuickMore,
                binding.editQuickOfftopic,

        ).forEach { it.setOnClickListener { view -> editSelection(view) } }

        // start editing content right away
        binding.sourceText.requestFocus()

        setupTheming(binding.root)

    }

    private fun setupTheming(view: View) {
        val styleLevel = view.styleLevel ?: return

        for (idx in 0 until binding.editQuickButtonArea.childCount) {
            styleLevel.bind(TEXT_LINKS, binding.editQuickButtonArea.getChildAt(idx), ImageViewColorAdapter())
        }
        styleLevel.bind(TEXT, binding.editInsertFromClipboard, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.editInsertFromClipboard, CheckBoxAdapter())
        styleLevel.bind(TEXT, binding.sourceText, EditTextAdapter())
        styleLevel.bind(TEXT_LINKS, binding.sourceText, EditTextLineAdapter())
        styleLevel.bind(TEXT_LINKS, binding.sourceTextLayout, EditTextLayoutBoxStrokeAdapter())
        styleLevel.bind(TEXT, binding.sourceTextLayout, EditTextLayoutHintAdapter())
        styleLevel.bind(TEXT, binding.editFormattingHelperLabel, TextViewColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.editFormattingHelperLabel, TextViewLinksAdapter())
    }

    /**
     * Handler of all small editing buttons above content input.
     */
    fun editSelection(clicked: View) {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var paste = if (binding.editInsertFromClipboard.isChecked && clipboard.hasPrimaryClip() && clipboard.primaryClip!!.itemCount > 0) {
            clipboard.primaryClip!!.getItemAt(0).text.toString()
        } else {
            ""
        }

        // check whether we have text selected in content input
        if (paste.isEmpty() && binding.sourceText.hasSelection()) {
            // delete selection
            paste = binding.sourceText.text!!.substring(binding.sourceText.selectionStart until binding.sourceText.selectionEnd)
            binding.sourceText.text!!.delete(binding.sourceText.selectionStart, binding.sourceText.selectionEnd)
        }

        val moreTxt = ctx.getString(R.string.more_tag_default)

        when (clicked.id) {
            R.id.edit_quick_bold -> insertInCursorPosition("<b>", paste, "</b>")
            R.id.edit_quick_italic -> insertInCursorPosition( "<i>", paste, "</i>")
            R.id.edit_quick_underlined -> insertInCursorPosition("<u>", paste, "</u>")
            R.id.edit_quick_strikethrough -> insertInCursorPosition("<s>", paste, "</s>")
            R.id.edit_quick_code -> insertInCursorPosition("```\n", paste, "\n```\n")
            R.id.edit_quick_quote -> insertInCursorPosition(paste.replace(LINE_START, "> ") + "\n\n", "")
            R.id.edit_quick_number_list -> insertInCursorPosition("\n1. ", paste, "\n2. \n3. ")
            R.id.edit_quick_bullet_list -> insertInCursorPosition("\n* ", paste, "\n* \n* ")
            R.id.edit_quick_link -> insertInCursorPosition("<a href=\"$paste\">", paste, "</a>")
            R.id.edit_quick_image -> insertInCursorPosition("<img src='", paste, "' />")
            R.id.edit_quick_more -> insertInCursorPosition("[MORE=$moreTxt]", paste, "[/MORE]")
            R.id.edit_quick_offtopic -> insertInCursorPosition("<span class='offtop'>", paste, "</span>")
        }

        binding.editInsertFromClipboard.isChecked = false
    }

    /**
     * Image upload button requires special handling
     */
    fun uploadImage(clicked: View) {
        // sometimes we need SD-card access to load the image
        if (ContextCompat.checkSelfPermission(ctx, WRITE_EXTERNAL_STORAGE) != PERMISSION_GRANTED) {
            permissionCall.launch(WRITE_EXTERNAL_STORAGE)
            return
        }

        if (binding.editInsertFromClipboard.isChecked) {
            // delegate to just paste image link from clipboard
            editSelection(clicked)
            return
        }

        // not from clipboard, show upload dialog
        selectImageCall.launch("image/*")
    }

    private fun requestImageUpload(uri: Uri?) {
        if (uri == null)
            return

        val stream = parent.activity?.contentResolver?.openInputStream(uri) ?: return

        val dialog = MaterialDialog(ctx)
                .title(R.string.please_wait)
                .message(R.string.uploading)

        GlobalScope.launch(Dispatchers.Main) {
            dialog.show()

            try {
                val link = withContext(Dispatchers.IO) { Network.uploadImage(stream.readBytes()) }
                MaterialDialog(ctx)
                        .title(R.string.insert_image)
                        .message(R.string.select_image_width)
                        .listItems(res = R.array.image_sizes, selection = {_, index, _ ->
                            val spec = when (index) {
                                0 -> "100"
                                1 -> "200"
                                2 -> "300"
                                3 -> "500"
                                4 -> "800"
                                else -> "auto"
                            }
                            insertInCursorPosition("<img width='$spec' height='auto' src='", link, "' />")
                        }).show()
            } catch (ex: Exception) {
                Network.reportErrors(ctx, ex)
            }

            dialog.dismiss()
        }
    }

    /**
     * Helper function for inserting quick snippets of markup into the various parts of edited text
     * @param prefix prefix preceding content.
     *          This is most likely non-empty. Cursor is positioned after it in all cases.
     * @param what content to insert.
     *          If it's empty and [suffix] is not, cursor will be positioned here
     * @param suffix suffix after content. Can be empty fairly often. Cursor will be placed after it if [what] is
     *          not empty.
     */
    private fun insertInCursorPosition(prefix: String, what: String, suffix: String = "") {
        var cursorPos = binding.sourceText.selectionStart
        if (cursorPos == -1)
            cursorPos = binding.sourceText.text!!.length

        val beforeCursor = binding.sourceText.text!!.substring(0, cursorPos)
        val afterCursor = binding.sourceText.text!!.substring(cursorPos, binding.sourceText.text!!.length)

        val beforeCursorWithPrefix = beforeCursor + prefix
        val suffixWithAfterCursor = suffix + afterCursor
        val result = beforeCursorWithPrefix + what + suffixWithAfterCursor
        binding.sourceText.setText(result)

        binding.sourceText.setSelection(cursorPos + prefix.length, cursorPos + prefix.length + what.length)
    }
}