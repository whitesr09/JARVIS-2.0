package com.nshd.jarvis2.core

/**
 * v1.9.0 (#81): usage meter — requests + rough token estimate per
 * provider, today and this week, computed from the AppLog "API"
 * lines (every provider call already logs
 * "<providerId> model=<m> POST /chat/completions → <code> in <n>ms").
 *
 * Token estimate is deliberately rough and labelled as such: chat
 * traffic averages ~4 chars/token; a completed request is estimated
 * at (prompt+reply) ≈ 1,200 tokens unless the log line carries an
 * explicit count. The point is spotting WHICH provider eats the
 * quota, not billing accuracy.
 */
object UsageMeter {

    data class ProviderUsage(
        val providerId: String,
        val requestsToday: Int,
        val requestsWeek: Int,
        val estTokensToday: Int,
        val estTokensWeek: Int
    )

    private val REQUEST_LINE = Regex(
        "^(\\S+) model=\\S+ POST .*→ \\d{3} in \\d+ms"
    )
    private const val EST_TOKENS_PER_REQUEST = 1_200
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun summarize(entries: List<AppLog.Entry>): List<ProviderUsage> {
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now)
        val weekStart = todayStart - 6 * DAY_MS
        val byProvider = linkedMapOf<String, IntArray>() // [today, week]
        entries.filter { it.tag == "API" }.forEach { entry ->
            val match = REQUEST_LINE.find(entry.message) ?: return@forEach
            val providerId = match.groupValues[1]
            val counts = byProvider.getOrPut(providerId) { intArrayOf(0, 0) }
            if (entry.timeMs >= weekStart) counts[1]++
            if (entry.timeMs >= todayStart) counts[0]++
        }
        return byProvider.map { (id, counts) ->
            ProviderUsage(
                providerId = id,
                requestsToday = counts[0],
                requestsWeek = counts[1],
                estTokensToday = counts[0] * EST_TOKENS_PER_REQUEST,
                estTokensWeek = counts[1] * EST_TOKENS_PER_REQUEST
            )
        }.sortedByDescending { it.requestsWeek }
    }

    private fun startOfDay(nowMs: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = nowMs
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
