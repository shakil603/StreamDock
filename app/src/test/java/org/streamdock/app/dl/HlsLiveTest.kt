package org.streamdock.app.dl

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Optional live check, run only when -PhlsStream=<playlist url> is passed to Gradle.
 *  It proves the stream path end to end on real bytes: playlist → segments appended to one file. */
class HlsLiveTest {
    @Test fun realStreamDownload() {
        val url = System.getProperty("streamdock.hlsStream", "").orEmpty().trim()
        assumeTrue("Optional live HLS check (pass -PhlsStream=<playlist>)", url.isNotBlank())
        val item = DownloadItem(id = "live", url = url, name = "live.ts", agent = "StreamDock-LiveTest")
        DownloadEngine.probeHls(item)
        println("LIVE hls: mode=${item.mode} segments=${item.hlsSegments.size} duration=${item.hlsDuration}")
        item.hlsSegments.subList(minOf(3, item.hlsSegments.size), item.hlsSegments.size).clear()
        val file = File.createTempFile("streamdock-live-", ".ts")
        try {
            DownloadEngine.runHls(item, file, { false }) { }
            println("LIVE hls saved: bytes=${file.length()} segments=${item.hlsDone} complete=${item.complete()} progress=${item.progressText()}")
            assertTrue("segments must produce real bytes", file.length() > 0)
            assertTrue("every fetched segment must be recorded", item.hlsDone == item.hlsSegments.size)
        } finally { file.delete() }
    }
}
