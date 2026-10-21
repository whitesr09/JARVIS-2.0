package com.nshd.jarvis2.service

import android.content.Context
import android.os.PowerManager
import android.provider.Settings

/**
 * Readable accessibility / battery status for the Digital Hands features.
 *
 * Vivo/iQOO (and Android 13+ restricted-settings rules) can silently
 * switch a sideloaded app's accessibility service OFF. The app cannot
 * prevent that, so these checks power the in-app warnings and the
 * Settings → Digital Hands keep-alive guide instead: detect it fast,
 * route the user to the exact fix.
 */
object A11yStatus {

    /**
     * True when the Jarvis accessibility service is enabled — either the
     * service process is live, or the secure setting lists it (the
     * service may just not have connected yet).
     */
    fun isEnabled(context: Context): Boolean {
        if (JarvisAccessibilityService.isEnabled()) return true
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val expected = "${context.packageName}/${JarvisAccessibilityService::class.java.name}"
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** True when the app is battery-whitelisted (runs unrestricted). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
