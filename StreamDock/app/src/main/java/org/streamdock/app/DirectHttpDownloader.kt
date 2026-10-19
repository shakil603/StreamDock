package org.streamdock.app

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpMediaException(val code: Int) : IOException(when (code) {
    429 -> "HTTP 429: the server is rate-limiting requests. Wait before trying again; no automatic retry was made."
    403 -> "HTTP 403: the server rejected the NewPipe stream request. No automatic retry with another engine was made."
    else -> "Media server returned HTTP $code"
})

/** No cookies, proxy rotation, client/token tricks, retries or silent engine switching.
 * Sequential ranges bound each request. Every returned range and EOF is validated. */
class DirectHttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).followSslRedirects(false).build(),
    private val chunkBytes: Long = 4L * 1024 * 1024
) {
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }
    fun download(url: String, output: File, headers: Map<String, String>, stopped: () -> Boolean,
                 progress: (Long, Long?) -> Unit) {
        require(url.startsWith("https://")) { "NewPipe direct transfer requires HTTPS." }
        require(chunkBytes > 0)
        var position = 0L
        var total: Long? = null
        fun checkStopped() { if (stopped()) throw InterruptedException("Cancelled") }
        try {
            checkStopped()
            output.outputStream().use { sink ->
                while (true) {
                    checkStopped()
                    val requestedEnd = total?.let { minOf(position + chunkBytes - 1, it - 1) }
                        ?: (position + chunkBytes - 1)
                    val builder = Request.Builder().url(url)
                    headers.forEach { (key, value) -> builder.header(key, value) }
                    builder.header("Accept-Encoding", "identity").header("Range", "bytes=$position-$requestedEnd")
                    val call = client.newCall(builder.build())
                    active = call
                    checkStopped()
                    call.execute().use { response ->
                        if (response.code != 200 && response.code != 206) throw HttpMediaException(response.code)
                        if (response.request.url.scheme != "https") throw IOException("HTTPS downgrade rejected")
                        val encoding = response.header("Content-Encoding").orEmpty()
                        if (encoding.isNotBlank() && !encoding.equals("identity", true)) throw IOException("Unexpected media content encoding")
                        val mime = response.header("Content-Type").orEmpty().lowercase()
                        if (mime.contains("text/html") || mime.contains("application/json") || mime.contains("mpegurl") || mime.contains("dash+xml")) {
                            throw IOException("Server returned a page/manifest instead of a progressive media file.")
                        }
                        val body = response.body ?: throw IOException("Empty response")
                        val before = position
                        val expected: Long?
                        if (response.code == 206) {
                            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
                                .matchEntire(response.header("Content-Range").orEmpty())
                                ?: throw IOException("Missing/invalid Content-Range")
                            val start = range.groupValues[1].toLong()
                            val end = range.groupValues[2].toLong()
                            val size = range.groupValues[3].toLong()
                            if (start != position || end < start || end > requestedEnd || size <= end || (total != null && total != size)) {
                                throw IOException("Inconsistent media range; partial file discarded")
                            }
                            total = size
                            expected = end - start + 1
                        } else {
                            if (position != 0L) throw IOException("Server ignored a continuation range; partial file discarded")
                            expected = body.contentLength().takeIf { it >= 0 }
                            total = expected
                        }
                        body.byteStream().use { source ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkStopped()
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (expected != null && position - before + count > expected) throw IOException("Oversized media response")
                                sink.write(buffer, 0, count)
                                position += count
                                progress(position, total)
                            }
                        }
                        if (expected != null && position - before != expected) throw IOException("Truncated media response; partial file discarded")
                        if (position == before) throw IOException("Empty media response")
                        if (response.code == 200 || position == total) {
                            checkStopped()
                            progress(position, position)
                            return
                        }
                    }
                }
            }
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally { active = null }
    }
}
