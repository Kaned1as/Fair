package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import butterknife.BindView
import butterknife.ButterKnife
import butterknife.OnClick
import com.afollestad.materialdialogs.MaterialDialog
import com.google.android.material.textfield.TextInputLayout
import com.kanedias.dybr.fair.dto.Auth
import com.kanedias.dybr.fair.dto.ProfileCreateRequest
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.showThemed
import io.github.anderscheow.validator.Validation
import io.github.anderscheow.validator.Validator
import io.github.anderscheow.validator.rules.common.MaxRule
import io.github.anderscheow.validator.rules.common.NotEmptyRule
import io.github.anderscheow.validator.rules.common.PastRule
import io.github.anderscheow.validator.rules.common.RegexRule
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Fragment for creating blog for currently logged in profile.
 *
 * @author Kanedias
 *
 * Created on 14.01.18
 */
class AddBlogFragment: Fragment() {

    @BindView(R.id.blog_slug)
    lateinit var slugInputLayout: TextInputLayout

    @BindView(R.id.blog_slug_input)
    lateinit var slugInput: EditText

    @BindView(R.id.blog_title)
    lateinit var titleInputLayout: TextInputLayout

    @BindView(R.id.blog_title_input)
    lateinit var titleInput: EditText

    private lateinit var activity: MainActivity

    private lateinit var slugCheck: Validation
    private lateinit var titleCheck: Validation

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_create_blog, container, false)
        ButterKnife.bind(this, view)
        activity = context as MainActivity

        val maxSlugLen = slugInputLayout.counterMaxLength
        slugCheck = Validation(slugInputLayout)
                .add(NotEmptyRule(R.string.must_be_not_empty))
                .add(RegexRule("[0-9a-zA-Z\\-]+", R.string.must_only_contain_alphanumeric))
                .add(MaxRule(maxSlugLen, getString(R.string.must_be_no_longer_than, maxSlugLen)))

        val maxTitleLen = titleInputLayout.counterMaxLength
        titleCheck = Validation(titleInputLayout)
                .add(NotEmptyRule(R.string.must_be_not_empty))
                .add(MaxRule(maxTitleLen, R.string.must_be_in_the_past))

        return view
    }

    @OnClick(R.id.blog_create_button)
    fun confirm(confirmBtn: View) {
        Validator.with(confirmBtn.context)
                .setListener(object: Validator.OnValidateListener {
                    override fun onValidateFailed(errors: List<String>) {}
                    override fun onValidateSuccess(values: List<String>) { doConfirm() }
                }).validate(slugCheck, titleCheck)
    }

    fun doConfirm() {
        val profReq = ProfileCreateRequest().apply {
            id = Auth.profile?.id
            blogSlug = slugInput.text.toString()
            blogTitle = titleInput.text.toString()
        }

        val progressDialog = MaterialDialog(requireContext())
                .title(R.string.please_wait)
                .message(R.string.checking_in_progress)

        lifecycleScope.launch {
            progressDialog.showThemed(activity.styleLevel)

            try {
                val updated = withContext(Dispatchers.IO) { Network.updateProfile(profReq) }
                Auth.updateCurrentProfile(updated)

                //we created blog successfully, return to main activity
                Toast.makeText(requireContext(), R.string.blog_created, Toast.LENGTH_SHORT).show()
                handleSuccess()
            } catch (ex: Exception) {
                Network.reportErrors(context, ex)
            }

            progressDialog.dismiss()
        }
    }

    /**
     * Handle successful blog addition. Navigate back to [MainActivity] and update sidebar account list.
     */
    private fun handleSuccess() {
        parentFragmentManager.popBackStack()
        activity.refresh()
    }
}