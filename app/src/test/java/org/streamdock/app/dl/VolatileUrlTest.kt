package org.streamdock.app.dl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Facebook-style windowed CDN URLs must never be fetched with parallel ranges: the assembled file
 *  would be the right length and completely scrambled. */
class VolatileUrlTest {
    @Test fun `facebook byte-window urls are single connection only`() {
        assertTrue(DownloadPlanner.volatileChunkUrl("https://video.xx.fbcdn.net/v/t42/video.mp4?bytestart=0&byteend=131071&oh=abc&oe=def"))
        assertTrue(DownloadPlanner.volatileChunkUrl("https://scontent.xx.fbcdn.net/v/video?sig=1"))
        assertTrue(DownloadPlanner.volatileChunkUrl("https://cdn.example.com/media/chunk_0001.m4s"))
        assertTrue(DownloadPlanner.volatileChunkUrl("https://cdn.example.com/a/segment-12.ts"))
    }

    @Test fun `ordinary static files keep multi-connection`() {
        assertFalse(DownloadPlanner.volatileChunkUrl("https://cdn.example.com/movies/feature.mp4"))
        assertFalse(DownloadPlanner.volatileChunkUrl("https://cdn.example.com/audio/track.m4a?token=short"))
        assertFalse(DownloadPlanner.volatileChunkUrl("https://s3.example.com/bucket/segments-index.json"))
    }
}
