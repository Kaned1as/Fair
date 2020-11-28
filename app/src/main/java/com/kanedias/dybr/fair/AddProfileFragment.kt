package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onCancel
import com.afollestad.materialdialogs.datetime.datePicker
import com.kanedias.dybr.fair.databinding.FragmentCreateProfileBinding
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

    private lateinit var binding: FragmentCreateProfileBinding
    private lateinit var activity: MainActivity

    private lateinit var nicknameCheck: Validation
    private lateinit var birthdayCheck: Validation

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentCreateProfileBinding.inflate(inflater, container, false)
        activity = context as MainActivity

        val maxNicknameLen = binding.profNickname.counterMaxLength
        nicknameCheck = Validation(binding.profNickname)
                .add(NotEmptyRule(R.string.must_be_not_empty))
                .add(MaxRule(maxNicknameLen, getString(R.string.must_be_no_longer_than, maxNicknameLen)))

        birthdayCheck = Validation(binding.profBirthday)
                .add(RegexRule("\\d{4}-\\d{2}-\\d{2}", R.string.must_be_in_iso_form))
                .add(PastRule(SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()), R.string.must_be_in_the_past))

        val currentDate = Calendar.getInstance()
        val birthdayDatePicker = MaterialDialog(activity).apply {
            datePicker(maxDate = currentDate) { _, _ ->
                val year = currentDate.get(Calendar.YEAR)
                val month = currentDate.get(Calendar.MONTH) + 1
                val day = currentDate.get(Calendar.DAY_OF_MONTH)
                binding.profBirthdayInput.setText("%04d-%02d-%02d".format(year, month, day))
            }
            negativeButton {
                binding.profBirthdayInput.isFocusable = true
                binding.profBirthdayInput.isFocusableInTouchMode = true
                binding.profBirthdayInput.requestFocus()
            }
            onCancel {
                binding.profBirthdayInput.isFocusable = true
                binding.profBirthdayInput.isFocusableInTouchMode = true
                binding.profBirthdayInput.requestFocus()
            }
        }
        binding.profBirthdayInput.setOnClickListener { birthdayDatePicker.show() }
        binding.profBirthdayInput.setOnFocusChangeListener { v, hasFocus -> if (!hasFocus) v.isFocusable = false }
        binding.profCreateButton.setOnClickListener { confirm(it) }

        return binding.root
    }

    fun confirm(confirmBtn: View) {
        Validator.with(confirmBtn.context)
                .setListener(object: Validator.OnValidateListener {
                    override fun onValidateFailed(errors: List<String>) {}
                    override fun onValidateSuccess(values: List<String>) { doConfirm() }
                }).validate(nicknameCheck, birthdayCheck)
    }

    private fun doConfirm() {
        val profReq = ProfileCreateRequest().apply {
            nickname = binding.profNicknameInput.text.toString()
            birthday = binding.profBirthdayInput.text.toString()
            description = binding.profDescriptionInput.text.toString()
            isCommunity = binding.profCommunityMarker.isChecked
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