package fr.wokgui.phototv

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object MediaMetadataStore {
    data class Signature(
        val modified: Long,
        val size: Long,
        val mime: String
    ) {
        val cacheable: Boolean
            get() = modified > 0L || size > 0L
    }

    data class CachedInfo(
        val width: Int,
        val height: Int,
        val takenAt: Long,
        val camera: String,
        val location: String
    )

    data class CachedFingerprint(
        val hash: Long,
        val meanLuma: Int,
        val aspectRatio: Float
    )

    private const val MAX_ROWS = 50_000
    @Volatile private var helper: Helper? = null
    @Volatile var infoHits: Long = 0
        private set
    @Volatile var infoMisses: Long = 0
        private set
    @Volatile var fingerprintHits: Long = 0
        private set
    @Volatile var fingerprintMisses: Long = 0
        private set
    @Volatile var digestHits: Long = 0
        private set
    @Volatile var digestMisses: Long = 0
        private set

    fun signature(context: Context, uri: Uri, mime: String): Signature {
        val doc = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
        return Signature(
            modified = runCatching { doc?.lastModified() ?: 0L }.getOrDefault(0L),
            size = runCatching { doc?.length() ?: 0L }.getOrDefault(0L),
            mime = mime
        )
    }

    fun readInfo(context: Context, uri: Uri, signature: Signature): CachedInfo? {
        if (!signature.cacheable) {
            infoMisses++
            return null
        }
        val row = db(context).query(
            "media_metadata",
            arrayOf("width", "height", "taken_at", "camera", "location"),
            "uri=? AND modified=? AND size=? AND mime=?",
            arrayOf(uri.toString(), signature.modified.toString(), signature.size.toString(), signature.mime),
            null, null, null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else CachedInfo(
                width = cursor.getInt(0),
                height = cursor.getInt(1),
                takenAt = cursor.getLong(2),
                camera = cursor.getString(3).orEmpty(),
                location = cursor.getString(4).orEmpty()
            )
        }
        if (row != null) {
            infoHits++
            touch(context, uri)
        } else {
            infoMisses++
        }
        return row
    }

    fun writeInfo(
        context: Context,
        uri: Uri,
        signature: Signature,
        info: MediaInfo
    ) {
        if (!signature.cacheable) return
        val values = ContentValues().apply {
            put("uri", uri.toString())
            put("modified", signature.modified)
            put("size", signature.size)
            put("mime", signature.mime)
            put("width", info.width)
            put("height", info.height)
            put("taken_at", info.takenAt)
            put("camera", info.camera)
            put("location", info.location)
            put("last_seen", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict(
            "media_metadata",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        trimIfNeeded(context)
    }

    fun readFingerprint(
        context: Context,
        uri: Uri,
        signature: Signature
    ): CachedFingerprint? {
        if (!signature.cacheable) {
            fingerprintMisses++
            return null
        }
        val row = db(context).query(
            "visual_fingerprints",
            arrayOf("hash_value", "mean_luma", "aspect_ratio"),
            "uri=? AND modified=? AND size=?",
            arrayOf(uri.toString(), signature.modified.toString(), signature.size.toString()),
            null, null, null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else CachedFingerprint(
                hash = cursor.getLong(0),
                meanLuma = cursor.getInt(1),
                aspectRatio = cursor.getFloat(2)
            )
        }
        if (row != null) fingerprintHits++ else fingerprintMisses++
        return row
    }

    fun writeFingerprint(
        context: Context,
        uri: Uri,
        signature: Signature,
        fingerprint: CachedFingerprint
    ) {
        if (!signature.cacheable) return
        val values = ContentValues().apply {
            put("uri", uri.toString())
            put("modified", signature.modified)
            put("size", signature.size)
            put("hash_value", fingerprint.hash)
            put("mean_luma", fingerprint.meanLuma)
            put("aspect_ratio", fingerprint.aspectRatio)
            put("last_seen", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict(
            "visual_fingerprints",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        trimIfNeeded(context)
    }

    fun readExactDigest(
        context: Context,
        uri: Uri,
        signature: Signature
    ): String? {
        if (!signature.cacheable) {
            digestMisses++
            return null
        }
        val value = db(context).query(
            "exact_digests",
            arrayOf("sha256"),
            "uri=? AND modified=? AND size=?",
            arrayOf(uri.toString(), signature.modified.toString(), signature.size.toString()),
            null, null, null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }
        if (value != null) digestHits++ else digestMisses++
        return value
    }

    fun writeExactDigest(
        context: Context,
        uri: Uri,
        signature: Signature,
        sha256: String
    ) {
        if (!signature.cacheable || sha256.isBlank()) return
        val values = ContentValues().apply {
            put("uri", uri.toString())
            put("modified", signature.modified)
            put("size", signature.size)
            put("sha256", sha256)
            put("last_seen", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict(
            "exact_digests",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        trimIfNeeded(context)
    }

    fun stats(): String =
        "métadonnées $infoHits hits/$infoMisses miss • perceptuel $fingerprintHits/$fingerprintMisses • SHA-256 $digestHits/$digestMisses"

    private fun touch(context: Context, uri: Uri) {
        val values = ContentValues().apply { put("last_seen", System.currentTimeMillis()) }
        db(context).update("media_metadata", values, "uri=?", arrayOf(uri.toString()))
    }

    private fun trimIfNeeded(context: Context) {
        val database = db(context)
        val count = database.rawQuery("SELECT COUNT(*) FROM media_metadata", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (count > MAX_ROWS) {
            val remove = count - MAX_ROWS
            database.execSQL(
                "DELETE FROM media_metadata WHERE uri IN (" +
                    "SELECT uri FROM media_metadata ORDER BY last_seen ASC LIMIT $remove)"
            )
        }

        val fpCount = database.rawQuery("SELECT COUNT(*) FROM visual_fingerprints", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (fpCount > MAX_ROWS) {
            val remove = fpCount - MAX_ROWS
            database.execSQL(
                "DELETE FROM visual_fingerprints WHERE uri IN (" +
                    "SELECT uri FROM visual_fingerprints ORDER BY last_seen ASC LIMIT $remove)"
            )
        }

        val digestCount = database.rawQuery("SELECT COUNT(*) FROM exact_digests", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (digestCount > MAX_ROWS) {
            val remove = digestCount - MAX_ROWS
            database.execSQL(
                "DELETE FROM exact_digests WHERE uri IN (" +
                    "SELECT uri FROM exact_digests ORDER BY last_seen ASC LIMIT $remove)"
            )
        }
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
        SQLiteOpenHelper(context, "photo_tv_media_cache.db", null, 2) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE media_metadata (
                    uri TEXT PRIMARY KEY,
                    modified INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    mime TEXT NOT NULL,
                    width INTEGER NOT NULL,
                    height INTEGER NOT NULL,
                    taken_at INTEGER NOT NULL,
                    camera TEXT NOT NULL,
                    location TEXT NOT NULL,
                    last_seen INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE visual_fingerprints (
                    uri TEXT PRIMARY KEY,
                    modified INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    hash_value INTEGER NOT NULL,
                    mean_luma INTEGER NOT NULL,
                    aspect_ratio REAL NOT NULL,
                    last_seen INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE exact_digests (
                    uri TEXT PRIMARY KEY,
                    modified INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    sha256 TEXT NOT NULL,
                    last_seen INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_media_seen ON media_metadata(last_seen)")
            db.execSQL("CREATE INDEX idx_fp_seen ON visual_fingerprints(last_seen)")
            db.execSQL("CREATE INDEX idx_digest_seen ON exact_digests(last_seen)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS exact_digests (
                        uri TEXT PRIMARY KEY,
                        modified INTEGER NOT NULL,
                        size INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        last_seen INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_digest_seen ON exact_digests(last_seen)")
            }
        }
    }
}
