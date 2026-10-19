package org.streamdock.app

import java.io.File

/** FFmpeg consumes downloaded local files only; it never extracts a website URL. */
class NativeMediaProcessor(private val app: DockApp, private val stopped: () -> Boolean) {
    @Volatile private var process: Process? = null
    private var prepared = false

    /** Fail before downloading/copying large files when the native runtime cannot start. */
    fun prepare() = EngineBoundary.call("FFmpeg preflight") {
        if (!prepared) {
            if (stopped()) throw InterruptedException("Cancelled")
            Diagnostics.stage("FFmpeg preflight: extracting shared runtime and FFmpeg archives")
            app.initFfmpeg()
            val paths = app.nativeRuntimePaths()
            paths.verify()
            Diagnostics.stage("FFmpeg preflight: libc++_shared.so present; testing executable")
            val output = execute(listOf("-version"))
            Diagnostics.stage("FFmpeg preflight passed: ${output.lineSequence().firstOrNull().orEmpty().take(180)}")
            prepared = true
        }
    }
    fun cancel() { process?.destroy() }
    fun audio(source: File, output: File, kind: OutputKind, copyCodec: Boolean = false) {
        require(kind == OutputKind.MP3 || kind == OutputKind.M4A)
        val args = mutableListOf("-i", source.absolutePath, "-map", "0:a:0", "-vn")
        if (copyCodec) args.addAll(listOf("-c:a", "copy"))
        else args.addAll(listOf("-c:a", if (kind == OutputKind.MP3) "libmp3lame" else "aac", "-b:a", "192k"))
        if (kind == OutputKind.M4A) args.addAll(listOf("-movflags", "+faststart"))
        args.add(output.absolutePath)
        run(args)
    }
    /** Merge DASH video + audio without re-encoding — but into a container that can actually hold both
     *  codecs. MP4 only accepts H.264/HEVC + AAC/MP3 in practice; VP9/AV1/Opus belong in Matroska, and
     *  copying them into an .mp4 is exactly what makes a "downloaded fine" video unplayable. */
    fun merge(video: File, audio: File, output: File, videoShort: String, audioShort: String): File {
        val mp4Native = videoShort == "mp4" && audioShort == "m4a"
        val target = if (mp4Native || audioShort == "mp3") output
            else File(output.parentFile, output.nameWithoutExtension + ".mkv")
        val args = mutableListOf("-i", video.absolutePath, "-i", audio.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-c", "copy")
        if (target.extension == "mp4") args.addAll(listOf("-movflags", "+faststart"))
        args.add(target.absolutePath)
        run(args)
        return target
    }
    private fun run(arguments: List<String>) = EngineBoundary.call("FFmpeg") {
        prepare()
        execute(arguments)
    }
    private fun execute(arguments: List<String>): String {
        if (stopped()) throw InterruptedException("Cancelled")
        val paths = app.nativeRuntimePaths()
        val builder = ProcessBuilder(listOf(paths.executable.absolutePath, "-nostdin", "-y", "-hide_banner", "-loglevel", "error") + arguments)
            .redirectErrorStream(true)
        builder.environment().putAll(paths.environment())
        val current = builder.start().also { process = it }
        val log = StringBuilder()
        try {
            if (stopped()) { current.destroy(); throw InterruptedException("Cancelled") }
            current.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(2048)
                while (true) {
                    if (stopped()) { current.destroy(); throw InterruptedException("Cancelled") }
                    val count = reader.read(buffer)
                    if (count < 0) break
                    log.append(buffer, 0, count)
                    if (log.length > 8192) log.delete(0, log.length - 8192)
                }
            }
            check(current.waitFor() == 0) { "FFmpeg: ${log.toString().takeLast(1200)}" }
            return log.toString()
        } finally { current.destroy(); process = null }
    }
}
