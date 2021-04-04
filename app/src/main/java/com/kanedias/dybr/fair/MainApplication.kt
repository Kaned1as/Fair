package com.kanedias.dybr.fair

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import com.ftinc.scoop.Scoop
import com.kanedias.dybr.fair.database.DbProvider
import com.kanedias.dybr.fair.dto.Auth
import com.kanedias.dybr.fair.scheduling.SyncNotificationsWorker
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.service.UserPrefs
import com.kanedias.dybr.fair.themes.*
import org.acra.ACRA
import org.acra.config.CoreConfigurationBuilder
import org.acra.config.DialogConfigurationBuilder
import org.acra.config.MailSenderConfigurationBuilder
import org.acra.data.StringFormat


/**
 * Place to initialize all data prior to launching activities
 *
 * @author Kanedias
 */
class MainApplication : Application() {

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when(key) {
            UserPrefs.NOTIFICATION_CHECK_PREF -> rescheduleJobs()
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)

        // init crash reporting
        val builder = CoreConfigurationBuilder(this)
            .setBuildConfigClass(BuildConfig::class.java)
            .setReportFormat(StringFormat.JSON)
            .setAlsoReportToAndroidFramework(true)
            .apply {
                getPluginConfigurationBuilder(DialogConfigurationBuilder::class.java)
                    .setResIcon(R.mipmap.ic_launcher)
                    .setResText(R.string.app_crashed)
                    .setResCommentPrompt(R.string.leave_crash_comment)
                    .setResTheme(R.style.AppTheme)
            }
            .apply {
                getPluginConfigurationBuilder(MailSenderConfigurationBuilder::class.java)
                    .setMailTo("kanedias@gmx.net")
                    .setSubject(getString(R.string.app_crash_report))
                    .setReportFileName("crash-report.json")
            }
        ACRA.init(this, builder)
    }

    override fun onCreate() {
        super.onCreate()

        DbProvider.setHelper(this)
        UserPrefs.init(this)
        Network.init(this)
        Auth.init(this)

        initTheming()
        initStatusNotifications()
        rescheduleJobs()

        // load last account if it exists
        val acc = DbProvider.helper.accDao.queryBuilder().where().eq("current", true).queryForFirst()
        acc?.let {
            Auth.updateCurrentUser(acc)
        }

        // setup configuration change listener
        UserPrefs.registerListener(preferenceListener)
    }

    private fun rescheduleJobs() {
        // don't schedule anything in crash reporter process
        if (ACRA.isACRASenderServiceProcess())
            return

        // stop scheduling current jobs
        WorkManager.getInstance().cancelUniqueWork(SYNC_NOTIFICATIONS_UNIQUE_JOB).result.get()

        // replace with new one
        SyncNotificationsWorker.scheduleJob()
    }

    private fun initStatusNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notifMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val syncNotifChannel = NotificationChannel(NC_SYNC_NOTIFICATIONS,
                    getString(R.string.notifications),
                    NotificationManager.IMPORTANCE_HIGH)

            notifMgr.createNotificationChannel(syncNotifChannel)
        }
    }

    private fun initTheming() {
        Scoop.initialize(mapOf(
                BACKGROUND to ContextCompat.getColor(this, R.color.scoop_default_background_color),
                TOOLBAR to ContextCompat.getColor(this, R.color.scoop_default_primary_color),
                STATUS_BAR to ContextCompat.getColor(this, R.color.scoop_default_primary_dark_color),
                TOOLBAR_TEXT to ContextCompat.getColor(this, R.color.scoop_default_toolbar_text_color),
                TEXT_HEADERS to ContextCompat.getColor(this, R.color.scoop_default_toolbar_text_color),
                TEXT to ContextCompat.getColor(this, R.color.scoop_default_text_color),
                TEXT_LINKS to ContextCompat.getColor(this, R.color.scoop_default_toolbar_text_color),
                TEXT_BLOCK to ContextCompat.getColor(this, R.color.scoop_default_text_block_background_color),
                TEXT_OFFTOP to ContextCompat.getColor(this, R.color.scoop_default_offtop_text_color),
                ACCENT to ContextCompat.getColor(this, R.color.scoop_default_accent_color),
                ACCENT_TEXT to ContextCompat.getColor(this, R.color.scoop_default_accent_text_color),
                DIVIDER to ContextCompat.getColor(this, R.color.scoop_default_accent_color))
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        DbProvider.releaseHelper()
    }
}
