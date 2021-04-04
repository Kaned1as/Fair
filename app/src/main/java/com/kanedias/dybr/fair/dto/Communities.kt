package com.kanedias.dybr.fair.dto

import com.squareup.moshi.Json
import moe.banana.jsonapi2.HasOne
import moe.banana.jsonapi2.JsonApi
import moe.banana.jsonapi2.Policy
import moe.banana.jsonapi2.Resource
import java.util.*

/**
 * Community join request. Newcomers must post this to indicate their willingness
 * to participate in a community.
 *
 * Relationships are important so they are listed in the example.
 *
 * Example
 * ```
 * {
 *   "data": {
 *     "id": "string",
 *     "type": "community-join-request",
 *     "attributes": {
 *       "message": "string",
 *     }
 *   },
 *   "relationships": {
 *     "profile": {
 *       "id": "32",
 *       "type": "profiles"
 *     },
 *     "community": {
 *       "id": "515",
 *       "type": "profiles"
 *     }
 *   }
 * }
 * ```
 *
 * @author Kanedias
 *
 * Created on 2020-08-31
 */
@JsonApi(type = "community-join-requests", policy = Policy.SERIALIZATION_ONLY)
class CommunityJoinRequest: Resource() {

    /**
     * Message that user enters, it's visible to admins
     * who review this join request.
     */
    @Json(name = "message")
    lateinit var message: String

    /**
     * Profile join request is created for
     */
    @Json(name = "profile")
    var profile : HasOne<OwnProfile>? = null

    /**
     * Community that join request is created for
     */
    @Json(name = "community")
    var community : HasOne<OwnProfile>? = null
}


/**
 * Community join response. Returned after successful creation of [CommunityJoinRequest]
 *
 * Example
 * ```
 * {
 *   "data": {
 *     "id": "string",
 *     "type": "community-join-request",
 *     "attributes": {
 *       "message": "as-in-request",
 *       "admin-note": "string",
 *       "state": "pending",
 *       "discarded": true,
 *       "created-at": "string"
 *     }
 *   },
 *   "relationships": {
 *     "profile": {
 *       "id": "32",
 *       "type": "profiles"
 *     },
 *     "community": {
 *       "id": "515",
 *       "type": "profiles"
 *     }
 *   }
 * }
 * ```
 *
 * @author Kanedias
 *
 * Created on 2020-08-31
 */
@JsonApi(type = "community-join-requests", policy = Policy.DESERIALIZATION_ONLY)
class CommunityJoinResponse: Resource() {

    /**
     * State of this join request. Can be "approved" or "pending"
     */
    @Json(name = "state")
    lateinit var state: String

    /**
     * Message that user enters, it's visible to admins
     * who review this join request.
     */
    @Json(name = "message")
    lateinit var message: String

    /**
     * Community mod response to this request. Optional.
     */
    @Json(name = "admin-note")
    lateinit var adminNote: String

    /**
     * Join request creation date
     */
    @Json(name = "created-at")
    lateinit var createdAt: Date

    /**
     * Profile that join request is created for
     */
    @Json(name = "profile")
    lateinit var profile : HasOne<OwnProfile>

    /**
     * Community that join request is created for
     */
    @Json(name = "community")
    lateinit var community : HasOne<OwnProfile>

    /**
     * Community participant, present in case this request was approved
     */
    @Json(name = "participant")
    lateinit var participant: HasOne<CommunityParticipant>
}

/**
 *
 */
@JsonApi(type = "community-participants", policy = Policy.DESERIALIZATION_ONLY)
class CommunityParticipantResponse: Resource() {

    /**
     * Permissions of specified participant
     */
    @Json(name = "permissions")
    lateinit var permissions: List<String>

    /**
     * Profile this participant represents
     */
    @Json(name = "profile")
    lateinit var profile : HasOne<OwnProfile>

    /**
     * Community this participant is from
     */
    @Json(name = "community")
    lateinit var community : HasOne<OwnProfile>
}

typealias CommunityParticipant = CommunityParticipantResponse