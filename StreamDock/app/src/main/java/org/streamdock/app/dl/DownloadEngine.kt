package org.streamdock.app.dl

import android.content.Context
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/** Raised when the user pauses; treated as "stop cleanly", never as a failure. */
class PausedSignal : IOException("Paused by user")
/** Raised when the remote file changed under us; the partial data is kept but cannot be continued. */
class ValidatorSignal(message: String) : IOException(message)

/** Resumable, multi-connection downloader.
 *
 *  - Every segment writes at its own offset through a positional FileChannel, so pausing, losing the
 *    connection or restarting the app never discards received bytes.
 *  - Resume is only attempted through Range/If-Range when a validator (ETag or Last-Modified) is known,
 *    and every 206 answer is checked against the expected offset and total size.
 *  - Failures are retried with growing backoff while the item stays RUNNING; a pause stops immediately. */
object DownloadEngine {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private val calls = java.util.concurrent.ConcurrentHashMap<String, Call>()

    fun cancelAllFor(id: String) {
        calls.entries.filter { it.key.startsWith("$id:") }.forEach { runCatching { it.value.cancel() } }
    }

    private fun fetchText(url: String, item: DownloadItem, limit: Long = 1_000_000L): String {
        val builder = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (item.agent.isNotBlank()) builder.header("User-Agent", item.agent)
        if (item.referer.isNotBlank()) builder.header("Referer", item.referer)
        client.newCall(builder.get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} while reading the stream playlist.")
            val body = response.body ?: throw IOException("Empty playlist response.")
            val source = body.source()
            val text = source.readUtf8Limited(limit)
            return text
        }
    }

    /** HLS: read the playlist (master → best variant → media), then remember the segment list.
     *  Saved output is the concatenated stream (MPEG-TS / fMP4), which the app can play and most players accept. */
    fun probeHls(item: DownloadItem) {
        var base = item.url
        var text = fetchText(base, item)
        if (!HlsPlaylist.isPlaylist(text)) throw IOException("This stream did not return a valid playlist.")
        if (HlsPlaylist.isMaster(text)) {
            val best = HlsPlaylist.bestVariant(text) ?: throw IOException("The stream playlist listed no playable variant.")
            base = HlsPlaylist.resolve(item.url, best)
            text = fetchText(base, item)
        }
        val media = HlsPlaylist.media(text)
        if (media.encrypted) throw IOException("This stream is DRM/encrypted; StreamDock does not decrypt protected streams.")
        if (media.segments.isEmpty()) throw IOException("The stream playlist contained no segments.")
        item.mode = DlMode.HLS
        item.hlsSegments.clear()
        // Segment URIs are relative to the media playlist, which is the variant when a master was served.
        media.initUri?.let { item.hlsSegments += HlsPlaylist.resolve(base, it) }
        media.segments.forEach { item.hlsSegments += HlsPlaylist.resolve(base, it) }
        item.hlsDuration = media.durationSeconds
        item.hlsDone = 0
        item.hlsSizes.clear()
        item.note = "Stream • ${media.segments.size} segments"
    }

    /** Download the remaining HLS segments in order, appending each one. Pause keeps the finished prefix,
     *  and Resume continues at the next segment boundary without re-downloading earlier segments. */
    fun runHls(item: DownloadItem, file: File, paused: () -> Boolean, progress: (Long) -> Unit) {
        RandomAccessFile(file, "rw").use { raf ->
            val keep = HlsPlaylist.resumeLength(item.hlsSizes, item.hlsDone)
            if (raf.length() != keep) raf.setLength(keep)
            var position = keep
            while (item.hlsDone < item.hlsSegments.size) {
                if (paused()) throw PausedSignal()
                val segmentUrl = item.hlsSegments[item.hlsDone]
                val builder = Request.Builder().url(segmentUrl).header("Accept-Encoding", "identity")
                if (item.agent.isNotBlank()) builder.header("User-Agent", item.agent)
                if (item.referer.isNotBlank()) builder.header("Referer", item.referer)
                val call = client.newCall(builder.get().build())
                calls["${item.id}:hls"] = call
                var written = 0L
                try {
                    call.execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} on segment ${item.hlsDone + 1}.")
                        val body = response.body ?: throw IOException("Empty segment ${item.hlsDone + 1}.")
                        body.byteStream().use { source ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (paused()) throw PausedSignal()
                                val read = source.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                var at = 0
                                while (at < read) at += raf.channel.write(java.nio.ByteBuffer.wrap(buffer, at, read - at), position + at)
                                position += read; written += read
                                progress(read.toLong())
                            }
                        }
                    }
                } finally { calls.remove("${item.id}:hls") }
                if (written <= 0) throw IOException("Segment ${item.hlsDone + 1} came back empty.")
                item.hlsSizes += written
                item.hlsDone += 1
                progress(0)
            }
        }
    }

    /** Ask the server what it supports (one tiny request). Called once per item. */
    fun probe(item: DownloadItem, dir: File, connections: Int) {
        // Streaming media (captured HLS) is handled by the playlist path instead of byte ranges.
        if (item.url.lowercase().contains(".m3u8") || item.mime.contains("mpegurl", true)) { probeHls(item); return }
        val request = base(item).header("Range", "bytes=0-0").get().build()
        val call = client.newCall(request); calls["${item.id}:probe"] = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("Server answered HTTP ${response.code} for this file.")
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.contains("mpegurl") || contentType.contains("dash+xml")) { probeHls(item); return }
                val finalUrl = response.request.url
                if (finalUrl.scheme != "https") throw IOException("Insecure redirect blocked; the file would be fetched over plain HTTP.")
                item.validator = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() }
                    ?: response.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }
                val rangeOk = response.code == 206 && response.header("Content-Range") != null &&
                    DownloadPlanner.rangeMatches(response.header("Content-Range"), 0L, item.total)
                if (response.code == 206) {
                    val size = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                    item.total = if (size > 0) size else item.total
                    // A one-byte range answer is not proof of a stable, seekable file: require the server to
                    // advertise range support, otherwise a chunked/volatile URL would be stitched together
                    // from different byte windows and arrive complete but scrambled.
                    item.acceptRanges = response.header("Accept-Ranges").orEmpty().contains("bytes", true)
                } else if (response.code == 200) {
                    item.acceptRanges = false
                    val length = response.body?.contentLength() ?: -1L
                    if (length > 0) item.total = length
                } else if (!rangeOk) throw IOException("Server answered HTTP ${response.code} for this file.")
                val validatorUsable = !item.validator.isNullOrBlank()
                val volatile = DownloadPlanner.volatileChunkUrl(item.url)
                item.segments.clear()
                // Multi-connection only with proven ranges + a stable validator + a static (not windowed) URL;
                // otherwise one honest connection that cannot interleave different byte windows.
                item.segments.addAll(DownloadPlanner.plan(item.total, item.acceptRanges && validatorUsable && !volatile, item.validator, connections))
                item.note = when {
                    item.segments.size > 1 -> "${item.segments.size} connections"
                    volatile -> "single connection (the CDN serves this file in windows; parallel ranges would corrupt it)"
                    else -> "single connection"
                }
            }
        } finally { calls.remove("${item.id}:probe") }
    }

    private fun base(item: DownloadItem): Request.Builder {
        val b = Request.Builder().url(item.url).header("Accept-Encoding", "identity")
        if (item.agent.isNotBlank()) b.header("User-Agent", item.agent)
        if (item.referer.isNotBlank()) b.header("Referer", item.referer)
        return b
    }

    /** Download one segment until it is finished. Throws PausedSignal on pause and ValidatorSignal if the remote file changed. */
    fun runSegment(item: DownloadItem, index: Int, file: File, paused: () -> Boolean, progress: (Long) -> Unit) {
        val segment = item.segments[index]
        if (segment.remaining == 0L) return
        RandomAccessFile(file, "rw").use { raf ->
            if (item.total > 0 && raf.length() < item.total) raf.setLength(item.total)
            val channel = raf.channel
            var position = segment.nextOffset()
            while (segment.remaining != 0L) {
                if (paused()) throw PausedSignal()
                val header = DownloadPlanner.rangeHeader(segment)
                val builder = base(item).header("Range", header)
                if (!item.validator.isNullOrBlank() && position > segment.start) builder.header("If-Range", item.validator!!)
                val call = client.newCall(builder.get().build())
                calls["${item.id}:$index"] = call
                try {
                    call.execute().use { response ->
                        if (response.code == 200 && position > segment.start)
                            throw ValidatorSignal("The remote file changed while downloading. Restart this download.")
                        if (response.code != 206 && response.code != 200) throw IOException("HTTP ${response.code} while downloading.")
                        if (response.code == 206) {
                            val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L
                            if (!DownloadPlanner.rangeMatches(response.header("Content-Range"), position, item.total))
                                throw ValidatorSignal("The server returned an unexpected range. Restart this download.")
                            if (total > 0 && item.total > 0 && total != item.total)
                                throw ValidatorSignal("The file size changed on the server. Restart this download.")
                            if (DownloadPlanner.validatorChanged(response.header("ETag"), item.validator)) {
                                item.validator = response.header("ETag")?.trim()
                            }
                        }
                        val body = response.body ?: throw IOException("Empty response body.")
                        body.byteStream().use { source ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                if (paused()) throw PausedSignal()
                                val read = source.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                var written = 0
                                while (written < read) {
                                    val at = position + written
                                    written += channel.write(java.nio.ByteBuffer.wrap(buffer, written, read - written), at)
                                }
                                position += read
                                segment.done += read
                                progress(read.toLong())
                                if (segment.remaining == 0L) break
                            }
                        }
                        if (segment.remaining <= 0L) return
                        // The server closed early (connection drop): loop and continue with a fresh Range request.
                    }
                } finally { calls.remove("${item.id}:$index") }
            }
        }
    }

    private fun okio.BufferedSource.readUtf8Limited(limit: Long): String {
        val buffer = okio.Buffer()
        while (buffer.size < limit) {
            val read = read(buffer, minOf(64 * 1024L, limit - buffer.size))
            if (read == -1L) break
        }
        if (!exhausted()) throw IOException("The playlist was larger than the safety limit.")
        return buffer.readUtf8()
    }

    fun partFile(dir: File) = File(dir, "data.bin")
    fun isComplete(item: DownloadItem, file: File): Boolean {
        if (item.mode == DlMode.HLS) return item.complete() && file.length() > 0
        if (!item.complete()) return false
        val expected = DownloadPlanner.expectedSize(item.segments)
        if (expected < 0) return true
        return file.length() == expected
    }
    /** Cheap sanity check: the head of the file must not be an HTML/JSON error page. */
    fun looksLikeErrorPage(file: File): Boolean {
        if (file.length() < 8) return false
        return file.inputStream().use { source ->
            val head = ByteArray(256); val n = source.read(head)
            val text = String(head, 0, n.coerceAtLeast(0), Charsets.UTF_8).trimStart().lowercase()
            text.startsWith("<!doctype html") || text.startsWith("<html") || text.startsWith("{\"errno\"") || text.startsWith("{\"error\"")
        }
    }
}
