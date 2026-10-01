package fr.wokgui.phototv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PhotoTvProfile(
    val id: String,
    val name: String,
    val guest: Boolean,
    val snapshot: String
)

object ProfileStore {
    private const val PREFS = "photo_tv_profiles"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active"
    private const val DEFAULT_ID = "default"

    @Synchronized
    fun list(context: Context): List<PhotoTvProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
        val parsed = runCatching {
            val arr = JSONArray(raw ?: "[]")
            (0 until arr.length()).mapNotNull { index ->
                val o = arr.optJSONObject(index) ?: return@mapNotNull null
                val id = o.optString("id").trim()
                val name = o.optString("name").trim()
                if (id.isBlank() || name.isBlank()) return@mapNotNull null
                PhotoTvProfile(
                    id = id,
                    name = name.take(40),
                    guest = o.optBoolean("guest", false),
                    snapshot = o.optString("snapshot", "")
                )
            }
        }.getOrDefault(emptyList())

        if (parsed.isNotEmpty()) return parsed
        val initial = listOf(PhotoTvProfile(DEFAULT_ID, "Principal", false, ""))
        write(context, initial, DEFAULT_ID)
        return initial
    }

    fun active(context: Context): PhotoTvProfile {
        val all = list(context)
        val activeId = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE, DEFAULT_ID)
        return all.firstOrNull { it.id == activeId } ?: all.first()
    }

    fun activeName(context: Context): String = active(context).name

    fun isGuest(context: Context): Boolean = active(context).guest

    @Synchronized
    fun saveActiveSnapshot(context: Context, snapshot: String) {
        val all = list(context).toMutableList()
        val active = active(context)
        val index = all.indexOfFirst { it.id == active.id }
        if (index < 0) return
        all[index] = active.copy(snapshot = snapshot)
        write(context, all, active.id)
    }

    @Synchronized
    fun switch(context: Context, targetId: String, currentSnapshot: String): String? {
        saveActiveSnapshot(context, currentSnapshot)
        val all = list(context)
        val target = all.firstOrNull { it.id == targetId } ?: return null
        write(context, all, target.id)
        return target.snapshot.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun create(context: Context, name: String, guest: Boolean, snapshot: String): PhotoTvProfile {
        val cleanName = name.trim().ifBlank { if (guest) "Invités" else "Profil" }.take(40)
        val all = list(context).toMutableList()
        val profile = PhotoTvProfile(
            id = UUID.randomUUID().toString(),
            name = uniqueName(cleanName, all),
            guest = guest,
            snapshot = snapshot
        )
        all += profile
        write(context, all, profile.id)
        return profile
    }

    @Synchronized
    fun ensureGuest(context: Context, snapshot: String): PhotoTvProfile {
        list(context).firstOrNull { it.guest }?.let { guest ->
            switch(context, guest.id, snapshot)
            return guest
        }
        return create(context, "Invités", true, snapshot)
    }

    @Synchronized
    fun rename(context: Context, id: String, name: String): Boolean {
        val all = list(context).toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return false
        val clean = name.trim().take(40)
        if (clean.isBlank()) return false
        val others = all.filterNot { it.id == id }
        all[index] = all[index].copy(name = uniqueName(clean, others))
        write(context, all, active(context).id)
        return true
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        if (id == DEFAULT_ID) return false
        val all = list(context).toMutableList()
        val removed = all.removeAll { it.id == id }
        if (!removed) return false
        val activeId = active(context).id
        write(context, all.ifEmpty { listOf(PhotoTvProfile(DEFAULT_ID, "Principal", false, "")) },
            if (activeId == id) DEFAULT_ID else activeId)
        return true
    }

    private fun uniqueName(base: String, existing: List<PhotoTvProfile>): String {
        if (existing.none { it.name.equals(base, ignoreCase = true) }) return base
        var n = 2
        while (existing.any { it.name.equals("$base $n", ignoreCase = true) }) n++
        return "$base $n"
    }

    private fun write(context: Context, profiles: List<PhotoTvProfile>, activeId: String) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("guest", p.guest)
                    .put("snapshot", p.snapshot)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, arr.toString())
            .putString(KEY_ACTIVE, activeId)
            .apply()
    }
}
