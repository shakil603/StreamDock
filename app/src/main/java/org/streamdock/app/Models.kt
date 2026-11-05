package org.streamdock.app

import java.net.URI

data class PreviewStream(
    val label: String, val url: String, val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioHeaders: Map<String, String> = headers
)
data class MediaDetails(
    val source: String, val title: String, val author: String, val duration: Long,
    val thumbnail: String?, val engine: String, val previews: List<PreviewStream>, val notice: String? = null
)
enum class OutputKind(val label: String, val extension: String) {
    MP4("Video · MP4", "mp4"), WEBM("Video · WEBM", "webm"),
    MP3("Audio · MP3", "mp3"), M4A("Audio · M4A", "m4a");
    override fun toString() = label
}
object InputPolicy {
    fun validate(raw: String): String {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("Enter a valid HTTPS URL.") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null) {
            "Enter a complete HTTPS URL. Local files and other protocols are not accepted."
        }
        return value
    }
    fun formatSelector(kind: OutputKind, height: Int): String {
        val limit = if (height > 0) "[height<=${height}]" else ""
        return when (kind) {
            OutputKind.MP4 -> "bestvideo[ext=mp4]$limit+bestaudio[ext=m4a]/best[ext=mp4]$limit"
            OutputKind.WEBM -> "bestvideo[ext=webm]$limit+bestaudio[ext=webm]/best[ext=webm]$limit"
            OutputKind.MP3, OutputKind.M4A -> "bestaudio/best"
        }
    }
}
