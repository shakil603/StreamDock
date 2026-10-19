package org.streamdock.app.dl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** HLS parsing decides whether a captured browser stream can be saved at all, so it is pinned here. */
class HlsPlaylistTest {
    private val media = """
        #EXTM3U
        #EXT-X-VERSION:3
        #EXT-X-TARGETDURATION:6
        #EXT-X-MAP:URI="init.mp4"
        #EXTINF:6.0,
        seg-1.ts
        #EXTINF:6.0,
        seg-2.ts
        #EXTINF:3.5,
        https://cdn.example.com/seg-3.ts
    """.trimIndent()

    private val master = """
        #EXTM3U
        #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
        360/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
        720/index.m3u8
    """.trimIndent()

    @Test fun `detects playlists and masters`() {
        assertTrue(HlsPlaylist.isPlaylist(media))
        assertTrue(HlsPlaylist.isPlaylist("\n#EXTM3U\n"))
        assertTrue(HlsPlaylist.isMaster(master))
        assertFalse(HlsPlaylist.isMaster(media))
    }

    @Test fun `best variant wins on bandwidth`() {
        assertEquals("720/index.m3u8", HlsPlaylist.bestVariant(master))
        assertNull(HlsPlaylist.bestVariant(media))
    }

    @Test fun `media playlist keeps order, init segment and duration`() {
        val parsed = HlsPlaylist.media(media)
        assertEquals("init.mp4", parsed.initUri)
        assertEquals(listOf("seg-1.ts", "seg-2.ts", "https://cdn.example.com/seg-3.ts"), parsed.segments)
        assertEquals(15.5, parsed.durationSeconds, 0.001)
        assertFalse(parsed.encrypted)
    }

    @Test fun `encrypted playlists are recognised so they can be refused honestly`() {
        assertTrue(HlsPlaylist.media("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"k\"\nseg.ts").encrypted)
        assertFalse(HlsPlaylist.media("#EXTM3U\n#EXT-X-KEY:METHOD=NONE\nseg.ts").encrypted)
    }

    @Test fun `relative segment urls resolve against the playlist url`() {
        assertEquals("https://cdn.example.com/a/b/seg-1.ts", HlsPlaylist.resolve("https://cdn.example.com/a/b/index.m3u8", "seg-1.ts"))
        assertEquals("https://cdn.example.com/a/seg-1.ts", HlsPlaylist.resolve("https://cdn.example.com/a/b/index.m3u8", "../seg-1.ts"))
        assertEquals("https://other.example.com/x.ts", HlsPlaylist.resolve("https://cdn.example.com/a/b/index.m3u8", "https://other.example.com/x.ts"))
        assertEquals("https://cdn.example.com/x.ts", HlsPlaylist.resolve("https://cdn.example.com/a/b/index.m3u8", "http://cdn.example.com/x.ts"))
    }

    @Test fun `resume keeps only whole segments`() {
        val sizes = listOf(100L, 200L, 300L)
        assertEquals(0L, HlsPlaylist.resumeLength(sizes, 0))
        assertEquals(100L, HlsPlaylist.resumeLength(sizes, 1))
        assertEquals(300L, HlsPlaylist.resumeLength(sizes, 2))
        assertEquals(600L, HlsPlaylist.resumeLength(sizes, 9))
    }
}
