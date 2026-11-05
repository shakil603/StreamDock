package org.streamdock.app

import org.junit.Assert.*
import org.junit.Test

/** The browser media list must stay observation-only and predictable. No network is used here. */
class MediaSnifferTest {
    @Test fun playableMediaIsRecognised() {
        assertEquals(MediaSniffer.Kind.VIDEO, MediaSniffer.classify("https://cdn.example.com/video.mp4", null, null))
        assertEquals(MediaSniffer.Kind.VIDEO, MediaSniffer.classify("https://cdn.example.com/v/clip.webm?token=abc", null, null))
        assertEquals(MediaSniffer.Kind.AUDIO, MediaSniffer.classify("https://cdn.example.com/song.m4a", null, null))
        assertEquals(MediaSniffer.Kind.STREAM, MediaSniffer.classify("https://cdn.example.com/hls/master.m3u8", null, null))
        assertEquals(MediaSniffer.Kind.STREAM, MediaSniffer.classify("https://cdn.example.com/dash/manifest.mpd", null, null))
    }
    @Test fun mimeAndAcceptHintsAreUsedWhenThePathHasNoExtension() {
        assertEquals(MediaSniffer.Kind.VIDEO, MediaSniffer.classify("https://v.example.com/play?id=9", "video/mp4", null))
        assertEquals(MediaSniffer.Kind.AUDIO, MediaSniffer.classify("https://a.example.com/stream", "audio/mpeg; charset=binary", null))
        assertEquals(MediaSniffer.Kind.STREAM, MediaSniffer.classify("https://s.example.com/live", "application/vnd.apple.mpegurl", null))
        assertEquals(MediaSniffer.Kind.VIDEO, MediaSniffer.classify("https://x.example.com/media", null, "video/webm,*/*;q=0.8"))
    }
    @Test fun pagesScriptsAndImagesAreNeverOfferedAsMedia() {
        for (url in listOf(
            "https://site.example.com/index.html",
            "https://site.example.com/app.js",
            "https://site.example.com/logo.png",
            "https://site.example.com/thumb/cover.jpg",
            "https://site.example.com/data.json",
            "https://site.example.com/favicon.ico",
            "https://site.example.com/poster.webp",
        )) assertNull(url, MediaSniffer.classify(url, null, null))
        assertNull(MediaSniffer.classify("http://insecure.example.com/video.mp4", null, null))
        assertNull(MediaSniffer.classify("data:video/mp4;base64,AAAA", null, null))
        assertNull(MediaSniffer.classify("blob:https://site.example.com/9b1c", null, null))
    }
    @Test fun thumbnailAndPosterWordsAreRejectedEvenWithMediaExtensions() {
        assertNull(MediaSniffer.classify("https://cdn.example.com/thumbnail.mp4", null, null))
        assertNull(MediaSniffer.classify("https://cdn.example.com/placeholder-video/movie.mp4", null, null))
    }
    @Test fun listIsDeduplicatedCappedAndObservational() {
        val sniffer = MediaSniffer(limit = 3)
        assertTrue(sniffer.note("https://cdn.example.com/1.mp4", null, null, "https://site.example.com/page"))
        assertFalse("Duplicate URLs must not be added twice", sniffer.note("https://cdn.example.com/1.mp4", null, null, "https://site.example.com/page"))
        assertFalse("Non-media must never enter the list", sniffer.note("https://cdn.example.com/logo.png", null, null, "https://site.example.com/page"))
        sniffer.note("https://cdn.example.com/2.mp4", null, null, "https://site.example.com/page")
        sniffer.note("https://cdn.example.com/3.m3u8", null, null, "https://site.example.com/page")
        sniffer.note("https://cdn.example.com/4.mp4", null, null, "https://site.example.com/page")
        assertEquals(3, sniffer.size())
        assertEquals("https://cdn.example.com/4.mp4", sniffer.list().first().url)
        assertTrue("The oldest entry is dropped first", sniffer.list().none { it.url.endsWith("/1.mp4") })
        sniffer.clear(); assertEquals(0, sniffer.size())
    }
    @Test fun recordedEntriesKeepThePageTheyCameFrom() {
        val sniffer = MediaSniffer()
        sniffer.note("https://cdn.example.com/movie.mp4", null, null, "https://watch.example.com/title/1")
        val found = sniffer.list().single()
        assertEquals("https://cdn.example.com/movie.mp4", found.url)
        assertEquals("https://watch.example.com/title/1", found.page)
        assertEquals(MediaSniffer.Kind.VIDEO, found.kind)
    }
    @Test fun fileNameIsSafeForDisplay() {
        assertEquals("movie.mp4", MediaSniffer.fileName("https://cdn.example.com/path/movie.mp4?token=1#t=10"))
        assertEquals("captured-media", MediaSniffer.fileName("https://cdn.example.com/"))
    }
}
