package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

sealed class PhotoSourceSpec {
    data class Tree(val uri: Uri, val exactMode: Boolean) : PhotoSourceSpec()
    data class Picked(val uris: List<Uri>) : PhotoSourceSpec()
}

object SourceStore {
    private const val PREFS = "photo_tv"
    private const val KEY_TREE = "takeout_tree"
    private const val KEY_TREE_EXACT = "takeout_tree_exact"
    private const val KEY_SOURCE_MODE = "source_mode"
    private const val KEY_PICKED_URIS = "picked_uris"
    private const val KEY_SOURCES_V2 = "sources_v2"

    private const val MODE_TREE_EXACT = "tree_exact"
    private const val MODE_TREE_LOCAL = "tree_local"
    private const val MODE_PICKED = "picked"

    fun saveTree(context: Context, uri: Uri, exactMode: Boolean) {
        saveAll(context, listOf(PhotoSourceSpec.Tree(uri, exactMode)))
        writeLegacyTree(context, uri, exactMode)
    }

    fun savePicked(context: Context, uris: List<Uri>) {
        val distinct = uris.distinct()
        saveAll(context, listOf(PhotoSourceSpec.Picked(distinct)))
        writeLegacyPicked(context, distinct)
    }

    fun addTree(context: Context, uri: Uri, exactMode: Boolean) {
        val current = loadAll(context).toMutableList()
        val candidate = PhotoSourceSpec.Tree(uri, exactMode)
        current.removeAll { it is PhotoSourceSpec.Tree && it.uri == uri }
        current += candidate
        saveAll(context, current)
    }

    fun addPicked(context: Context, uris: List<Uri>): List<Uri> {
        val current = loadAll(context).toMutableList()
        val existing = current.filterIsInstance<PhotoSourceSpec.Picked>().flatMap { it.uris }
        current.removeAll { it is PhotoSourceSpec.Picked }
        val merged = (existing + uris).distinct()
        if (merged.isNotEmpty()) current += PhotoSourceSpec.Picked(merged)
        saveAll(context, current)
        return merged
    }

    fun loadAll(context: Context): List<PhotoSourceSpec> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val modern = parseSources(prefs.getString(KEY_SOURCES_V2, null))
        if (modern.isNotEmpty()) return modern
        return loadLegacy(context)?.let(::listOf).orEmpty()
    }

    fun load(context: Context): PhotoSourceSpec? = loadAll(context).firstOrNull()

    fun describe(context: Context): List<String> =
        loadAll(context).map { source ->
            when (source) {
                is PhotoSourceSpec.Tree -> {
                    val decoded = Uri.decode(source.uri.lastPathSegment.orEmpty())
                    val name = decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { "Dossier" }
                    (if (source.exactMode) "Takeout • " else "Dossier • ") + name
                }
                is PhotoSourceSpec.Picked -> "Sélection de photos • " + source.uris.size + " fichier(s)"
            }
        }

    fun removeAt(context: Context, index: Int) {
        val current = loadAll(context).toMutableList()
        if (index !in current.indices) return
        current.removeAt(index)
        saveAll(context, current)
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SOURCES_V2)
            .remove(KEY_TREE)
            .remove(KEY_TREE_EXACT)
            .remove(KEY_SOURCE_MODE)
            .remove(KEY_PICKED_URIS)
            .apply()
    }

    private fun saveAll(context: Context, sources: List<PhotoSourceSpec>) {
        val arr = JSONArray()
        sources.forEach { source ->
            when (source) {
                is PhotoSourceSpec.Tree -> arr.put(JSONObject().apply {
                    put("type", "tree")
                    put("uri", source.uri.toString())
                    put("exact", source.exactMode)
                })
                is PhotoSourceSpec.Picked -> arr.put(JSONObject().apply {
                    put("type", "picked")
                    put("uris", JSONArray().apply { source.uris.distinct().forEach { put(it.toString()) } })
                })
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCES_V2, arr.toString())
            .apply()
    }

    private fun parseSources(raw: String?): List<PhotoSourceSpec> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    when (o.optString("type")) {
                        "tree" -> {
                            val uri = o.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: continue
                            add(PhotoSourceSpec.Tree(uri, o.optBoolean("exact", false)))
                        }
                        "picked" -> {
                            val values = mutableListOf<Uri>()
                            val uris = o.optJSONArray("uris")
                            if (uris != null) {
                                for (j in 0 until uris.length()) {
                                    uris.optString(j).takeIf { it.isNotBlank() }?.let { values += Uri.parse(it) }
                                }
                            }
                            if (values.isNotEmpty()) add(PhotoSourceSpec.Picked(values.distinct()))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadLegacy(context: Context): PhotoSourceSpec? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_SOURCE_MODE, null)) {
            MODE_PICKED -> {
                val values = parseUris(prefs.getString(KEY_PICKED_URIS, null))
                if (values.isEmpty()) null else PhotoSourceSpec.Picked(values)
            }
            MODE_TREE_EXACT -> prefs.getString(KEY_TREE, null)
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.let { PhotoSourceSpec.Tree(it, exactMode = true) }
            MODE_TREE_LOCAL -> prefs.getString(KEY_TREE, null)
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.let { PhotoSourceSpec.Tree(it, exactMode = false) }
            else -> prefs.getString(KEY_TREE, null)
                ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                ?.let { PhotoSourceSpec.Tree(it, prefs.getBoolean(KEY_TREE_EXACT, true)) }
        }
    }

    private fun writeLegacyTree(context: Context, uri: Uri, exactMode: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_MODE, if (exactMode) MODE_TREE_EXACT else MODE_TREE_LOCAL)
            .putString(KEY_TREE, uri.toString())
            .putBoolean(KEY_TREE_EXACT, exactMode)
            .remove(KEY_PICKED_URIS)
            .apply()
    }

    private fun writeLegacyPicked(context: Context, uris: List<Uri>) {
        val array = JSONArray()
        uris.distinct().forEach { array.put(it.toString()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_MODE, MODE_PICKED)
            .putString(KEY_PICKED_URIS, array.toString())
            .remove(KEY_TREE)
            .remove(KEY_TREE_EXACT)
            .apply()
    }

    private fun parseUris(raw: String?): List<Uri> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotBlank()) runCatching { Uri.parse(value) }.getOrNull()?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())
    }
}
