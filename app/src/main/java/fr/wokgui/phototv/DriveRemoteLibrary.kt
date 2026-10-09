package fr.wokgui.phototv

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

object DriveRemoteLibrary {
    private const val SCHEME="phototv-drive"
    private const val CACHE_LIMIT=256L*1024*1024
    private const val MEMBER_LIMIT=128L*1024*1024
    private val lock=Any()
    private val mediaLocks=Array(32) { Any() }
    @Volatile private var references: Map<String,Ref> = emptyMap()
    data class Ref(val archive: String,val size: Long,val entry: RemoteZip.Entry)
    fun isRemote(uri: Uri) = uri.scheme==SCHEME
    private fun root(context: Context)=File(context.filesDir,"drive-catalog").apply { mkdirs() }
    private fun sha(value: String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun encode(e: RemoteZip.Entry)=JSONObject().put("name",e.name).put("method",e.method).put("flags",e.flags).put("crc",e.crc)
        .put("compressed",e.compressed).put("uncompressed",e.uncompressed).put("offset",e.offset)
    private fun decode(o: JSONObject)=RemoteZip.Entry(o.getString("name"),o.getInt("method"),o.getInt("flags"),o.getLong("crc"),o.getLong("compressed"),o.getLong("uncompressed"),o.getLong("offset"))
    private fun publish(file: File,value: JSONObject) { val temp=File(file.parentFile,file.name+".tmp");temp.writeText(value.toString());check(temp.renameTo(file)) { "Enregistrement de l’index impossible." } }
    private fun zip(context: Context,archive: String,size: Long)=RemoteZip(size) { start,count->DriveSync.readRange(context,archive,start,count) }

    fun synchronize(context: Context, archives: List<JSONObject>, cancelled: ()->Boolean, progress: (String)->Unit,
        fetch: (String,Long,Int)->ByteArray = { id,start,count->DriveSync.readRange(context,id,start,count) }): Boolean {
        var changed=false
        val pool=Executors.newFixedThreadPool(4)
        try {
            // Discover every archive and its album descriptors before processing
            // thousands of individual photo titles in the first archive.
            archives.sortedBy { it.optString("modifiedTime") }.forEachIndexed { number, archive ->
                check(!cancelled()) { "Indexation suspendue ; reprise automatique prévue." }
                val id=archive.getString("id");val size=archive.getString("size").toLong()
                val file=File(root(context),sha(id+":"+archive.optString("md5Checksum"))+".json")
                val existing=if(file.isFile)runCatching { JSONObject(file.readText()) }.getOrNull() else null
                if(existing?.optBoolean("complete")==true || existing?.optBoolean("albumsDiscovered")==true) return@forEachIndexed
                val reader=RemoteZip(size) { start,count->fetch(id,start,count) }
                val state=existing ?: JSONObject().put("archive",id).put("size",size).put("modified",archive.optString("modifiedTime"))
                    .put("entries",JSONArray().apply { reader.entries().filter { !it.name.endsWith('/') }.forEach { put(encode(it)) } })
                    .put("metadata",JSONObject()).put("complete",false)
                val entries=state.getJSONArray("entries");val metadata=state.getJSONObject("metadata")
                val albumFiles=setOf("metadata.json","métadonnées.json","metadatos.json","metadades.json","metadati.json","metadáta.json","metadaten.json","метаданные.json","метаданни.json")
                val descriptors=(0 until entries.length()).map { decode(entries.getJSONObject(it)) }
                    .filter { it.name.substringAfterLast('/').lowercase(java.util.Locale.ROOT) in albumFiles && !metadata.has(it.name) && it.uncompressed<=2L*1024*1024 }
                for(entry in descriptors) {
                    check(!cancelled()) { "Indexation suspendue ; reprise automatique prévue." }
                    val bytes=ByteArrayOutputStream();reader.read(entry,bytes,2L*1024*1024)
                    metadata.put(entry.name,runCatching { JSONObject(bytes.toString("UTF-8")) }.getOrDefault(JSONObject()))
                }
                state.put("albumsDiscovered",true);publish(file,state);SourceStore.addDrive(context)
                context.getSharedPreferences("photo_tv_drive",Context.MODE_PRIVATE).edit().putLong("generation",System.currentTimeMillis()).apply()
                changed=true
                progress("Drive : découverte des albums • fichier ${number+1}/${archives.size}")
            }
            archives.sortedBy { it.optString("modifiedTime") }.forEachIndexed { archiveNumber,archive ->
                check(!cancelled()) { "Indexation suspendue ; reprise automatique prévue." }
                val id=archive.getString("id");val size=archive.getString("size").toLong()
                val file=File(root(context),sha(id+":"+archive.optString("md5Checksum"))+".json")
                val reader=RemoteZip(size) { start,count->fetch(id,start,count) }
                val existing=if(file.isFile)runCatching { JSONObject(file.readText()) }.getOrNull() else null
                if(existing?.optBoolean("complete")==true)return@forEachIndexed
                val state=existing ?: JSONObject().put("archive",id).put("size",size).put("modified",archive.optString("modifiedTime"))
                    .put("entries",JSONArray().apply { reader.entries().filter { !it.name.endsWith('/') }.forEach { put(encode(it)) } })
                    .put("metadata",JSONObject()).put("complete",false)
                val entries=state.getJSONArray("entries");val metadata=state.getJSONObject("metadata")
                val totalJson=(0 until entries.length()).count { entries.getJSONObject(it).getString("name").endsWith(".json",true) }
                val todo=(0 until entries.length()).map { decode(entries.getJSONObject(it)) }
                    .filter { it.name.endsWith(".json",true) && !metadata.has(it.name) && it.uncompressed<=2L*1024*1024 }
                    .sortedBy { if(it.name.substringAfterLast('/').contains("metadata",true))0 else 1 }
                publish(file,state)
                for(batch in todo.chunked(32)) {
                    check(!cancelled()) { "Indexation suspendue ; reprise automatique prévue." }
                    val futures=batch.map { entry->pool.submit<Pair<String,JSONObject>> {
                        check(!cancelled()) { "Indexation suspendue." }
                        val bytes=ByteArrayOutputStream();reader.read(entry,bytes,2L*1024*1024)
                        // Non-photo JSON files may have array roots; retain an empty marker.
                        entry.name to runCatching { JSONObject(bytes.toString("UTF-8")) }.getOrDefault(JSONObject())
                    } }
                    var failure: Throwable?=null
                    futures.forEach { future->runCatching { future.get() }.onSuccess { (name,json)->metadata.put(name,json) }.onFailure { failure=it.cause?:it } }
                    publish(file,state)
                    SourceStore.addDrive(context)
                    context.getSharedPreferences("photo_tv_drive",Context.MODE_PRIVATE).edit().putLong("generation",System.currentTimeMillis()).apply()
                    changed=true
                    progress("Drive : titres et albums • archive ${archiveNumber+1}/${archives.size} • ${metadata.length()}/${totalJson}")
                    failure?.let { throw it }
                }
                state.put("complete",true);publish(file,state);SourceStore.addDrive(context)
                context.getSharedPreferences("photo_tv_drive",Context.MODE_PRIVATE).edit().putLong("generation",System.currentTimeMillis()).apply()
                changed=true
            }
        } finally { pool.shutdownNow() }
        return changed
    }

    fun load(context: Context): List<PhotoItem> {
        // Keep one archive's raw directory in memory at a time on small TVs.
        val states=root(context).listFiles().orEmpty().filter { it.extension=="json" }
            .mapNotNull { file->runCatching { JSONObject(file.readText()).optString("modified") to file }.getOrNull() }
            .sortedBy { it.first }.map { it.second }
        val refs=linkedMapOf<String,Ref>();val folderMetadata=linkedMapOf<String,MutableMap<String,JSONObject>>()
        states.forEach { file->
            val state=JSONObject(file.readText())
            val meta=state.getJSONObject("metadata")
            meta.keys().forEach { path->folderMetadata.getOrPut(path.substringBeforeLast('/',"")){linkedMapOf()}[path.substringAfterLast('/')]=meta.getJSONObject(path) }
        }
        val merged=linkedMapOf<String,PhotoItem>()
        val namesByFolder=folderMetadata.mapValues { (_,metadata)->metadata.entries.associate { it.key.lowercase(java.util.Locale.ROOT) to it.value } }
        val titlesByFolder=folderMetadata.mapValues { (_,metadata)->metadata.values.groupBy { it.optString("title").trim() }.filterValues { it.size==1 }.mapValues { it.value.single() } }
        val albumsByFolder=folderMetadata.mapValues { (_,metadata)->TakeoutLibrary.importedAlbumName(metadata.toList()) }
        states.forEach { file->
            val state=JSONObject(file.readText())
            val entries=state.getJSONArray("entries");val archive=state.getString("archive");val size=state.getLong("size")
            for(i in 0 until entries.length()) {
                val e=decode(entries.getJSONObject(i));val name=e.name.substringAfterLast('/')
                val type=MediaTypeDetector.classifyPhoto(name,null) ?: continue
                if(e.uncompressed>MEMBER_LIMIT || e.flags and 1!=0 || e.method !in listOf(0,8))continue
                val folder=e.name.substringBeforeLast('/',"")
                val names=namesByFolder[folder]
                val json=names?.get("$name.json".lowercase(java.util.Locale.ROOT))
                    ?: names?.get("$name.supplemental-metadata.json".lowercase(java.util.Locale.ROOT)) ?: titlesByFolder[folder]?.get(name)
                val album=albumsByFolder[folder] ?: folder.substringAfterLast('/').ifBlank { "Google Photos" }
                val key=sha(archive+":"+e.name+":"+e.crc);refs[key]=Ref(archive,size,e)
                val uri=Uri.parse("$SCHEME://media/$key")
                val sourceId=json?.optString("url").orEmpty()
                val identity=sourceId.ifBlank { key }
                val geo=json?.optJSONObject("geoDataExif") ?: json?.optJSONObject("geoData")
                val lat=geo?.optDouble("latitude",0.0)?:0.0;val lon=geo?.optDouble("longitude",0.0)?:0.0
                val photo=PhotoItem(uri,TakeoutLibrary.importedTitle(name,json),linkedSetOf(album),
                    takenAt=(json?.optJSONObject("photoTakenTime")?.optString("timestamp")?.toLongOrNull()?:0L)*1000,
                    description=json?.optString("description").orEmpty(),
                    location=if(lat!=0.0 || lon!=0.0)String.format(java.util.Locale.US,"%.5f, %.5f",lat,lon)else "",
                    camera=listOf(json?.optString("cameraMake").orEmpty(),json?.optString("cameraModel").orEmpty()).filter { it.isNotBlank() }.joinToString(" "),
                    mediaType=type,sourceId=sourceId,sourceLabel="Google Photos • Drive en ligne")
                val previous=merged[identity]
                merged[identity]=if(previous==null)photo else photo.copy(albums=previous.albums+photo.albums,sourceCopies=previous.sourceCopies+1)
            }
        }
        references=refs
        return merged.values.toList()
    }

    fun materialize(context: Context,uri: Uri,
        fetch: (String,Long,Int)->ByteArray = { id,start,count->DriveSync.readRange(context,id,start,count) }): File {
        require(isRemote(uri));val key=uri.lastPathSegment ?: error("Photo Drive invalide.")
        return synchronized(mediaLocks[(key.hashCode() and Int.MAX_VALUE) % mediaLocks.size]) {
        require(Regex("[a-f0-9]{64}").matches(key))
        val dir=File(context.cacheDir,"drive-media").apply { mkdirs() };val target=File(dir,key)
        if(target.isFile) { target.setLastModified(System.currentTimeMillis());return@synchronized target }
        if(references[key]==null) synchronized(lock) { if(references[key]==null)load(context) }
        val ref=references[key] ?: error("Photo Drive inconnue.")
        synchronized(lock) { trim(dir,CACHE_LIMIT-ref.entry.uncompressed) }
        require(ref.entry.uncompressed<=MEMBER_LIMIT && context.cacheDir.usableSpace>ref.entry.uncompressed+64L*1024*1024) { "Espace insuffisant pour le cache photo." }
        val temp=File(dir,key+".part")
        try {
            temp.outputStream().use { RemoteZip(ref.size) { start,count->fetch(ref.archive,start,count) }.read(ref.entry,it,MEMBER_LIMIT) }
            check(temp.renameTo(target));synchronized(lock) { trim(dir,CACHE_LIMIT,key) };target
        } finally { temp.delete() }
        }
    }
    private fun trim(dir: File,limit: Long,keep: String="") {
        val files=dir.listFiles().orEmpty().filter { it.isFile && it.extension!="part" }.sortedBy { it.lastModified() }
        var total=files.sumOf { it.length() }
        for(file in files) { if(total<=limit)break;if(file.name!=keep && file.delete())total-=file.length() }
    }
    fun open(context: Context,uri: Uri)=materialize(context,uri).inputStream()
}
