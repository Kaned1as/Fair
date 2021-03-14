package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.kanedias.dybr.fair.databinding.FragmentParticipantListItemBinding
import com.kanedias.dybr.fair.databinding.FragmentProfileListFullscreenBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.misc.showFullscreenFragment
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.*
import moe.banana.jsonapi2.ArrayDocument
import java.util.*

/**
 * Fragment which displays community participant lists.
 *
 * @author Kanedias
 *
 * Created on 2021-03-14
 */
open class ParticipantListFragment : UserContentListFragment() {

    companion object {
        const val COMMUNITY_PROFILE_ID = "community-profile-id"
    }

    override fun getRibbonView() = binding.profileRibbon
    override fun getRefresher() = binding.profileRefresher
    override fun getRibbonAdapter() = profileAdapter
    override fun retrieveData(pageNum: Int, starter: Long): () -> ArrayDocument<CommunityParticipant> = {
        Network.communityParticipants(communityProfileId, pageNum = pageNum)
    }

    /**
     * Community to retrieve participants for
     */
    private lateinit var communityProfileId: String

    private lateinit var profileAdapter: LoadMoreAdapter

    private lateinit var binding: FragmentProfileListFullscreenBinding
    private lateinit var activity: MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        communityProfileId = requireArguments().getString(COMMUNITY_PROFILE_ID).toString()

        binding = FragmentProfileListFullscreenBinding.inflate(inflater, container, false)
        activity = context as MainActivity
        profileAdapter = ParticipantListAdapter()

        setupUI()
        setupTheming()
        loadMore()

        return binding.root
    }

    private fun setupUI() {
        binding.profileListToolbar.title = requireArguments().getString("title", getString(R.string.search))
        binding.profileListToolbar.subtitle = requireArguments().getString("subtitle", "")

        binding.profileListToolbar.navigationIcon = DrawerArrowDrawable(activity).apply { progress = 1.0f }
        binding.profileListToolbar.setNavigationOnClickListener { parentFragmentManager.popBackStack() }

        binding.profileRefresher.setOnRefreshListener { loadMore(reset = true) }
        binding.profileRibbon.adapter = profileAdapter
    }

    private fun setupTheming() {
        // this is a fullscreen fragment, add new style
        styleLevel = Scoop.getInstance().addStyleLevel()

        styleLevel.bind(TOOLBAR, binding.profileListToolbar, DefaultColorAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.profileListToolbar, ToolbarTextAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.profileListToolbar, ToolbarIconsAdapter())

        styleLevel.bind(BACKGROUND, binding.profileRibbonArea, DefaultColorAdapter())
        styleLevel.bindBgDrawable(BACKGROUND, binding.profileRibbon)
        styleLevel.bindStatusBar(activity, STATUS_BAR)

        Auth.profile?.let { applyTheme(activity, it, styleLevel) }
    }

    /**
     * Main adapter of this fragment's recycler view. Shows profiles and handles
     * refreshing and page loading.
     */
    inner class ParticipantListAdapter : UserContentListFragment.LoadMoreAdapter() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(activity)
            val view = inflater.inflate(R.layout.fragment_participant_list_item, parent, false)
            return ParticipantViewHolder(view, this@ParticipantListFragment)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val participant = items[position - headers.size] as CommunityParticipant
            (holder as ParticipantViewHolder).setup(participant)
        }
    }

    /**
     * View holder for showing profiles as list.
     *
     * @param iv inflated view to be used by this holder
     * @param parentFragment fragment in which this holder is shown
     *
     * @author Kanedias
     */
    class ParticipantViewHolder(iv: View, private val parentFragment: UserContentListFragment) : RecyclerView.ViewHolder(iv) {

        private val profileItem = FragmentParticipantListItemBinding.bind(iv)

        init {
            setupTheming()
        }

        fun setupTheming() {
            parentFragment.styleLevel.bind(TEXT_BLOCK, itemView, CardViewColorAdapter())
            parentFragment.styleLevel.bind(TEXT, profileItem.profileName, TextViewColorAdapter())
            parentFragment.styleLevel.bind(TEXT, profileItem.profileName, TextViewDrawableAdapter())
            parentFragment.styleLevel.bind(TEXT, profileItem.participantJoinDateLabel, TextViewColorAdapter())
            parentFragment.styleLevel.bind(TEXT, profileItem.participantJoinDate, TextViewColorAdapter())
            parentFragment.styleLevel.bind(TEXT, profileItem.participantPermissionsLabel, TextViewColorAdapter())
            parentFragment.styleLevel.bind(TEXT_LINKS, profileItem.participantPermissions, TextViewColorAdapter())
        }

        private fun showProfile(profile: OwnProfile) {
            val profShow = ProfileFragment().apply { this.profile = profile }
            profShow.show(parentFragment.parentFragmentManager, "Showing user profile fragment")
        }

        private fun showBlog(profile: OwnProfile) {
            val browseFragment = EntryListFragmentFull().apply { this.profile = profile }
            parentFragment.activity?.showFullscreenFragment(browseFragment)
        }

        fun setup(participant: CommunityParticipant) {
            val profile = participant.profile.get(participant.document)

            profileItem.profileAvatar.setOnClickListener { showProfile(profile) }
            itemView.setOnClickListener { showBlog(profile) }

            profileItem.profileName.text = profile.nickname
            when (profile.isCommunity) {
                true -> profileItem.profileName.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.community, 0, 0, 0)
                false -> profileItem.profileName.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }

            // TODO: participant attributes should include created-at and updated-at
            profileItem.participantJoinDateLabel.visibility = View.GONE
            profileItem.participantJoinDate.visibility = View.GONE

            profileItem.participantPermissions.text = when {
                participant.permissions.contains("admin") -> itemView.context.getString(R.string.community_admin)
                participant.permissions.contains("mod") -> itemView.context.getString(R.string.community_mod)
                participant.permissions.contains("post") -> itemView.context.getString(R.string.community_participant)
                participant.permissions.contains("read") -> itemView.context.getString(R.string.community_guest)
                else -> itemView.context.getString(R.string.community_guest)
            }

            // set avatar
            val avatar = Network.resolve(profile.settings.avatar) ?: Network.defaultAvatar()
            Glide.with(profileItem.profileAvatar)
                    .load(avatar.toString())
                    .apply(RequestOptions().centerInside().circleCrop())
                    .into(profileItem.profileAvatar)
        }
    }
}