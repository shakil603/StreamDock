package org.streamdock.app

import java.io.File

/** What a file actually is, read from its first bytes instead of trusting its name.
 *
 *  This exists because a video that downloads correctly can still be unplayable: the bytes may be
 *  WebM/Matroska while the file is named `.mp4` (or an HTML error page saved as a video). Players then
 *  report a corrupt file. StreamDock now checks the container before it claims success. */
enum class MediaContainer(val label: String, val extension: String, val playableMedia: Boolean) {
    MP4("MP4 / QuickTime", "mp4", true),
    MATROSKA("Matroska / WebM", "mkv", true),
    MPEG_TS("MPEG transport stream", "ts", true),
    FLV("Flash video", "flv", true),
    MP3("MP3 audio", "mp3", true),
    M4A("M4A / AAC audio", "m4a", true),
    OGG("Ogg audio", "ogg", true),
    FLAC("FLAC audio", "flac", true),
    WAV("WAV audio", "wav", true),
    JPEG("JPEG image", "jpg", true),
    PNG("PNG image", "png", true),
    ZIP("ZIP archive", "zip", false),
    HTML("web page (not media)", "html", false),
    PLAYLIST("HLS playlist (not a finished file)", "m3u8", false),
    UNKNOWN("unrecognized data", "bin", false)
}

object MediaContainerReader {
    /** Sniff the container from up to 64 leading bytes. Never throws; unknown data returns UNKNOWN. */
    fun sniff(head: ByteArray): MediaContainer {
        if (head.size < 4) return MediaContainer.UNKNOWN
        fun ascii(at: Int, length: Int): String =
            if (at + length <= head.size) String(head, at, length, Charsets.US_ASCII) else ""
        val b0 = head[0].toInt() and 0xff
        val b1 = head[1].toInt() and 0xff
        val b2 = head[2].toInt() and 0xff
        val b3 = head[3].toInt() and 0xff
        // ISO-BMFF (MP4/MOV/M4A): size + 'ftyp' (or a leading 'wide'/'free' box) — the brand decides audio vs video.
        val brand = when {
            ascii(4, 4) == "ftyp" -> ascii(8, 4)
            ascii(4, 4) in setOf("moov", "mdat", "free") -> "mp4"
            else -> ""
        }
        if (brand.isNotEmpty()) {
            // Only genuinely audio-only brands split off from MP4. Generic brands (isom/iso2/mp42/dash) appear
            // in both audio-only and video files, so they must stay MP4 — a test caught that mistake.
            val audioOnlyBrands = setOf("M4A ", "M4B ", "M4P ", "F4A ", "mp42a")
            return if (brand in audioOnlyBrands) MediaContainer.M4A else MediaContainer.MP4
        }
        // EBML (Matroska / WebM)
        if (b0 == 0x1a && b1 == 0x45 && b2 == 0xdf && b3 == 0xa3) return MediaContainer.MATROSKA
        // MPEG transport stream: 188-byte packets start with 0x47
        if (b0 == 0x47 && (head.size < 189 || (head[188].toInt() and 0xff) == 0x47)) return MediaContainer.MPEG_TS
        if (ascii(0, 3) == "FLV") return MediaContainer.FLV
        if (ascii(0, 3) == "ID3") return MediaContainer.MP3
        if (b0 == 0xff && (b1 and 0xe0) == 0xe0 && (b1 and 0x18) != 0x08) return MediaContainer.MP3 // MPEG audio frame sync
        if (ascii(0, 4) == "fLaC") return MediaContainer.FLAC
        if (ascii(0, 4) == "OggS") return MediaContainer.OGG
        if (head.size >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WAVE") return MediaContainer.WAV
        if (b0 == 0xff && b1 == 0xd8 && b2 == 0xff) return MediaContainer.JPEG
        if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4e && b3 == 0x47) return MediaContainer.PNG
        if (b0 == 0x50 && b1 == 0x4b && (b2 == 0x03 || b2 == 0x05 || b2 == 0x07)) return MediaContainer.ZIP
        val text = if (head.size >= 16) String(head, 0, 16, Charsets.ISO_8859_1).trimStart().lowercase() else ""
        if (text.startsWith("#extm3u")) return MediaContainer.PLAYLIST
        if (text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<?xml")) return MediaContainer.HTML
        return MediaContainer.UNKNOWN
    }

    fun sniff(file: File, probe: Int = 64): MediaContainer {
        if (!file.isFile || file.length() == 0L) return MediaContainer.UNKNOWN
        return runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(probe)
                var read = 0
                while (read < probe) {
                    val count = input.read(head, read, probe - read)
                    if (count < 0) break
                    read += count
                }
                sniff(head.copyOf(read))
            }
        }.getOrDefault(MediaContainer.UNKNOWN)
    }
}
