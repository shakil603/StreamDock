package org.streamdock.app

/** Observation-only media list for the built-in browser.
 *
 * The sniffer never changes a request, never replays website cookies and never bypasses page logic.
 * It only records media URLs that the page itself requested or declared in its own markup, so the
 * user can play or save content the page already delivered to their own browser session. */
class MediaSniffer(private val limit: Int = 40) {
    enum class Kind(val label: String) { STREAM("HLS/DASH stream"), VIDEO("video file"), AUDIO("audio file") }
    data class Found(val url: String, val kind: Kind, val page: String, val at: Long, val seq: Long)
    private val items = LinkedHashMap<String, Found>()
    private var sequence = 0L

    @Synchronized fun note(rawUrl: String, mime: String?, accept: String?, page: String): Boolean {
        if (classify(rawUrl, mime, accept) == null) return false
        if (items.containsKey(rawUrl)) return false
        items[rawUrl] = Found(rawUrl, classify(rawUrl, mime, accept)!!, page, System.currentTimeMillis(), ++sequence)
        while (items.size > limit) {
            val oldest = items.keys.firstOrNull() ?: break
            items.remove(oldest)
        }
        return true
    }
    @Synchronized fun list(): List<Found> = items.values.sortedByDescending { it.seq }
    @Synchronized fun size() = items.size
    @Synchronized fun clear() = items.clear()

    companion object {
        private val videoExt = setOf("mp4","m4v","mov","webm","mkv","flv","ts","m2ts","ogv","3gp")
        private val audioExt = setOf("mp3","m4a","aac","ogg","opus","flac","wav")
        private val streamExt = setOf("m3u8","mpd")
        private val streamMime = setOf("application/vnd.apple.mpegurl","application/x-mpegurl","audio/mpegurl","application/dash+xml")
        private val rejectedExt = setOf("jpg","jpeg","png","gif","webp","svg","css","js","json","html","htm","ico","woff","woff2","ttf","vtt","xml","txt")
        private val rejectedWords = setOf("thumbnail","poster","sprite","placeholder","favicon","logo","avatar","emoji")

        /** Pure classification used by both the WebView callbacks and the offline unit tests. */
        fun classify(rawUrl: String, mime: String?, accept: String?): Kind? {
            val url = rawUrl.trim()
            if (!url.startsWith("https://", true)) return null   // Browser content is HTTPS-only; nothing else is offered.
            val lower = url.lowercase()
            if (rejectedWords.any { lower.contains(it) }) return null
            // Only the URL path is inspected, so dots in the hostname are never mistaken for extensions.
            val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('#').substringBefore('?')
            val ext = if (path.substringAfterLast('/').contains('.')) path.substringAfterLast('.', "").lowercase() else ""
            if (ext in rejectedExt) return null
            if (ext in streamExt) return Kind.STREAM
            val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (m in streamMime) return Kind.STREAM
            if (ext in videoExt) return Kind.VIDEO
            if (ext in audioExt) return Kind.AUDIO
            if (m.startsWith("video/")) return Kind.VIDEO
            if (m.startsWith("audio/")) return Kind.AUDIO
            if (ext.isEmpty() && accept.orEmpty().lowercase().startsWith("video/")) return Kind.VIDEO
            return null
        }
        fun fileName(url: String): String =
            url.substringBefore('#').substringBefore('?').substringAfterLast('/').ifBlank { "captured-media" }
    }
}
