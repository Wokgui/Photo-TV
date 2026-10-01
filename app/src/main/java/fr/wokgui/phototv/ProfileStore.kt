package fr.wokgui.phototv

import android.content.Context

object ProfileStore {
    val names = listOf("Personnel", "Famille", "Invités")

    data class Snapshot(
        val favorites: Set<String> = emptySet(),
        val hiddenAlbums: Set<String> = emptySet(),
        val selectedAlbums: Set<String> = emptySet(),
        val favoritesOnly: Boolean = false,
        val smartSelectionMode: Int = SmartSelectionPolicy.OFF
    )

    private const val PREFS = "photo_tv_profiles"
    private const val ACTIVE = "active_profile"

    fun active(context: Context): String {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(ACTIVE, names.first()).orEmpty()
        return value.takeIf { it in names } ?: names.first()
    }

    fun isGuest(name: String): Boolean = name == "Invités"

    fun cycle(context: Context, current: String, direction: Int = 1): String {
        val index = names.indexOf(current).coerceAtLeast(0)
        val next = names[(index + direction + names.size) % names.size]
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ACTIVE, next)
            .apply()
        return next
    }

    fun save(context: Context, name: String, snapshot: Snapshot) {
        val key = safe(name)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("${key}_favorites", snapshot.favorites)
            .putStringSet("${key}_hidden", snapshot.hiddenAlbums)
            .putStringSet("${key}_selected", snapshot.selectedAlbums)
            .putBoolean("${key}_favorites_only", snapshot.favoritesOnly)
            .putInt("${key}_smart_mode", snapshot.smartSelectionMode.coerceIn(0, 4))
            .apply()
    }

    fun load(context: Context, name: String): Snapshot {
        val key = safe(name)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            favorites = prefs.getStringSet("${key}_favorites", emptySet())?.toSet().orEmpty(),
            hiddenAlbums = prefs.getStringSet("${key}_hidden", emptySet())?.toSet().orEmpty(),
            selectedAlbums = prefs.getStringSet("${key}_selected", emptySet())?.toSet().orEmpty(),
            favoritesOnly = prefs.getBoolean("${key}_favorites_only", false),
            smartSelectionMode = prefs.getInt("${key}_smart_mode", SmartSelectionPolicy.OFF).coerceIn(0, 4)
        )
    }

    private fun safe(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
