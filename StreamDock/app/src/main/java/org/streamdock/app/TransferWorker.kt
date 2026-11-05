package org.streamdock.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.schabi.newpipe.extractor.NewPipe
import org.streamdock.app.terabox.TeraFile
import org.streamdock.app.terabox.TeraMedia
import org.streamdock.app.terabox.TeraTransfer
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import java.io.File

class TransferWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    private val http = DirectHttpDownloader()
    private val processor = NativeMediaProcessor(context.applicationContext as DockApp) { isStopped }
    @Volatile private var usedYtDlp = false
    private var engine = "Transfer"
    private var phase = "Preparing"
    private var previousPhase = ""
    private var produced: java.io.File? = null
    private var lastProgress = 0L
    private fun status(message: String, percent: Int) {
        phase = message
        val now = System.nanoTime()
        if (message != previousPhase) Diagnostics.stage(message)
        if (message != previousPhase || now - lastProgress > 700_000_000L) {
            setProgressAsync(workDataOf("percent" to percent.coerceIn(0, 99), "stage" to message, "engine" to engine))
            lastProgress = now
            previousPhase = message
        }
    }
    private fun foreground(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel("transfers", "Downloads & conversions", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "transfers")
            .setSmallIcon(R.drawable.ic_dock).setContentTitle("StreamDock")
            .setContentText("Preparing media…").setOngoing(true)
            .addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(id.hashCode(), notification)
    }
    override fun doWork(): Result {
        val folder = File(applicationContext.filesDir, "transfers/$id")
        folder.deleteRecursively()
        folder.mkdirs()
        try {
            require(inputData.getString("teraJob") == null) { "This old TeraBox job format is no longer used. Analyze the share again in the TeraBox section." }
            val teraUrl = inputData.getString("teraUrl")
            setForegroundAsync(foreground()).get()
            val kind = OutputKind.valueOf(inputData.getString("kind") ?: "MP4")
            val local = inputData.getString("local")
            if (teraUrl != null) downloadTera(folder,teraUrl)
            else if (local != null) {
                engine = "Local / FFmpeg"
                convertLocal(Uri.parse(local), folder, kind)
            } else download(folder, kind)
            if (isStopped) return Result.failure()
            val output = produced ?: File(folder, "media.${kind.extension}")
            check(output.isFile && output.length() > 0) { "No complete output file was produced." }
            // Verify what the bytes really are before calling anything a success. A video that "downloads
            // fine" but is stored under the wrong container is exactly what players report as corrupt.
            val container = MediaContainerReader.sniff(output)
            check(container.playableMedia) { "The downloaded data is ${container.label}, not playable media. Nothing was saved as a finished file; try another format." }
            val nameMismatch = !container.extension.equals(kind.extension, true) &&
                !(container == MediaContainer.MP4 && kind == OutputKind.MP4) &&
                !(container == MediaContainer.MATROSKA && kind == OutputKind.WEBM) &&
                !(container == MediaContainer.M4A && kind == OutputKind.M4A) &&
                !(container == MediaContainer.MP3 && kind == OutputKind.MP3)
            status("$engine: ${container.label} verified — exporting to the selected file…", 98)
            val destination = Uri.parse(inputData.getString("destination") ?: error("Missing destination"))
            applicationContext.contentResolver.openFileDescriptor(destination, "wt")?.use { descriptor ->
                java.io.FileOutputStream(descriptor.fileDescriptor).use { target ->
                    output.inputStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) throw InterruptedException("Cancelled")
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count)
                        }
                    }
                    target.flush()
                    runCatching { descriptor.fileDescriptor.sync() }
                }
            } ?: applicationContext.contentResolver.openOutputStream(destination, "wt")?.use { target ->
                output.inputStream().use { source ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (isStopped) throw InterruptedException("Cancelled")
                        val count = source.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                    }
                }
                target.flush()
            }
            // The exported bytes must be the same size as the verified file; a short export is not a success.
            val exportedSize = applicationContext.contentResolver.openFileDescriptor(destination, "r")?.use { it.statSize } ?: -1L
            check(exportedSize < 0 || exportedSize == output.length()) {
                "The saved file is incomplete ($exportedSize of ${output.length()} bytes). Nothing was reported as finished."
            }
            // Rename the document to its true container so no player ever opens VP9 bytes as .mp4.
            var finalDestination = destination
            var renameNote = ""
            if (nameMismatch) {
                val requested = android.provider.DocumentsContract.getDocumentId(destination).substringAfterLast('/')
                val newName = (requested.substringBeforeLast('.', requested)) + "." + container.extension
                val renamed = runCatching {
                    android.provider.DocumentsContract.renameDocument(applicationContext.contentResolver, destination, newName)
                }.getOrNull()
                if (renamed != null) { finalDestination = renamed; renameNote = "saved as .${container.extension} (${container.label}) because the source container was not .${kind.extension}" }
                else renameNote = "the content is ${container.label} — rename the file to .${container.extension} in your file manager if your player complains"
            }
            Diagnostics.stage("$engine: transfer and export complete (${container.label})")
            return Result.success(workDataOf(
                "destination" to finalDestination.toString(), "engine" to engine,
                "container" to container.label, "notice" to renameNote))
        } catch (e: Exception) {
            Diagnostics.error("$engine / $phase", e)
            // Keep full diagnostics, but put the actual final error before any warnings in the UI.
            val message = e.message.orEmpty()
            val summary = message.lineSequence().lastOrNull { it.startsWith("ERROR:") }
                ?: message.ifBlank { e.javaClass.simpleName }
            return Result.failure(workDataOf("error" to "$engine: ${DiagnosticText.redact(summary).take(1400)}", "engine" to engine))
        } finally {
            http.cancel()
            processor.cancel()
            folder.deleteRecursively()
        }
    }
    /** Resolved TeraBox file link with bounded resume retries; Referer headers belong to the same first-party share chain. */
    private fun downloadTera(folder: File, url: String) {
        engine = "TeraBox"
        val name = inputData.getString("teraName").orEmpty().ifBlank { "terabox-file" }
        val expected = inputData.getLong("teraSize", -1L)
        val extension = name.substringAfterLast('.', "bin").lowercase().take(8).filter { it.isLetterOrDigit() }.ifBlank { "bin" }
        val output = File(folder, "media.$extension")
        val transport = TeraTransfer(httpClient())
        try {
            transport.download(TeraMedia(url, inputData.getString("teraReferer").orEmpty(), TeraFile("", name, expected, false, "", url)),
                output, { isStopped }) { done, total ->
                val percent = if (total > 0) (done * 96 / total).toInt() else 0
                val label = if (total > 0) "TeraBox: ${done / 1048576} / ${total / 1048576} MB" else "TeraBox: ${done / 1048576} MB"
                status(label, percent)
            }
        } finally { transport.cancel() }
        produced = output
        status("TeraBox: file received complete; exporting…", 97)
    }
    private fun httpClient() = okhttp3.OkHttpClient.Builder()
        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS).readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    private fun download(folder: File, kind: OutputKind) {
        val url = InputPolicy.validate(inputData.getString("url") ?: error("Missing URL"))
        engine = "NewPipe"
        status("NewPipe: identifying the service…", 0)
        val app = applicationContext as DockApp
        val service = EngineBoundary.call("NewPipe") {
            app.initPipe()
            // Only unsupported service lookup chooses yt-dlp. Extraction/download failures
            // on a recognized service, including 403/429, NEVER silently switch engines.
            try { NewPipe.getServiceByUrl(url) } catch (_: ExtractionException) { null }
        }
        if (service != null) {
            val transfer = NewPipeTransfer(http, processor, { isStopped }, ::status)
            transfer.download(service, url, folder, kind, inputData.getInt("height", 0))
            transfer.produced?.let { produced = it }
        } else {
            downloadYtDlp(url, folder, kind)
        }
    }
    private fun downloadYtDlp(url: String, folder: File, kind: OutputKind) = EngineBoundary.call("yt-dlp transfer") {
        engine = "yt-dlp"
        usedYtDlp = true
        status("yt-dlp: this service is not supported by NewPipe…", 0)
        (applicationContext as DockApp).initNative()
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--socket-timeout", "30")
        request.addOption("--retries", "0")
        request.addOption("--fragment-retries", "0")
        request.addOption("--newline")
        request.addOption("-o", File(folder, "media.%(ext)s").absolutePath)
        request.addOption("-f", InputPolicy.formatSelector(kind, inputData.getInt("height", 0)))
        when (kind) {
            OutputKind.MP4, OutputKind.WEBM -> request.addOption("--merge-output-format", kind.extension)
            OutputKind.MP3, OutputKind.M4A -> {
                request.addOption("-x")
                request.addOption("--audio-format", kind.extension)
                request.addOption("--audio-quality", "192K")
            }
        }
        if (isStopped) throw InterruptedException()
        YoutubeDL.getInstance().execute(request, id.toString(), false) { progress, _, _ ->
            if (isStopped) YoutubeDL.getInstance().destroyProcessById(id.toString())
            else status("yt-dlp: download / processing…", (progress * .95f).toInt())
        }
        // yt-dlp/FFmpeg may fall back to Matroska or another extension; trust the file that exists, not the wish.
        val written = folder.listFiles()?.filter { it.isFile && it.length() > 0 }
            ?.sortedByDescending { it.length() }?.firstOrNull()
        if (written != null) {
            if (written.extension.lowercase() != kind.extension) status("yt-dlp: the container came back as .${written.extension} (codecs not native to .${kind.extension}).", 96)
            produced = written
        }
    }
    private fun convertLocal(uri: Uri, folder: File, kind: OutputKind) {
        require(kind == OutputKind.MP3 || kind == OutputKind.M4A)
        status("Local: checking FFmpeg runtime…", 0)
        processor.prepare()
        status("Local: preparing video…", 0)
        val source = File(folder, "source")
        applicationContext.contentResolver.openInputStream(uri).use { input ->
            checkNotNull(input) { "Could not open the selected video." }
            source.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (isStopped) throw InterruptedException()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
        status("FFmpeg: audio conversion…", 10)
        processor.audio(source, File(folder, "media.${kind.extension}"), kind)
    }
    override fun onStopped() {
        http.cancel()
        processor.cancel()
        if (usedYtDlp) YoutubeDL.getInstance().destroyProcessById(id.toString())
        super.onStopped()
    }
}
