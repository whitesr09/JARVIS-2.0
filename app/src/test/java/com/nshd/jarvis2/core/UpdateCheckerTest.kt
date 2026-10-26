package com.nshd.jarvis2.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private fun run(
        sha: String,
        created: String,
        conclusion: String = "success",
        name: String = "Build JARVIS-2.0 APK"
    ) = JSONObject()
        .put("head_sha", sha)
        .put("created_at", created)
        .put("conclusion", conclusion)
        .put("name", name)

    private fun response(vararg runs: JSONObject): String =
        JSONObject().put("workflow_runs", JSONArray().apply {
            runs.forEach { put(it) }
        }).toString()

    @Test
    fun choosesNewestSuccessfulExpectedWorkflow() {
        val json = response(
            run("older", "2026-10-01T10:00:00Z"),
            run("failed-newer", "2026-10-25T10:00:00Z", conclusion = "failure"),
            run("other-workflow", "2026-10-26T10:00:00Z", name = "Other workflow"),
            run("latest", "2026-10-26T09:00:00Z")
        )

        assertEquals("latest", UpdateChecker.newestSuccessfulSha(json))
    }

    @Test
    fun ignoresMissingShaAndReturnsNullWhenNoSuccessfulBuildExists() {
        assertNull(
            UpdateChecker.newestSuccessfulSha(
                response(run("", "2026-10-26T09:00:00Z"))
            )
        )
        assertNull(
            UpdateChecker.newestSuccessfulSha(
                response(run("failed", "2026-10-26T09:00:00Z", conclusion = "failure"))
            )
        )
        assertNull(UpdateChecker.newestSuccessfulSha(JSONObject().put("workflow_runs", JSONArray()).toString()))
    }
}
