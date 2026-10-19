package org.streamdock.app

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg

class DockApp : Application() {
    private var pipeReady = false
    override fun onCreate() {
        super.onCreate()
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("en"))
        Diagnostics.install(this)
        org.streamdock.app.dl.DownloadCenter.init(this)
        org.streamdock.app.dl.TransferKeepAlive.schedule(this)
        // Do not eagerly load extractor classes on the UI/startup path.
    }
    @Synchronized fun initPipe() = EngineBoundary.call("NewPipe") {
        if (!pipeReady) {
            NewPipe.init(PipeDownloader())
            pipeReady = true
        }
    }
    fun nativeRuntimePaths() = NativeRuntimePaths(
        java.io.File(applicationInfo.nativeLibraryDir), noBackupFilesDir, cacheDir
    )
    @Synchronized fun initNative() = initFfmpeg()

    @Synchronized fun initFfmpeg() = EngineBoundary.call("FFmpeg shared runtime") {
        // libc++_shared.so and OpenSSL are in the bundled PYTHON archive.
        // init() extracts it; this does NOT run yt-dlp or access a website.
        NativeRuntimeBootstrap.initialize(
            sharedRuntime = { YoutubeDL.getInstance().init(this) },
            ffmpegRuntime = { FFmpeg.getInstance().init(this) },
            verify = { nativeRuntimePaths().verify() }
        )
    }
}
