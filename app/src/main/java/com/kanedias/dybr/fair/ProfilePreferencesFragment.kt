package com.kanedias.dybr.fair

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.StyleLevel
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.kanedias.dybr.fair.databinding.FragmentProfilePreferencesBinding
import com.kanedias.dybr.fair.dto.OwnProfile
import com.kanedias.dybr.fair.dto.ProfileCreateRequest
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.ui.showToastAtView
import kotlinx.coroutines.*

/**
 * @author Kanedias
 *
 * Created on 18.08.19
 */
class ProfilePreferencesFragment: Fragment() {

    companion object {
        const val PROFILE = "profile"
    }

    /**
     * Profile that we are editing
     */
    private lateinit var profile: OwnProfile

    private lateinit var binding: FragmentProfilePreferencesBinding
    private lateinit var switches: List<SwitchCompat>
    private lateinit var titles: List<TextView>

    private lateinit var styleLevel: StyleLevel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        profile = requireArguments().get(PROFILE) as OwnProfile
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        binding = FragmentProfilePreferencesBinding.inflate(inflater, container, false)
        switches = listOf(
                binding.newPostsInFavoritesSwitch,
                binding.newCommentsInSubscribedSwitch,
                binding.participateInFeedSwitch,
                binding.reactionsGlobalSwitch,
                binding.reactionsInBlogSwitch
        )
        titles = listOf(
                binding.notificationsScreenText,
                binding.privacyScreenText,
                binding.reactionsScreenText
        )


        setupUI()
        setupTheming()

        return binding.root
    }

    private fun setupTheming() {
        styleLevel = Scoop.getInstance().addStyleLevel()
        lifecycle.addObserver(styleLevel)

        styleLevel.bind(BACKGROUND, binding.root, NoRewriteBgPicAdapter())
        styleLevel.bindBgDrawable(BACKGROUND, binding.root)

        styleLevel.bind(TEXT_BLOCK, binding.profilePreferencesSwitchesArea, DefaultColorAdapter())

        styleLevel.bind(TOOLBAR, binding.profilePreferencesToolbar)
        styleLevel.bind(TOOLBAR_TEXT, binding.profilePreferencesToolbar, ToolbarTextAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.profilePreferencesToolbar, ToolbarIconsAdapter())

        styleLevel.bindStatusBar(activity, STATUS_BAR)

        switches.forEach {
            styleLevel.bind(TEXT_LINKS, it, SwitchColorThumbAdapter())
            styleLevel.bind(TEXT_LINKS, it, SwitchDrawableColorAdapter())
            styleLevel.bind(TEXT, it, SwitchColorAdapter())
            styleLevel.bind(TEXT, it, TextViewColorAdapter())
        }

        titles.forEach { styleLevel.bind(TEXT_HEADERS, it, TextViewColorAdapter()) }
    }

    /**
     * Apply settings from profile to switches
     */
    private fun setupUI() {
        binding.profilePreferencesToolbar.setTitle(R.string.profile_settings)
        binding.profilePreferencesToolbar.navigationIcon = DrawerArrowDrawable(activity).apply { progress = 1.0f }
        binding.profilePreferencesToolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        binding.newPostsInFavoritesSwitch.isChecked = profile.settings.notifications.entries.enable
        binding.newPostsInFavoritesSwitch.setOnCheckedChangeListener { view, isChecked ->
            profile.settings.notifications.apply { entries = entries.copy(enable = isChecked) }
            applySettings(view)
        }

        binding.newCommentsInSubscribedSwitch.isChecked = profile.settings.notifications.entries.enable
        binding.newCommentsInSubscribedSwitch.setOnCheckedChangeListener { view, isChecked ->
            profile.settings.notifications.apply { comments = comments.copy(enable = isChecked) }
            applySettings(view)
        }

        binding.participateInFeedSwitch.isChecked = profile.settings.privacy.dybrfeed
        binding.participateInFeedSwitch.setOnCheckedChangeListener { view, isChecked ->
            profile.settings.apply { privacy = privacy.copy(dybrfeed = isChecked) }
            applySettings(view)
        }

        binding.reactionsGlobalSwitch.isChecked = !profile.settings.reactions.disable
        binding.reactionsGlobalSwitch.setOnCheckedChangeListener { view, isChecked ->
            profile.settings.reactions.disable = !isChecked
            binding.reactionsInBlogSwitch.isEnabled = isChecked
            applySettings(view)
        }

        binding.reactionsInBlogSwitch.isChecked = !profile.settings.reactions.disableInBlog
        binding.reactionsInBlogSwitch.isEnabled = binding.reactionsGlobalSwitch.isChecked
        binding.reactionsInBlogSwitch.setOnCheckedChangeListener { view, isChecked ->
            profile.settings.reactions.disableInBlog = !isChecked
            applySettings(view)
        }
    }

    private fun applySettings(view: View) {
        val profReq = ProfileCreateRequest().apply {
            id = profile.id
            settings = profile.settings
        }

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Network.updateProfile(profReq) }
                showToastAtView(view, getString(R.string.applied))
            } catch (ex: Exception) {
                Network.reportErrors(context, ex)
            }
        }
    }
}