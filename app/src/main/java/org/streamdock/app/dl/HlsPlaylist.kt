package org.streamdock.app.dl

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Pure HLS playlist parsing. Streaming media captured by the browser is usually HLS, so saving it means
 *  fetching the segments in order and appending them (MPEG-TS / fMP4 fragments concatenate). */
data class HlsVariant(val bandwidth: Long, val uri: String)
data class HlsMedia(val initUri: String?, val segments: List<String>, val durationSeconds: Double, val encrypted: Boolean)

object HlsPlaylist {
    fun isPlaylist(text: String): Boolean = text.trimStart().startsWith("#EXTM3U")
    fun isMaster(text: String): Boolean = text.contains("#EXT-X-STREAM-INF")

    /** Master playlists list variants; the highest bandwidth is chosen. */
    fun variants(text: String): List<HlsVariant> {
        val lines = text.lines()
        val out = ArrayList<HlsVariant>()
        var bandwidth = -1L
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF") -> bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                line.isEmpty() || line.startsWith("#") -> {}
                bandwidth >= 0 -> { out += HlsVariant(bandwidth, line); bandwidth = -1L }
            }
        }
        return out
    }
    fun bestVariant(text: String): String? = variants(text).maxByOrNull { it.bandwidth }?.uri

    /** Media playlist: optional init segment plus the segment URIs in playback order. */
    fun media(text: String): HlsMedia {
        val init = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val segments = ArrayList<String>()
        var duration = 0.0
        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:") -> duration += line.removePrefix("#EXTINF:").substringBefore(',').toDoubleOrNull() ?: 0.0
                line.isEmpty() || line.startsWith("#") -> {}
                else -> segments += line
            }
        }
        val encrypted = text.contains("#EXT-X-KEY:") && !text.contains("METHOD=NONE")
        return HlsMedia(init, segments, duration, encrypted)
    }

    fun resolve(baseUrl: String, uri: String): String {
        if (uri.startsWith("https://")) return uri
        if (uri.startsWith("http://")) return uri.replaceFirst("http://", "https://")
        val base = baseUrl.toHttpUrlOrNull() ?: return uri
        return base.resolve(uri)?.toString() ?: uri
    }

    /** Resume at segment boundaries: the file must be cut back to the end of the last complete segment. */
    fun resumeLength(sizes: List<Long>, done: Int): Long {
        if (done <= 0) return 0L
        var total = 0L
        for (i in 0 until done.coerceAtMost(sizes.size)) total += sizes[i]
        return total
    }
}
