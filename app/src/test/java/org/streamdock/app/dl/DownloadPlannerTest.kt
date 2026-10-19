package org.streamdock.app.dl

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The arithmetic behind parallel + resumable downloads is tested offline; no network is used. */
class DownloadPlannerTest {
    private val MB = 1024L * 1024

    @Test fun smallFilesOrUnknownSizeStayOnOneConnection() {
        assertEquals(1, DownloadPlanner.plan(3 * MB, true, "\"etag\"", 8).size)
        assertEquals(1, DownloadPlanner.plan(-1, false, null, 8).size)
        assertEquals(listOf(Segment(0, 3 * MB - 1)), DownloadPlanner.plan(3 * MB, true, "\"e\"", 4))
        assertEquals(listOf(Segment(0, -1)), DownloadPlanner.plan(-1, true, "\"e\"", 4))
    }
    @Test fun multiConnectionNeedsRangeSupportAndAValidator() {
        assertEquals(1, DownloadPlanner.plan(100 * MB, false, "\"e\"", 8).size)   // server ignores ranges
        assertEquals(1, DownloadPlanner.plan(100 * MB, true, null, 8).size)      // no validator → resume unsafe
        assertEquals(1, DownloadPlanner.plan(100 * MB, true, "  ", 8).size)
        assertEquals(4, DownloadPlanner.plan(100 * MB, true, "\"e\"", 4).size)
    }
    @Test fun segmentsTileTheWholeFileWithoutGapsOrOverlap() {
        for (connections in listOf(2, 3, 4, 6, 8, 16)) {
            val total = 97 * MB + 1234
            val segments = DownloadPlanner.plan(total, true, "\"e\"", connections)
            assertEquals("connections = $connections", 0L, segments.first().start)
            assertEquals("last segment must end at EOF ($connections)", total - 1, segments.last().end)
            segments.zipWithNext().forEach { (a, b) ->
                assertEquals("no gap/overlap between ${a} and ${b}", a.end + 1, b.start)
                assertTrue(a.length > 0)
            }
            assertEquals(total, DownloadPlanner.expectedSize(segments))
        }
    }
    @Test fun resumeRangeStartsAfterTheBytesAlreadyWritten() {
        val segment = Segment(10 * MB, 20 * MB - 1, done = 3 * MB)
        assertEquals("bytes=${13 * MB}-${20 * MB - 1}", DownloadPlanner.rangeHeader(segment))
        val openEnded = Segment(0, -1, done = 512)
        assertEquals("bytes=512-", DownloadPlanner.rangeHeader(openEnded))
        assertEquals(7 * MB, segment.remaining)
        segment.done = segment.length
        assertEquals(0L, segment.remaining)
    }
    @Test fun onlyExactMatchingRangesAreAccepted() {
        assertTrue(DownloadPlanner.rangeMatches("bytes 4096-8191/16384", 4096, 16384))
        assertFalse("wrong start", DownloadPlanner.rangeMatches("bytes 0-8191/16384", 4096, 16384))
        assertFalse("size mismatch", DownloadPlanner.rangeMatches("bytes 4096-8191/9999", 4096, 16384))
        assertFalse("garbage", DownloadPlanner.rangeMatches("garbage", 0, 100))
        assertFalse("null", DownloadPlanner.rangeMatches(null, 0, 100))
        assertTrue("unknown size is allowed", DownloadPlanner.rangeMatches("bytes 0-1023/*", 0, -1))
    }
    @Test fun validatorChangesAreDetectedAndNeverInvented() {
        assertFalse(DownloadPlanner.validatorChanged("\"abc\"", null))
        assertFalse(DownloadPlanner.validatorChanged("\"abc\"", "\"abc\""))
        assertTrue(DownloadPlanner.validatorChanged("\"def\"", "\"abc\""))
        assertTrue("missing validator after knowing one is unsafe", DownloadPlanner.validatorChanged(null, "\"abc\""))
    }
    @Test fun downloadItemSurvivesAJsonRoundTrip() {
        val item = DownloadItem(url = "https://cdn.example.com/a.mp4", referer = "https://site.example.com/p", agent = "UA",
            name = "a.mp4", mime = "video/mp4", total = 9 * MB, validator = "\"etag1\"", acceptRanges = true,
            segments = DownloadPlanner.plan(9 * MB, true, "\"etag1\"", 2).toMutableList(), destUri = "content://dest/1", status = DlStatus.PAUSED)
        item.segments[0].done = 1 * MB
        val copy = DownloadItem.from(JSONObject(item.toJson().toString()))
        assertEquals(item.id, copy.id); assertEquals(item.url, copy.url); assertEquals(item.referer, copy.referer)
        assertEquals(item.total, copy.total); assertEquals(item.validator, copy.validator); assertEquals(item.destUri, copy.destUri)
        assertEquals(item.segments.size, copy.segments.size)
        assertEquals(1 * MB, copy.segments[0].done)
        assertEquals(item.downloaded, copy.downloaded)
        assertEquals(DlStatus.PAUSED, copy.status)
    }
    @Test fun aRunningItemComesBackQueuedAfterRestartAndKeepsItsBytes() {
        val item = DownloadItem(url = "https://cdn.example.com/b.bin", name = "b.bin", total = 12 * MB, status = DlStatus.RUNNING,
            segments = mutableListOf(Segment(0, 6 * MB - 1, 2 * MB), Segment(6 * MB, 12 * MB - 1, 0)))
        val restored = DownloadItem.from(JSONObject(item.toJson().toString()))
        assertEquals(DlStatus.WAITING, restored.status)
        assertEquals(2 * MB, restored.downloaded)
        assertEquals(16, restored.percent())
    }
    @Test fun progressTextNeverShowsFakeCompletion() {
        val item = DownloadItem(url = "https://c/x", name = "x", total = 10 * MB, segments = mutableListOf(Segment(0, 10 * MB - 1, 5 * MB)))
        assertEquals(50, item.percent()); assertFalse(item.complete())
        assertTrue(item.progressText().contains("50%"))
        item.segments[0].done = 10 * MB
        assertTrue(item.complete()); assertEquals(100, item.percent())
    }

    @Test fun `hls items report segment progress and survive a round trip`() {
        val item = DownloadItem(id = "h1", url = "https://cdn.example.com/index.m3u8", referer = "https://page.example.com/", name = "clip.ts", agent = "ua")
        item.mode = DlMode.HLS
        item.hlsSegments += listOf("a.ts", "b.ts", "c.ts", "d.ts")
        item.hlsSizes += listOf(10L, 20L)
        item.hlsDone = 2
        item.hlsDuration = 42.0
        assertEquals(50, item.percent())
        assertTrue(item.progressText().contains("2/4 segments"))
        assertFalse(item.complete())
        val restored = DownloadItem.from(JSONObject(item.toJson().toString()))
        assertEquals(DlMode.HLS, restored.mode)
        assertEquals(2, restored.hlsDone)
        assertEquals(listOf("a.ts", "b.ts", "c.ts", "d.ts"), restored.hlsSegments)
        assertEquals(30L, restored.downloaded) // 10 + 20 bytes fetched so far
        restored.hlsDone = 4
        assertTrue(restored.complete())
    }
}
