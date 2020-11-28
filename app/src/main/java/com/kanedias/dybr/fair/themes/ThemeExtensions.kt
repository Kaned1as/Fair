package com.kanedias.dybr.fair.themes

import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.getActionButton
import com.afollestad.materialdialogs.checkbox.getCheckBoxPrompt
import com.afollestad.materialdialogs.customview.getCustomView
import com.afollestad.materialdialogs.input.getInputField
import com.afollestad.materialdialogs.internal.button.DialogActionButton
import com.ftinc.scoop.StyleLevel
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.kanedias.dybr.fair.R

fun MaterialDialog.showThemed(styleLevel: StyleLevel) {

    val dialogTitle = this.view.titleLayout.findViewById(R.id.md_text_title) as TextView
    val dialogMessage = this.view.contentLayout.findViewById(R.id.md_text_message) as? TextView
    val posButton = this.view.buttonsLayout?.findViewById<DialogActionButton>(R.id.md_button_positive)
    val neuButton = this.view.buttonsLayout?.findViewById<DialogActionButton>(R.id.md_button_neutral)
    val negButton = this.view.buttonsLayout?.findViewById<DialogActionButton>(R.id.md_button_negative)
    val checkBox = this.view.buttonsLayout?.findViewById(R.id.md_checkbox_prompt) as? CheckBox
    val textInput = this.view.contentLayout.findViewById(R.id.md_input_message) as? EditText

    styleLevel.bind(TEXT_BLOCK, this.view, NoRewriteBgPicAdapter())
    styleLevel.bind(TEXT_HEADERS, dialogTitle, TextViewColorAdapter())
    styleLevel.bind(TEXT, dialogMessage, TextViewColorAdapter())

    posButton?.let { styleLevel.bind(TEXT_LINKS, it, MaterialDialogButtonAdapter()) }
    neuButton?.let { styleLevel.bind(TEXT_LINKS, it, MaterialDialogButtonAdapter()) }
    negButton?.let { styleLevel.bind(TEXT_LINKS, it, MaterialDialogButtonAdapter()) }

    checkBox?.let { styleLevel.bind(TEXT_LINKS, it, CheckBoxAdapter()) }
    textInput?.let {
        styleLevel.bind(TEXT_LINKS, it, EditTextLineAdapter())
        styleLevel.bind(TEXT, it, EditTextAdapter())
    }

    show()
}