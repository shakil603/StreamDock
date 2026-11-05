package org.streamdock.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The corruption guard: a finished file is only called a finished file when its bytes agree. */
class MediaContainerTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun text(value: String, pad: Int = 0) = (value + " ".repeat(pad)).toByteArray(Charsets.US_ASCII)

    @Test fun `mp4 and m4a are told apart from ftyp brands`() {
        val mp4 = text("0000ftyp") + text("mp42") + ByteArray(40)
        assertEquals(MediaContainer.MP4, MediaContainerReader.sniff(mp4))
        val m4a = text("0000ftyp") + text("M4A ") + ByteArray(40)
        assertEquals(MediaContainer.M4A, MediaContainerReader.sniff(m4a))
    }

    @Test fun `matroska webm is detected so it is never written as mp4`() {
        assertEquals(MediaContainer.MATROSKA, MediaContainerReader.sniff(bytes(0x1a, 0x45, 0xdf, 0xa3) + ByteArray(60)))
    }

    @Test fun `transport stream needs two packet sync bytes`() {
        val ts = ByteArray(190) { 0 }
        ts[0] = 0x47; ts[188] = 0x47
        assertEquals(MediaContainer.MPEG_TS, MediaContainerReader.sniff(ts))
        val stray = ByteArray(190) { 0 }
        stray[0] = 0x47
        assertEquals(MediaContainer.UNKNOWN, MediaContainerReader.sniff(stray))
    }

    @Test fun `audio containers are recognised`() {
        assertEquals(MediaContainer.MP3, MediaContainerReader.sniff(text("ID3") + ByteArray(61)))
        assertEquals(MediaContainer.FLAC, MediaContainerReader.sniff(text("fLaC") + ByteArray(60)))
        assertEquals(MediaContainer.OGG, MediaContainerReader.sniff(text("OggS") + ByteArray(60)))
        assertEquals(MediaContainer.WAV, MediaContainerReader.sniff(text("RIFF0000WAVE") + ByteArray(52)))
    }

    @Test fun `non-media answers are refused instead of reported as a finished video`() {
        assertFalse(MediaContainerReader.sniff(text("<html><body>error") + ByteArray(48)).playableMedia)
        assertFalse(MediaContainerReader.sniff(text("<!DOCTYPE html>") + ByteArray(51)).playableMedia)
        assertEquals(MediaContainer.PLAYLIST, MediaContainerReader.sniff(text("#EXTM3U\n#EXT-X") + ByteArray(53)))
        assertFalse(MediaContainerReader.sniff(text("PK\u0003\u0004") + ByteArray(60)).playableMedia)
    }

    @Test fun `sniffing a file reads only its head and handles empty files`() {
        val file = File.createTempFile("streamdock-container-", ".bin")
        try {
            file.writeBytes(text("0000ftyp") + text("isom") + ByteArray(4096))
            assertEquals(MediaContainer.MP4, MediaContainerReader.sniff(file))
            file.writeBytes(ByteArray(0))
            assertEquals(MediaContainer.UNKNOWN, MediaContainerReader.sniff(file))
        } finally { file.delete() }
    }

    @Test fun `every playable container exposes a usable extension`() {
        MediaContainer.entries.filter { it.playableMedia }.forEach {
            assertTrue("extension missing for $it", it.extension.isNotBlank())
            assertFalse("extension must not contain dots for $it", it.extension.contains('.'))
        }
    }
}
