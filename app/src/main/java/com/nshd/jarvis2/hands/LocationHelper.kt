package com.nshd.jarvis2.hands

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.nshd.jarvis2.core.AppLog
import java.util.Locale

/**
 * v2.0.0 (#114): "where am I" + share-my-location, built on the
 * platform LocationManager's last-known fixes (no Fused Location —
 * no Play Services dependency in this app) and the on-device
 * Geocoder for a human place name. ACCESS_FINE_LOCATION is already
 * declared; the runtime grant is checked on every call.
 */
object LocationHelper {

    data class Fix(
        val latitude: Double,
        val longitude: Double,
        /** "Kochi, Kerala" style label, "" when geocoding fails. */
        val placeLabel: String
    )

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

    /** Best last-known fix (freshest of GPS / network), or null. */
    fun lastKnownFix(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return null
        val candidates = mutableListOf<Location>()
        for (provider in listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER
        )) {
            runCatching { manager.getLastKnownLocation(provider) }
                .getOrNull()?.let { candidates += it }
        }
        val best = candidates.maxByOrNull { it.time } ?: run {
            AppLog.w("Hands", "location: no last-known fix available")
            return null
        }
        return Fix(
            latitude = best.latitude,
            longitude = best.longitude,
            placeLabel = geocode(context, best.latitude, best.longitude)
        )
    }

    /** Google Maps link for a fix (used by share-my-location). */
    fun mapsLink(fix: Fix): String =
        "https://maps.google.com/?q=${fix.latitude},${fix.longitude}"

    /** One spoken-style sentence: "You're near Kochi, Kerala (9.93, 76.27)." */
    fun describe(fix: Fix): String {
        val coords = "(%.4f, %.4f)".format(
            Locale.US, fix.latitude, fix.longitude
        )
        return if (fix.placeLabel.isNotBlank()) {
            "You're near ${fix.placeLabel} $coords."
        } else {
            "Your last known location is $coords."
        }
    }

    private fun geocode(context: Context, lat: Double, lng: Double): String {
        return try {
            val results = Geocoder(context, Locale.getDefault())
                .getFromLocation(lat, lng, 1)
            val address = results?.firstOrNull() ?: return ""
            val locality = address.locality ?: address.subAdminArea ?: ""
            val region = address.adminArea ?: ""
            listOf(locality, region).filter { it.isNotBlank() }
                .distinct().joinToString(", ")
        } catch (e: Exception) {
            ""
        }
    }
}
