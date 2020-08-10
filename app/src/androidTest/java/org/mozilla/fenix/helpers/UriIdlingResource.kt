package org.mozilla.fenix.helpers

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.test.espresso.IdlingResource
import androidx.test.espresso.IdlingResource.ResourceCallback
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Lice`nse is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



/**
 * An implementation of [IdlingResource] useful for monitoring idleness of network traffic.
 *
 *
 * This is similar to [androidx.test.espresso.contrib.CountingIdlingResource], with
 * the additional idleness constraint that the counter must be 0 for a set period of time before the
 * resource becomes idle.
 *
 *
 * A network timeout is required to be reasonably sure that the webview has finished loading.
 * Imagine the case where each response that comes back causes another request to be made until
 * loading is complete. The counter will go from 0->1->0->1->0->1..., but we don't want to report
 * the webview as idle each time this happens.
 *
 *
 * **This API is currently in beta.**
 */
class UriIdlingResource @VisibleForTesting internal constructor(
    resourceName: String,
    timeoutMs: Long,
    debug: Boolean,
    handler: HandlerIntf
) :
    IdlingResource {
    private val resourceName: String
    private val timeoutMs: Long
    private val debug: Boolean

    // Read and modified from multiple threads
    private val counter =
        AtomicInteger(0)
    private val ignoredRegexes =
        CopyOnWriteArrayList<Pattern>()
    private val idle =
        AtomicBoolean(true)
    private val transitionToIdle: Runnable

    @Volatile
    private var resourceCallback: ResourceCallback? = null
    private val handler: HandlerIntf

    constructor(resourceName: String, timeoutMs: Long) : this(
        resourceName,
        timeoutMs,
        false,
        DefaultHandler(Handler(Looper.getMainLooper()))
    ) {
    }

    override fun getName(): String {
        return resourceName
    }

    override fun isIdleNow(): Boolean {
        return idle.get()
    }

    override fun registerIdleTransitionCallback(resourceCallback: ResourceCallback) {
        this.resourceCallback = resourceCallback
    }

    /**
     * Add a regex pattern to the ignore list.
     *
     *
     * All request URIs are checked against all patterns, and matches are ignored for the purposes
     * of detecting when the webview is idle.
     *
     *
     * Ignored patterns can only be added when the webview is idle.
     */
    fun ignoreUri(pattern: Pattern) {
        if (!isIdleNow) {
            Log.e(
                TAG,
                "Ignored patterns can only be added when the resource is idle."
            )
        } else {
            ignoredRegexes.add(pattern)
        }
    }

    /**
     * Called when a request is made.
     *
     *
     * If the URI is not blacklisted the idle counter is incremented.
     */
    fun beginLoad(uri: String) {
        if (uriIsIgnored(uri)) {
            return
        }
        idle.set(false)
        val count = counter.getAndIncrement().toLong()
        if (count == 0L) {
            handler.removeCallbacks(transitionToIdle)
        }
        if (debug) {
            Log.i(
                TAG,
                "Resource " + resourceName + " counter increased to " + (count + 1)
            )
        }
    }

    /**
     * Called when a request is completed (ie the response is returned).
     *
     *
     * If the URI is not blacklisted the idle counter is decremented. Once the idle counter reaches
     * 0, the idle update thread will set the resource as idle after the appropriate timeout.
     */
    fun endLoad(uri: String) {
        if (uriIsIgnored(uri)) {
            return
        }
        val count = counter.decrementAndGet()
        check(count >= 0) { "Counter has been corrupted! Count=$count" }
        if (count == 0) {
            handler.postDelayed(transitionToIdle, timeoutMs)
        }
        if (debug) {
            Log.i(
                TAG,
                "Resource $resourceName counter decreased to $count"
            )
        }
    }

    private fun uriIsIgnored(uri: String): Boolean {
        for (pattern in ignoredRegexes) {
            if (pattern.matcher(uri).matches()) {
                Log.i(
                    TAG,
                    "Resource $resourceName ignored URI: <$uri>"
                )
                return true
            }
        }
        return false
    }

    @VisibleForTesting
    fun forceIdleTransition() {
        transitionToIdle.run()
    }

    /**
     * Wraps a Handler object.
     *
     *
     * Mock this for testing purposes.
     */
    interface HandlerIntf {
        fun postDelayed(runnable: Runnable?, millis: Long)
        fun removeCallbacks(runnable: Runnable?)
    }

    private class DefaultHandler(private val handler: Handler) :
        HandlerIntf {
        override fun postDelayed(
            runnable: Runnable?,
            millis: Long
        ) {
            handler.postDelayed(runnable!!, millis)
        }

        override fun removeCallbacks(runnable: Runnable?) {
            handler.removeCallbacks(runnable!!)
        }

    }

    companion object {
        private const val TAG = "UriIdlingResource"
    }

    init {
        require(timeoutMs > 0) { "timeoutMs has to be greater than 0" }
        this.resourceName = resourceName
        this.timeoutMs = timeoutMs
        this.debug = debug
        this.handler = handler
        transitionToIdle = Runnable {
            idle.set(true)
            if (resourceCallback != null) {
                resourceCallback!!.onTransitionToIdle()
            }
        }
    }
}