package org.streamdock.app.terabox

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bounded transport retries, strong ETag/If-Range resume, and strict range/body validation. */
class TeraTransfer(client: OkHttpClient) {
    private val http=client.newBuilder().callTimeout(0,TimeUnit.SECONDS).build()
    fun cancel()=http.dispatcher.cancelAll()
    fun download(media: TeraMedia, file: File, stopped: ()->Boolean, progress: (Long,Long)->Unit) {
        file.delete(); var validator: String?=null; var attempts=0
        while(true) {
            if(stopped()) throw InterruptedException("Cancelled")
            if(validator==null) file.delete()
            var pos=if(file.exists()) file.length() else 0L
            val request=Request.Builder().url(media.url).header("User-Agent",TeraEngine.AGENT).header("Referer",media.referer)
                .header("Accept-Encoding","identity").header("Range","bytes=$pos-")
            if(pos>0) request.header("If-Range",validator!!)
            try {
                http.newCall(request.build()).execute().use { r ->
                    if(r.code!=200 && r.code!=206) throw TeraFailure("Download HTTP ${r.code}. No alternate engine was used.")
                    validateType(r.header("Content-Type").orEmpty())
                    if(!r.header("Content-Encoding").isNullOrBlank() && r.header("Content-Encoding")!="identity") throw TeraFailure("Unexpected content encoding.")
                    val body=r.body ?: throw TeraFailure("Empty file response.")
                    val expected: Long
                    val total: Long
                    if(r.code==206) {
                        val range=parseRange(r.header("Content-Range").orEmpty(),pos)
                        expected=range.second-range.first+1; total=range.third
                        if(pos>0 && r.header("ETag")!=validator) throw TeraFailure("The remote file changed. Start a new download.")
                    } else {
                        pos=0 // A 200 response never gets appended to a partial file.
                        expected=body.contentLength(); total=expected
                    }
                    if(media.file.size>=0 && total>=0 && total!=media.file.size) throw TeraFailure("Remote file size differs from the selected file.")
                    validator=r.header("ETag")?.takeIf { it.startsWith('"') && it.endsWith('"') }
                    var read=0L
                    java.io.RandomAccessFile(file,"rw").use { sink ->
                        if(pos==0L) sink.setLength(0)
                        sink.seek(pos)
                        body.byteStream().use { source ->
                            val buffer=ByteArray(64*1024)
                            while(true) {
                                if(stopped()) throw InterruptedException("Cancelled")
                                val n=source.read(buffer); if(n<0) break
                                if(expected>=0 && read+n>expected) throw TeraFailure("Oversized media response.")
                                sink.write(buffer,0,n); read+=n; progress(pos+read,total)
                            }
                        }
                    }
                    if(expected>=0 && read!=expected) throw IOException("Interrupted file body")
                    if(read==0L || (total>=0 && file.length()!=total)) throw IOException("Incomplete file body")
                    if(media.file.size>=0 && file.length()!=media.file.size) throw TeraFailure("Downloaded file size mismatch.")
                    file.inputStream().use { source ->
                        val head=ByteArray(256); val n=source.read(head)
                        val prefix=String(head,0,n.coerceAtLeast(0),Charsets.UTF_8).trimStart().lowercase()
                        if(prefix.startsWith("<!doctype html") || prefix.startsWith("<html") || prefix.startsWith("{\"errno\"")) throw TeraFailure("Server returned an error page, not the selected file.")
                    }
                    return
                }
            } catch(e: TeraFailure) { throw e }
            catch(e: IOException) {
                if(stopped()) throw InterruptedException("Cancelled")
                if(++attempts>2) throw IOException("Connection interrupted after bounded retries. No complete file was exported.",e)
                Thread.sleep(750L*attempts)
            }
        }
    }
    companion object {
        fun validateType(type: String) {
            val t=type.lowercase()
            if(t.contains("text/html") || t.contains("application/json") || t.contains("mpegurl") || t.contains("dash+xml")) throw TeraFailure("Server returned a page or manifest, not an original file.")
        }
        fun parseRange(value: String, position: Long): Triple<Long,Long,Long> {
            val m=Regex("bytes (\\d+)-(\\d+)/(\\d+)",RegexOption.IGNORE_CASE).matchEntire(value) ?: throw TeraFailure("Invalid Content-Range.")
            val (start,end,total)=m.groupValues.drop(1).map { it.toLongOrNull() ?: throw TeraFailure("Invalid range size.") }
            if(start!=position || end<start || total<=end) throw TeraFailure("Inconsistent Content-Range.")
            return Triple(start,end,total)
        }
    }
}
