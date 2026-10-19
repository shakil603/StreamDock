package org.streamdock.app

import org.junit.Assert.*
import org.junit.Test

class PipeTrackSelectorTest {
    private fun video(h: Int, only: Boolean = false, ext: String = "mp4") = PipeTrack("https://example.org/$h/$only", ext, h, videoOnly = only)
    private val m4a = PipeTrack("https://example.org/audio", "m4a", bitrate = 128)
    @Test fun highestUnderCapSelected() {
        assertEquals(720, PipeTrackSelector.select(OutputKind.MP4, 720, listOf(video(1080), video(720), video(360)), listOf(m4a)).main.height)
    }
    @Test fun adaptiveVideoGetsSeparateAudio() {
        val result = PipeTrackSelector.select(OutputKind.MP4, 0, listOf(video(1080, true), video(360)), listOf(m4a))
        assertEquals(1080, result.main.height); assertEquals(m4a, result.audio)
    }
    @Test fun sameResolutionPrefersMuxed() {
        val result = PipeTrackSelector.select(OutputKind.MP4, 0, listOf(video(720, true), video(720)), listOf(m4a))
        assertFalse(result.main.videoOnly); assertNull(result.audio)
    }
    @Test fun noAudioNeverProducesSilentVideo() {
        val result = PipeTrackSelector.select(OutputKind.MP4, 0, listOf(video(1080, true), video(360)), emptyList())
        assertEquals(360, result.main.height)
    }
    @Test(expected = IllegalStateException::class) fun wrongContainerFails() {
        PipeTrackSelector.select(OutputKind.WEBM, 0, listOf(video(720)), listOf(m4a))
    }
    @Test(expected = IllegalStateException::class) fun unknownHeightCannotViolateCap() {
        PipeTrackSelector.select(OutputKind.MP4, 720, listOf(video(0)), listOf(m4a))
    }
    @Test fun m4aAvoidsUnnecessaryTranscode() {
        val result = PipeTrackSelector.select(OutputKind.M4A, 0, emptyList(), listOf(m4a, PipeTrack("https://example.org/opus", "webm", bitrate = 256)))
        assertEquals(m4a, result.main); assertTrue(result.audioOnly)
    }
    @Test fun audioCanBeExtractedFromMuxedVideo() {
        val result = PipeTrackSelector.select(OutputKind.MP3, 0, listOf(video(360)), emptyList())
        assertTrue(result.audioOnly); assertEquals(360, result.main.height)
    }
    @Test(expected = IllegalStateException::class) fun onlySilentVideoCannotMakeAudio() {
        PipeTrackSelector.select(OutputKind.MP3, 0, listOf(video(1080, true)), emptyList())
    }
}
