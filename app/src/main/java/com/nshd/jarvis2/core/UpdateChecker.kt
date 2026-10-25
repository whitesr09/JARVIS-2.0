package com.nshd.jarvis2.core

import android.content.Context
import com.nshd.jarvis2.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks whether main has a newer successful APK build than this installed
 * source revision. A build timestamp is not a reliable installed-version
 * identifier, so comparison uses the source commit SHA embedded by CI.
 */
object UpdateChecker {

    const val ACTIONS_URL =
        "https://github.com/whitesr09/JARVIS-2.0/actions"
    private const val RUNS_API =
        "https://api.github.com/repos/whitesr09/JARVIS-2.0/actions/runs" +
            "?branch=main&status=success&per_page=10"

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _statusLine = MutableStateFlow("Not checked yet")
    val statusLine: StateFlow<String> = _statusLine.asStateFlow()

    @Volatile
    private var checkedThisProcess = false
    private var checkInProgress = false
    private val checkLock = Any()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Blocking network call — invoke from Dispatchers.IO only. */
    fun check(context: Context) {
        synchronized(checkLock) {
            if (checkedThisProcess || checkInProgress) return
            checkInProgress = true
        }

        var completed = false
        try {
            val installedSha = BuildConfig.GIT_COMMIT_SHA
            if (installedSha.isBlank() || installedSha == "unknown") {
                _updateAvailable.value = false
                _statusLine.value =
                    "This APK has no build revision metadata; update status cannot be verified."
                return
            }

            val request = Request.Builder()
                .url(RUNS_API)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    _statusLine.value =
                        "Couldn't check GitHub (HTTP ${response.code}); try again later."
                    return
                }

                val runs = JSONObject(response.body?.string().orEmpty())
                    .optJSONArray("workflow_runs")
                var newestSha: String? = null
                var newestCreated = ""

                if (runs != null) {
                    for (i in 0 until runs.length()) {
                        val run = runs.optJSONObject(i) ?: continue
                        if (run.optString("name") != "Build JARVIS-2.0 APK") continue
                        if (run.optString("conclusion") != "success") continue
                        val sha = run.optString("head_sha").takeIf {
                            it.isNotBlank() && it != "null"
                        } ?: continue
                        val created = run.optString("created_at")
                        if (newestSha == null || created > newestCreated) {
                            newestSha = sha
                            newestCreated = created
                        }
                    }
                }

                if (newestSha == null) {
                    _updateAvailable.value = false
                    _statusLine.value = "No successful main-branch APK build could be identified."
                    completed = true
                    return
                }

                val installed = installedSha.trim()
                val latest = newestSha.trim()
                _updateAvailable.value = installed != latest
                _statusLine.value = if (installed == latest) {
                    "This APK matches the latest successful main-branch build."
                } else {
                    "A different successful main-branch build is available. Open Actions to inspect its artifact."
                }
                AppLog.i(
                    "App",
                    "Update check: installed revision=${installed.take(12)}, latest revision=${latest.take(12)}, available=${_updateAvailable.value}"
                )
                completed = true
            }
        } catch (e: Exception) {
            _statusLine.value = "Update check failed — you're offline or GitHub is unreachable."
            AppLog.w("App", "Update check failed: ${e.javaClass.simpleName}")
        } finally {
            synchronized(checkLock) {
                checkInProgress = false
                // A transient failure must not prevent a later retry.
                if (completed) checkedThisProcess = true
            }
        }
    }
}
