package org.streamdock.app.dl

/** Splitting rules for parallel (multi-connection) downloads.
 *  Pure functions so the arithmetic is unit-tested instead of trusted. */
object DownloadPlanner {
    const val MIN_SEGMENT = 4L * 1024 * 1024      // segments smaller than this add overhead, not speed
    const val MIN_MULTI_TOTAL = 8L * 1024 * 1024  // small files stay single-connection

    /** Decide the byte ranges. Multi-connection is used only when the server proved range support
     *  (a 206 answer) and gave a stable validator (ETag/Last-Modified) — otherwise resume would be unsafe. */

    /** CDNs such as Facebook's video edge serve *windows* of a file through query parameters
     *  (`bytestart`, `byteend`, `oh`, `oe`) or per-chunk paths. Two parallel ranges over such a URL are
     *  not two ranges of one file, so the assembled result is complete but scrambled. Those URLs get a
     *  single connection. */
    fun volatileChunkUrl(url: String): Boolean {
        val lower = url.lowercase()
        // Explicit byte-window parameters: the URL itself names a slice of the file.
        if (listOf("bytestart=", "byteend=", "start_index=", "end_index=").any { lower.contains(it) }) return true
        // Facebook/Meta edge hosts address each window with their own signature parameters.
        if (lower.contains("fbcdn") || lower.contains("scontent")) return true
        // Fragment-style paths need a number: "/chunk_0001", "/segment-12", "/part3" — an index like
        // "segments-index.json" is a normal static file and keeps multi-connection.
        if (Regex("(chunk|segment|piece|frag|seg|part)[-_]?\\d+").containsMatchIn(lower)) return true
        return false
    }

    fun plan(total: Long, acceptRanges: Boolean, validator: String?, connections: Int): List<Segment> {
        val wanted = connections.coerceIn(1, 16)
        // A blank validator means the same as none: without it, resume could silently mix two different files.
        if (!acceptRanges || validator.isNullOrBlank() || total <= 0)
            return listOf(Segment(0, if (total > 0) total - 1 else -1))
        if (wanted == 1 || total < MIN_MULTI_TOTAL) return listOf(Segment(0, total - 1))
        val usable = minOf(wanted, (total / MIN_SEGMENT).toInt().coerceAtLeast(1)).coerceAtLeast(1)
        if (usable == 1) return listOf(Segment(0, total - 1))
        val base = total / usable
        val out = ArrayList<Segment>(usable)
        var start = 0L
        for (i in 0 until usable) {
            val end = if (i == usable - 1) total - 1 else (start + base - 1)
            out += Segment(start, end)
            start = end + 1
        }
        return out
    }

    /** Range header for the next read of a segment: resumes after the bytes already written. */
    fun rangeHeader(segment: Segment): String {
        val from = segment.nextOffset()
        return if (segment.end < 0) "bytes=$from-" else "bytes=$from-${segment.end}"
    }

    /** Total bytes that must exist when every segment is finished. */
    fun expectedSize(segments: List<Segment>): Long =
        if (segments.isEmpty() || segments.any { it.end < 0 }) -1L else segments.sumOf { it.length }

    /** A 206 answer is only accepted when it starts exactly where we asked and matches the known size. */
    fun rangeMatches(header: String?, expectedStart: Long, knownTotal: Long): Boolean {
        // The total may legitimately be "*" when the server does not know the size.
        val m = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE).matchEntire(header.orEmpty().trim()) ?: return false
        val start = m.groupValues[1].toLongOrNull() ?: return false
        val end = m.groupValues[2].toLongOrNull() ?: return false
        val size = if (m.groupValues[3] == "*") -1L else (m.groupValues[3].toLongOrNull() ?: return false)
        if (start != expectedStart || end < start) return false
        if (size > 0 && knownTotal > 0 && size != knownTotal) return false
        return true
    }

    /** The remote file may only be continued when its validator is unchanged. */
    fun validatorChanged(current: String?, stored: String?): Boolean {
        if (stored.isNullOrBlank()) return false
        if (current.isNullOrBlank()) return true
        return current.trim() != stored.trim()
    }
}
