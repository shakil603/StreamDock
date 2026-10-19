package org.streamdock.app

/** Only independently downloadable progressive HTTP resources reach this selector. */
data class PipeTrack(
    val url: String, val extension: String, val height: Int = 0,
    val bitrate: Int = 0, val videoOnly: Boolean = false
)
data class PipeDownloadPlan(val main: PipeTrack, val audio: PipeTrack? = null, val audioOnly: Boolean = false)

object PipeTrackSelector {
    fun select(kind: OutputKind, heightCap: Int, videos: List<PipeTrack>, audios: List<PipeTrack>): PipeDownloadPlan {
        if (kind == OutputKind.MP3 || kind == OutputKind.M4A) {
            val audio = audios.sortedWith(compareByDescending<PipeTrack> { it.extension == kind.extension }
                .thenByDescending { it.bitrate }).firstOrNull()
            val source = audio ?: videos.filter { !it.videoOnly }.maxByOrNull { it.height }
                ?: error("No downloadable NewPipe audio was found. Live/HLS/DASH-only streams are not supported by this transfer path.")
            return PipeDownloadPlan(source, audioOnly = true)
        }
        val extensions = if (kind == OutputKind.MP4) setOf("m4a") else setOf("webm", "opus", "ogg")
        val audio = audios.filter { it.extension in extensions }.maxByOrNull { it.bitrate }
        val video = videos.filter {
            it.extension == kind.extension &&
                (heightCap <= 0 || (it.height > 0 && it.height <= heightCap)) &&
                (!it.videoOnly || audio != null)
        }.sortedWith(compareByDescending<PipeTrack> { it.height }
            .thenBy { it.videoOnly }.thenByDescending { it.bitrate }).firstOrNull()
            ?: error("No NewPipe video+audio matches the selected ${kind.extension.uppercase()} quality. Choose another format or quality.")
        return PipeDownloadPlan(video, if (video.videoOnly) audio else null)
    }
}
