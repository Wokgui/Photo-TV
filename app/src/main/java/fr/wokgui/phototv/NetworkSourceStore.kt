package fr.wokgui.phototv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedNetworkSource(
    val id: String,
    val kind: NetworkLibrary.Kind,
    val baseUrl: String,
    val username: String,
    val label: String
)

object NetworkSourceStore {
    private const val PREFS = "photo_tv_network_sources"
    private const val KEY = "sources"

    fun list(context: Context): List<SavedNetworkSource> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val kind = runCatching { NetworkLibrary.Kind.valueOf(o.optString("kind")) }.getOrNull() ?: continue
                    val baseUrl = o.optString("baseUrl").trim()
                    if (baseUrl.isBlank()) continue
                    add(
                        SavedNetworkSource(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                            kind = kind,
                            baseUrl = baseUrl,
                            username = o.optString("username"),
                            label = o.optString("label").ifBlank { defaultLabel(kind, baseUrl) }
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(
        context: Context,
        kind: NetworkLibrary.Kind,
        baseUrl: String,
        username: String,
        label: String? = null
    ): SavedNetworkSource {
        val current = list(context).toMutableList()
        val normalized = baseUrl.trim().trimEnd('/')
        val existingIndex = current.indexOfFirst {
            it.kind == kind && it.baseUrl.trim().trimEnd('/').equals(normalized, ignoreCase = true)
        }
        val source = if (existingIndex >= 0) {
            current[existingIndex].copy(
                username = username.trim(),
                label = label?.trim().takeUnless { it.isNullOrBlank() } ?: current[existingIndex].label
            ).also { current[existingIndex] = it }
        } else {
            SavedNetworkSource(
                id = UUID.randomUUID().toString(),
                kind = kind,
                baseUrl = baseUrl.trim(),
                username = username.trim(),
                label = label?.trim().takeUnless { it.isNullOrBlank() } ?: defaultLabel(kind, baseUrl)
            ).also { current += it }
        }
        save(context, current.takeLast(20))
        return source
    }

    fun delete(context: Context, id: String) {
        save(context, list(context).filterNot { it.id == id })
    }

    private fun save(context: Context, items: List<SavedNetworkSource>) {
        val arr = JSONArray()
        items.forEach { source ->
            arr.put(JSONObject().apply {
                put("id", source.id)
                put("kind", source.kind.name)
                put("baseUrl", source.baseUrl)
                put("username", source.username)
                put("label", source.label)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun defaultLabel(kind: NetworkLibrary.Kind, baseUrl: String): String {
        val prefix = if (kind == NetworkLibrary.Kind.WEBDAV) "WebDAV" else "SMB"
        val cleaned = baseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("smb://")
            .trimEnd('/')
        return "$prefix • $cleaned"
    }
}
