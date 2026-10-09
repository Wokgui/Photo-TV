package fr.wokgui.phototv

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/** Reads the central directory and selected members, never the whole archive. */
class RemoteZip(private val size: Long, private val range: (Long, Int) -> ByteArray) {
    data class Entry(val name: String, val method: Int, val flags: Int, val crc: Long,
        val compressed: Long, val uncompressed: Long, val offset: Long)
    private fun bytes(offset: Long, count: Int): ByteArray {
        require(offset >= 0 && count >= 0 && offset <= size - count) { "Plage ZIP invalide." }
        return range(offset, count).also { check(it.size == count) { "Réponse ZIP incomplète." } }
    }
    private fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p+1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, p: Int): Long = (0..3).fold(0L) { n,i -> n or ((b[p+i].toLong() and 255) shl (8*i)) }
    private fun u64(b: ByteArray, p: Int): Long = (0..7).fold(0L) { n,i -> n or ((b[p+i].toLong() and 255) shl (8*i)) }.also { require(it >= 0) }
    fun entries(): List<Entry> {
        require(size >= 22) { "Archive ZIP trop courte." }
        val tailSize=minOf(size,65557).toInt();val tail=bytes(size-tailSize,tailSize)
        val eocd=(tail.size-22 downTo 0).firstOrNull { u32(tail,it)==0x06054b50L && it+22+u16(tail,it+20)==tail.size }
            ?: error("Fin d’archive ZIP introuvable.")
        require(u16(tail,eocd+4)==0 && u16(tail,eocd+6)==0) { "ZIP multi-disques non pris en charge." }
        var count=u16(tail,eocd+10).toLong();var length=u32(tail,eocd+12);var start=u32(tail,eocd+16)
        if(count==65535L || length==0xffffffffL || start==0xffffffffL) {
            val locator=bytes(size-tailSize+eocd-20,20);require(u32(locator,0)==0x07064b50L && u32(locator,4)==0L && u32(locator,16)==1L)
            val record=bytes(u64(locator,8),56);require(u32(record,0)==0x06064b50L && u32(record,16)==0L && u32(record,20)==0L)
            count=u64(record,32);length=u64(record,40);start=u64(record,48)
        }
        require(count<=200000 && length<=64L*1024*1024 && start<=size-length) { "Index ZIP trop volumineux ou invalide." }
        val central=bytes(start,length.toInt());var pos=0
        return buildList {
            repeat(count.toInt()) {
                require(pos<=central.size-46 && u32(central,pos)==0x02014b50L) { "Index ZIP invalide." }
                val nameSize=u16(central,pos+28);val extraSize=u16(central,pos+30);val comment=u16(central,pos+32)
                val end=pos.toLong()+46+nameSize+extraSize+comment;require(end<=central.size)
                val flags=u16(central,pos+8);val method=u16(central,pos+10);val crc=u32(central,pos+16)
                var compressed=u32(central,pos+20);var unpacked=u32(central,pos+24);var offset=u32(central,pos+42)
                if(compressed==0xffffffffL || unpacked==0xffffffffL || offset==0xffffffffL) {
                    var x=pos+46+nameSize;val stop=x+extraSize;var found=false
                    while(x+4<=stop) {
                        val id=u16(central,x);val len=u16(central,x+2);require(x+4+len<=stop)
                        if(id==1) {
                            var z=x+4
                            fun next(): Long { require(z+8<=x+4+len);val v=u64(central,z);z+=8;return v }
                            if(unpacked==0xffffffffL)unpacked=next();if(compressed==0xffffffffL)compressed=next();if(offset==0xffffffffL)offset=next()
                            found=true;break
                        };x+=4+len
                    };require(found) { "Données ZIP64 absentes." }
                }
                val charset=if(flags and 2048!=0) Charsets.UTF_8 else Charset.forName("CP437")
                val name=String(central,pos+46,nameSize,charset).replace('\\','/')
                require(offset>=0 && compressed>=0 && unpacked>=0)
                add(Entry(name,method,flags,crc,compressed,unpacked,offset));pos=end.toInt()
            }
        }
    }
    fun read(entry: Entry, output: OutputStream, maxBytes: Long) {
        require(entry.uncompressed<=maxBytes && entry.flags and 1==0 && entry.method in listOf(0,8)) { "Média ZIP trop grand ou non pris en charge." }
        // Fetch the photo header with its first data block. Large photos then use
        // 4 MiB ranges rather than many separate 1 MiB network round trips.
        // Descriptor files still require only their small exact range.
        val prefixSize=minOf(size-entry.offset,4L*1024*1024,30L+entry.name.toByteArray(Charsets.UTF_8).size+256L+entry.compressed).toInt()
        val local=bytes(entry.offset,prefixSize);require(u32(local,0)==0x04034b50L && u16(local,8)==entry.method)
        val start=Math.addExact(entry.offset,30L+u16(local,26)+u16(local,28))
        require(start<=size-entry.compressed)
        val compressed=object: InputStream() {
            val localStart=(start-entry.offset).toInt()
            var block=if(localStart<=local.size) local.copyOfRange(localStart,minOf(local.size.toLong(),localStart+entry.compressed).toInt()) else ByteArray(0)
            var position=block.size.toLong();var cursor=0
            override fun read(): Int { val one=ByteArray(1);return if(read(one,0,1)<0)-1 else one[0].toInt() and 255 }
            override fun read(b: ByteArray,off: Int,len: Int): Int {
                if(len==0)return 0
                if(cursor==block.size) {
                    if(position>=entry.compressed)return -1
                    block=bytes(start+position,minOf(4*1024*1024L,entry.compressed-position).toInt());position+=block.size;cursor=0
                }
                val n=minOf(len,block.size-cursor);System.arraycopy(block,cursor,b,off,n);cursor+=n;return n
            }
        }
        val inflater=if(entry.method==8)Inflater(true)else null
        val input=if(inflater!=null)InflaterInputStream(compressed,inflater,65536)else compressed
        val crc=CRC32();var written=0L
        try { input.use { val buffer=ByteArray(65536);while(true) { val n=it.read(buffer);if(n<0)break
            written+=n;check(written<=maxBytes && written<=entry.uncompressed) { "Média ZIP trop long." }
            crc.update(buffer,0,n);output.write(buffer,0,n)
        } };check(written==entry.uncompressed && crc.value==entry.crc) { "Média ZIP incomplet ou corrompu." }
        } finally { inflater?.end() }
    }
}
