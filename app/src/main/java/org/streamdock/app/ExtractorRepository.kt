package org.streamdock.app

import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException

class ExtractorRepository(private val app: DockApp) {
    fun extract(raw: String, directYtDlp: Boolean = false): MediaDetails {
        val url = InputPolicy.validate(raw)
        if (directYtDlp) {
            Diagnostics.stage("Analysis: user selected yt-dlp directly")
            return fallback(url, "Analyzed directly with yt-dlp.")
        }
        val primary = try {
            Diagnostics.stage("Analysis: NewPipe initializing / extracting")
            EngineBoundary.call("NewPipe") { extractWithNewPipe(url) }
        } catch (e: CancellationException) { throw e
        } catch (e: InterruptedException) { throw e
        } catch (e: ReCaptchaException) { throw e
        } catch (e: ContentNotAvailableException) { throw e
        } catch (e: Exception) {
            Diagnostics.error("NewPipe failed; switching engine", e)
            null
        }
        if (primary != null && primary.previews.isNotEmpty()) {
            Diagnostics.stage("Analysis: NewPipe success")
            return primary
        }
        // Run fallback outside the primary try/catch: never invoke yt-dlp twice
        // when the fallback itself fails.
        return fallback(url, "No NewPipe streams were found; yt-dlp was used.")
    }
    private fun extractWithNewPipe(url: String): MediaDetails {
        app.initPipe()
        val service = NewPipe.getServiceByUrl(url)
        val info = StreamInfo.getInfo(service, url)
        val audio = info.audioStreams.filter { it.isUrl }
        val previews = info.videoStreams.filter { it.isUrl }.map {
            PreviewStream("${it.resolution} · ${it.format?.name ?: "Video"}", it.content)
        } + info.videoOnlyStreams.filter { it.isUrl && audio.isNotEmpty() }.map {
            PreviewStream("${it.resolution} · adaptive", it.content, audio.first().content)
        } + audio.map {
            PreviewStream("Audio · ${it.averageBitrate} kbps", it.content)
        }
        return MediaDetails(url, info.name, info.uploaderName, info.duration,
            info.thumbnails.firstOrNull()?.url, "NewPipe", previews)
    }
    private fun fallback(url: String, notice: String): MediaDetails = EngineBoundary.call("yt-dlp") {
        Diagnostics.stage("Analysis: yt-dlp native initialization")
        app.initNative()
        Diagnostics.stage("Analysis: yt-dlp extracting")
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--skip-download")
        request.addOption("--dump-single-json")
        request.addOption("--socket-timeout", "25")
        request.addOption("--retries", "2")
        request.addOption("-f", "best/bestvideo+bestaudio")
        val result = YoutubeDL.getInstance().execute(request)
        val json = JSONObject(result.out)
        fun headers(obj: JSONObject): Map<String, String> {
            val h = obj.optJSONObject("http_headers") ?: json.optJSONObject("http_headers") ?: return emptyMap()
            return h.keys().asSequence().associateWith { h.getString(it) }
        }
        val previews = mutableListOf<PreviewStream>()
        val direct = json.optString("url")
        if (direct.startsWith("https://")) previews += PreviewStream("Best available", direct, headers = headers(json))
        val requested = json.optJSONArray("requested_formats")
        if (previews.isEmpty() && requested != null) {
            val parts = (0 until requested.length()).map { requested.getJSONObject(it) }
            val video = parts.firstOrNull { it.optString("vcodec", "none") != "none" }
            val audio = parts.firstOrNull { it.optString("vcodec", "none") == "none" }
            if (video != null) previews += PreviewStream("Best adaptive", video.getString("url"),
                audio?.optString("url"), headers(video), audio?.let { headers(it) } ?: emptyMap())
        }
        val formats = json.optJSONArray("formats")
        if (formats != null) for (i in 0 until formats.length()) {
            val f = formats.getJSONObject(i)
            if (f.optBoolean("has_drm") || !f.optString("url").startsWith("https://")) continue
            val hasVideo = f.optString("vcodec", "none") != "none"
            val hasAudio = f.optString("acodec", "none") != "none"
            if (hasAudio) previews += PreviewStream(
                if (hasVideo) "${f.optInt("height")}p · ${f.optString("ext")}" else "Audio · ${f.optString("ext")}",
                f.getString("url"), headers = headers(f))
        }
        Diagnostics.stage("Analysis: yt-dlp success")
        MediaDetails(url, json.optString("title", "Untitled"), json.optString("uploader", "Unknown"),
            json.optDouble("duration", 0.0).toLong(), json.optString("thumbnail"), "yt-dlp", previews.distinctBy { it.url }, notice)
    }
}
