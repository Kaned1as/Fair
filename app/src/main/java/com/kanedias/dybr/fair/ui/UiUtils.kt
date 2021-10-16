package com.kanedias.dybr.fair.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.anderscheow.validator.Validation
import io.github.anderscheow.validator.rules.BaseRule


/**
 * Enables or disables all child views recursively descending from [view]
 * @param view view to disable
 * @param enabled true if all views need to be enabled, false otherwise
 */
fun toggleEnableRecursive(view: View, enabled: Boolean) {
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            toggleEnableRecursive(view.getChildAt(i), enabled)
        }
    }

    // dim image if it's image view
    if (view is ImageView) {
        val res = view.drawable.mutate()
        res.colorFilter = when (enabled) {
            true -> null
            false -> PorterDuffColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN)
        }
    }

    // disable for good
    view.isEnabled = enabled
}

/**
 * Show toast exactly under specified view
 *
 * @param view view at which toast should be located
 * @param text text of toast
 */
fun showToastAtView(view: View, text: String) {
    val toast = Toast.makeText(view.context, text, Toast.LENGTH_SHORT)

    val location = IntArray(2)
    view.getLocationOnScreen(location)

    toast.setGravity(Gravity.TOP or Gravity.START, location[0] - 25, location[1] - 10)
    toast.show()
}

fun showToastAtView(view: View, @StringRes textResId: Int) {
    val textStr = view.context.getString(textResId)
    showToastAtView(view, textStr)
}

/**
 * Open the URL using the default browser on this device
 */
fun openUrlExternally(ctx: Context, uri: Uri) {
    val pkgMgr = ctx.packageManager
    val intent = Intent(Intent.ACTION_VIEW, uri)

    // detect default browser
    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
    val defaultBrowser = pkgMgr.resolveActivity(browserIntent, PackageManager.MATCH_DEFAULT_ONLY) ?: return
    // use default browser to open the url
    intent.component = with(defaultBrowser.activityInfo) { ComponentName(applicationInfo.packageName, name) }
    ctx.startActivity(intent)
}

class CheckBoxCheckedRule(private val view: CheckBox, errorRes: Int) : BaseRule(errorRes) {

    override fun validate(value: String?): Boolean {
        when (view.isChecked) {
            true -> view.error = null
            false -> view.error = getErrorMessage()
        }

        return view.isChecked
    }

}

class PasswordMatchRule(private val passwordInput: EditText, errorRes: Int) : BaseRule(errorRes) {

    override fun validate(value: String?): Boolean {
        return passwordInput.text.toString() == value
    }

}