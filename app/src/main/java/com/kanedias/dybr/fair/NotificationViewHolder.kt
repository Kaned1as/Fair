package com.kanedias.dybr.fair

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.ftinc.scoop.adapters.ImageViewColorAdapter
import com.kanedias.dybr.fair.databinding.FragmentNotificationListItemBinding
import java.text.SimpleDateFormat
import java.util.*
import com.kanedias.dybr.fair.dto.Notification
import com.kanedias.dybr.fair.dto.NotificationRequest
import com.kanedias.dybr.fair.markdown.handleMarkdown
import com.kanedias.dybr.fair.misc.showFullscreenFragment
import com.kanedias.dybr.fair.scheduling.SyncNotificationsWorker
import com.kanedias.dybr.fair.service.Network
import com.kanedias.dybr.fair.themes.*
import com.kanedias.dybr.fair.ui.*
import kotlinx.coroutines.*

/**
 * View holder for showing notifications in main tab.
 * @param iv inflated view to be used by this holder
 *
 * @see NotificationListFragment
 *
 * @author Kanedias
 */
class NotificationViewHolder(iv: View, private val parent: UserContentListFragment) : RecyclerView.ViewHolder(iv) {

    private val binding = FragmentNotificationListItemBinding.bind(iv)

    /**
     * Entry that this holder represents
     */
    private lateinit var notification: Notification

    /**
     * Listener to show comments of this entry
     */
    private val commentShow = View.OnClickListener {
        val activity = it.context as AppCompatActivity

        parent.lifecycleScope.launch {
            Network.perform(
                networkAction = { Network.loadEntry(notification.entryId) },
                uiAction = { entry ->
                    val commentsPage = CommentListFragment().apply {
                        arguments = Bundle().apply {
                            putSerializable(CommentListFragment.ENTRY_ARG, entry)
                            putString(CommentListFragment.COMMENT_ID_ARG, notification.comment.get().id)
                        }
                    }
                    activity.showFullscreenFragment(commentsPage)

                    if (notification.state == "new") {
                        switchRead()
                    }
                }
            )
        }
    }

    init {
        binding.root.setOnClickListener(commentShow)
        binding.notificationRead.setOnClickListener { switchRead() }


        setupTheming()
    }

    fun switchRead() {
        val marked = NotificationRequest().apply {
            id = notification.id
            state = if (notification.state == "new") { "read" } else { "new" }
        }

        parent.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Network.updateNotification(marked) }
                SyncNotificationsWorker.markRead(itemView.context, notification)

                notification.state = marked.state
                updateState()
            } catch (ex: Exception) {
                Network.reportErrors(itemView.context, ex)
            }
        }
    }

    private fun setupTheming() {
        val styleLevel = parent.styleLevel

        styleLevel.bind(TEXT_BLOCK, itemView, CardViewColorAdapter())
        styleLevel.bind(TEXT, binding.notificationCause, TextViewDisableAwareColorAdapter())
        styleLevel.bind(TEXT, binding.notificationBlog, TextViewDisableAwareColorAdapter())
        styleLevel.bind(TEXT, binding.notificationProfile, TextViewDisableAwareColorAdapter())
        styleLevel.bind(TEXT, binding.notificationDate, TextViewDisableAwareColorAdapter())
        styleLevel.bind(TEXT, binding.notificationMessage, TextViewDisableAwareColorAdapter())
        styleLevel.bind(TEXT_LINKS, binding.notificationMessage, TextViewLinksAdapter())
        styleLevel.bind(TEXT_LINKS, binding.notificationRead, ImageViewColorAdapter())
    }

    /**
     * Called when this holder should be refreshed based on what it must show now
     */
    fun setup(notification: Notification) {
        this.notification = notification

        updateState()

        val comment = notification.comment.get(notification.document)
        val profile = notification.profile.get(notification.document)
        val source = notification.source.get(notification.document)

        // setup text views from entry data
        binding.notificationDate.text = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(comment.createdAt)
        binding.notificationCause.setText(R.string.comment)
        binding.notificationProfile.text = profile.nickname
        binding.notificationBlog.text = source.blogTitle
        binding.notificationMessage.handleMarkdown(comment.content)
    }

    private fun updateState() {
        if (notification.state == "read") {
            binding.notificationRead.setImageResource(R.drawable.done_all)
            toggleEnableRecursive(binding.notificationContentArea, enabled = false)
        } else { // state == "new"
            binding.notificationRead.setImageResource(R.drawable.done)
            toggleEnableRecursive(binding.notificationContentArea, enabled = true)
        }
    }
}