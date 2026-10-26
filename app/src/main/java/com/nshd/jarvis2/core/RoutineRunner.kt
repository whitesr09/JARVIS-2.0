package com.nshd.jarvis2.core

import android.content.Context
import com.nshd.jarvis2.brain.AgentPipeline
import com.nshd.jarvis2.brain.ProviderStore
import com.nshd.jarvis2.memory.MemoryDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v2.0.0 (#115): routine execution + triggers.
 *
 * A routine is a named list of command texts. Running one feeds each
 * step through the SAME pipeline entry the queue uses (quick commands
 * first, then the agent loop), so every step keeps its normal safety
 * behaviour — a step that sends a message still stops at the
 * always-confirm gate with recipient + text.
 *
 * Triggers: MANUAL (chat/voice command or the Settings Run button),
 * ON_WAKE (after a wake-word wake, at most once per day) and
 * ON_APP_OPEN (MainActivity composition, at most once per day).
 * Headless runs park their summary in [BrainStore] — the Chat screen
 * surfaces it once as an assistant message — and log every step.
 * There is deliberately NO new scheduler: wake/app-open are the
 * routine moments this app already owns.
 */
object RoutineRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Fire-and-forget run for wake / app-open / Settings triggers. */
    fun runAsync(context: Context, routine: BrainStore.Routine, surface: String) {
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val summary = AgentPipeline.runRoutineSteps(
                    context = appContext,
                    routine = routine,
                    settings = SettingsLoader.load(appContext),
                    memoryDb = MemoryDb(appContext),
                    providerStore = ProviderStore(appContext),
                    onEvent = {}
                )
                BrainStore.markRoutineRan(
                    appContext, routine.name, BrainStore.todayKey()
                )
                BrainStore.setPendingSummary(appContext, summary)
                AppLog.i("Brain", "routine finished on $surface")
            }.onFailure { e ->
                AppLog.e(
                    "Brain",
                    "routine failed on $surface (${e.javaClass.simpleName})"
                )
            }
        }
    }

    /** Wake-word trigger: ON_WAKE routines that haven't run today. */
    fun maybeRunOnWake(context: Context) {
        val today = BrainStore.todayKey()
        BrainStore.routines(context)
            .filter { it.trigger == "ON_WAKE" && it.lastRunDay != today }
            .forEach { runAsync(context, it, "wake") }
    }

    /** App-open trigger: ON_APP_OPEN routines that haven't run today. */
    fun maybeRunOnAppOpen(context: Context) {
        val today = BrainStore.todayKey()
        BrainStore.routines(context)
            .filter { it.trigger == "ON_APP_OPEN" && it.lastRunDay != today }
            .forEach { runAsync(context, it, "app-open") }
    }
}
