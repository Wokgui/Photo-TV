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

    data class CachedCropAnchor(
        val x: Float,
        val y: Float,
        val weight: Float
    )

    data class CachedScene(
        val label: String,
        val confidence: Float
    )

    data class CachedQuality(
        val score: Int
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
    @Volatile var cropHits: Long = 0
        private set
    @Volatile var cropMisses: Long = 0
        private set
    @Volatile var sceneHits: Long = 0
        private set
    @Volatile var sceneMisses: Long = 0
        private set
    @Volatile var qualityHits: Long = 0
        private set
    @Volatile var qualityMisses: Long = 0
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

    fun readCropAnchor(
        context: Context,
        uri: Uri,
        signature: Signature
    ): CachedCropAnchor? {
        if (!signature.cacheable) {
            cropMisses++
            return null
        }
        val row = db(context).query(
            "smart_crop_anchors",
            arrayOf("anchor_x", "anchor_y", "anchor_weight"),
            "uri=? AND modified=? AND size=?",
            arrayOf(uri.toString(), signature.modified.toString(), signature.size.toString()),
            null, null, null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else CachedCropAnchor(
                x = cursor.getFloat(0),
                y = cursor.getFloat(1),
                weight = cursor.getFloat(2)
            )
        }
        if (row != null) cropHits++ else cropMisses++
        return row
    }

    fun writeCropAnchor(
        context: Context,
        uri: Uri,
        signature: Signature,
        anchor: CachedCropAnchor
    ) {
        if (!signature.cacheable) return
        val values = ContentValues().apply {
            put("uri", uri.toString())
            put("modified", signature.modified)
            put("size", signature.size)
            put("anchor_x", anchor.x.coerceIn(0f, 1f))
            put("anchor_y", anchor.y.coerceIn(0f, 1f))
            put("anchor_weight", anchor.weight.coerceAtLeast(.001f))
            put("last_seen", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict(
            "smart_crop_anchors",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        trimIfNeeded(context)
    }

    fun readScene(
        context: Context,
        uri: Uri,
        signature: Signature
    ): CachedScene? {
        if (!signature.cacheable) {
            sceneMisses++
            return null
        }
        val row = db(context).query(
            "scene_labels",
            arrayOf("label", "confidence"),
            "uri=? AND modified=? AND size=?",
            arrayOf(uri.toString(), signature.modified.toString(), signature.size.toString()),
            null, null, null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else CachedScene(
                label = cursor.getString(0).orEmpty(),
                confidence = cursor.getFloat(1)
            )
        }
        if (row != null) sceneHits++ else sceneMisses++
        return row
    }

    fun writeScene(
        context: Context,
        uri: Uri,
        signature: Signature,
        scene: CachedScene
    ) {
        if (!signature.cacheable || scene.label.isBlank()) return
        val values = ContentValues().apply {
            put("uri", uri.toString())
            put("modified", signature.modified)
            put("size", signature.size)
            put("label", scene.label)
            put("confidence", scene.confidence.coerceIn(0f, 1f))
            put("last_seen", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict(
            "scene_labels",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        trimIfNeeded(context)
    }

    fun readQuality(
        context: Context,
        uri: Uri,
        signature: Signature
    ): CachedQuality? {
        if (!signature.cacheable) {
            qualityMisses++
            return null
        }
        val row = db(context).query(
            "quality_scores",
            arrayOf("score"),
            "uri=? AND modified=? AND size=?",
            arrayOf(uri.toString(), signature.modified.toString(), signature.size.toString()),
            null, null, null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else CachedQuality(cursor.getInt(0).coerceIn(0, 100))
        }
        if (row != null) qualityHits++ else qualityMisses++
        return row
    }

    fun writeQuality(
        context: Context,
        uri: Uri,
        signature: Signature,
        quality: CachedQuality
    ) {
        if (!signature.cacheable) return
        val values = ContentValues().apply {
            put("uri", uri.toString())
            put("modified", signature.modified)
            put("size", signature.size)
            put("score", quality.score.coerceIn(0, 100))
            put("last_seen", System.currentTimeMillis())
        }
        db(context).insertWithOnConflict(
            "quality_scores",
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
        "métadonnées $infoHits hits/$infoMisses miss • perceptuel $fingerprintHits/$fingerprintMisses • crop $cropHits/$cropMisses • scènes $sceneHits/$sceneMisses • qualité $qualityHits/$qualityMisses • SHA-256 $digestHits/$digestMisses"

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

        val cropCount = database.rawQuery("SELECT COUNT(*) FROM smart_crop_anchors", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (cropCount > MAX_ROWS) {
            val remove = cropCount - MAX_ROWS
            database.execSQL(
                "DELETE FROM smart_crop_anchors WHERE uri IN (" +
                    "SELECT uri FROM smart_crop_anchors ORDER BY last_seen ASC LIMIT $remove)"
            )
        }

        val sceneCount = database.rawQuery("SELECT COUNT(*) FROM scene_labels", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (sceneCount > MAX_ROWS) {
            val remove = sceneCount - MAX_ROWS
            database.execSQL(
                "DELETE FROM scene_labels WHERE uri IN (" +
                    "SELECT uri FROM scene_labels ORDER BY last_seen ASC LIMIT $remove)"
            )
        }

        val qualityCount = database.rawQuery("SELECT COUNT(*) FROM quality_scores", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }
        if (qualityCount > MAX_ROWS) {
            val remove = qualityCount - MAX_ROWS
            database.execSQL(
                "DELETE FROM quality_scores WHERE uri IN (" +
                    "SELECT uri FROM quality_scores ORDER BY last_seen ASC LIMIT $remove)"
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
        SQLiteOpenHelper(context, "photo_tv_media_cache.db", null, 6) {

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
            db.execSQL(
                """
                CREATE TABLE smart_crop_anchors (
                    uri TEXT PRIMARY KEY,
                    modified INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    anchor_x REAL NOT NULL,
                    anchor_y REAL NOT NULL,
                    anchor_weight REAL NOT NULL,
                    last_seen INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_media_seen ON media_metadata(last_seen)")
            db.execSQL("CREATE INDEX idx_fp_seen ON visual_fingerprints(last_seen)")
            db.execSQL("CREATE INDEX idx_digest_seen ON exact_digests(last_seen)")
            db.execSQL("CREATE INDEX idx_crop_seen ON smart_crop_anchors(last_seen)")
            db.execSQL(
                """
                CREATE TABLE scene_labels (
                    uri TEXT PRIMARY KEY,
                    modified INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    label TEXT NOT NULL,
                    confidence REAL NOT NULL,
                    last_seen INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_scene_seen ON scene_labels(last_seen)")
            db.execSQL(
                """
                CREATE TABLE quality_scores (
                    uri TEXT PRIMARY KEY,
                    modified INTEGER NOT NULL,
                    size INTEGER NOT NULL,
                    score INTEGER NOT NULL,
                    last_seen INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_quality_seen ON quality_scores(last_seen)")
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
            if (oldVersion < 3) {
                db.delete("media_metadata", null, null)
            }
            if (oldVersion < 4) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS smart_crop_anchors (
                        uri TEXT PRIMARY KEY,
                        modified INTEGER NOT NULL,
                        size INTEGER NOT NULL,
                        anchor_x REAL NOT NULL,
                        anchor_y REAL NOT NULL,
                        anchor_weight REAL NOT NULL,
                        last_seen INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_crop_seen ON smart_crop_anchors(last_seen)")
            }
            if (oldVersion < 5) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scene_labels (
                        uri TEXT PRIMARY KEY,
                        modified INTEGER NOT NULL,
                        size INTEGER NOT NULL,
                        label TEXT NOT NULL,
                        confidence REAL NOT NULL,
                        last_seen INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_scene_seen ON scene_labels(last_seen)")
            }
            if (oldVersion < 6) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS quality_scores (
                        uri TEXT PRIMARY KEY,
                        modified INTEGER NOT NULL,
                        size INTEGER NOT NULL,
                        score INTEGER NOT NULL,
                        last_seen INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_quality_seen ON quality_scores(last_seen)")
            }
        }
    }
}
