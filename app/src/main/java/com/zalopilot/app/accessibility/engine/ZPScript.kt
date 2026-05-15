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
            id = optNonBlank(o, "id"),
            action = o.optString("action", ""),
            screen = optNonBlank(o, "screen"),
            timeoutMs = optLongField(o, "timeoutMs"),
            count = optNonBlank(o, "count"),
            indexVar = optNonBlank(o, "indexVar"),
            key = optNonBlank(o, "key"),
            gt = optIntField(o, "gt"),
            lt = optIntField(o, "lt"),
            eq = optIntField(o, "eq"),
            gte = optIntField(o, "gte"),
            lte = optIntField(o, "lte"),
            ms = optLongField(o, "ms"),
            varName = optNonBlank(o, "var"),
            step = optNonBlank(o, "step"),
            doSteps = if (doArr != null) parseSteps(doArr) else emptyList()
        )
    }

    /** Android [JSONObject.optString] với fallback `null` → NPE khi key thiếu. */
    private fun optNonBlank(o: JSONObject, key: String): String? =
        o.optString(key, "").takeIf { it.isNotBlank() }

    private fun optIntField(o: JSONObject, key: String): Int? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optInt(key)
    }

    private fun optLongField(o: JSONObject, key: String): Long? {
        if (!o.has(key) || o.isNull(key)) return null
        return when (val raw = o.opt(key)) {
            is Number -> raw.toLong()
            else -> null
        }
    }
}
