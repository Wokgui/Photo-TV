package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import org.json.JSONArray

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

    private const val MODE_TREE_EXACT = "tree_exact"
    private const val MODE_TREE_LOCAL = "tree_local"
    private const val MODE_PICKED = "picked"

    fun saveTree(context: Context, uri: Uri, exactMode: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SOURCE_MODE, if (exactMode) MODE_TREE_EXACT else MODE_TREE_LOCAL)
            .putString(KEY_TREE, uri.toString())
            .putBoolean(KEY_TREE_EXACT, exactMode)
            .remove(KEY_PICKED_URIS)
            .apply()
    }

    fun savePicked(context: Context, uris: List<Uri>) {
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

    fun load(context: Context): PhotoSourceSpec? {
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

            else -> {
                // Backward compatibility with versions that only stored a tree URI.
                prefs.getString(KEY_TREE, null)
                    ?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    ?.let {
                        PhotoSourceSpec.Tree(
                            it,
                            exactMode = prefs.getBoolean(KEY_TREE_EXACT, true)
                        )
                    }
            }
        }
    }

    private fun parseUris(raw: String?): List<Uri> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotBlank()) {
                        runCatching { Uri.parse(value) }.getOrNull()?.let { add(it) }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
}
