package org.streamdock.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** On-device only. No analytics, upload or raw metadata/stream URLs. */
object Diagnostics {
    private lateinit var file: File
    private lateinit var header: String
    fun install(context: Context) {
        file = File(context.filesDir, "diagnostics.txt")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        header = "StreamDock $version\n${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nABI: ${Build.SUPPORTED_ABIS.joinToString()}\nNewPipe v0.26.5 + Android URL-codec compat / youtubedl-android 0.18.1\n"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                if (error !is VirtualMachineError && error !is ThreadDeath) error("Uncaught / ${thread.name}", error)
            } catch (_: Throwable) {
                // Reporting must never replace the platform's crash handler.
            } finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(1) }
            }
        }
        stage("Application opened")
    }
    @Synchronized fun stage(value: String) { append(value) }
    @Synchronized fun error(stage: String, error: Throwable) {
        append("$stage\n${DiagnosticText.redact(Log.getStackTraceString(error))}")
    }
    private fun append(value: String) {
        if (!::file.isInitialized) return
        try {
            val old = if (file.exists()) file.readText().takeLast(24_000) else ""
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.writeText((old + "\n[$time] " + DiagnosticText.redact(value) + "\n").takeLast(40_000))
        } catch (_: Exception) { /* Disk full or file unavailable: keep the original result. */ }
    }
    @Synchronized fun report(): String = try {
        header + "\n" + if (file.exists()) file.readText() else "No diagnostic events."
    } catch (_: Exception) { "Diagnostic file unavailable." }
}
