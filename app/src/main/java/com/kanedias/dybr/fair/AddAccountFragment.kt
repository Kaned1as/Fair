package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.database.entities.Account
import com.kanedias.dybr.fair.databinding.FragmentCreateAccountBinding
import com.kanedias.dybr.fair.dto.RegisterRequest
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.showThemed
import com.kanedias.dybr.fair.ui.CheckBoxCheckedRule
import com.kanedias.dybr.fair.ui.PasswordMatchRule
import com.kanedias.dybr.fair.ui.Sidebar
import io.github.anderscheow.validator.Validation
import io.github.anderscheow.validator.Validator
import io.github.anderscheow.validator.rules.common.notEmpty
import io.github.anderscheow.validator.rules.regex.EmailRule
import kotlinx.coroutines.*

/**
 * Fragment responsible for adding account. Appears when you click "add account" in the sidebar.
 * This may be either registration or logging in.
 *
 * @see Sidebar.addAccount
 * @author Kanedias
 *
 * Created on 11/11/2017.
 */
class AddAccountFragment : Fragment() {

    private lateinit var activity: MainActivity
    private lateinit var emailCheck: Validation
    private lateinit var tosCheck: Validation
    private lateinit var ageCheck: Validation
    private lateinit var pwdCheck: Validation
    private lateinit var pwdConfirmCheck: Validation

    private lateinit var binding: FragmentCreateAccountBinding
    private lateinit var registerInputs: List<View>
    private lateinit var loginInputs: List<View>

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentCreateAccountBinding.inflate(inflater, container, false)
        activity = context as MainActivity

        loginInputs = listOf(binding.accEmail, binding.accPassword)
        registerInputs = listOf(
                binding.accEmail,
                binding.accPassword,
                binding.accPasswordConfirm,
                binding.accTermsofserviceCheckbox,
                binding.accIsOver18Checkbox
        )

        binding.registerCheckbox.setOnCheckedChangeListener { _, checked -> switchToRegister(checked) }
        binding.confirmButton.setOnClickListener { confirm(binding.confirmButton) }

        emailCheck = Validation(binding.accEmail).add(EmailRule(R.string.email_is_invalid))
        pwdCheck = Validation(binding.accPassword).notEmpty(R.string.must_be_not_empty)
        pwdConfirmCheck = Validation(binding.accPasswordConfirm).add(PasswordMatchRule(binding.accPasswordInput, R.string.passwords_not_match))
        tosCheck = Validation("").add(CheckBoxCheckedRule(binding.accTermsofserviceCheckbox, R.string.tos_not_accepted_error))
        ageCheck = Validation("").add(CheckBoxCheckedRule(binding.accIsOver18Checkbox, R.string.age_not_confirmed_error))

        return binding.root
    }

    /**
     * Show/hide additional register fields, change main button text
     */
    fun switchToRegister(checked: Boolean) {
        if (checked) {
            binding.confirmButton.setText(R.string.register)
            loginInputs.forEach { it.visibility = View.GONE }
            registerInputs.forEach { it.visibility = View.VISIBLE }
        } else {
            binding.confirmButton.setText(R.string.enter)
            registerInputs.forEach { it.visibility = View.GONE }
            loginInputs.forEach { it.visibility = View.VISIBLE }
        }
        binding.accEmailInput.requestFocus()
    }

    /**
     * Performs register/login call after all fields are validated
     */
    fun confirm(confirmBtn: View) {
        if (binding.registerCheckbox.isChecked) {
            // send register query
            Validator.with(confirmBtn.context)
                    .setListener(object: Validator.OnValidateListener {
                        override fun onValidateFailed(errors: List<String>) {}
                        override fun onValidateSuccess(values: List<String>) { doRegistration() }
                    }).validate(emailCheck, tosCheck, ageCheck, pwdCheck, pwdConfirmCheck)
        } else {
            // send login query
            Validator.with(confirmBtn.context)
                    .setListener(object: Validator.OnValidateListener {
                        override fun onValidateFailed(errors: List<String>) {}
                        override fun onValidateSuccess(values: List<String>) { doLogin() }
                    }).validate(emailCheck, pwdCheck)

        }

    }

    /**
     * Creates session for the user, saves auth and closes fragment on success.
     */
    private fun doLogin() {
        if (DbProvider.helper.accDao.queryForEq("email", binding.accEmailInput.text.toString()).isNotEmpty()) {
            // we already have this account as active, skip!
            Toast.makeText(activity, R.string.email_already_added, Toast.LENGTH_SHORT).show()
            return
        }

        val acc = Account().apply {
            email = binding.accEmailInput.text.toString()
            password = binding.accPasswordInput.text.toString()
            current = true
        }

        val progressDialog = MaterialDialog(requireContext())
                .title(R.string.please_wait)
                .message(R.string.checking_in_progress)

        lifecycleScope.launch {
            progressDialog.showThemed(activity.styleLevel)

            try {
                withContext(Dispatchers.IO) { Network.login(acc) }
                Toast.makeText(requireContext(), R.string.login_successful, Toast.LENGTH_SHORT).show()

                //we logged in successfully, return to main activity
                DbProvider.helper.accDao.create(acc)

                activity.performLogin(acc)
                parentFragmentManager.popBackStack()
            } catch (ex: Exception) {
                val errorMap = mapOf(
                        "email_not_confirmed" to getString(R.string.email_not_activated_yet),
                        "email_not_found" to getString(R.string.email_not_found),
                        "email_invalid" to getString(R.string.email_is_invalid),
                        "password_invalid" to getString(R.string.incorrect_password)
                )

                Network.reportErrors(ctx = context, ex = ex, detailMapping = errorMap)
            }

            progressDialog.dismiss()
        }
    }

    /**
     * Obtains registration info from input fields, sends registration request, handles answer.
     * Also saves account, makes it current and closes the fragment if everything was successful.
     */
    private fun doRegistration() {
        val req = RegisterRequest().apply {
            email = binding.accEmailInput.text.toString()
            password = binding.accPasswordInput.text.toString()
            confirmPassword = binding.accPasswordConfirmInput.text.toString()
            termsOfService = binding.accTermsofserviceCheckbox.isChecked
            isAdult = binding.accIsOver18Checkbox.isChecked
        }

        val progressDialog = MaterialDialog(requireContext())
                .title(R.string.please_wait)
                .message(R.string.checking_in_progress)

        lifecycleScope.launch {
            progressDialog.showThemed(activity.styleLevel)

            try {
                val response = withContext(Dispatchers.IO) { Network.createAccount(req) }
                val acc = Account().apply {
                    serverId = response.id
                    email = response.email
                    password = req.password // get from request, can't be obtained from user info
                    createdAt = response.createdAt
                    updatedAt = response.updatedAt
                    isAdult = response.isAdult // default is false
                    current = true // we just registered, certainly we want to use it now
                }

                DbProvider.helper.accDao.create(acc)
                Toast.makeText(requireContext(), R.string.congrats_diary_registered, Toast.LENGTH_SHORT).show()

                // let's make sure user understood what's needed of him
                MaterialDialog(requireContext())
                        .title(R.string.next_steps)
                        .message(R.string.registered_please_confirm_mail)
                        .positiveButton(android.R.string.ok)
                        .show()

                // return to main activity
                activity.refresh()
                parentFragmentManager.popBackStack()
            } catch (ex: Exception) {
                val errorMap = mapOf(
                        "email_registered" to getString(R.string.email_already_registered),
                        "email_invalid" to getString(R.string.email_is_invalid)
                )
                Network.reportErrors(ctx = requireContext(), ex = ex, detailMapping = errorMap)
            }

            progressDialog.dismiss()
        }
    }
}