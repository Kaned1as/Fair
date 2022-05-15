package com.kanedias.dybr.fair.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import android.view.View
import android.widget.*
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.afollestad.materialdialogs.MaterialDialog
import com.kanedias.dybr.fair.*
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.dto.Auth
import com.kanedias.dybr.fair.database.entities.Account
import com.kanedias.dybr.fair.dto.OwnProfile
import com.kanedias.dybr.fair.misc.showFullscreenFragment
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.showThemed
import kotlinx.coroutines.*
import moe.banana.jsonapi2.ArrayDocument
import java.lang.RuntimeException

/**
 * Sidebar views and controls.
 * This represents sidebar that can be shown by dragging from the left of main window.
 *
 * @see MainActivity
 * @author Kanedias
 *
 * Created on 05.11.17
 */
class Sidebar(private val drawer: DrawerLayout, private val activity: MainActivity) {

    private val fragManager = activity.supportFragmentManager
    private val binding = activity.binding.sidebarLayout
    private val profileListRows = listOf(
            binding.myBookmarks,
            binding.myFavorites,
            binding.myReaders,
            binding.myBanned,
            binding.myCommunities
    )

    init {
        binding.sidebarHeaderArea.setOnClickListener { toggleHeader() }
        binding.addAccountRow.setOnClickListener { addAccount() }
        binding.myBookmarks.setOnClickListener { goToBookmarks() }
        binding.myFavorites.setOnClickListener { goToFavorites() }
        binding.myReaders.setOnClickListener { goToReaders() }
        binding.myBanned.setOnClickListener { goToBanned() }
        binding.mySettings.setOnClickListener { goToSettings() }
        binding.myProfile.setOnClickListener { goToProfile() }
        binding.myCommunities.setOnClickListener { goToCommunities(it) }
        binding.joinCommunity.setOnClickListener { searchNewCommunities() }

        updateSidebar()
    }

    /**
     * Hides/shows add-account button and list of saved accounts
     * Positioned just below the header of the sidebar
     */
    private fun toggleHeader() {
        if (binding.accountsArea.visibility == View.GONE) {
            showHeader()
        } else {
            hideHeader()
        }
    }

    private fun showHeader() {
        expand(binding.accountsArea)
        flipAnimator(false, binding.headerFlip).start()
    }

    private fun hideHeader() {
        collapse(binding.accountsArea)
        flipAnimator(true, binding.headerFlip).start()
    }

    /**
     * Shows add-account fragment instead of main view
     */
    private fun addAccount() {
        drawer.closeDrawers()
        activity.showFullscreenFragment(AddAccountFragment())
    }

    private fun goToBookmarks() {
        drawer.closeDrawers()
        activity.showFullscreenFragment(BookmarkListFragmentFull())
    }

    // TODO: replace with explicit call!
    private fun goToFavorites() {
        class ProfileListFavFragment: ProfileListSearchFragment() {

            override fun retrieveData(pageNum: Int, starter: Long): () -> ArrayDocument<OwnProfile> = {
                val prof = Network.loadProfile(filters.getValue("profileId"))
                val favs = prof.favorites.get(prof.document)
                val from = (pageNum - 1) * 20
                val to = minOf(pageNum * 20, favs.size)
                ArrayDocument<OwnProfile>().apply { addAll(favs.subList(from, to)) }
            }
        }

        val authenticated = Auth.profile ?: return
        val frag = ProfileListFavFragment().apply {
            arguments = Bundle().apply { putSerializable("filters", HashMap(mapOf("profileId" to authenticated.id))) }
        }

        drawer.closeDrawers()
        activity.showFullscreenFragment(frag)
    }

    // TODO: replace with explicit call!
    private fun goToReaders() {
        class ProfileListReadersFragment: ProfileListSearchFragment() {

            override fun retrieveData(pageNum: Int, starter: Long): () -> ArrayDocument<OwnProfile> = {
                val prof = Network.loadProfile(filters.getValue("profileId"))
                val readers = prof.readers.get(prof.document)
                val from = (pageNum - 1) * 20
                val to = minOf(pageNum * 20, readers.size)
                ArrayDocument<OwnProfile>().apply { addAll(readers.subList(from, to)) }
            }
        }

        val authenticated = Auth.profile ?: return
        val frag = ProfileListReadersFragment().apply {
            arguments = Bundle().apply { putSerializable("filters", HashMap(mapOf("profileId" to authenticated.id))) }
        }

        drawer.closeDrawers()
        activity.showFullscreenFragment(frag)
    }

    private fun goToBanned() {
        class ProfileListBannedFragment: ProfileListSearchFragment() {

            override fun retrieveData(pageNum: Int, starter: Long): () -> ArrayDocument<OwnProfile> = {
                val actionLists = Network.loadActionLists()
                val banned = actionLists
                        .filter {
                            it.action == "ban" && it.scope == "blog"     // banned entirely
                            || it.action == "hide" && it.scope == "feed" // or hidden from feed
                        }
                        .flatMap { list -> list.profiles.get(list.document) }
                        .toList()
                val from = (pageNum - 1) * 20
                val to = minOf(pageNum * 20, banned.size)
                ArrayDocument<OwnProfile>().apply { addAll(banned.subList(from, to)) }
            }
        }

        val authenticated = Auth.profile ?: return
        val frag = ProfileListBannedFragment().apply {
            // not needed here but kept for consistency
            arguments = Bundle().apply {
                putSerializable("filters", HashMap(mapOf("profileId" to authenticated.id)))
            }
        }

        drawer.closeDrawers()
        activity.showFullscreenFragment(frag)
    }

    private fun goToSettings() {
        drawer.closeDrawers()
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun goToProfile() {
        if (Auth.profile == null)
            return

        val dialog = MaterialDialog(activity)
                .cancelable(false)
                .title(R.string.please_wait)
                .message(R.string.loading_profile)

        activity.lifecycleScope.launch {
            dialog.showThemed(activity.styleLevel)

            try {
                val prof = withContext(Dispatchers.IO) { Network.loadProfile(Auth.profile!!.id) }
                val profShow = ProfileFragment().apply { profile = prof }
                profShow.show(activity.supportFragmentManager, "Showing my profile fragment")
            } catch (ex: Exception) {
                Network.reportErrors(activity, ex)
            }

            dialog.dismiss()
        }
    }

    private fun goToCommunities(row: View) {
        val myCommunities = Auth.profile?.communities
        if (myCommunities == null || myCommunities.size() == 0) {
            showToastAtView(row, R.string.no_communities_yet)
            return
        }

        drawer.closeDrawers()
        val communityFilter = myCommunities.joinToString(separator = ",", transform = { it.id })
        val fragment = ProfileListSearchFragment().apply {
            arguments = Bundle().apply {
                putSerializable("filters", HashMap(mapOf("profile-id" to communityFilter)))
            }
        }
        activity.showFullscreenFragment(fragment)
    }

    private fun searchNewCommunities() {
        drawer.closeDrawers()
        val fragment = ProfileListSearchFragment().apply {
            arguments = Bundle().apply {
                putSerializable("filters", HashMap(mapOf("is-community" to "1")))
            }
        }
        activity.showFullscreenFragment(fragment)
    }

    /**
     * Update sidebar after account change/refresh
     */
    fun updateSidebar() {
        updateAccountsArea()
        updateProfileRow()
        updateBlogRow()
        updateProfileListRows()
    }

    /**
     * Update accounts area after possible account change
     */
    private fun updateAccountsArea() {
        val inflater = activity.layoutInflater

        // set welcome message to current user nickname
        binding.currentUserName.text = Auth.profile?.nickname ?: Auth.user.email

        // update account area views
        // remove previous accounts, they may be invalid
        binding.accountsArea.removeViews(1, binding.accountsArea.childCount - 1)

        // populate account list
        val allAccs = DbProvider.helper.accDao.queryForAll()
        for (acc in allAccs) {
            val view = inflater.inflate(R.layout.activity_main_sidebar_account_row, binding.accountsArea, false)
            val accName = view.findViewById<TextView>(R.id.account_name)
            val accRemove = view.findViewById<ImageView>(R.id.account_remove)

            // setup account row - set email as account name
            accName.text = acc.email
            accName.setOnClickListener {
                // account name clicked, make it active
                Auth.user.current = false
                DbProvider.helper.accDao.update(Auth.user)

                acc.current = true
                DbProvider.helper.accDao.update(acc)

                // start re-login sequence
                drawer.closeDrawers()
                activity.performLogin(acc)
            }

            // setup account row - handle click on delete button
            accRemove.setOnClickListener {
                // "delete account" confirmation dialog
                MaterialDialog(view.context)
                        .title(R.string.delete_account)
                        .message(R.string.are_you_sure)
                        .positiveButton(android.R.string.yes, click = { deleteAccount(acc) })
                        .negativeButton(android.R.string.no)
                        .showThemed(activity.styleLevel)
            }

            // add finished account row to the layout
            binding.accountsArea.addView(view)
        }

        // special setup item - inflate guest account row
        val guestRow = inflater.inflate(R.layout.activity_main_sidebar_account_row, binding.accountsArea, false)
        guestRow.findViewById<ImageView>(R.id.account_remove).visibility = View.GONE
        val guestName = guestRow.findViewById<TextView>(R.id.account_name)
        guestName.text = activity.getString(R.string.guest)
        guestName.setOnClickListener {
            Auth.user.current = false
            DbProvider.helper.accDao.update(Auth.user)

            drawer.closeDrawers()
            activity.performLogin(Auth.guest)
        }
        binding.accountsArea.addView(guestRow)

        // if we are logged in there's no point in showing accounts row
        // unless we are specifically asked to
        if (Auth.profile != null) {
            hideHeader()
        }
    }

    /**
     * update "My Profile" row
     */
    private fun updateProfileRow() {

        if (Auth.profile == null) {
            // no profile, set to disabled
            binding.myProfile.isEnabled = false
            binding.switchProfile.visibility = View.GONE
            binding.addProfile.visibility = View.GONE
            binding.setupProfile.visibility = View.GONE

            // if account is present, enable "add-profile" button
            if (Auth.user !== Auth.guest) {
                binding.addProfile.visibility = View.VISIBLE
                binding.addProfile.setOnClickListener {
                    activity.addProfile()
                    drawer.closeDrawers()
                }
            }
            return
        }

        // We have a profile, then
        binding.myProfile.isEnabled = true
        binding.addProfile.visibility = View.GONE
        binding.switchProfile.visibility = View.VISIBLE
        binding.setupProfile.visibility = View.VISIBLE

        // handle click on profile change button
        // we need to ignore subsequent clicks if profiles are already loading
        binding.switchProfile.setOnClickListener {
            val swapAnim = ValueAnimator.ofFloat(1f, -1f, 1f)
            swapAnim.interpolator = FastOutSlowInInterpolator()
            swapAnim.addUpdateListener { binding.addProfile.scaleX = swapAnim.animatedValue as Float }
            swapAnim.duration = 1_000
            swapAnim.repeatCount = ValueAnimator.INFINITE
            swapAnim.start()

            activity.lifecycleScope.launch {
                try {
                    // force profile selection even if we only have one
                    activity.startProfileSelector(true)
                    drawer.closeDrawers()
                } catch (ex: Exception) {
                    Network.reportErrors(activity, ex)
                }

                swapAnim.repeatCount = 0 // stop gracefully
            }
        }

        binding.setupProfile.setOnClickListener {
            activity.showProfilePreferences()
            drawer.closeDrawers()
        }
    }

    /**
     * Updates blog row according to retrieved info in
     * @see [Auth.profile]
     */
    private fun updateBlogRow() {

        if (Auth.profile == null) {
            // no profile yet, disable everything
            binding.myBlog.isEnabled = false
            binding.myBlog.setText(R.string.my_blog)
            binding.addBlog.visibility = View.GONE
            return
        }

        if (Auth.profile?.blogSlug == null) {
            // no blog yet, disable click, show "Add blog" button
            binding.myBlog.isEnabled = false
            binding.myBlog.setText(R.string.my_blog)
            binding.addBlog.visibility = View.VISIBLE
            binding.addBlog.setOnClickListener {
                activity.createBlog()
                drawer.closeDrawers()
            }
            return
        }

        // we have a blog, show it
        binding.myBlog.isEnabled = true
        binding.myBlog.hint = activity.getString(R.string.my_blog)
        binding.myBlog.text = Auth.profile?.blogTitle
        binding.addBlog.visibility = View.GONE
        binding.myBlog.setOnClickListener {
            for (i in 0..fragManager.backStackEntryCount) {
                fragManager.popBackStack()
            }
            activity.binding.mainPager.setCurrentItem(0, true)
            drawer.closeDrawers()
        }
    }

    private fun updateProfileListRows() {
        when (Auth.profile) {
            null -> profileListRows.forEach { toggleEnableRecursive(it, false) }
            else -> profileListRows.forEach { toggleEnableRecursive(it, true) }
        }
    }

    /**
     * Delete account from local database, delete cookies and re-login as guest
     * if it's current account that was deleted.
     */
    private fun deleteAccount(acc: Account) {
        // if we deleted current account, set it to guest
        if (Auth.user.email == acc.email) {
            drawer.closeDrawers()
            activity.performLogin(Auth.guest)
        }

        // all accounts are present in the DB, inner id is set either on query
        // or in Register/Login persist step, see AddAccountFragment
        DbProvider.helper.accDao.delete(acc)
        updateSidebar()
    }

    /**
     * Animates via slowly negating scaleY of target view. Used in arrow-like buttons
     * to turn ⌄ in ⌃ and back.
     * @return created animator
     */
    private fun flipAnimator(isFlipped: Boolean, v: View): ValueAnimator {
        val animator = ValueAnimator.ofFloat(if (isFlipped) -1f else 1f, if (isFlipped) 1f else -1f)
        animator.interpolator = FastOutSlowInInterpolator()

        animator.addUpdateListener { valueAnimator ->
            // update view height when flipping
            v.scaleY = valueAnimator.animatedValue as Float
        }
        return animator
    }

    /**
     * Animates via slowly changing target view height. Used to show/hide account list.
     * @return created animator
     */
    private fun slideAnimator(start: Int, end: Int, v: View): ValueAnimator {
        val animator = ValueAnimator.ofInt(start, end)
        animator.interpolator = FastOutSlowInInterpolator()

        animator.addUpdateListener { valueAnimator ->
            // update height
            val value = valueAnimator.animatedValue as Int
            val layoutParams = v.layoutParams
            layoutParams.height = value
            v.layoutParams = layoutParams
        }
        return animator
    }

    /**
     * Expands target layout by making it visible and increasing its height
     * @see slideAnimator
     */
    private fun expand(v: View) {
        // set layout visible
        v.visibility = View.VISIBLE

        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        v.measure(widthSpec, heightSpec)

        val animator = slideAnimator(0, v.measuredHeight, v)
        animator.start()
    }

    /**
     * Collapses target layout by decreasing its height and making it gone
     * @see slideAnimator
     */
    private fun collapse(v: View) {
        val finalHeight = v.height
        val animator = slideAnimator(finalHeight, 0, v)

        animator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animator: Animator) {
                v.visibility = View.GONE
            }
        })
        animator.start()
    }
}