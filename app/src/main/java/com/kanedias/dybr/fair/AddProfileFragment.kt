package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
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
import com.kanedias.dybr.fair.dto.ProfileSettings
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
 * Fragment for creating profile for currently logged in account.
 *
 * @author Kanedias
 *
 * Created on 24.12.17
 */
class AddProfileFragment: Fragment() {

    @BindView(R.id.prof_nickname)
    lateinit var nicknameInputLayout: TextInputLayout

    @BindView(R.id.prof_nickname_input)
    lateinit var nicknameInput: EditText

    @BindView(R.id.prof_birthday)
    lateinit var birthdayInputLayout: TextInputLayout

    @BindView(R.id.prof_birthday_input)
    lateinit var birthdayInput: EditText

    @BindView(R.id.prof_description_input)
    lateinit var descInput: EditText

    @BindView(R.id.prof_community_marker)
    lateinit var communityMarker: CheckBox

    private lateinit var activity: MainActivity

    private lateinit var nicknameCheck: Validation
    private lateinit var birthdayCheck: Validation

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_create_profile, container, false)
        ButterKnife.bind(this, view)
        activity = context as MainActivity

        val maxNicknameLen = nicknameInputLayout.counterMaxLength
        nicknameCheck = Validation(nicknameInputLayout)
                .add(NotEmptyRule(R.string.must_be_not_empty))
                .add(MaxRule(maxNicknameLen, getString(R.string.must_be_no_longer_than, maxNicknameLen)))

        birthdayCheck = Validation(birthdayInputLayout)
                .add(RegexRule("\\d{4}-\\d{2}-\\d{2}", R.string.must_be_in_iso_form))
                .add(PastRule(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()), R.string.must_be_in_the_past))


        return view
    }

    @OnClick(R.id.prof_create_button)
    fun confirm(confirmBtn: View) {
        Validator.with(confirmBtn.context)
                .setListener(object: Validator.OnValidateListener {
                    override fun onValidateFailed(errors: List<String>) {}
                    override fun onValidateSuccess(values: List<String>) { doConfirm() }
                }).validate(nicknameCheck, birthdayCheck)
    }

    private fun doConfirm() {
        val profReq = ProfileCreateRequest().apply {
            nickname = nicknameInput.text.toString()
            birthday = birthdayInput.text.toString()
            description = descInput.text.toString()
            isCommunity = communityMarker.isChecked
        }

        if (profReq.isCommunity) {
            // community settings are essential, for now we need
            // them to be auto-joinable while we don't yet have handling of manual requests
            profReq.settings = ProfileSettings().apply { community.joinRequest = "auto" }

        }

        val progressDialog = MaterialDialog(requireContext())
                .title(R.string.please_wait)
                .message(R.string.checking_in_progress)

        lifecycleScope.launch {
            progressDialog.showThemed(activity.styleLevel)

            try {
                val profile = withContext(Dispatchers.IO) { Network.createProfile(profReq) }
                Auth.updateCurrentProfile(profile)

                //we created profile successfully, return to main activity
                Toast.makeText(context, R.string.profile_created, Toast.LENGTH_SHORT).show()
                handleSuccess()
            } catch (ex: Exception) {
                Network.reportErrors(context, ex, mapOf(422 to R.string.invalid_credentials))
            }

            progressDialog.dismiss()
        }
    }

    /**
     * Handle successful account addition. Navigate back to [MainActivity] and update sidebar account list.
     */
    private fun handleSuccess() {
        parentFragmentManager.popBackStack()
        activity.refresh()
    }
}