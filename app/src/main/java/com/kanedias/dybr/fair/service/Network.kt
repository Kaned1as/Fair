package com.kanedias.dybr.fair.service

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.text.SpannableStringBuilder
import android.util.Log
import android.widget.Toast
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import com.auth0.android.jwt.JWT
import com.kanedias.dybr.fair.BuildConfig
import com.kanedias.dybr.fair.COMMENT_PAGE_SIZE
import com.kanedias.dybr.fair.PAGE_SIZE
import com.kanedias.dybr.fair.R
import com.kanedias.dybr.fair.database.entities.Account
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.kanedias.dybr.fair.dto.*
import com.kanedias.dybr.fair.markdown.mdRendererFrom
import com.kanedias.dybr.fair.markdown.postProcessSpans
import com.kanedias.dybr.fair.misc.HttpApiException
import com.kanedias.dybr.fair.misc.HttpException
import com.kanedias.html2md.Html2Markdown
import com.squareup.moshi.Json
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.banana.jsonapi2.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSource
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.util.*
import java.net.HttpURLConnection.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.http.HttpMethod
import org.json.JSONObject
import kotlin.random.Random
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.jvm.kotlinProperty


/**
 * Singleton for outlining network-related operations. Has heavy dependencies on [Auth].
 * Common network operations and http client routines go here.
 *
 * State machine of API interchange can be roughly described as:
 * ```
 *  Android-client                      Dybr server
 *       |                                  |
 *       |                                  |
 *       |    Register/login to /users      |\
 *       | ------------------------------>  | \
 *       |    User info + access token      |  |
 *       | <------------------------------  |  |
 *       |                                  |  |-- Auth. Without this only limited guest mode is available.
 *       |     Get/create own profiles      |  |   Order of operations here is important.
 *       | ------------------------------>  |  |
 *       |   List of profiles/current       | /
 *       | <------------------------------  |/
 *       |                                  |
 *       |    Get diary entries/comments    |
 *       | ------------------------------>  | -- can be performed without auth, but with it content may be richer
 *       |    Post diary entries/comments   |
 *       | ------------------------------>  | -- guests can't post diary entries but can comment
 *       |      Post comments/pics          |
 *       | ------------------------------>  |\
 *       |      Change profile settings     | \
 *       | ------------------------------>  |  |
 *       |     Administrate communities     |  |-- authorized users only
 *       | ------------------------------>  |  |
 *       |     Like/dislike/emote           | /
 *       | ------------------------------>  |/
 *       |                                  |
 *       |     Responses/confirmations      |
 *       | <------------------------------  |
 *       |     Lists of entries/comments    |
 *       | <------------------------------  |
 *       |         URLs to images           |
 *       | <------------------------------  |
 *       |   Content of entries/comments    |
 *       | <------------------------------  |
 *       |                                  |
 * ```
 *
 * @author Kanedias
 *
 * Created on 12.11.17
 */
@WorkerThread
object Network {

    const val DEFAULT_DYBR_API_ENDPOINT = "https://dybr.ru/v2/"
    const val USER_AGENT = "Fair ${BuildConfig.VERSION_NAME}"

    private lateinit var MAIN_DYBR_API_ENDPOINT: HttpUrl
    private lateinit var IMG_UPLOAD_ENDPOINT: String
    private lateinit var USERS_ENDPOINT: String
    private lateinit var SESSIONS_ENDPOINT: String
    private lateinit var PROFILES_ENDPOINT: String
    private lateinit var BLOGS_ENDPOINT: String
    private lateinit var ENTRIES_ENDPOINT: String
    private lateinit var FAVORITES_ENDPOINT: String
    private lateinit var COMMENTS_ENDPOINT: String
    private lateinit var NOTIFICATIONS_ENDPOINT: String
    private lateinit var BOOKMARKS_ENDPOINT: String
    private lateinit var REACTIONS_ENDPOINT: String
    private lateinit var REACTION_SETS_ENDPOINT: String
    private lateinit var ACTION_LISTS_ENDPOINT: String
    private lateinit var COMMUNITY_JOIN_REQ_ENDPOINT: String

    private val MIME_JSON_API = "application/vnd.api+json".toMediaType()

    private val jsonApiAdapter = ResourceAdapterFactory.builder()
            .add(LoginRequest::class.java)
            .add(LoginResponse::class.java)
            .add(RegisterRequest::class.java)
            .add(RegisterResponse::class.java)
            .add(ProfileCreateRequest::class.java)
            .add(ProfileResponse::class.java)
            .add(EntryCreateRequest::class.java)
            .add(EntryResponse::class.java)
            .add(EntryTagResponse::class.java)
            .add(CreateCommentRequest::class.java)
            .add(CommentResponse::class.java)
            .add(DesignResponse::class.java)
            .add(ReactionTypeResponse::class.java)
            .add(ReactionSetResponse::class.java)
            .add(CreateReactionRequest::class.java)
            .add(ReactionResponse::class.java)
            .add(CommunityJoinRequest::class.java)
            .add(CommunityJoinResponse::class.java)
            .add(CommunityParticipantResponse::class.java)
            .setJsonNameMapping { it.kotlinProperty?.findAnnotation<Json>()?.name ?: it.name }
            .build()

    private val jsonConverter = Moshi.Builder()
            .add(jsonApiAdapter)
            .add(KotlinJsonAdapterFactory())
            .add(Date::class.java, Rfc3339DateJsonAdapter())
            .build()

    private lateinit var appCtx: Context
    private lateinit var prefChangeListener: OnSharedPreferenceChangeListener

    /**
     * OkHttp [Interceptor] for populating authorization header.
     *
     * Authorizes all requests if we have access token for account (i.e. logged in).
     */
    private val authorizer = Interceptor { chain ->
        val origRequest = chain.request()
        val alreadyHasAuth = origRequest.headers.names().contains("Authorization")

        // skip if we already have auth header in request
        if (Auth.user.accessToken == null || alreadyHasAuth)
            return@Interceptor chain.proceed(origRequest)

        val authorisedReq  = origRequest.newBuilder()
                .header("Authorization", "Bearer ${Auth.user.accessToken}")
                .build()

        return@Interceptor chain.proceed(authorisedReq)
    }

    private val nonceHandler = Interceptor { chain ->
        val origRequest = chain.request()
        val alreadyHasNonce = origRequest.url.queryParameterNames.contains("nonce")

        // skip if we already have auth header in request
        if (alreadyHasNonce || !HttpMethod.requiresRequestBody(origRequest.method))
            return@Interceptor chain.proceed(origRequest)

        val nonce = Random.nextInt(0, Int.MAX_VALUE).toString()
        val enrichedReq  = origRequest.newBuilder()
                .url(origRequest.url.newBuilder().addQueryParameter("nonce", nonce).build())
                .build()

        return@Interceptor chain.proceed(enrichedReq)
    }

    private val userAgent = Interceptor { chain ->
        chain.proceed(chain
                .request()
                .newBuilder()
                .header("User-Agent", USER_AGENT)
                .build())
    }

    private lateinit var httpClient: OkHttpClient

    @UiThread
    fun init(ctx: Context) {
        appCtx = ctx

        // reinitialize endpoints
        setupEndpoints(ctx)

        prefChangeListener = OnSharedPreferenceChangeListener { _, _ -> setupEndpoints(ctx) }
        UserPrefs.registerListener(prefChangeListener)

        // setup http client
        httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool())
                .dispatcher(Dispatcher())
                .addInterceptor(authorizer)
                .addInterceptor(nonceHandler)
                .addInterceptor(userAgent)
                .build()
    }

    @AnyThread
    private fun setupEndpoints(ctx: Context) {
        val endpoint = UserPrefs.homeServerUrl

        // endpoint itself must be valid url
        val valid = endpoint.toHttpUrlOrNull()
        if (valid == null || !endpoint.endsWith("/")) {
            // url is invalid
            Toast.makeText(ctx, R.string.invalid_dybr_endpoint, Toast.LENGTH_SHORT).show()
            MAIN_DYBR_API_ENDPOINT = DEFAULT_DYBR_API_ENDPOINT.toHttpUrl()
            UserPrefs.homeServerUrl = DEFAULT_DYBR_API_ENDPOINT
        } else {
            // url is valid, proceed
            MAIN_DYBR_API_ENDPOINT = valid
        }

        IMG_UPLOAD_ENDPOINT = resolve("image-upload")!!.toString()
        USERS_ENDPOINT = resolve("users")!!.toString()
        SESSIONS_ENDPOINT = resolve("sessions")!!.toString()
        PROFILES_ENDPOINT = resolve("profiles")!!.toString()
        BLOGS_ENDPOINT = resolve("blogs")!!.toString()
        ENTRIES_ENDPOINT = resolve("entries")!!.toString()
        FAVORITES_ENDPOINT = resolve("favorites")!!.toString()
        COMMENTS_ENDPOINT = resolve("comments")!!.toString()
        NOTIFICATIONS_ENDPOINT = resolve("notifications")!!.toString()
        BOOKMARKS_ENDPOINT = resolve("bookmarks")!!.toString()
        REACTIONS_ENDPOINT = resolve("reactions")!!.toString()
        REACTION_SETS_ENDPOINT = resolve("reaction-sets")!!.toString()
        ACTION_LISTS_ENDPOINT = resolve("lists")!!.toString()
        COMMUNITY_JOIN_REQ_ENDPOINT = resolve("community-join-requests")!!.toString()
    }

    @AnyThread
    fun resolve(url: String?): HttpUrl? {
        if (url.isNullOrEmpty())
            return null

        return MAIN_DYBR_API_ENDPOINT.resolve(url)
    }

    @AnyThread
    fun defaultAvatar(): HttpUrl {
        return resolve("/static/media/default_ava.9b2e26d5.jpg")!!
    }

    /**
     * Perform registration with specified register request
     * @param regInfo registration request with all necessary fields filled
     */
    @WorkerThread
    fun createAccount(regInfo: RegisterRequest): RegisterResponse {
        val reqBody = toWrappedJson(regInfo).toRequestBody(MIME_JSON_API)

        // we need empty auth in order to create users
        val req = Request.Builder().post(reqBody).url(USERS_ENDPOINT).header("Authorization", "").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't create account")
        }

        // response is successful, should have register response in answer
        return fromWrappedJson(resp.body!!.source(), RegisterResponse::class.java)!!
    }

    /**
     * Logs in with specified account, i.e. creates session, obtains access token for this user
     * ands sets [Auth.user] if all went good.
     * After this call succeeds you can be certain that [httpClient] sends requests with correct
     * "Authorization" header.
     *
     * @param acc account to authenticate with
     * @param profileId specific profile id that was requested at login
     * @return true if auth was successful, false otherwise
     * @throws IOException on connection fail
     */
    @WorkerThread
    fun login(acc: Account, profileId: String? = null) {
        val loginRequest = LoginRequest().apply {
            action = "login"
            email = acc.email
            password = acc.password
        }

        if (acc.accessToken != null) {
            // we already have access token for this account
            val jwt = JWT(acc.accessToken!!)
            val currentProfile = jwt.audience?.firstOrNull()
            val profileChangeNeeded = profileId != null && profileId != currentProfile

            // if token is close to expiration then better get a new one
            val weekAfter = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 7) }.time
            val expirationTime = jwt.expiresAt ?: weekAfter
            if (expirationTime >= weekAfter && !profileChangeNeeded) {
                // token is fresh and we are already logged in, skip network exchange completely
                Auth.updateCurrentUser(acc)
                return
            }

            // JWT expired, set known profile if we have it
            loginRequest.profile = currentProfile
        }

        if (profileId != null) {
            // force login profile to specific id
            loginRequest.profile = profileId
        }

        val body = toWrappedJson(loginRequest).toRequestBody(MIME_JSON_API)
        val req = Request.Builder().post(body).url(SESSIONS_ENDPOINT).build()
        val resp = httpClient.newCall(req).execute()

        // unprocessable entity error can be returned when something is wrong with your input
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't login with email ${acc.email}")
        }

        // if response is successful we should have login response in body
        val response = fromWrappedJson(resp.body!!.source(), LoginResponse::class.java)!!

        // login successful
        // memorize the access token and use it in next requests
        acc.accessToken = response.accessToken
        Auth.updateCurrentUser(acc)

        // save user info
        val reqUser = Request.Builder().url(USERS_ENDPOINT).build()
        val respUser = httpClient.newCall(reqUser).execute() // returns array with only one element
        val user = fromWrappedJson(respUser.body!!.source(), User::class.java)
            ?: return

        // memorize account info
        acc.apply {
            serverId = user.id
            createdAt = user.createdAt
            updatedAt = user.updatedAt
            isAdult = user.isAdult
        }
    }

    private fun <T: ResourceIdentifier> toWrappedJson(obj: T): String {
        val wrapped = ObjectDocument<T>().apply { set(obj) }
        val type = Types.newParameterizedType(Document::class.java, obj::class.java)
        val reqAdapter = jsonConverter.adapter<Document>(type)
        return reqAdapter.toJson(wrapped)
    }

    private fun <T: ResourceIdentifier> toWrappedListJson(obj: T): String {
        val wrapped = ArrayDocument<T>().apply { add(obj) }
        val type = Types.newParameterizedType(ArrayDocument::class.java, obj::class.java)
        val reqAdapter = jsonConverter.adapter<Document>(type)
        return reqAdapter.toJson(wrapped)
    }

    private fun <T: ResourceIdentifier> fromWrappedJson(obj: BufferedSource, clazz: Class<T>): T? {
        val type = Types.newParameterizedType(Document::class.java, clazz)
        val reqAdapter = jsonConverter.adapter<Document>(type)
        val doc = reqAdapter.fromJson(obj)!!
        return doc.asObjectDocument<T>().get()
    }

    private fun <T: ResourceIdentifier> fromWrappedListJson(obj: BufferedSource, clazz: Class<T>): ArrayDocument<T> {
        val type = Types.newParameterizedType(Document::class.java, clazz)
        val reqAdapter = jsonConverter.adapter<Document>(type)
        val doc = reqAdapter.fromJson(obj)!!
        return doc.asArrayDocument()
    }

    /**
     * Converts links/metadata json buffer from document to object
     * @param buffer Json-API buffer containing map-like structure
     * @return string<->string map or empty if buffer is null
     */
    fun <T> bufferToObject(buffer: JsonBuffer<Any>?, type: Class<T>): T? {
        if (buffer == null)
            return null

        val mapAdapter = jsonConverter.adapter(type)
        return buffer.get<T>(mapAdapter)
    }

    /**
     * Load all profiles for current account. The account must be already set in [Auth.user].
     * Guests can't request any profiles.
     * @return list of profiles that are bound to currently logged in user
     */
    @WorkerThread
    fun loadUserProfiles(): List<OwnProfile> {
        val req = Request.Builder().url("$USERS_ENDPOINT/${Auth.user.serverId}?include=profiles,favorites,my-communities").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't load user profiles")

        // there's an edge-case when user is deleted on server but we still have Auth.user.serverId set
        val user = fromWrappedJson(resp.body!!.source(), User::class.java)
                ?: return emptyList()
        return user.profiles.get(user.document)
    }

    /**
     * Fully loads profile, with blog and favorites
     * @param id identifier of profile to load
     */
    @WorkerThread
    fun loadProfile(id: String): OwnProfile {
        val req = Request.Builder().url("$PROFILES_ENDPOINT/$id?include=favorites,my-communities").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load profile $id")
        }

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), OwnProfile::class.java)!!
    }

    /**
     * Loads readers of the profile
     *
     * @param id id of the profile
     */
    @WorkerThread
    fun loadProfileReaders(id: String): ArrayDocument<OwnProfile> {
        val req = Request.Builder().url("$PROFILES_ENDPOINT/$id/readers").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load subscribers for profile $id")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), OwnProfile::class.java)
    }

    /**
     * Loads subscriptions of the profile
     *
     * @param id id of the profile
     */
    @WorkerThread
    fun loadProfileSubscriptions(id: String): ArrayDocument<OwnProfile> {
        // TODO: should be `subscriptions` on backend
        val req = Request.Builder().url("$PROFILES_ENDPOINT/$id/subscribers").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load subscribers for profile $id")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), OwnProfile::class.java)
    }

    /**
     * Loads all profile tags, returns as document
     * @param id identifier of profile to load
     * @return array document containing all profile tags
     */
    @WorkerThread
    fun loadProfileTags(id: String): ArrayDocument<EntryTag> {
        val req = Request.Builder().url("$PROFILES_ENDPOINT/$id/tags").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load profile $id")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), EntryTag::class.java)
    }

    /**
     * Fully loads profile, with blog and favorites
     * @param nickname nickname of profile to load
     */
    @WorkerThread
    fun loadProfileByNickname(nickname: String): OwnProfile {
        val req = Request.Builder().url("$PROFILES_ENDPOINT?filters[nickname]=$nickname&include=favorites").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load profile for nickname $nickname")
        }

        // response is returned after execute call, body is not null
        val filtered = fromWrappedListJson(resp.body!!.source(), OwnProfile::class.java)
        if (filtered.isEmpty())
            throw HttpException(404, "", "")

        return filtered[0]
    }

    /**
     * Retrieves a list of profiles matching provided criteria. No linked objects are included
     * by default, only attributes.
     *
     * @param filters criteria to select among all profiles
     * @param pageNum index of page to retrieve, defaults to 1
     * @param pageSize page size, i.e., how many data objects reside on one page
     */
    @WorkerThread
    fun searchProfiles(filters: Map<String, String>,
                       pageNum: Int = 1,
                       pageSize: Int = PAGE_SIZE): ArrayDocument<OwnProfile> {
        val builder = PROFILES_ENDPOINT.toHttpUrl().newBuilder()
        builder.addQueryParameter("page[number]", pageNum.toString())
                .addQueryParameter("page[size]", pageSize.toString())
                .addQueryParameter("sort", "-created-at")

        for ((type, value) in filters) {
            builder.addQueryParameter("filters[$type]", value)
        }

        val req = Request.Builder().url(builder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't perform profile search for $filters")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), OwnProfile::class.java)
    }

    /**
     * Remove own profile. Warning: this deletes diaries, comments and all associated data with it.
     * Use with caution.
     *
     * @warning doesn't work for now
     * @param prof Profile to delete. Only profile id is actually needed.
     */
    @WorkerThread
    fun removeProfile(prof: OwnProfile, keepComments: Boolean = false) {
        val queryBuilder = "$PROFILES_ENDPOINT/${prof.id}".toHttpUrl().newBuilder()
        if (keepComments) {
            queryBuilder.addQueryParameter("comments", "keep")
        }

        val req = Request.Builder().delete().url(queryBuilder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't remove profile ${prof.nickname}")

        // we don't need answer body
    }

    /**
     * Create new profile for logged in user
     * @param prof profile creation request with all info filled in
     */
    @WorkerThread
    fun createProfile(prof: ProfileCreateRequest): OwnProfile {
        return createEntity(PROFILES_ENDPOINT, prof)
    }

    /**
     * Add the requested profile to the favorites of current user's profile
     * @param prof profile to add to favorites
     */
    @WorkerThread
    fun addFavorite(prof: OwnProfile) {
        val reqBody = toWrappedListJson(ResourceIdentifier(prof)).toRequestBody(MIME_JSON_API)
        val req = Request.Builder()
            .url("$PROFILES_ENDPOINT/${Auth.profile!!.id}/relationships/favorites")
            .post(reqBody)
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't add favorite to profile ${prof.nickname}")
    }

    /**
     * Remove requested profile from favorites of current user's profile
     * @param prof profile to remove from favorites
     */
    @WorkerThread
    fun removeFavorite(prof: OwnProfile) {
        val reqBody = toWrappedListJson(ResourceIdentifier(prof)).toRequestBody(MIME_JSON_API)
        val req = Request.Builder()
            .url("$PROFILES_ENDPOINT/${Auth.profile!!.id}/relationships/favorites")
            .delete(reqBody)
            .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't remove favorite for profile ${prof.nickname}")
    }

    /**
     * Updates existing profile.
     *
     * @param profReq profile to update. Must exist on server. Must have id field set.
     */
    @WorkerThread
    fun updateProfile(profReq: ProfileCreateRequest): OwnProfile {
        val reqBody = toWrappedJson(profReq).toRequestBody(MIME_JSON_API)
        val req = Request.Builder().url("$PROFILES_ENDPOINT/${profReq.id}").patch(reqBody).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't update profile")

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), OwnProfile::class.java)!!
    }

    /**
     * Loads current design of the profile. Retrieves all profile designs and filters by `settings.currentDesign`
     * @param prof profile to load design from
     * @return current design of the profile or null if nothing found
     */
    @WorkerThread
    fun loadProfileDesign(prof: OwnProfile): Design? {
        val designId = prof.settings.currentDesign

        if (designId.isNullOrEmpty() || designId == "0") {
            // use parent/default design
            return null
        }

        val req = Request.Builder().url("$PROFILES_ENDPOINT/${prof.id}/relationships/designs/$designId").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load profile design for ${prof.nickname}")
        }

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), Design::class.java)
    }

    /**
     * Load one particular profile by slug
     * @param slug slug of requested profile blog
     */
    @WorkerThread
    fun loadProfileBySlug(slug: String): OwnProfile {
        val req = Request.Builder().url("$PROFILES_ENDPOINT?filters[blog-slug]=$slug").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load blog with name $slug")
        }

        // response is returned after execute call, body is not null
        val filtered = fromWrappedListJson(resp.body!!.source(), OwnProfile::class.java)
        if (filtered.isEmpty())
            throw HttpException(404, "", "No such profile found: $slug")

        return filtered[0]
    }

    /**
     * Pull diary entries from blog denoted by [prof]. Includes profiles and blog for returned entry list.
     * The resulting URL will be like this: http://dybr.ru/api/v1/blogs/<blog-slug>
     *
     * @param prof profile with blog to retrieve entries from
     * @param pageNum page number to retrieve
     * @param starter timestamp in the form of unixtime, used to indicate start of paging sequence
     */
    @WorkerThread
    fun loadEntries(prof: OwnProfile? = null,
                    pageNum: Int = 1,
                    filters: Map<String, String> = emptyMap(),
                    starter: Long = System.currentTimeMillis() / 1000): ArrayDocument<Entry> {
        // handle special case when we selected tab with favorites
        val builder = when (prof) {
            Auth.favoritesMarker ->
                "$PROFILES_ENDPOINT/${Auth.profile?.id}/favorites/entries".toHttpUrl()
                    .newBuilder()
            Auth.communitiesMarker ->
                "$PROFILES_ENDPOINT/${Auth.profile?.id}/communities/entries".toHttpUrl()
                    .newBuilder()
            Auth.worldMarker ->
                ENTRIES_ENDPOINT.toHttpUrl()
                    .newBuilder()
                    .addQueryParameter("filters[feed]", "1")
            null -> ENTRIES_ENDPOINT.toHttpUrl().newBuilder()
            else -> "$BLOGS_ENDPOINT/${prof.id}/entries".toHttpUrl().newBuilder()
        }

        builder.addQueryParameter("page[number]", pageNum.toString())
                .addQueryParameter("page[size]", PAGE_SIZE.toString())
                .addQueryParameter("page[starter]", starter.toString())
                .addQueryParameter("include", "profiles,community,reactions")
                .addQueryParameter("sort", "-created-at")

        for ((type, value) in filters) {
            builder.addQueryParameter("filters[$type]", value)
        }

        val req = Request.Builder().url(builder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load entries for blog ${prof?.blogSlug}, page $pageNum")
        }

        // response is returned after execute call, body is not null
        val entryDoc = fromWrappedListJson(resp.body!!.source(), Entry::class.java)
        entryDoc.map { it.content }.forEach { preloadMarkdown(it) }

        return entryDoc
    }

    /**
     * Load one particular entry by its ID. Includes blog and profile.
     * @param id identifier of requested entry
     */
    @WorkerThread
    fun loadEntry(id: String): Entry {
        val req = Request.Builder().url("$ENTRIES_ENDPOINT/$id?include=profiles,reactions,community").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load entry $id")
        }

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), Entry::class.java)!!
    }

    /**
     * Pull diary comments from entry denoted by [entry].
     *
     * @param entry entry to retrieve comments from
     */
    @WorkerThread
    fun loadComments(entry: Entry, pageNum: Int = 1, pageSize: Int = COMMENT_PAGE_SIZE): ArrayDocument<Comment> {
        val builder = "$ENTRIES_ENDPOINT/${entry.id}/comments".toHttpUrl().newBuilder()
                .addQueryParameter("page[number]", pageNum.toString())
                .addQueryParameter("page[size]", pageSize.toString())
                .addQueryParameter("include", "profiles,entries")
                .addQueryParameter("sort", "created-at")

        val req = Request.Builder().url(builder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load comments for entry ${entry.id}")
        }

        // response is returned after execute call, body is not null
        val commentDoc = fromWrappedListJson(resp.body!!.source(), Comment::class.java)
        commentDoc.map { it.content }.forEach { preloadMarkdown(it) }
        return commentDoc
    }

    /**
     * Put markdown content into local cache
     */
    @AnyThread
    private fun preloadMarkdown(html: String) {
        val mdContent = Html2Markdown().parseExtended(html)
        val spanned = mdRendererFrom(appCtx).toMarkdown(mdContent) as SpannableStringBuilder
        postProcessSpans(spanned, appCtx)

        SpanCache.putId(html.hashCode(), spanned)
    }

    /**
     * Create new entry comment on server.
     * @param comment comment to create. Must not exist on server. Should be filled with entry, body content etc.
     */
    @WorkerThread
    fun createComment(comment: CreateCommentRequest): Comment {
        return createEntity(COMMENTS_ENDPOINT, comment)
    }

    /**
     * Updates existing comment. Only content attribute is changeable.
     * @param comment comment to update. Must exist on server. Must have id field set and null entry.
     */
    @WorkerThread
    fun updateComment(comment: CreateCommentRequest): Comment {
        val reqBody = toWrappedJson(comment).toRequestBody(MIME_JSON_API)
        val req = Request.Builder().url("$COMMENTS_ENDPOINT/${comment.id}").patch(reqBody).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't update comment ${comment.id}")

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), Comment::class.java)!!
    }

    /**
     * Deletes existing comment.
     * @param comment comment to delete. Must exist on server. Must have id field set.
     */
    @WorkerThread
    fun deleteComment(comment: Comment) {
        val req = Request.Builder().url("$COMMENTS_ENDPOINT/${comment.id}").delete().build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't delete comment ${comment.id}")
    }

    /**
     * Create new blog entry on server.
     * @param entry entry to create. Must not exist on server. Should be filled with blog, title, body content etc.
     */
    @WorkerThread
    fun createEntry(entry: EntryCreateRequest): Entry {
        return createEntity(ENTRIES_ENDPOINT, entry)
    }

    /**
     * Updates existing entry. Content, title attributes are changeable.
     * @param entry entry to update. Must exist on server. Must have id field set and null blog.
     */
    @WorkerThread
    fun updateEntry(entry: EntryCreateRequest): Entry {
        val reqBody = toWrappedJson(entry).toRequestBody(MIME_JSON_API)
        val req = Request.Builder().url("$ENTRIES_ENDPOINT/${entry.id}").patch(reqBody).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't update entry ${entry.id}")

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), Entry::class.java)!!
    }

    /**
     * Deletes existing entry.
     * @param entry entry to delete. Must exist on server. Must have id field set.
     */
    @WorkerThread
    fun deleteEntry(entry: Entry) {
        val req = Request.Builder().url("$ENTRIES_ENDPOINT/${entry.id}").delete().build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't delete entry ${entry.id}")
    }

    /**
     * Loads notifications for current profile
     *
     * @param pageSize number of data entities on one page
     * @param pageNum page number to retrieve
     * @param onlyNew true if only new (unread) notifications should be loaded
     */
    @WorkerThread
    fun loadNotifications(pageSize: Int = PAGE_SIZE, pageNum: Int = 1, onlyNew: Boolean = false) : ArrayDocument<Notification> {
        if (Auth.profile == null)
            return ArrayDocument()

        val builder = "$PROFILES_ENDPOINT/${Auth.profile?.id}/notifications".toHttpUrl().newBuilder()
        builder.addQueryParameter("page[number]", pageNum.toString())
                .addQueryParameter("page[size]", pageSize.toString())
                .addQueryParameter("include", "comments,profiles")
                .addQueryParameter("sort", "state,-comment_id")

        if (onlyNew) {
            builder.addQueryParameter("filters[state]", "new")
        }

        val req = Request.Builder().url(builder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load notifications for profile ${Auth.profile?.nickname}")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), Notification::class.java)
    }

    /**
     * Updates existing notification. Only state attribute is changeable.
     * @param notification notification to update. Must exist on server. Must have id field set.
     */
    @WorkerThread
    fun updateNotification(notification: NotificationRequest): Notification {
        val reqBody = toWrappedJson(notification).toRequestBody(MIME_JSON_API)
        val req = Request.Builder().url("$NOTIFICATIONS_ENDPOINT/${notification.id}").patch(reqBody).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't update notification ${notification.id}")

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), Notification::class.java)!!
    }


    /**
     * Mark notifications for denoted [entry] as read for currently logged in profile.
     * Doesn't do anything if user is not logged in.
     *
     * @param entry entry that should be marked as read in notifications
     * @return true if any notifications were updated, false otherwise
     */
    @WorkerThread
    fun markNotificationsReadFor(entry: Entry): Boolean {
        if (Auth.profile == null) {
            // not logged in
            return false
        }

        val builder = "$PROFILES_ENDPOINT/${Auth.profile?.id}/relationships/notifications".toHttpUrl().newBuilder()
        builder.addQueryParameter("filters[entry_id]", entry.id)
                .addQueryParameter("filters[state]", "new")
                .addQueryParameter("include", "comments,profiles")

        val req = Request.Builder().url(builder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load notifications for profile ${Auth.profile?.nickname}")
        }

        val unreadNotifications = fromWrappedListJson(resp.body!!.source(), Notification::class.java)
        for (notif in unreadNotifications) {
            val update = NotificationRequest().apply {
                id = notif.id
                state = "read"
            }
            updateNotification(update)
        }

        return unreadNotifications.size > 0
    }

    /**
     * Marks all notifications read for currently logged in profile.
     * Doesn't do anything if user is not logged in.
     */
    @WorkerThread
    fun markAllNotificationsRead() {
        if (Auth.profile == null) {
            // not logged in
            return
        }

        val reqBody = "".toRequestBody(MIME_JSON_API)
        val req = Request.Builder().url("$PROFILES_ENDPOINT/${Auth.profile?.id}/relationships/notifications/read-all")
            .post(reqBody).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't mark notifications read")
    }

    /**
     * Can probably be made to work, but this registration workflow was not tested yet.
     */
    @WorkerThread
    fun confirmRegistration(emailToConfirm: String, tokenFromMail: String): LoginResponse {
        val confirmation = ConfirmRequest().apply {
            action = "confirm"
            email = emailToConfirm
            confirmToken = tokenFromMail
        }

        val body = toWrappedJson(confirmation).toRequestBody(MIME_JSON_API)
        val req = Request.Builder().post(body).url(SESSIONS_ENDPOINT).build()
        val resp = httpClient.newCall(req).execute()

        // unprocessable entity error can be returned when something is wrong with your input
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't confirm registration for email $emailToConfirm")
        }

        // if response is successful we should have login response in body
        return fromWrappedJson(resp.body!!.source(), LoginResponse::class.java)!!
    }


    /**
     * Update current profile's subscription to denoted [entry]
     *
     * @param entry entry to modify status for
     * @param subscribe if true, subscribe, else unsubscribe from denoted entry
     */
    @WorkerThread
    fun updateSubscription(entry: Entry, subscribe: Boolean) {
        val subscription = ResourceIdentifier().apply {
            type = "subscriptions"
            id = entry.id
        }
        val req = Request.Builder()
                .apply {
                    if (subscribe) {
                        this.post(toWrappedListJson(subscription).toRequestBody(MIME_JSON_API))
                    } else {
                        this.patch("""{ "data": [] }""".toRequestBody(MIME_JSON_API))
                    }
                }
                .url("$ENTRIES_ENDPOINT/${entry.id}/relationships/subscriptions")
                .build()

        val resp = httpClient.newCall(req).execute()

        // unprocessable entity error can be returned when something is wrong with your input
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't (un)subscribe to entry ${entry.id}")
        }
    }

    /**
     * Update current profile's bookmark to denoted [entry]
     *
     * @param entry entry to modify status for
     * @param bookmark if true, bookmark, else remove bookmark to denoted entry
     */
    @WorkerThread
    fun updateBookmark(entry: Entry, bookmark: Boolean) {
        val bookmarkReq = CreateBookmarkRequest().apply {
            this.entry = HasOne(entry)
        }

        val req = Request.Builder()
                .apply {
                    if (bookmark) {
                        this.post(toWrappedJson(bookmarkReq).toRequestBody(MIME_JSON_API))
                            .url(BOOKMARKS_ENDPOINT)
                    } else {
                        this.delete()
                            .url("$BOOKMARKS_ENDPOINT/${entry.id}") // FIXME: SPEC VIOLATION
                    }
                }
                .build()

        val resp = httpClient.newCall(req).execute()

        // unprocessable entity error can be returned when something is wrong with your input
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't (un)bookmark to entry ${entry.id}")
        }
    }

    /**
     * Loads bookmarks for current profile
     *
     * @param pageSize number of data entities on one page
     * @param pageNum page number to retrieve
     */
    @WorkerThread
    fun loadBookmarks(pageNum: Int = 1, pageSize: Int = PAGE_SIZE): ArrayDocument<Bookmark> {
        val builder = BOOKMARKS_ENDPOINT.toHttpUrl().newBuilder()
                .addQueryParameter("page[number]", pageNum.toString())
                .addQueryParameter("page[size]", pageSize.toString())
                .addQueryParameter("include", "entries,profiles")
                .addQueryParameter("sort", "-created-at")

        val req = Request.Builder().url(builder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load bookmarks")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), Bookmark::class.java)
    }

    /**
     * Loads reaction sets. Only one reaction set is present atm, it's the first one.
     */
    @WorkerThread
    fun loadReactionSets(): List<ReactionSet> {
        val req = Request.Builder().url("$REACTION_SETS_ENDPOINT?include=reactions").build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load reaction sets")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), ReactionSet::class.java)
    }

    /**
     * Create reaction with type [reactionType] that will be attached to [entry]
     *
     * @param entry Entry for which reaction should be created
     * @param reactionType emoji to attach
     */
    @WorkerThread
    fun createReaction(entry: Entry, reactionType: ReactionType): Reaction {
        val reactionReq = CreateReactionRequest().apply {
            this.reactionType = HasOne(reactionType)
            this.author = HasOne(Auth.profile)
            this.entry = HasOne(entry)
        }

        val req = Request.Builder()
                .post(toWrappedJson(reactionReq).toRequestBody(MIME_JSON_API))
                .url(REACTIONS_ENDPOINT)
                .build()

        val resp = httpClient.newCall(req).execute()

        // unprocessable entity error can be returned when something is wrong with your input
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't (un)bookmark to entry ${entry.id}")
        }

        // we need to add reaction type as an include to this document
        // as type is not returned as response
        return fromWrappedJson(resp.body!!.source(), Reaction::class.java)!!.apply {
            document.addInclude(reactionType)
        }
    }

    /**
     * Create a join request for specified community. Depending on community join type
     * it can be approved automatically.
     *
     * @param communityProf profile of community to join to
     */
    @WorkerThread
    fun communityJoin(communityProf: OwnProfile): CommunityJoinResponse {
        val joinReq = CommunityJoinRequest().apply {
            this.message = "" // TODO: add message input when non-auto-join communities are implemented
            this.profile = HasOne(Auth.profile)
            this.community = HasOne(communityProf)
        }

        val req = Request.Builder()
                .post(toWrappedJson(joinReq).toRequestBody(MIME_JSON_API))
                .url(COMMUNITY_JOIN_REQ_ENDPOINT)
                .build()

        val resp = httpClient.newCall(req).execute()

        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't join community ${communityProf.nickname} with id ${communityProf.id}")
        }

        return fromWrappedJson(resp.body!!.source(), CommunityJoinResponse::class.java)!!
    }

    /**
     * Leave specified community.
     *
     * @param communityProf profile of community to leave from
     */
    @WorkerThread
    fun communityLeave(communityProf: OwnProfile) {
        val leaveReq = ArrayDocument<ResourceIdentifier>().apply { add(ResourceIdentifier(Auth.profile)) }
        val docType = Types.newParameterizedType(Document::class.java, ResourceIdentifier::class.java)
        val req = Request.Builder()
                .url("$PROFILES_ENDPOINT/${communityProf.id}/relationships/community-participants")
                .delete(jsonConverter.adapter<Document>(docType).toJson(leaveReq).toRequestBody(MIME_JSON_API))
                .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't leave community ${communityProf.nickname} with id ${communityProf.id}")
        }
    }

    /**
     * Retrieves list of community participants from given community id.
     *
     * Notes:
     * * Community id is a simple profile id
     * * Community participant class is a greater entity than profile, it encompasses also
     *   permissions, join date and other relevant data.
     * @param communityProfId community profile id
     * @param pageSize number of data entities on one page
     * @param pageNum page number to retrieve
     * @return array document of all community participants (not paged yet) for a given community id
     */
    @WorkerThread
    fun communityParticipants(communityProfId: String, pageNum: Int = 1, pageSize: Int = PAGE_SIZE): ArrayDocument<CommunityParticipant> {
        val builder = "$PROFILES_ENDPOINT/${communityProfId}/community-participants".toHttpUrl().newBuilder()
            .addQueryParameter("page[number]", pageNum.toString())
            .addQueryParameter("page[size]", PAGE_SIZE.toString())
            .addQueryParameter("include", "entries,profiles")
            .addQueryParameter("sort", "-created-at")

        val req = Request.Builder().url(builder.build()).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load community participants")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), CommunityParticipant::class.java)
    }

    /**
     * Deletes reaction [myReaction]. This reaction must exist and must have
     * current profile as its author.
     *
     * @param myReaction reaction to delete
     */
    @WorkerThread
    fun deleteReaction(myReaction: Reaction) {
        val req = Request.Builder().url("$REACTIONS_ENDPOINT/${myReaction.id}").delete().build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't delete reaction ${myReaction.id}")
    }

    @WorkerThread
    fun removeFromActionList(list: ActionList, prof: OwnProfile) {
        val req = Request.Builder()
                .delete()
                .url("$ACTION_LISTS_ENDPOINT/${list.id}/relationships/profiles/${prof.id}")
                .build()

        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't remove profile ${prof.id} from action list ${list.id}")
        }
    }

    @WorkerThread
    fun addToActionList(listItem: ActionListRequest): ActionListResponse {
        return createEntity(ACTION_LISTS_ENDPOINT, listItem)
    }

    @WorkerThread
    fun loadActionLists(): MutableList<ActionList> {
        val req = Request.Builder().url(ACTION_LISTS_ENDPOINT).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't load action lists")
        }

        // response is returned after execute call, body is not null
        return fromWrappedListJson(resp.body!!.source(), ActionList::class.java)
    }

    private inline fun <reified S : ResourceIdentifier, reified R : ResourceIdentifier> createEntity(
        endpoint: String,
        entity: S
    ): R {
        val reqBody = toWrappedJson(entity).toRequestBody(MIME_JSON_API)
        val req = Request.Builder().url(endpoint).post(reqBody).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful)
            throw extractErrors(resp, "Can't create ${entity.type}")

        // response is returned after execute call, body is not null
        return fromWrappedJson(resp.body!!.source(), R::class.java)!!
    }

    /**
     * Uploads image to dybr-dedicated storage and returns link to it.
     *
     * @param image content of image
     * @param filename filename to use
     * @return link to the uploaded image on dybr server
     */
    @WorkerThread
    fun uploadImage(image: ByteArray, filename: String? = null): String {
        val name = filename ?: "unknown"

        val uploadForm = image.toRequestBody("image/*".toMediaType(), 0, image.size)
        val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "android-$name", uploadForm)
                .build()

        val req = Request.Builder().post(body).url(IMG_UPLOAD_ENDPOINT).build()
        val resp = httpClient.newCall(req).execute()

        // unprocessable entity error can be returned when something is wrong with your input
        if (!resp.isSuccessful) {
            throw extractErrors(resp, "Can't upload image")
        }

        // if response is successful we should have login response in body
        val respJson = JSONObject(resp.body!!.string())
        return respJson.get("link") as String
    }

    /**
     * Helper function to avoid long `try { ... } catch(...) { report }` blocks in code.
     *
     * @param networkAction action to be performed in background thread
     * @param uiAction action to be performed after [networkAction], in UI thread
     */
    suspend fun <T> perform(
            networkAction: () -> T,
            uiAction: (input: T) -> Unit = {},
            errorAction: () -> Unit = {}) {
        try {
            val result = withContext(Dispatchers.IO) { networkAction() }
            uiAction(result)
        } catch (ex: Exception) {
            errorAction()
            reportErrors(appCtx, ex)
        }
    }

    /**
     * Tries to deduce errors from non-successful response.
     * @param resp http response that was not successful
     * @return [HttpApiException] if specific errors were found or generic [HttpException] for this response
     */
    private fun extractErrors(resp: Response, message: String) : HttpException {
        if (resp.code in HTTP_BAD_REQUEST until HTTP_INTERNAL_ERROR) { // 400-499: client error
            if (resp.body?.contentType() != MIME_JSON_API) {
                return HttpException(resp, message)
            }

            // try to get error info
            val body = resp.body!!.source()
            if (body.exhausted()) // no content
                return HttpException(resp, message)

            val errAdapter = jsonConverter.adapter(Document::class.java)
            val errDoc = errAdapter.fromJson(body)!!
            if (errDoc.errors.isNotEmpty()) // likely
                return HttpApiException(resp, errDoc.errors)
        }
        return HttpException(resp, message)
    }

    /**
     * Handle typical network-related problems.
     * * Handles API-level problems - answers with errors from API
     * * Handles HTTP-level problems - answers with strings from [errMapping]
     * * Handles connection IO-level problems
     * @param ctx context to get error string from
     * @param errMapping mapping of ids like `HttpUrlConnection.HTTP_NOT_FOUND -> R.string.not_found`
     */
    fun reportErrors(ctx: Context?,
                     ex: Exception,
                     errMapping: Map<Int, Int> = emptyMap(),
                     detailMapping: Map<String, String> = emptyMap()) {
        if (ctx == null) {
            // user left the app/closed the fragment
            return
        }

        when (ex) {
            // API exchange error, most specific
            is HttpApiException -> for (error in ex.errors) {
                Toast.makeText(ctx, detailMapping[error.detail]
                        ?: error.detail, Toast.LENGTH_LONG).show()
            }

            // non-200 code, but couldn't deduce any API errors
            is HttpException -> {
                if (errMapping.containsKey(ex.code)) {
                    // we have, take value from map
                    val key = ctx.getString(errMapping.getValue(ex.code))
                    Toast.makeText(ctx, key, Toast.LENGTH_SHORT).show()
                } else {
                    // we haven't, show generic error
                    val errorText = ctx.getString(R.string.unexpected_website_error)
                    Toast.makeText(ctx, "$errorText: ${ex.message}", Toast.LENGTH_SHORT).show()
                }
            }

            // generic connection-level error, show as-is
            is IOException -> {
                Log.w("Fair/Network", "Connection error", ex)
                val errorText = ctx.getString(R.string.error_connecting)
                Toast.makeText(ctx, "$errorText: ${ex.message}", Toast.LENGTH_SHORT).show()
            }

            else -> throw ex
        }
    }
}