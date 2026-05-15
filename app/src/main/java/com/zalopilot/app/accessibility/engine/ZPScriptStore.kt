package com.zalopilot.app.accessibility.engine

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ZPScriptMeta(
    val id: String,
    val version: Int,
    val file: String,
    val desc: String
)

@Singleton
class ZPScriptStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("zp_scripts", Context.MODE_PRIVATE)
    private val scriptsDir: File
        get() = File(context.filesDir, "scripts").also { it.mkdirs() }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun getServerBaseUrl(): String {
        val custom = prefs.getString(KEY_SERVER_URL, null)?.trim().orEmpty()
        if (custom.isNotEmpty()) {
            return if (custom.endsWith("/")) custom else "$custom/"
        }
        return DEFAULT_SERVER_BASE
    }

    fun setServerBaseUrl(url: String) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    fun resetServerBaseUrl() {
        prefs.edit().remove(KEY_SERVER_URL).apply()
    }

    fun getActiveScriptId(): String =
        prefs.getString(KEY_ACTIVE_SCRIPT_ID, DEFAULT_SCRIPT_ID) ?: DEFAULT_SCRIPT_ID

    fun setActiveScriptId(id: String) {
        prefs.edit().putString(KEY_ACTIVE_SCRIPT_ID, id).apply()
    }

    fun listLocal(): List<ZPScriptMeta> {
        val metas = mutableListOf<ZPScriptMeta>()
        scriptsDir.listFiles()?.filter { it.extension == "json" }?.forEach { f ->
            runCatching {
                val json = JSONObject(f.readText())
                metas.add(
                    ZPScriptMeta(
                        id = json.optString("id", f.nameWithoutExtension),
                        version = json.optInt("version", 1),
                        file = f.name,
                        desc = json.optString("desc", f.name)
                    )
                )
            }
        }
        return metas.sortedBy { it.id }
    }

    fun load(id: String): JSONObject? {
        val bundled = loadFromAssets(id)
        val local = loadNewestLocal(id)
        if (local == null) return bundled
        if (bundled == null) return local
        val localVer = local.optInt("version", 0)
        val bundledVer = bundled.optInt("version", 0)
        return if (localVer >= bundledVer) local else bundled
    }

    private fun loadNewestLocal(id: String): JSONObject? {
        val candidates = scriptsDir.listFiles()?.filter { f ->
            f.extension == "json" && (
                f.name == localFileName(id) ||
                    f.name.startsWith("${id}_v")
                )
        }.orEmpty()
        if (candidates.isEmpty()) return null
        val best = candidates.maxByOrNull { f ->
            runCatching { JSONObject(f.readText()).optInt("version", 0) }.getOrDefault(0)
        } ?: return null
        return runCatching { JSONObject(best.readText()) }.getOrNull()
    }

    fun loadActiveScript(): JSONObject? = load(getActiveScriptId())

    fun save(id: String, version: Int, json: JSONObject) {
        val file = File(scriptsDir, "${id}_v${version}.json")
        file.writeText(json.toString(2))
        prefs.edit().putInt(versionKey(id), version).apply()
    }

    fun delete(id: String) {
        scriptsDir.listFiles()?.filter {
            it.name.startsWith(id)
        }?.forEach { it.delete() }
        prefs.edit().remove(versionKey(id)).apply()
    }

    fun getLocalVersion(id: String): Int = prefs.getInt(versionKey(id), 0)

    fun clearCache() {
        scriptsDir.listFiles()?.forEach { it.delete() }
        listLocal().forEach { meta ->
            prefs.edit().remove(versionKey(meta.id)).apply()
        }
    }

    suspend fun fetchIndex(): JSONObject? = withContext(Dispatchers.IO) {
        val url = getServerBaseUrl() + "index.json"
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                JSONObject(body)
            }
        }.getOrNull()
    }

    suspend fun downloadScript(meta: ZPScriptMeta): JSONObject? = withContext(Dispatchers.IO) {
        val url = getServerBaseUrl() + meta.file
        runCatching {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val json = JSONObject(body)
                save(meta.id, meta.version, json)
                json
            }
        }.getOrNull()
    }

    fun parseIndexScripts(index: JSONObject): List<ZPScriptMeta> {
        val arr = index.optJSONArray("scripts") ?: return emptyList()
        val out = ArrayList<ZPScriptMeta>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                ZPScriptMeta(
                    id = o.optString("id"),
                    version = o.optInt("version", 1),
                    file = o.optString("file"),
                    desc = o.optString("desc", o.optString("id"))
                )
            )
        }
        return out
    }

    private fun loadFromAssets(id: String): JSONObject? {
        val path = "scripts/$id.json"
        return runCatching {
            context.assets.open(path).bufferedReader().use { reader ->
                JSONObject(reader.readText())
            }
        }.getOrNull()
    }

    private fun localFileName(id: String): String = "${id}.json"

    private fun versionKey(id: String) = "script_version_$id"

    companion object {
        const val DEFAULT_SERVER_BASE = "https://sungnhon.xyz/ZaloPilot/scripts/"
        const val DEFAULT_SCRIPT_ID = "visit_contacts_v1"
        private const val KEY_SERVER_URL = "script_server_url"
        private const val KEY_ACTIVE_SCRIPT_ID = "active_script_id"
    }
}
