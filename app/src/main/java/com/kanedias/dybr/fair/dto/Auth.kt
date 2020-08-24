package com.kanedias.dybr.fair.dto

import android.content.Context
import com.kanedias.dybr.fair.R
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.database.entities.Account

/**
 * Auth static entity providing info about user login status
 *
 * @author Kanedias
 *
 * Created on 05.11.17
 */
object Auth {
    // service variables, changed only once
    lateinit var guest: Account
    lateinit var favoritesMarker: OwnProfile
    lateinit var communitiesMarker: OwnProfile
    lateinit var worldMarker: OwnProfile

    lateinit var user: Account

    /**
     * Current profile after it's loaded
     * Filled in [Auth.updateCurrentProfile]
     */
    var profile: OwnProfile? = null

    fun init(ctx: Context) {
        // setup special values
        this.guest = Account().apply { email = ctx.getString(R.string.guest) }
        this.worldMarker = OwnProfile().apply { id = "world"; blogSlug = ctx.getString(R.string.world) }
        this.communitiesMarker = OwnProfile().apply { id = "world"; blogSlug = ctx.getString(R.string.communities) }
        this.favoritesMarker = OwnProfile().apply { id = "favorites"; blogSlug = ctx.getString(R.string.favorite) }

        this.user = guest
    }

    fun updateCurrentUser(acc: Account) {
        this.user = acc
        this.profile = null

        if (acc !== this.guest) {
            DbProvider.helper.accDao.update(Auth.user)
        }
    }

    fun updateCurrentProfile(prof: OwnProfile?) {
        this.profile = prof
    }
}