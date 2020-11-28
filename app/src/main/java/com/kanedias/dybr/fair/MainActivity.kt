package com.kanedias.dybr.fair

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Bundle
import androidx.core.view.GravityCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.widget.SearchView
import android.view.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cursoradapter.widget.CursorAdapter
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.WhichButton
import com.afollestad.materialdialogs.actions.getActionButton
import com.afollestad.materialdialogs.callbacks.onShow
import com.afollestad.materialdialogs.checkbox.checkBoxPrompt
import com.afollestad.materialdialogs.checkbox.getCheckBoxPrompt
import com.afollestad.materialdialogs.input.input
import com.afollestad.materialdialogs.list.customListAdapter
import com.auth0.android.jwt.JWT
import com.ftinc.scoop.Scoop
import com.ftinc.scoop.StyleLevel
import com.ftinc.scoop.adapters.DefaultColorAdapter
import com.ftinc.scoop.adapters.ImageViewColorAdapter
import com.ftinc.scoop.adapters.TextViewColorAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.database.entities.Account
import com.kanedias.dybr.fair.database.entities.SearchGotoInfo
import com.kanedias.dybr.fair.database.entities.SearchGotoInfo.*
import com.kanedias.dybr.fair.databinding.ActivityMainBinding
import com.kanedias.dybr.fair.databinding.ActivityMainProfileSelectionRowBinding
import com.kanedias.dybr.fair.databinding.ActivityMainSearchRowBinding
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.misc.showFullscreenFragment
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.ui.Sidebar
import com.kanedias.dybr.fair.misc.getTopFragment
import com.kanedias.dybr.fair.scheduling.SyncNotificationsWorker
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.service.UserPrefs
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.HashMap

/**
 * Main activity with drawer and sliding tabs where most of user interaction happens.
 * This activity is what you see when you start Fair app.
 *
 * @author Kanedias
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MY_DIARY_TAB = 0
        private const val FAV_TAB = 1
        private const val COMMUNITIES_TAB = 2
        private const val WORLD_TAB = 3
        private const val NOTIFICATIONS_TAB = 4
    }

    lateinit var binding: ActivityMainBinding

    /**
     * Sidebar that opens from the left (the second part of drawer)
     */
    private lateinit var sidebar: Sidebar

    private lateinit var donateHelper: DonateHelper

    /**
     * Style level for the activity. This is intentionally made public as various fragments that are not shown
     * in fullscreen should use this level from the activity.
     *
     * Fullscreen fragments and various dialogs should instead create and use their own style level.
     */
    lateinit var styleLevel: StyleLevel

    override fun attachBaseContext(newBase: Context) {
        // init custom language if we should
        val wrapped = initLanguage(newBase)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // init preferences
        donateHelper = DonateHelper(this)

        // set app bar
        setSupportActionBar(binding.toolbar)
        // setup click listeners, adapters etc.
        setupUI()
        // Setup profile themes
        setupTheming()
        // load user profile and initialize tabs
        performLogin(Auth.user)
    }

    private fun setupUI() {
        // init drawer and sidebar
        sidebar = Sidebar(binding.mainDrawer, this)

        // cross-join drawer and menu item in header
        val drawerToggle = ActionBarDrawerToggle(this, binding.mainDrawer, binding.toolbar, R.string.open, R.string.close)
        binding.mainDrawer.addDrawerListener(drawerToggle)
        binding.mainDrawer.addDrawerListener(object: DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                // without this buttons are non-clickable after activity layout changes
                binding.sidebarLayout.mainSidebarArea.bringToFront()
            }
        })
        drawerToggle.syncState()

        binding.floatingButton.setOnClickListener { addEntry() }

        // handle first launch
        if (UserPrefs.firstAppLaunch) {
            binding.mainDrawer.openDrawer(GravityCompat.START)
            UserPrefs.firstAppLaunch = false
        }

        // hack: resume fragment that is activated on tapping "back"
        supportFragmentManager.addOnBackStackChangedListener(object: FragmentManager.OnBackStackChangedListener {

            private var prevEntryCount = 0

            override fun onBackStackChanged() {
                val backStackEntryCount = supportFragmentManager.backStackEntryCount
                if (backStackEntryCount == 0) {
                    // we're in the main activity
                    Scoop.getInstance().setLastLevel(styleLevel)
                    styleLevel.rebind()
                    return
                }

                if (backStackEntryCount - prevEntryCount > 0) {
                    // fragment was added, style will be applied by its lifecycle
                    prevEntryCount = backStackEntryCount
                    return
                }


                // fragment was removed, rebind style levels on top
                prevEntryCount = backStackEntryCount
                val top = supportFragmentManager.fragments.findLast { it is UserContentListFragment }
                (top as? UserContentListFragment)?.styleLevel?.apply {
                    Scoop.getInstance().setLastLevel(this)
                    this.rebind()
                }
            }

        })
    }

    private fun setupTheming() {
        styleLevel = Scoop.getInstance().addStyleLevel()
        lifecycle.addObserver(styleLevel)

        styleLevel.bindStatusBar(this, STATUS_BAR)


        styleLevel.bind(TOOLBAR, binding.toolbar, DefaultColorAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.toolbar, ToolbarTextAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.toolbar, ToolbarIconsAdapter())

        styleLevel.bind(TOOLBAR, binding.slidingTabs)
        styleLevel.bind(TOOLBAR_TEXT, binding.slidingTabs, TabLayoutTextAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.slidingTabs, TabLayoutLineAdapter())
        styleLevel.bind(TOOLBAR_TEXT, binding.slidingTabs, TabLayoutDrawableAdapter())

        styleLevel.bind(ACCENT, binding.floatingButton, BackgroundTintColorAdapter())
        styleLevel.bind(ACCENT_TEXT, binding.floatingButton, FabIconAdapter())
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_action_bar_menu, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        setupTopSearch(menu)
        return super.onPrepareOptionsMenu(menu)
    }

    private fun dativeByType(preText: Int, type: EntityType) = getString(preText, when(type) {
        EntityType.PROFILE -> getString(R.string.dative_case_profile_name)
        EntityType.BLOG -> getString(R.string.dative_case_blog_name)
        EntityType.TAG -> getString(R.string.dative_case_tag)
        EntityType.TEXT -> getString(R.string.dative_case_text)
    })

    private fun setupTopSearch(menu: Menu) {
        val searchItem = menu.findItem(R.id.menu_search)
        val searchView = searchItem.actionView as SearchView

        // apply theme to search view
        styleLevel.bind(TOOLBAR_TEXT, searchView, SearchIconsAdapter())
        styleLevel.bind(TOOLBAR_TEXT, searchView, SearchTextAdapter())

        val initialSuggestions = constructSuggestions("")
        val searchAdapter = SearchItemAdapter(this, initialSuggestions)

        // initialize adapter, text listener and click handler
        searchView.queryHint = getString(R.string.go_to)
        searchView.suggestionsAdapter = searchAdapter
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {

            private fun handle(query: String?): Boolean {
                if (query.isNullOrEmpty())
                    return true

                searchAdapter.changeCursor(constructSuggestions(query))
                return true
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                return handle(query)
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return handle(newText)
            }

        })
        searchView.setOnSuggestionListener(object: SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(position: Int): Boolean {
                return false
            }

            override fun onSuggestionClick(position: Int): Boolean {
                searchItem.collapseActionView()

                val cursor = searchAdapter.getItem(position) as Cursor
                val name = cursor.getString(cursor.getColumnIndex("name"))
                val type = EntityType.valueOf(cursor.getString(cursor.getColumnIndex("type")))

                // jump to respective blog or profile if they exist
                lifecycleScope.launch {
                    try {
                        val fragment = when (type) {
                            EntityType.PROFILE -> ProfileListSearchFragment().apply {
                                arguments = Bundle().apply {
                                    putSerializable("filters", HashMap(mapOf("nickname|contains" to name)))
                                }
                            }
                            EntityType.BLOG -> ProfileListSearchFragment().apply {
                                arguments = Bundle().apply {
                                    putSerializable("filters", HashMap(mapOf("blog-title|contains" to name)))
                                }
                            }
                            EntityType.TAG -> EntryListSearchFragmentFull().apply {
                                arguments = Bundle().apply {
                                    putSerializable("filters", hashMapOf("tag" to name))
                                }
                            }
                            EntityType.TEXT -> EntryListSearchFragmentFull().apply {
                                arguments = Bundle().apply {
                                    putSerializable("filters", hashMapOf("content|contains" to name))
                                }
                            }
                        }
                        fragment.requireArguments().apply {
                            putString("title", name)
                            putString("subtitle", dativeByType(R.string.search_by, type))
                        }
                        showFullscreenFragment(fragment)
                        persistSearchSuggestion(type, name)
                    } catch (ex: Exception) {
                        Network.reportErrors(this@MainActivity, ex, mapOf(404 to R.string.not_found))
                    }
                }

                return true
            }

            private fun persistSearchSuggestion(type: EntityType, name: String) {
                // persist to saved searches if unique index does not object
                val dao = DbProvider.helper.gotoDao
                val unique = dao.queryBuilder().apply { where().eq("type", type).and().eq("name", name) }.query()
                if (unique.isEmpty()) { // don't know how to make this check better, like "replace-on-conflict"
                    dao.createOrUpdate(SearchGotoInfo(type, name))
                }
            }
        })
    }

    private fun constructSuggestions(prefix: String): Cursor {
        // first, collect suggestions from the favorites if we have them
        val favProfiles = Auth.profile?.favorites?.get(Auth.profile?.document) ?: emptyList()
        val suitableFavs = favProfiles.filter { it.nickname.startsWith(prefix) }

        // second, collect suggestions that we already searched for
        val suitableSaved = DbProvider.helper.gotoDao.queryBuilder()
                .where().like("name", "$prefix%").query()

        // we need a counter as MatrixCursor requires _id field unfortunately
        var counter = 0
        val cursor = MatrixCursor(arrayOf("_id", "name", "type", "source"), suitableFavs.size)
        if (prefix.isNotEmpty()) {
            // add two service rows with just prefix
            val searchByType = { type: EntityType -> dativeByType(R.string.search_by, type) }
            cursor.addRow(arrayOf(++counter, prefix, EntityType.BLOG.name, searchByType(EntityType.BLOG)))
            cursor.addRow(arrayOf(++counter, prefix, EntityType.PROFILE.name, searchByType(EntityType.PROFILE)))
            cursor.addRow(arrayOf(++counter, prefix, EntityType.TEXT.name, searchByType(EntityType.TEXT)))
            cursor.addRow(arrayOf(++counter, prefix, EntityType.TAG.name, searchByType(EntityType.TAG)))
        }

        // add suitable addresses from favorites and database
        val savedByType = { type: EntityType -> dativeByType(R.string.from_saved_search_by, type) }
        suitableFavs.forEach { cursor.addRow(arrayOf(++counter, it.nickname, EntityType.PROFILE.name, getString(R.string.from_profile_favorites))) }
        suitableSaved.forEach { cursor.addRow(arrayOf(++counter, it.name, it.type, savedByType(it.type))) }

        return cursor
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_donate -> donateHelper.donate()
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_about -> showFullscreenFragment(AboutFragment())
            else -> return super.onOptionsItemSelected(item)
        }

        // it was handled in `when` block or we wouldn't be at this point
        // confirm it
        return true
    }

    override fun onBackPressed() {
        // close drawer if it's open and stop processing further back actions
        if (binding.mainDrawer.isDrawerOpen(GravityCompat.START) || binding.mainDrawer.isDrawerOpen(GravityCompat.END)) {
            binding.mainDrawer.closeDrawers()
            return
        }

        super.onBackPressed()
    }

    /**
     * Logs in with specified account. This must be the very first action after opening the app.
     *
     * @param acc account to be logged in with
     */
    fun performLogin(acc: Account) {
        // skip re-login if we're logging in as guest
        if (acc === Auth.guest) {
            Auth.updateCurrentUser(Auth.guest)
            refresh()
            return
        }

        val progressDialog = MaterialDialog(this@MainActivity)
                .cancelable(false)
                .title(R.string.please_wait)
                .message(R.string.logging_in)

        lifecycleScope.launch {
            progressDialog.showThemed(styleLevel)

            try {
                // login with this account and reset profile/blog links
                withContext(Dispatchers.IO) { Network.login(acc) }

                // at this point we know we definitely have token available
                val token = JWT(acc.accessToken!!)
                if (token.audience.isNullOrEmpty() || token.audience?.first() == "0") {
                    // first time we're loading this account, select profile
                    startProfileSelector()
                } else {
                    // we already have profile, load it
                    val profile = withContext(Dispatchers.IO) { Network.loadProfile(token.audience!!.first()) }
                    Auth.updateCurrentProfile(profile)
                    Toast.makeText(this@MainActivity, R.string.login_successful, Toast.LENGTH_SHORT).show()
                }
            } catch (ex: Exception) {
                val detailMap = mapOf(
                        "email_registered" to getString(R.string.email_already_registered),
                        "email_not_confirmed" to getString(R.string.email_not_activated_yet),
                        "email_not_found" to getString(R.string.email_not_found),
                        "email_invalid" to getString(R.string.email_is_invalid),
                        "password_invalid" to getString(R.string.incorrect_password)
                )

                Network.reportErrors(this@MainActivity, ex, detailMapping = detailMap)
                becomeGuest()
            }

            progressDialog.dismiss()
            refresh()
        }
    }

    override fun onNewIntent(received: Intent) {
        super.onNewIntent(received)
        handleIntent(received)
    }

    private fun initLanguage(ctx: Context): Context {
        // can't use UserPrefs here, not initialized yet
        val userLang = UserPrefs.userPreferredLanguage
        if (userLang.isEmpty()) {
            // custom language not set
            return ctx
        }

        // override language
        val config = ctx.resources.configuration
        config.setLocale(Locale(userLang))
        return ctx.createConfigurationContext(config)
    }

    /**
     * Handle the passed intent. This is invoked whenever we need to actually react to the intent that was
     * passed to this activity, this can be just activity start from the app manager, click on a link or
     * on a notification belonging to this app
     * @param cause the passed intent. It will not be modified within this function.
     */
    private fun handleIntent(cause: Intent?) {
        if (cause == null)
            return

        when (cause.action) {
            ACTION_NOTIF_OPEN -> {
                val notification = cause.getSerializableExtra(EXTRA_NOTIFICATION) as? Notification
                if (notification != null) {
                    // we have notification, can handle notification click
                    lifecycleScope.launch {
                        try {
                            // load entry
                            val entry = withContext(Dispatchers.IO) { Network.loadEntry(notification.entryId) }

                            // launch comment list
                            val frag = CommentListFragment().apply {
                                arguments = Bundle().apply {
                                    putSerializable(CommentListFragment.ENTRY_ARG, entry)
                                    putString(CommentListFragment.COMMENT_ID_ARG, notification.comment.get().id)
                                }
                            }
                            showFullscreenFragment(frag)

                            // mark notification read
                            SyncNotificationsWorker.markRead(this@MainActivity, notification)
                        } catch (ex: Exception) {
                            Network.reportErrors(this@MainActivity, ex)
                        }
                    }
                    return
                }

                // we don't have entry or notification, open notifications tab
                val backToNotifications = {
                    lifecycleScope.launchWhenResumed {
                        for (i in 0..supportFragmentManager.backStackEntryCount) {
                            supportFragmentManager.popBackStack()
                        }
                        binding.mainPager.setCurrentItem(NOTIFICATIONS_TAB, true)

                        // if the fragment is already loaded, try to refresh it
                        val notifFragment = getTopFragment(NotificationListFragment::class)
                        notifFragment?.loadMore(reset = true)
                    }
                }

                if (supportFragmentManager.backStackEntryCount > 0) {
                    // confirm dismiss of all top fragments
                    MaterialDialog(this)
                            .title(R.string.confirm_action)
                            .message(R.string.return_to_notifications)
                            .negativeButton(android.R.string.cancel)
                            .positiveButton(android.R.string.ok, click = { backToNotifications() })
                            .showThemed(styleLevel)
                } else {
                    backToNotifications()
                }
            }

            Intent.ACTION_VIEW -> lifecycleScope.launch {
                // try to detect if it's someone trying to open the dybr.ru link with us
                if (cause.data?.authority?.contains("dybr.ru") == true) {
                    consumeCallingUrl(cause)
                }
            }
        }
    }

    /**
     * Take URI from the activity's intent, try to shape it into something usable
     * and handle the action user requested in it if possible. E.g. clicking on link
     * https://dybr.ru/blog/... should open that blog or entry inside the app so try
     * to guess what user wanted with it as much as possible.
     */
    private suspend fun consumeCallingUrl(cause: Intent) {
        try {
            val address = cause.data?.pathSegments ?: return // it's in the form of /blog/<slug>/[<entry>]
            val commentId = cause.data?.fragment
            when(address[0]) {
                "blog" -> {
                    val fragment = when (address.size) {
                        2 -> {  // the case for /blog/<slug>
                            val prof = withContext(Dispatchers.IO) { Network.loadProfileBySlug(address[1]) }
                            EntryListFragmentFull().apply { this.profile = prof }
                        }
                        3 -> { // the case for /blog/<slug>/<entry>
                            val entry = withContext(Dispatchers.IO) { Network.loadEntry(address[2]) }
                            CommentListFragment().apply {
                                arguments = Bundle().apply {
                                    putSerializable(CommentListFragment.ENTRY_ARG, entry)
                                    putString(CommentListFragment.COMMENT_ID_ARG, commentId)
                                }
                            }
                        }
                        else -> return
                    }

                    lifecycleScope.launchWhenResumed {
                        showFullscreenFragment(fragment)
                    }
                }
                "profile" -> {
                    val fragment = when (address.size) {
                        2 -> { // the case for /profile/<id>
                            val profile = withContext(Dispatchers.IO) { Network.loadProfile(address[1]) }
                            ProfileFragment().apply { this.profile = profile }
                        }
                        else -> return
                    }

                    lifecycleScope.launchWhenResumed {
                        fragment.show(supportFragmentManager, "Showing intent-requested profile fragment")
                    }
                }
                else -> return
            }
        } catch (ex: Exception) {
            Network.reportErrors(this@MainActivity, ex)
        }
    }

    /**
     * Load profiles for logged in user and show selector dialog to user.
     * If there's just one profile, select it right away.
     * If there are no any profiles for this user, suggest to create it.
     *
     * This should be invoked after [Auth.user] is populated
     *
     * @param showIfOne whether to show dialog if only one profile is available
     */
    suspend fun startProfileSelector(showIfOne: Boolean = false) {
        // retrieve profiles from server
        val profiles = withContext(Dispatchers.IO) { Network.loadUserProfiles() }

        // predefine what to do if new profile is needed

        if (profiles.isEmpty()) {
            // suggest user to create profile
            binding.mainDrawer.closeDrawers()
            MaterialDialog(this)
                    .title(R.string.switch_profile)
                    .message(R.string.no_profiles_create_one)
                    .positiveButton(R.string.create_new, click = { addProfile() })
                    .showThemed(styleLevel)
            return
        }

        if (profiles.size == 1 && !showIfOne) {
            // we have only one profile, use it
            withContext(Dispatchers.IO) { Network.login(Auth.user, profiles[0].id) }
            Auth.updateCurrentProfile(profiles[0])
            refresh()
            return
        }

        // we have multiple accounts to select from
        val profAdapter = ProfileListAdapter(profiles.toMutableList())
        MaterialDialog(this)
                .title(R.string.switch_profile)
                .customListAdapter(profAdapter)
                .positiveButton(R.string.create_new, click = { addProfile() })
                .apply { profAdapter.toDismiss = this }
                .showThemed(styleLevel)
    }

    fun showProfilePreferences() {
        val profile = Auth.profile ?: return

        showFullscreenFragment(ProfilePreferencesFragment().apply {
            arguments = Bundle().apply {
                putSerializable(ProfilePreferencesFragment.PROFILE, profile)
            }
        })
    }

    /**
     * Called when user selects profile from a dialog
     */
    private fun selectProfile(prof: OwnProfile) {
        // need to retrieve selected profile fully, i.e. with favorites and stuff
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Network.login(Auth.user, prof.id) }
                val fullProf = withContext(Dispatchers.IO) { Network.loadProfile(prof.id) }
                Auth.updateCurrentProfile(fullProf)

                // hide notifications as they may belong to another profile
                SyncNotificationsWorker.hideNotifications(applicationContext)

                // refresh ui with new profile
                refresh()
            } catch (ex: Exception) {
                Network.reportErrors(this@MainActivity, ex)
                becomeGuest()
            }
        }
    }

    /**
     * Show "Add profile" fragment
     */
    fun addProfile() {
        showFullscreenFragment(AddProfileFragment())
    }

    /**
     * Show "Add blog" fragment. Only one blog can belong to specific profile
     */
    fun createBlog() {
        showFullscreenFragment(AddBlogFragment())
    }


    /**
     * Delete specified profile from server and report it
     * @param prof profile to remove
     */
    private fun deleteProfile(prof: OwnProfile, keepComments: Boolean = false) {
        val progressDialog = MaterialDialog(this@MainActivity)
                .cancelable(false)
                .title(R.string.please_wait)
                .message(R.string.checking_in_progress)

        lifecycleScope.launch {
            progressDialog.showThemed(styleLevel)
            Network.perform(
                networkAction = { Network.removeProfile(prof, keepComments) },
                uiAction = {
                    Toast.makeText(this@MainActivity, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                }
            )
            progressDialog.dismiss()

            if (Auth.profile == prof) {
                Auth.user.accessToken = null
                DbProvider.helper.accDao.update(Auth.user)

                // we deleted current profile
                Auth.updateCurrentProfile(null)
                refresh()
            }
        }
    }

    /**
     * Become a guest user with no profile
     * What to do if auth failed/current account/profile was deleted
     */
    private fun becomeGuest() {
        Auth.updateCurrentUser(Auth.guest)
        refresh()
        binding.mainDrawer.openDrawer(GravityCompat.START)
    }

    /**
     * Refresh sliding tabs, sidebar (usually after auth/settings change)
     */
    fun refresh() {

        // apply theme if present
        Auth.profile?.let { applyTheme(this, it, styleLevel) }

        // load current blog and favorites
        lifecycleScope.launch {
            refreshUI()

            // the whole application may be started because of link click
            handleIntent(intent)
            intent = null // reset intent of the activity
        }
    }

    private fun refreshUI() {
        sidebar.updateSidebar()

        // don't show add entry button if we don't have blog to add it
        // can't move this code to EntryListFragments because context
        // is not attached when their user visible hint is set
        when (Auth.profile?.blogSlug) {
            null -> binding.floatingButton.hide()
            else -> binding.floatingButton.show()
        }

        when {
            // browsing as guest
            Auth.user === Auth.guest -> binding.mainPager.adapter = GuestTabAdapter()

            // profile not yet created
            Auth.profile === null -> binding.mainPager.adapter = GuestTabAdapter()

            // have account and profile
            else -> binding.mainPager.adapter = TabAdapter(Auth.profile)
        }

        // setup tabs, use dummy configurer as we only have icons
        TabLayoutMediator(binding.slidingTabs, binding.mainPager) { _, _ -> }.attach()
        (binding.mainPager.adapter as IconAwareTabAdapter).setupIcons()
    }

    /**
     * Add entry handler. Shows header view for adding diary entry.
     * @see Entry
     */
    private fun addEntry() {
        // we know that fragment is already present so it will return cached one
        val currFragment = supportFragmentManager.findFragmentByTag("f${binding.mainPager.currentItem}")
        if (currFragment !is EntryListFragment) {
            // notification tab, can't add anything there
            return
        }

        if (currFragment.getRefresher().isRefreshing) {
            // blog is not loaded yet
            Toast.makeText(this, R.string.still_loading, Toast.LENGTH_SHORT).show()
            return
        }

        currFragment.addCreateNewEntryForm()
    }

    inner class SearchItemAdapter(ctx: Context, cursor: Cursor): CursorAdapter(ctx, cursor, 0) {

        override fun newView(ctx: Context, cursor: Cursor, parent: ViewGroup?): View {
            return LayoutInflater.from(ctx).inflate(R.layout.activity_main_search_row, parent, false)
        }

        override fun bindView(view: View, ctx: Context, cursor: Cursor) {
            val searchItem = ActivityMainSearchRowBinding.bind(view)

            styleLevel.bind(TEXT_BLOCK, searchItem.root)
            styleLevel.bind(TEXT, searchItem.searchName, TextViewColorAdapter())
            styleLevel.bind(TEXT_OFFTOP, searchItem.searchSource, TextViewColorAdapter())

            searchItem.searchName.text = cursor.getString(cursor.getColumnIndex("name"))
            searchItem.searchSource.text = cursor.getString(cursor.getColumnIndex("source"))
        }
    }

    interface IconAwareTabAdapter {
        fun setupIcons()
    }

    inner class GuestTabAdapter: FragmentStateAdapter(supportFragmentManager, lifecycle), IconAwareTabAdapter {

        override fun getItemCount() = 1 // only world

        override fun createFragment(position: Int) = EntryListFragment().apply { profile = Auth.worldMarker }

        override fun setupIcons() {
            binding.slidingTabs.getTabAt(0)?.setIcon(R.drawable.earth)
        }
    }

    inner class TabAdapter(private val self: OwnProfile?): FragmentStateAdapter(supportFragmentManager, lifecycle), IconAwareTabAdapter {

        override fun getItemCount() = 5 // own blog, favorites, communities, world and notifications

        override fun createFragment(position: Int) = when(position) {
            MY_DIARY_TAB -> EntryListFragment().apply { profile = this@TabAdapter.self }
            FAV_TAB  -> EntryListFragment().apply { profile = Auth.favoritesMarker }
            COMMUNITIES_TAB -> EntryListFragment().apply { profile = Auth.communitiesMarker }
            WORLD_TAB -> EntryListFragment().apply { profile = Auth.worldMarker }
            NOTIFICATIONS_TAB -> NotificationListFragment()
            else -> EntryListFragment().apply { profile = null }
        }

        override fun setupIcons() {
            binding.slidingTabs.getTabAt(MY_DIARY_TAB)?.setIcon(R.drawable.home)
            binding.slidingTabs.getTabAt(FAV_TAB)?.setIcon(R.drawable.star_filled)
            binding.slidingTabs.getTabAt(COMMUNITIES_TAB)?.setIcon(R.drawable.community_big)
            binding.slidingTabs.getTabAt(WORLD_TAB)?.setIcon(R.drawable.earth)
            binding.slidingTabs.getTabAt(NOTIFICATIONS_TAB)?.setIcon(R.drawable.notification)
        }
    }

    /**
     * Profile selector adapter. We can't use standard one because we need a way to delete profile or add another.
     */
    inner class ProfileListAdapter(private val profiles: MutableList<OwnProfile>) : RecyclerView.Adapter<ProfileListAdapter.ProfileViewHolder>() {

        /**
         * Dismiss this if item is selected
         */
        lateinit var toDismiss: MaterialDialog

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
            val inflater = LayoutInflater.from(this@MainActivity)
            val v = inflater.inflate(R.layout.activity_main_profile_selection_row, parent, false)
            return ProfileViewHolder(v)
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) = holder.setup(position)

        override fun getItemCount() = profiles.size

        inner class ProfileViewHolder(v: View): RecyclerView.ViewHolder(v) {

            private val profileItem = ActivityMainProfileSelectionRowBinding.bind(v)

            init {
                styleLevel.bind(TEXT, profileItem.profileName, TextViewColorAdapter())
                styleLevel.bind(TEXT_LINKS, profileItem.profileRemove, ImageViewColorAdapter())
            }

            fun setup(pos: Int) {
                val prof = profiles[pos]
                profileItem.profileName.text = prof.nickname

                profileItem.profileRemove.setOnClickListener {
                    MaterialDialog(this@MainActivity)
                            .title(R.string.confirm_action)
                            .message(text = getString(R.string.confirm_profile_deletion).format(prof.nickname))
                            .negativeButton(android.R.string.no)
                            .checkBoxPrompt(R.string.keep_comments_in_other_blogs, onToggle = {})
                            .input(hint = prof.nickname, waitForPositiveButton = false, callback = { dialog, entered ->
                                val doubleConfirmed = entered.toString() == prof.nickname
                                dialog.getActionButton(WhichButton.POSITIVE).isEnabled = doubleConfirmed
                            })
                            .onShow { it.getActionButton(WhichButton.POSITIVE).isEnabled = false }
                            .positiveButton(R.string.confirm, click = {
                                val keepComments = it.getCheckBoxPrompt().isChecked
                                deleteProfile(prof, keepComments)
                                removeItem(pos)
                            })
                            .showThemed(styleLevel)
                }

                itemView.setOnClickListener {
                    selectProfile(prof)
                    toDismiss.dismiss()
                }
            }

            private fun removeItem(pos: Int) {
                profiles.removeAt(pos)
                notifyItemRemoved(pos)
            }
        }
    }
}
