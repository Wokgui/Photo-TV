package fr.wokgui.phototv
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.CRC32

class RemoteZipTest {
    @Test fun smallDescriptorUsesOneRequestAndRetainsExactPayload() {
        val (data,_)=fixture();var requests=0
        val zip=RemoteZip(data.size.toLong()){start,count->requests++;data.copyOfRange(start.toInt(),start.toInt()+count)}
        val descriptor=zip.entries().first();requests=0
        val result=ByteArrayOutputStream();zip.read(descriptor,result,2048)
        assertEquals(1,requests);assertEquals("{\"title\":\"Été\"}",result.toString("UTF-8"))
    }
    private fun fixture(stored: Boolean=false): Pair<ByteArray,ByteArray> {
        val payload=ByteArray(2*1024*1024){ (it*31).toByte() }
        val output=ByteArrayOutputStream()
        ZipOutputStream(output).use { zip->
            zip.putNextEntry(ZipEntry("Takeout/Google Photos/Été/metadata.json"));zip.write("{\"title\":\"Été\"}".toByteArray());zip.closeEntry()
            val entry=ZipEntry("Takeout/Google Photos/Été/photo.jpg")
            if(stored){entry.method=ZipEntry.STORED;entry.size=payload.size.toLong();entry.compressedSize=entry.size;entry.crc=CRC32().apply { update(payload) }.value}
            zip.putNextEntry(entry);zip.write(payload);zip.closeEntry()
        }
        return output.toByteArray() to payload
    }
    @Test fun readsCompressedMemberWithUnicodeNamesAndDataDescriptor() {
        val (data,payload)=fixture();var transferred=0L
        val zip=RemoteZip(data.size.toLong()){start,count->transferred+=count;data.copyOfRange(start.toInt(),start.toInt()+count)}
        val entries=zip.entries();assertEquals(2,entries.size);assertTrue(entries[0].name.contains("Été"))
        val out=ByteArrayOutputStream();zip.read(entries[1],out,payload.size.toLong());assertArrayEquals(payload,out.toByteArray())
        assertTrue(transferred<payload.size)
    }
    @Test fun indexesStoredArchiveWithoutFetchingItsPhotosAndReadsBoundedRanges() {
        val (data,payload)=fixture(true);var transferred=0L;var largest=0;var photoRequests=0
        val zip=RemoteZip(data.size.toLong()){start,count->transferred+=count;largest=maxOf(largest,count);data.copyOfRange(start.toInt(),start.toInt()+count)}
        val entries=zip.entries();assertTrue(transferred<100000)
        photoRequests=0
        val photoZip=RemoteZip(data.size.toLong()){start,count->photoRequests++;largest=maxOf(largest,count);data.copyOfRange(start.toInt(),start.toInt()+count)}
        val out=ByteArrayOutputStream();photoZip.read(entries[1],out,payload.size.toLong());assertArrayEquals(payload,out.toByteArray());assertTrue(largest<=4*1024*1024)
        assertEquals("A two-MiB photo should need only one range request",1,photoRequests)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMemberAboveOutputBudgetBeforeFetchingBody() {
        val (data,_)=fixture();val zip=RemoteZip(data.size.toLong()){start,count->data.copyOfRange(start.toInt(),start.toInt()+count)}
        zip.read(zip.entries()[1],ByteArrayOutputStream(),16)
    }
    @Test(expected=IllegalStateException::class) fun rejectsCorruptedStoredMember() {
        val (data,_)=fixture(true);val zip=RemoteZip(data.size.toLong()){start,count->data.copyOfRange(start.toInt(),start.toInt()+count)}
        val entry=zip.entries()[1];val local=entry.offset.toInt();val nameLength=(data[local+26].toInt() and 255) or ((data[local+27].toInt() and 255) shl 8)
        data[local+30+nameLength]=(data[local+30+nameLength].toInt() xor 1).toByte()
        zip.read(entry,ByteArrayOutputStream(),3L*1024*1024)
    }
    @Test(expected=IllegalStateException::class) fun rejectsTruncatedRangeResponses() {
        val (data,_)=fixture();RemoteZip(data.size.toLong()){_,count->ByteArray(count-1)}.entries()
    }
    @Test(expected=IllegalStateException::class) fun rejectsMissingEndDirectory() {
        RemoteZip(128){_,count->ByteArray(count)}.entries()
    }
}
