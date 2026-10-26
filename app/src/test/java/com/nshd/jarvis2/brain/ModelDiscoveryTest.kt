package com.nshd.jarvis2.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDiscoveryTest {

    @Test
    fun parseIdsTrimsDeduplicatesSortsAndSkipsOversizedIds() {
        val json = JSONObject().put(
            "data",
            JSONArray()
                .put(JSONObject().put("id", " model-z "))
                .put(JSONObject().put("id", "model-a"))
                .put(JSONObject().put("id", "model-a"))
                .put(JSONObject().put("id", "  "))
                .put(JSONObject().put("id", "x".repeat(257)))
                .put(JSONObject().put("id", 42))
        ).toString()

        val ids = ModelDiscovery.parseIds(json)

        assertEquals(listOf("42", "model-a", "model-z"), ids)
    }

    @Test
    fun parseIdsCapsDiscoveredModelCount() {
        val data = JSONArray()
        repeat(700) { index ->
            data.put(JSONObject().put("id", "provider-model-$index"))
        }

        val ids = ModelDiscovery.parseIds(JSONObject().put("data", data).toString())

        assertEquals(500, ids.size)
        assertTrue(ids.all { it.length <= 256 })
    }

    @Test
    fun parseIdsReturnsEmptyForMalformedOrUnexpectedResponse() {
        assertTrue(ModelDiscovery.parseIds("not-json").isEmpty())
        assertTrue(ModelDiscovery.parseIds(JSONObject().put("models", JSONArray()).toString()).isEmpty())
        assertTrue(ModelDiscovery.parseIds(JSONObject().put("data", JSONArray()).toString()).isEmpty())
    }
}
