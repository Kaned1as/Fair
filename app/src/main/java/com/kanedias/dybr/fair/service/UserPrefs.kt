package com.kanedias.dybr.fair.service

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.preference.PreferenceManager

/**
 * @author Kanedias
 *
 * Created on 2020-08-05
 */
object UserPrefs {

    const val HOME_SERVER_PREF = "home-server"
    const val FIRST_APP_LAUNCH_PREF = "first-app-launch"
    const val NOTIFICATION_CHECK_PREF = "notification-check-interval"
    const val USER_PREFERRED_LANGUAGE_PREF = "user-preferred-language"
    const val USER_PREFERRED_TEXT_SIZE_PREF = "user-preferred-text-size"

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
    }

    fun registerListener(listener: OnSharedPreferenceChangeListener) = prefs.registerOnSharedPreferenceChangeListener(listener)

    var firstAppLaunch: Boolean
        get() = prefs.getBoolean(FIRST_APP_LAUNCH_PREF, true)
        set(value) = prefs.edit().putBoolean(FIRST_APP_LAUNCH_PREF, value).apply()

    var notifCheckInterval: String
        get() = prefs.getString(NOTIFICATION_CHECK_PREF, "15")!!
        set(value) = prefs.edit().putString(NOTIFICATION_CHECK_PREF, value).apply()

    var userPreferredLanguage: String
        get() = prefs.getString(USER_PREFERRED_LANGUAGE_PREF, "")!!
        set(value) = prefs.edit().putString(USER_PREFERRED_LANGUAGE_PREF, value).apply()

    var userPreferredTextSize: Int
        get() = prefs.getString(USER_PREFERRED_TEXT_SIZE_PREF, "12")?.toIntOrNull() ?: 12
        set(value) = prefs.edit().putString(USER_PREFERRED_TEXT_SIZE_PREF, value.toString()).apply()

    var homeServerUrl: String
        get() = prefs.getString(HOME_SERVER_PREF, Network.DEFAULT_DYBR_API_ENDPOINT)!!
        set(value) = prefs.edit().putString(HOME_SERVER_PREF, value).apply()
}