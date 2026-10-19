package org.streamdock.app

import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

class NewPipeTransfer(
    private val http: DirectHttpDownloader,
    private val processor: NativeMediaProcessor,
    private val stopped: () -> Boolean,
    private val status: (String, Int) -> Unit
) {
    /** Set when FFmpeg had to pick a different container than the requested one. */
    var produced: File? = null
    fun download(service: StreamingService, url: String, folder: File, kind: OutputKind, height: Int) =
        EngineBoundary.call("NewPipe download") {
            status("NewPipe: resolving fresh stream URLs…", 0)
            val info = StreamInfo.getInfo(service, url)
            if (stopped()) throw InterruptedException("Cancelled")
            fun direct(s: Stream) = s.isUrl && s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.content.startsWith("https://")
            val videos = (info.videoStreams + info.videoOnlyStreams).filter { direct(it) }.map {
                PipeTrack(it.content, it.format?.suffix.orEmpty(),
                    it.height.takeIf { h -> h > 0 } ?: Regex("^(\\d+)").find(it.resolution)?.groupValues?.get(1)?.toIntOrNull() ?: 0,
                    it.bitrate.coerceAtLeast(0), it.isVideoOnly)
            }
            val audios = info.audioStreams.filter { direct(it) }.map {
                PipeTrack(it.content, it.format?.suffix.orEmpty(), bitrate = it.averageBitrate.coerceAtLeast(0))
            }
            val plan = PipeTrackSelector.select(kind, height, videos, audios)
            if (plan.audioOnly || plan.audio != null) {
                status("Checking FFmpeg runtime before download…", 0)
                processor.prepare()
            }
            val output = File(folder, "media.${kind.extension}")
            val main = if (!plan.audioOnly && plan.audio == null) output else File(folder, "track-main.bin")
            val headers = mapOf("User-Agent" to PipeDownloader.USER_AGENT, "Referer" to url)
            http.download(plan.main.url, main, headers, stopped) { done, total ->
                val max = if (plan.audio != null) 70 else 90
                val percent = total?.takeIf { it > 0 }?.let { ((done.toDouble() / it) * max).toInt() } ?: 0
                status("NewPipe: ${if (plan.audioOnly) "audio" else "video"} download…", percent)
            }
            if (plan.audioOnly) {
                status("NewPipe → FFmpeg: preparing audio…", 92)
                processor.audio(main, output, kind, plan.main.extension == kind.extension)
            } else if (plan.audio != null) {
                val audio = File(folder, "track-audio.bin")
                http.download(plan.audio.url, audio, headers, stopped) { done, total ->
                    val percent = total?.takeIf { it > 0 }?.let { 70 + ((done.toDouble() / it) * 20).toInt() } ?: 70
                    status("NewPipe: audio download…", percent)
                }
                status("NewPipe → FFmpeg: video + audio merge…", 92)
                val merged = processor.merge(main, audio, output, plan.main.extension, plan.audio.extension)
                if (merged != output) status("NewPipe: merged into Matroska (${merged.name}) because those codecs are not MP4-native.", 95)
                produced = merged
            }
            val finalFile = produced ?: output
            check(finalFile.isFile && finalFile.length() > 0) { "No completed NewPipe output file was found." }
            val container = MediaContainerReader.sniff(finalFile)
            check(container.playableMedia) { "The downloaded data is ${container.label}, not playable media; nothing was reported as a finished file." }
            produced = finalFile
        }
}
