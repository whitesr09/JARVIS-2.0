package com.nshd.jarvis2.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Plan #104: update checker. Jarvis ships through GitHub Actions
 * artifacts (no Play Store, no GitHub Releases), so the checker asks
 * the repo's Actions API for the newest SUCCESSFUL "Build JARVIS-2.0
 * APK" run on main and compares its creation time with this build's:
 * if a green build finished after the installed version was released
 * (approximated by the app's lastUpdateTime), an update is available
 * and the Updates tab shows a badge. The link target is the Actions
 * page itself, where the APK artifact is downloaded.
 */
object UpdateChecker {

    const val ACTIONS_URL =
        "https://github.com/whitesr09/JARVIS-2.0/actions"
    private const val RUNS_API =
        "https://api.github.com/repos/whitesr09/JARVIS-2.0/actions/runs" +
            "?branch=main&status=success&per_page=5"

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _statusLine = MutableStateFlow("Not checked yet")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    @Volatile
    private var checkedThisProcess = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Blocking network call — invoke from Dispatchers.IO only. */
    fun check(context: Context) {
        if (checkedThisProcess) return
        checkedThisProcess = true
        val appContext = context.applicationContext
        runCatching {
            val request = Request.Builder()
                .url(RUNS_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _statusLine.value =
                        "Couldn't reach GitHub (HTTP ${response.code}) — check your connection."
                    return
                }
                val body = response.body?.string().orEmpty()
                val runs = JSONObject(body).optJSONArray("workflow_runs")
                var newestBuildMs = 0L
                if (runs != null) {
                    for (i in 0 until runs.length()) {
                        val run = runs.optJSONObject(i) ?: continue
                        if (run.optString("name") != "Build JARVIS-2.0 APK") continue
                        val created = run.optString("created_at")
                        val parsed = parseIsoMs(created)
                        if (parsed > newestBuildMs) newestBuildMs = parsed
                    }
                }
                if (newestBuildMs == 0L) {
                    _statusLine.value = "No successful builds found on GitHub yet."
                    return
                }
                val info = appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
                val installedAt = info.lastUpdateTime
                if (newestBuildMs > installedAt) {
                    _updateAvailable.value = true
                    _statusLine.value =
                        "A newer build finished on GitHub after this install — " +
                            "open Actions to download the latest APK."
                } else {
                    _statusLine.value = "You're on the latest build."
                }
                AppLog.i(
                    "App",
                    "Update check: newest green build=$newestBuildMs installed=$installedAt " +
                        "available=${_updateAvailable.value}"
                )
            }
        }.onFailure {
            _statusLine.value = "Update check failed — you're offline or GitHub is unreachable."
            AppLog.w("App", "Update check failed: ${it.message}")
        }
    }

    /** Minimal ISO-8601 (yyyy-MM-ddTHH:mm:ssZ) parser — UTC only. */
    private fun parseIsoMs(value: String): Long = runCatching {
        val format = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US
        )
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        format.parse(value)?.time ?: 0L
    }.getOrDefault(0L)
}
