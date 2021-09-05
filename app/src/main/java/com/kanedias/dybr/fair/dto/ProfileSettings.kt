package com.kanedias.dybr.fair.dto

import com.squareup.moshi.Json
import java.io.Serializable
import java.util.*

/**
 * Main settings structure linked with profile
 * Example:
 * ```
 * "settings": {
 *     "avatar": "https://dybr.ru/img/3/xxxxxxxx.jpg",
 *     "subtext": "Sample text",
 *     "designs": {...},
 *     "notifications": {...},
 *     "community": {...},
 *     "privacy": {...},
 *     "permissions": {...},
 *     "pinned-entries": ["1", "2", "3"]
 * }
 * ```
 *
 * @see NotificationsSettings
 * @see PaginationSettings
 * @see PrivacySettings
 *
 * @author Kanedias
 *
 * Created on 28.07.18
 */
data class ProfileSettings(
        @Json(name = "avatar")
        val avatar: String? = null,

        @Json(name = "subtext")
        val subtext: String? = null,

        @Json(name = "current-design")
        val currentDesign: String? = null,

        @Json(name = "notifications")
        var notifications: NotificationsSettings? = null,

        @Json(name = "privacy")
        var privacy: PrivacySettings? = null,

        @Json(name = "anonymity") // TODO: rename on backend
        var anonimity: AnonimitySettings? = null,

        @Json(name = "community")
        var community: CommunitySettings? = null,

        @Json(name = "permissions")
        var permissions: RecordPermissions? = null,

        @Json(name = "pinned-entries")
        var pinnedEntries: MutableSet<String>? = null,

        @Json(name = "reactions")
        var reactions: ReactionConfig? = null
) : Serializable

/**
 * Only ever present in community profiles
 * Example:
 * ```
 * "community": {
 *     "join-request": "auto"
 * }
 * ```
 */
data class CommunitySettings(
        /**
         * How this community handles join requests.
         * "auto" means they're automatically approved after creation.
         * "manual" means it's a pre-moderated community.
         */
        @Json(name = "join-request")
        var joinRequest: String? = null
) : Serializable

/**
 * Example:
 * ```
 * "notifications": {
 *     "comments": {...},
 *     "entries": {...}
 * }
 * ```
 *
 * @see NotificationConfig
 */
data class NotificationsSettings(
        var comments: NotificationConfig? = null,
        var entries: NotificationConfig? = null
) : Serializable

/**
 * Example:
 * ```
 * "entries": {
 *     "enable": false,
 *     "regularity": "timely"
 * }
 * ```
 */
data class NotificationConfig (
        val enable: Boolean? = null,
        val regularity: String? = null
) : Serializable

/**
 * Example:
 * ```
 * "reactions": {
 *     "disable": false,
 *     "use-images": false,
 *     "disable-in-blog": false
 * }
 * ```
 */
data class ReactionConfig(
        @Json(name = "hide")
        var disable: Boolean? = null,

        @Json(name = "use-images")
        var useImages: Boolean? = null,

        @Json(name = "disable-in-blog")
        var disableInBlog: Boolean? = null
) : Serializable

/**
 * Pagination is a setting of a user, not profile.
 * Example:
 * ```
 * "pagination": {
 *     "blogs": 20,
 *     "comments": 20,
 *     "entries": 10,
 *     "profiles": 20
 * }
 * ```
 */
data class PaginationSettings(
        val blogs: Int = 20,
        val comments: Int = 20,
        val entries: Int = 10,
        val profiles: Int = 20
) : Serializable

/**
 * Example:
 * ```
 * "privacy": {
 *     "hide-content-from-search-engines": true,
 *     "dybrfeed": true
 * }
 * ```
 */
data class PrivacySettings(
        @Json(name = "dybrfeed")
        val dybrfeed: Boolean? = null,

        @Json(name = "hide-content-from-search-engines")
        val hideContent: Boolean? = null
) : Serializable

/**
 * Example:
 * ```
 * "anonimity": {
 *     "identity-change-rule": "time-limit",
 *     "identity-condition": {...}
 * }
 * ```
 */
data class AnonimitySettings(
        @Json(name = "identity-change-rule")
        val identityChangeRule: String? = null,

        @Json(name = "identity-condition")
        val identityCondition: TimeCondition? = null
) : Serializable

/**
 * Example: TBD
 */
data class TimeCondition(
        @Json(name = "at-time")
        val atTime: Date? = null,

        @Json(name = "after-hours")
        val afterHours: Int? = null,
) : Serializable