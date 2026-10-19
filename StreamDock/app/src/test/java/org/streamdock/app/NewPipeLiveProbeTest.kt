package org.streamdock.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.util.concurrent.TimeUnit
import java.io.File

/** Explicit opt-in only. JVM network probe, NOT an Android/native/end-to-end test.
 * Example: ./gradlew testDebugUnitTest --tests '*NewPipeLiveProbeTest' -PliveUrl=https://youtu.be/ID */
class NewPipeLiveProbeTest {
    @Test fun extractAndFetchStreamPrefix() {
        val raw = System.getProperty("streamdock.liveUrl", "").orEmpty()
        assumeTrue("No live URL provided; normal builds never contact a media website", raw.isNotBlank())
        val url = InputPolicy.validate(raw)
        NewPipe.init(PipeDownloader())
        try {
            val info = StreamInfo.getInfo(NewPipe.getServiceByUrl(url), url)
            val streams = info.videoStreams + info.videoOnlyStreams + info.audioStreams
            println("NewPipe metadata resolved; ${streams.size} stream entries")
            val direct = streams.firstOrNull { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.content.startsWith("https://") }
                ?: error("No progressive HTTPS stream")
            val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followSslRedirects(false)
                .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
            val request = Request.Builder().url(direct.content).header("Range", "bytes=0-65535")
                .header("User-Agent", PipeDownloader.USER_AGENT).header("Referer", url).header("Accept-Encoding", "identity").build()
            client.newCall(request).execute().use { response ->
                println("Prefix request HTTP ${response.code}; type=${response.header("Content-Type")}")
                assertTrue("Server rejected stream", response.code == 200 || response.code == 206)
                val bytes = ByteArray(64)
                assertTrue("Empty stream", response.body!!.byteStream().read(bytes) > 0)
                println("Received stream prefix. Content-Range: ${response.header("Content-Range")}")
                if (System.getProperty("streamdock.liveDownload") == "true") {
                    val total = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                    check(total != null && total in 1..(100L * 1024 * 1024)) { "Full probe limited to known lengths <=100 MiB" }
                    check(info.videoStreams.contains(direct)) { "Full probe requires a muxed video stream; native merging is not available on this JVM" }
                    response.close()
                    val output = File("build/live-probe/media.mp4").apply { parentFile?.mkdirs() }
                    DirectHttpDownloader(client).download(direct.content, output,
                        mapOf("User-Agent" to PipeDownloader.USER_AGENT, "Referer" to url), { false }) { _, _ -> }
                    assertEquals(total, output.length())
                    println("FULL progressive video transfer complete via DirectHttpDownloader: ${output.length()} bytes; output=${output.absolutePath}")
                }
                println("Android UI / native FFmpeg merging not tested.")
            }
        } catch (e: Exception) {
            println("Live probe failed: ${e.javaClass.simpleName}: ${DiagnosticText.redact(e.message.orEmpty())}")
            throw e
        }
    }
}
