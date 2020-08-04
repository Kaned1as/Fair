package com.kanedias.dybr.fair.service

import android.text.Spanned
import android.util.LruCache

/**
 * Service for caching spanned strings, as parsing them to markdown can take significant amount of time
 *
 * @author Kanedias
 *
 * Created on 2020-01-11
 */
object SpanCache {

    private val spanCache = LruCache<Int, Spanned>(3000)

    fun removeMessageId(hash: Int) {
        spanCache.remove(hash)
    }

    fun forMessageId(hash: Int): Spanned? {
        return spanCache[hash]
    }

    fun putId(hash: Int, md: Spanned) {
        synchronized(spanCache) {
            spanCache.put(hash, md)
        }
    }

    fun forMessageId(hash: Int, slowConverter: () -> Spanned): Spanned? {
        val cached = spanCache[hash]
        if (cached != null) {
            return cached
        }

        synchronized(spanCache) {
            val markdown = slowConverter.invoke()
            spanCache.put(hash, markdown)

            return markdown
        }
    }
}