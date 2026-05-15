package com.zalopilot.app.accessibility.engine

import org.json.JSONArray
import org.json.JSONObject

data class ZPScript(
    val id: String,
    val version: Int,
    val steps: List<ZPStep>
)

data class ZPStep(
    val id: String?,
    val action: String,
    val screen: String? = null,
    val timeoutMs: Long? = null,
    val count: String? = null,
    val indexVar: String? = null,
    val key: String? = null,
    val gt: Int? = null,
    val lt: Int? = null,
    val eq: Int? = null,
    val gte: Int? = null,
    val lte: Int? = null,
    val ms: Long? = null,
    val varName: String? = null,
    val step: String? = null,
    val doSteps: List<ZPStep> = emptyList()
)

object ZPScriptParser {
    fun parse(json: JSONObject): ZPScript {
        val stepsJson = json.optJSONArray("steps") ?: JSONArray()
        return ZPScript(
            id = json.optString("id", "unknown"),
            version = json.optInt("version", 1),
            steps = parseSteps(stepsJson)
        )
    }

    private fun parseSteps(arr: JSONArray): List<ZPStep> {
        val out = ArrayList<ZPStep>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(parseStep(o))
        }
        return out
    }

    private fun parseStep(o: JSONObject): ZPStep {
        val doArr = o.optJSONArray("do")
        return ZPStep(
            id = o.optString("id", null).takeIf { it.isNotBlank() },
            action = o.optString("action", ""),
            screen = o.optString("screen", null).takeIf { it.isNotBlank() },
            timeoutMs = if (o.has("timeoutMs")) o.optLong("timeoutMs") else null,
            count = o.optString("count", null).takeIf { it.isNotBlank() },
            indexVar = o.optString("indexVar", null).takeIf { it.isNotBlank() },
            key = o.optString("key", null).takeIf { it.isNotBlank() },
            gt = if (o.has("gt")) o.optInt("gt") else null,
            lt = if (o.has("lt")) o.optInt("lt") else null,
            eq = if (o.has("eq")) o.optInt("eq") else null,
            gte = if (o.has("gte")) o.optInt("gte") else null,
            lte = if (o.has("lte")) o.optInt("lte") else null,
            ms = if (o.has("ms")) o.optLong("ms") else null,
            varName = o.optString("var", null).takeIf { it.isNotBlank() },
            step = o.optString("step", null).takeIf { it.isNotBlank() },
            doSteps = if (doArr != null) parseSteps(doArr) else emptyList()
        )
    }
}
