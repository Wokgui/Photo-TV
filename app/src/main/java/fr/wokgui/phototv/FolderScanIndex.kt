package fr.wokgui.phototv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object FolderScanIndex {
    data class CachedFolder(
        val items: List<PhotoItem>,
        val missingExactAlbumMetadata: Boolean
    )

    private const val MAX_ROWS = 20_000
    @Volatile private var helper: Helper? = null
    @Volatile var hits: Long = 0
        private set
    @Volatile var misses: Long = 0
        private set

    fun signature(children: List<DocumentFile>, exactMode: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(byteArrayOf(if (exactMode) 1 else 0))
        children
            .sortedBy { it.uri.toString() }
            .forEach { child ->
                val line = buildString {
                    append(child.uri)
                    append('|')
                    append(child.name.orEmpty())
                    append('|')
                    append(child.type.orEmpty())
                    append('|')
                    append(if (child.isDirectory) 'D' else 'F')
                    append('|')
                    append(runCatching { child.lastModified() }.getOrDefault(0L))
                    append('|')
                    append(runCatching { child.length() }.getOrDefault(0L))
                    append('\n')
                }
                digest.update(line.toByteArray(Charsets.UTF_8))
            }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun read(
        context: Context,
        folderUri: Uri,
        exactMode: Boolean,
        signature: String
    ): CachedFolder? {
        val result = db(context).query(
            "folder_index",
            arrayOf("items_json", "missing_exact"),
            "folder_uri=? AND exact_mode=? AND signature=?",
            arrayOf(folderUri.toString(), if (exactMode) "1" else "0", signature),
            null, null, null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else {
                CachedFolder(
                    items = decodeItems(cursor.getString(0).orEmpty()),
                    missingExactAlbumMetadata = cursor.getInt(1) != 0
                )
            }
        }

        if (result != null) {
            hits++
            db(context).update(
                "folder_index",
                ContentValues().apply { put("last_seen", System.currentTimeMillis()) },
                "folder_uri=? AND exact_mode=?",
                arrayOf(folderUri.toString(), if (exactMode) "1" else "0")
            )
        } else {
            misses++
        }
        return result
    }

    fun write(
        context: Context,
        folderUri: Uri,
        exactMode: Boolean,
        signature: String,
        items: List<PhotoItem>,
        missingExactAlbumMetadata: Boolean
    ) {
        val values = ContentValues().apply {
            put("folder_uri", folderUri.toString())
            put("exact_mode", if (exactMode) 1 else 0)
            put("signature", signature)
            put("items_json", encodeItems(items))
            put("missing_exact", if (missingExactAlbumMetadata) 1 else 0)
            put("last_seen", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict(
            "folder_index",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        trim(context)
    }

    fun stats(): String = "dossiers $hits hits/$misses miss"

    private fun encodeItems(items: List<PhotoItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().apply {
                put("uri", item.uri.toString())
                put("title", item.title)
                put("albums", JSONArray(item.albums.toList()))
                put("takenAt", item.takenAt)
                put("description", item.description)
                put("location", item.location)
                put("camera", item.camera)
                put("width", item.width)
                put("height", item.height)
                put("mediaType", item.mediaType)
                put("sourceCopies", item.sourceCopies)
                put("sourceId", item.sourceId)
                put("sourceLabel", item.sourceLabel)
            })
        }
        return array.toString()
    }

    private fun decodeItems(raw: String): List<PhotoItem> =
        runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val uriText = o.optString("uri")
                    if (uriText.isBlank()) continue
                    val albumsArray = o.optJSONArray("albums")
                    val albums = linkedSetOf<String>()
                    if (albumsArray != null) {
                        for (j in 0 until albumsArray.length()) {
                            albumsArray.optString(j).takeIf { it.isNotBlank() }?.let(albums::add)
                        }
                    }
                    if (albums.isEmpty()) albums += "Album"
                    add(
                        PhotoItem(
                            uri = Uri.parse(uriText),
                            title = o.optString("title"),
                            albums = albums,
                            takenAt = o.optLong("takenAt", 0L),
                            description = o.optString("description"),
                            location = o.optString("location"),
                            camera = o.optString("camera"),
                            width = o.optInt("width", 0),
                            height = o.optInt("height", 0),
                            mediaType = o.optString("mediaType", "image"),
                            sourceCopies = o.optInt("sourceCopies", 1),
                            sourceId = o.optString("sourceId"),
                            sourceLabel = o.optString("sourceLabel")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())

    private fun trim(context: Context) {
        val database = db(context)
        val count = database.rawQuery("SELECT COUNT(*) FROM folder_index", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (count <= MAX_ROWS) return
        val remove = count - MAX_ROWS
        database.execSQL(
            "DELETE FROM folder_index WHERE rowid IN (" +
                "SELECT rowid FROM folder_index ORDER BY last_seen ASC LIMIT $remove)"
        )
    }

    private fun db(context: Context): SQLiteDatabase {
        var current = helper
        if (current == null) {
            synchronized(this) {
                current = helper
                if (current == null) {
                    current = Helper(context.applicationContext)
                    helper = current
                }
            }
        }
        return current!!.writableDatabase
    }

    private class Helper(context: Context) :
        SQLiteOpenHelper(context, "photo_tv_folder_index.db", null, 1) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE folder_index (
                    folder_uri TEXT NOT NULL,
                    exact_mode INTEGER NOT NULL,
                    signature TEXT NOT NULL,
                    items_json TEXT NOT NULL,
                    missing_exact INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL,
                    PRIMARY KEY(folder_uri, exact_mode)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_folder_seen ON folder_index(last_seen)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
