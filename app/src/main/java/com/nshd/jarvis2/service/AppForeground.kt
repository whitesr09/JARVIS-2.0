package com.nshd.jarvis2.service

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-process "is the Jarvis app itself in the foreground" tracker (F2).
 *
 * The floating overlay bubble must not float over Jarvis's own screens,
 * so [FloatingOverlayService] observes this and hides its window while
 * any app activity is started. Registered once from MainActivity via
 * [register]; activity counting keeps multi-activity flows (permission
 * dialogs are not activities, but pickers are) from flickering the flag.
 */
object AppForeground {

    private val foreground = AtomicBoolean(false)
    private val startedActivities = AtomicInteger(0)
    private val registered = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun isForeground(): Boolean = foreground.get()

    /** Idempotent — safe to call from every MainActivity.onCreate. */
    fun register(application: Application) {
        if (!registered.compareAndSet(false, true)) return
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    private fun setForeground(value: Boolean) {
        if (foreground.compareAndSet(!value, value)) {
            listeners.forEach { listener ->
                runCatching { listener(value) }
            }
        }
    }

    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            if (startedActivities.incrementAndGet() == 1) setForeground(true)
        }

        override fun onActivityStopped(activity: Activity) {
            if (startedActivities.decrementAndGet() <= 0) {
                startedActivities.set(0)
                setForeground(false)
            }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
