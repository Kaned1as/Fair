package com.kanedias.dybr.fair.dto

import com.squareup.moshi.Json
import moe.banana.jsonapi2.JsonApi
import moe.banana.jsonapi2.Policy
import moe.banana.jsonapi2.Resource

/**
 * Tag is an attachable entity that groups entries together. APIs usually support search by tag on all website,
 * in a particular blog or community.
 *
 * Example (result of `/v2/profiles/{id}/tags` call):
 * ```
 * {
 *   "data": [
 *     {
 *       "attributes": {
 *         "entries": 4,
 *         "name": "links"
 *       },
 *       "id": "3124",
 *       "type": "tags"
 *     },
 *     {
 *       "attributes": {
 *         "entries": 2,
 *         "name": "books"
 *       },
 *       "id": "3151",
 *       "type": "tags"
 *     }
 *   ]
 * }
 * ```
 * @author Kanedias
 *
 * Created on 17.11.17
 */
@JsonApi(type = "tags", policy = Policy.DESERIALIZATION_ONLY)
class EntryTagResponse: Resource() {
    /**
     * Number of entries this tag appears in
     */
    @Json(name = "entries")
    var entries: Int = 0

    /**
     * Tag name that is visible on the entry tag list
     */
    @Json(name = "name")
    lateinit var name: String
}

typealias EntryTag = EntryTagResponse