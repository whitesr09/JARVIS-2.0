package com.nshd.jarvis2.ui

/**
 * Plan #94: process-wide mirror of the user's reduce-motion setting.
 * JarvisApp keeps this in sync with JarvisSettings.reduceMotion so
 * deep UI (orb, overlay-driven composables) can read it without
 * threading the settings object through every call site.
 */
object MotionSettings {
    @Volatile
    var reduceMotion: Boolean = false
}
