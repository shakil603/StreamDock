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

class MediaProtocolException(message: String) : IOException(message)

/** Resilient direct transfer: sequential ranges bound each request, every returned range is validated,
 *  and transient network drops (e.g. SSL connection resets by peer) automatically reconnect from
 *  the last written byte without losing progress. */
class DirectHttpDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).followSslRedirects(false).build(),
    private val chunkBytes: Long = 4L * 1024 * 1024,
    private val maxRetries: Int = Int.MAX_VALUE
) {
    @Volatile private var active: Call? = null
    fun cancel() { active?.cancel() }

    fun download(
        url: String,
        output: File,
        headers: Map<String, String>,
        stopped: () -> Boolean,
        onRetry: ((Int, Throwable) -> Unit)? = null,
        progress: (Long, Long?) -> Unit
    ) {
        require(url.startsWith("https://")) { "NewPipe direct transfer requires HTTPS." }
        require(chunkBytes > 0)
        var position = 0L
        var total: Long? = null
        var attemptCount = 0

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

                    try {
                        call.execute().use { response ->
                            if (response.code != 200 && response.code != 206) throw HttpMediaException(response.code)
                            if (response.request.url.scheme != "https") throw MediaProtocolException("HTTPS downgrade rejected")
                            val encoding = response.header("Content-Encoding").orEmpty()
                            if (encoding.isNotBlank() && !encoding.equals("identity", true)) {
                                throw MediaProtocolException("Unexpected media content encoding")
                            }
                            val mime = response.header("Content-Type").orEmpty().lowercase()
                            if (mime.contains("text/html") || mime.contains("application/json") ||
                                mime.contains("mpegurl") || mime.contains("dash+xml")) {
                                throw MediaProtocolException("Server returned a page/manifest instead of a progressive media file.")
                            }
                            val body = response.body ?: throw IOException("Empty response")
                            val before = position
                            val expected: Long?
                            if (response.code == 206) {
                                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
                                    .matchEntire(response.header("Content-Range").orEmpty())
                                    ?: throw MediaProtocolException("Missing/invalid Content-Range")
                                val start = range.groupValues[1].toLong()
                                val end = range.groupValues[2].toLong()
                                val size = range.groupValues[3].toLong()
                                if (start != position || end < start || end > requestedEnd || size <= end || (total != null && total != size)) {
                                    throw MediaProtocolException("Inconsistent media range: start=$start position=$position end=$end requestedEnd=$requestedEnd size=$size total=$total; partial file discarded")
                                }
                                total = size
                                expected = end - start + 1
                            } else {
                                if (position != 0L) throw MediaProtocolException("Server ignored a continuation range; partial file discarded")
                                expected = body.contentLength().takeIf { it >= 0 }
                                total = expected
                            }

                            body.byteStream().use { source ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    checkStopped()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    if (expected != null && position - before + count > expected) {
                                        throw MediaProtocolException("Oversized media response")
                                    }
                                    sink.write(buffer, 0, count)
                                    position += count
                                    progress(position, total)
                                }
                            }
                            sink.flush()

                            if (expected != null && position - before != expected) {
                                if (position - before < expected) {
                                    throw java.io.EOFException("Premature EOF: received ${position - before} of $expected bytes")
                                } else {
                                    throw MediaProtocolException("Oversized media response")
                                }
                            }
                            if (position == before) throw MediaProtocolException("Empty media response")

                            // Successful chunk read; reset attempt counter
                            attemptCount = 0

                            if (response.code == 200 || position == total) {
                                checkStopped()
                                progress(position, position)
                                return
                            }
                        }
                    } catch (e: Exception) {
                        checkStopped()
                        if (e is InterruptedException || e is HttpMediaException || e is MediaProtocolException) {
                            throw e
                        }
                        if (e is IOException) {
                            // Infinite retries for network drops, timeouts, socket resets, and offline intervals
                            attemptCount++
                            onRetry?.invoke(attemptCount, e)
                            sink.flush()
                            val backoffMs = minOf(10_000L, 1_000L * attemptCount.coerceAtMost(10))
                            val resumeTime = System.currentTimeMillis() + backoffMs
                            while (System.currentTimeMillis() < resumeTime) {
                                checkStopped()
                                val waitChunk = (resumeTime - System.currentTimeMillis()).coerceAtMost(100L)
                                if (waitChunk > 0) Thread.sleep(waitChunk)
                            }
                            continue
                        }
                        throw e
                    }
                }
            }
        } catch (e: Exception) {
            if (e is MediaProtocolException) output.delete()
            throw e
        } finally { active = null }
    }
}
