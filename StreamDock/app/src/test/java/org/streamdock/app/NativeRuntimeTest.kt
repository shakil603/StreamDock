package org.streamdock.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class NativeRuntimeTest {
    @get:Rule val temp = TemporaryFolder()
    private fun paths() = NativeRuntimePaths(File(temp.root, "bin"), File(temp.root, "private"), File(temp.root, "tmp"))
    private fun fakeElf(file: File) {
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0, 0, 0, 0))
    }
    private fun completeRuntime(paths: NativeRuntimePaths) {
        fakeElf(paths.executable)
        fakeElf(paths.cppRuntime)
        fakeElf(File(paths.ffmpegLibraries, "libavcodec.so.61"))
        fakeElf(File(paths.ffmpegLibraries, "libavformat.so.61"))
    }
    @Test fun initializationOrderIsSharedThenFfmpegThenVerify() {
        val calls = mutableListOf<String>()
        NativeRuntimeBootstrap.initialize({ calls += "shared" }, { calls += "ffmpeg" }, { calls += "verify" })
        assertEquals(listOf("shared", "ffmpeg", "verify"), calls)
    }
    @Test fun sharedFailurePreventsFfmpegInitialization() {
        var next = false
        try {
            NativeRuntimeBootstrap.initialize({ throw IOException("disk full") }, { next = true }, { next = true })
            fail("Expected failure")
        } catch (_: IOException) { assertFalse(next) }
    }
    @Test fun ffmpegFailurePreventsVerification() {
        var verified = false
        try {
            NativeRuntimeBootstrap.initialize({}, { throw IOException("unzip failed") }, { verified = true })
            fail("Expected failure")
        } catch (_: IOException) { assertFalse(verified) }
    }
    @Test(expected = IOException::class) fun verificationErrorsAreNotSuppressed() {
        NativeRuntimeBootstrap.initialize({}, {}, { throw IOException("missing runtime") })
    }
    @Test fun missingCppReproducesOldInitializationGap() {
        val paths = paths()
        fakeElf(paths.executable)
        fakeElf(File(paths.ffmpegLibraries, "libavcodec.so.61"))
        fakeElf(File(paths.ffmpegLibraries, "libavformat.so.61"))
        try { paths.verify(); fail("Missing C++ must fail") }
        catch (e: IOException) { assertTrue(e.message!!.contains("libc++_shared.so")) }
    }
    @Test fun completeFileLayoutPassesPreflightFileChecks() {
        val paths = paths(); completeRuntime(paths); paths.verify()
    }
    @Test fun nonElfRuntimeRejected() {
        val paths = paths(); completeRuntime(paths); paths.cppRuntime.writeText("not an ELF library")
        try { paths.verify(); fail("Invalid C++ must fail") }
        catch (e: IOException) { assertTrue(e.message!!.contains("invalid")) }
    }
    @Test fun sharedRuntimeDirectoryIsFirstInChildSearchPath() {
        val paths = paths()
        assertEquals(listOf(paths.pythonLibraries, paths.ffmpegLibraries, paths.nativeDirectory)
            .joinToString(File.pathSeparator) { it.absolutePath }, paths.environment()["LD_LIBRARY_PATH"])
        assertEquals(paths.temporaryDirectory.absolutePath, paths.environment()["TMPDIR"])
    }
    @Test fun initializationProducesRequiredSharedRuntimeBeforeVerify() {
        val paths = paths()
        NativeRuntimeBootstrap.initialize(
            { fakeElf(paths.cppRuntime) },
            { fakeElf(paths.executable); fakeElf(File(paths.ffmpegLibraries, "libavcodec.so.61")); fakeElf(File(paths.ffmpegLibraries, "libavformat.so.61")) },
            { paths.verify() }
        )
    }
}
