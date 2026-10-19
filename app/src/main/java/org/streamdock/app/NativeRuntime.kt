package org.streamdock.app

import java.io.File
import java.io.IOException

/** Pure orchestration: FFmpeg's dependencies span BOTH upstream archives. */
object NativeRuntimeBootstrap {
    fun initialize(sharedRuntime: () -> Unit, ffmpegRuntime: () -> Unit, verify: () -> Unit) {
        sharedRuntime()
        ffmpegRuntime()
        verify()
    }
}

/** Keep initialization checks and the child process search path in one place. */
class NativeRuntimePaths(
    val nativeDirectory: File,
    noBackupDirectory: File,
    val temporaryDirectory: File
) {
    private val packages = File(noBackupDirectory, "youtubedl-android/packages")
    val pythonLibraries = File(packages, "python/usr/lib")
    val ffmpegLibraries = File(packages, "ffmpeg/usr/lib")
    val executable = File(nativeDirectory, "libffmpeg.so")
    val cppRuntime = File(pythonLibraries, "libc++_shared.so")

    fun environment(): Map<String, String> = mapOf(
        // Match the wrapper's order. No untrusted/system-inherited library path.
        "LD_LIBRARY_PATH" to listOf(pythonLibraries, ffmpegLibraries, nativeDirectory)
            .joinToString(File.pathSeparator) { it.absolutePath },
        "TMPDIR" to temporaryDirectory.absolutePath
    )

    fun verify() {
        requireElf(executable)
        requireElf(cppRuntime)
        // Exact SONAMEs of the pinned 0.18.1 FFmpeg archive; do not accept empty dirs.
        requireElf(File(ffmpegLibraries, "libavcodec.so.61"))
        requireElf(File(ffmpegLibraries, "libavformat.so.61"))
    }

    private fun requireElf(file: File) {
        if (!file.isFile) throw IOException("FFmpeg runtime incomplete: ${file.name} was not found.")
        val magic = ByteArray(4)
        val count = file.inputStream().use { it.read(magic) }
        if (count != 4 || !magic.contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))) {
            throw IOException("FFmpeg runtime file invalid: ${file.name}")
        }
    }
}
