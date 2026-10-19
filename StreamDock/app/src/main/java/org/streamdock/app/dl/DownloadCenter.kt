package org.streamdock.app.dl

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Queue + control for in-app downloads.
 *
 *  - Several files download at the same time (configurable, default 3).
 *  - Each file uses several parallel connections when the server allows it (configurable).
 *  - Pause stops the running transfers immediately but keeps every received byte; Resume continues with
 *    Range/If-Range. A dropped connection is retried with growing backoff and never deletes the part file.
 *  - Unfinished items resume automatically when the app or the service starts again. */
object DownloadCenter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = state.asStateFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private var appContext: Context? = null

    var maxConcurrent: Int = 3
    var connectionsPerFile: Int = 4

    @Synchronized fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val prefs = app.getSharedPreferences("download-engine", Context.MODE_PRIVATE)
        maxConcurrent = prefs.getInt("maxConcurrent", 3).coerceIn(1, 5)
        connectionsPerFile = prefs.getInt("connections", 4).coerceIn(1, 16)
        val restored = DownloadStore.all(app).map { item ->
            // A download that was running when the process died goes back to the queue, keeping its bytes.
            if (item.status == DlStatus.RUNNING) { item.status = DlStatus.WAITING; item.note = "Resuming after restart" }
            DownloadStore.save(app, item); item
        }
        state.value = restored
        purgeFinished()
        if (restored.any { it.active }) { schedule() }
    }

    fun settings(context: Context, concurrent: Int, connections: Int) {
        maxConcurrent = concurrent.coerceIn(1, 5); connectionsPerFile = connections.coerceIn(1, 16)
        context.getSharedPreferences("download-engine", Context.MODE_PRIVATE).edit()
            .putInt("maxConcurrent", maxConcurrent).putInt("connections", connectionsPerFile).apply()
        val ctx = appContext; if (ctx != null) state.value.toList().filter { it.active }.forEach { item ->
            item.note = "New connection setting applies after this file finishes"; DownloadStore.save(ctx, item)
        }
        publish()
    }

    /** Add a download. The caller has already chosen the destination file through ACTION_CREATE_DOCUMENT. */
    fun enqueue(context: Context, url: String, referer: String, agent: String, name: String, mime: String, total: Long, destination: Uri): String {
        init(context)
        val app = context.applicationContext
        val item = DownloadItem(url = url, referer = referer, agent = agent, name = name, mime = mime, total = total, destUri = destination.toString(), status = DlStatus.WAITING)
        DownloadStore.save(app, item)
        state.value = listOf(item) + state.value
        TransferService.start(app)
        schedule()
        return item.id
    }

    fun pause(context: Context, id: String) {
        val item = state.value.firstOrNull { it.id == id } ?: return
        if (!item.active) return
        item.status = DlStatus.PAUSED; item.note = "Paused — ${item.progressText()}"
        item.speed = 0
        DownloadEngine.cancelAllFor(id)
        jobs.remove(id)?.cancel()
        DownloadStore.save(context, item); publish()
    }
    fun resume(context: Context, id: String) {
        val item = state.value.firstOrNull { it.id == id } ?: return
        if (item.status == DlStatus.COMPLETED || item.status == DlStatus.CANCELLED) return
        if (item.status == DlStatus.FAILED) { item.error = "" }
        if (item.status == DlStatus.EXPORT_FAILED) { export(context, item); return }
        item.status = DlStatus.WAITING; item.note = "Queued"
        DownloadStore.save(context, item); schedule()
    }
    fun cancel(context: Context, id: String) {
        DownloadEngine.cancelAllFor(id); jobs.remove(id)?.cancel()
        val item = state.value.firstOrNull { it.id == id }
        if (item != null) { item.status = DlStatus.CANCELLED }
        DownloadStore.delete(context, id)
        state.value = state.value.filterNot { it.id == id }
        publish(); schedule()
    }
    fun restart(context: Context, id: String) {
        val item = state.value.firstOrNull { it.id == id } ?: return
        DownloadEngine.cancelAllFor(id); jobs.remove(id)?.cancel()
        val dir = DownloadStore.dir(context, id)
        DownloadEngine.partFile(dir).delete()
        item.segments.clear(); item.total = -1L; item.validator = null; item.acceptRanges = false
        item.hlsSegments.clear(); item.hlsSizes.clear(); item.hlsDone = 0; item.hlsDuration = 0.0; item.mode = DlMode.FILE
        item.status = DlStatus.WAITING; item.error = ""; item.exported = false; item.note = "Restarted"
        DownloadStore.save(context, item); schedule()
    }
    fun pauseAll(context: Context) { state.value.filter { it.active }.forEach { pause(context, it.id) } }
    fun resumeAll(context: Context) { state.value.filter { it.status == DlStatus.PAUSED || it.status == DlStatus.FAILED }.forEach { resume(context, it.id) } }
    fun remove(context: Context, id: String) { cancel(context, id) }

    /** Copy a finished part file to the user's chosen destination (SAF). The part file only disappears after that worked. */
    fun export(context: Context, item: DownloadItem) {
        val file = DownloadEngine.partFile(DownloadStore.dir(context, item.id))
        if (!file.isFile || file.length() <= 0) { item.status = DlStatus.FAILED; item.error = "No data to export."; DownloadStore.save(context, item); publish(); return }
        item.status = DlStatus.RUNNING; item.note = "Saving to your folder…"; publish()
        scope.launch {
            try {
                context.contentResolver.openOutputStream(Uri.parse(item.destUri), "wt").use { target ->
                    if (target == null) throw IOException("The chosen folder is no longer available.")
                    file.inputStream().use { source ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) { val n = source.read(buffer); if (n < 0) break; target.write(buffer, 0, n) }
                    }
                }
                // Verify what actually landed in the user's folder: size and container. The in-app engine
                // stores exactly the bytes it received, but a truncated export or a wrong container is
                // exactly what makes a "downloaded fine" video unplayable.
                val container = org.streamdock.app.MediaContainerReader.sniff(file)
                val exportedSize = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(item.destUri), "r")?.use { it.statSize } }.getOrNull() ?: -1L
                val sizeOk = exportedSize < 0 || exportedSize == file.length()
                val mediaOk = container.playableMedia || container == org.streamdock.app.MediaContainer.UNKNOWN && item.mime.startsWith("application/octet")
                if (!sizeOk || !mediaOk) {
                    item.status = DlStatus.EXPORT_FAILED
                    item.error = when {
                        !sizeOk -> "The saved copy is incomplete ($exportedSize of ${file.length()} bytes). The download is kept — tap Choose folder to save it again."
                        else -> "The received data is ${container.label}, not playable media (the server may have sent a page or a manifest). The data is kept; nothing is reported as a finished download."
                    }
                    item.exported = false
                } else {
                    item.exported = true; item.status = DlStatus.COMPLETED
                    item.note = "Saved • ${item.progressText()} • ${container.label}"
                }
                item.speed = 0
                DownloadStore.save(context, item)
                if (item.exported) file.delete()
                publish()
            } catch (e: Exception) {
                item.status = DlStatus.EXPORT_FAILED
                item.error = "Could not write the file: ${e.javaClass.simpleName}. The download is kept — choose the folder again."
                DownloadStore.save(context, item); publish()
            }
        }
    }

    private fun schedule() {
        val ctx: Context = appContext ?: return
        val active = state.value.count { it.status == DlStatus.RUNNING }
        val slots = (maxConcurrent - active).coerceAtLeast(0)
        state.value.filter { it.status == DlStatus.WAITING }.sortedBy { it.createdAt }.take(slots).forEach { item ->
            if (jobs.containsKey(item.id)) return@forEach
            item.status = DlStatus.RUNNING
            DownloadStore.save(ctx, item)
            jobs[item.id] = scope.launch { runItem(ctx, item) }
        }
        publish()
    }

    private suspend fun runItem(context: Context, item: DownloadItem) {
        val dir = DownloadStore.dir(context, item.id)
        val file = DownloadEngine.partFile(dir)
        try {
            while (true) {
                if (!item.active) return
                if (item.segments.isEmpty() || item.total <= 0) {
                    DownloadEngine.probe(item, dir, connectionsPerFile)
                    DownloadStore.save(context, item)
                }
                if (item.mode == DlMode.HLS) {
                    DownloadStore.save(context, item)
                    val ticker = scope.launch {
                        var last = item.downloaded; var lastAt = System.nanoTime()
                        while (isActive && item.status == DlStatus.RUNNING) {
                            delay(900)
                            val now = item.downloaded; val at = System.nanoTime()
                            val seconds = ((at - lastAt).toDouble() / 1_000_000_000.0).coerceAtLeast(0.001)
                            item.speed = ((now - last).toDouble() / seconds).toLong().coerceAtLeast(0)
                            last = now; lastAt = at; publish()
                        }
                    }
                    try {
                        var attempt = 0
                        while (item.hlsDone < item.hlsSegments.size) {
                            try {
                                DownloadEngine.runHls(item, file, { !item.active }) { publishThrottled() }
                                break
                            } catch (paused: PausedSignal) { throw paused
                            } catch (e: IOException) {
                                if (!item.active) throw PausedSignal()
                                attempt++
                                if (attempt > 5) throw e
                                val waitMs = minOf(30_000L, 1_000L shl minOf(attempt, 5))
                                item.note = "Stream interrupted — retrying in ${waitMs / 1000}s (resumes at segment ${item.hlsDone + 1})"
                                DownloadStore.save(context, item); publish()
                                delay(waitMs)
                            }
                        }
                    } finally { ticker.cancel() }
                    if (!item.active) return
                    if (!DownloadEngine.isComplete(item, file)) throw IOException("The stream is still incomplete; resume to continue.")
                    export(context, item)
                    return
                }
                if (item.segments.isEmpty()) throw IOException("This link cannot be downloaded (no ranges and no size).")
                // Progress + speed ticker for the UI (cancelled in the finally below).
                val ticker = scope.launch {
                    var last = item.downloaded; var lastAt = System.nanoTime()
                    while (isActive && item.status == DlStatus.RUNNING) {
                        delay(900)
                        val now = item.downloaded; val at = System.nanoTime()
                        val seconds = ((at - lastAt).toDouble() / 1_000_000_000.0).coerceAtLeast(0.001)
                        item.speed = ((now - last).toDouble() / seconds).toLong().coerceAtLeast(0)
                        last = now; lastAt = at
                        DownloadStore.save(context, item)
                        publish()
                    }
                }
                try {
                    coroutineScope {
                        item.segments.indices.map { index ->
                            async {
                                var attempt = 0
                                while (true) {
                                    try {
                                        DownloadEngine.runSegment(item, index, file, { !item.active }) { publishThrottled() }
                                        return@async
                                    } catch (paused: PausedSignal) { throw paused
                                    } catch (changed: ValidatorSignal) { throw changed
                                    } catch (e: IOException) {
                                        if (!item.active) throw PausedSignal()
                                        attempt++
                                        val waitMs = minOf(60_000L, 1_000L shl minOf(attempt, 6))
                                        item.note = "Connection interrupted — retrying in ${waitMs / 1000}s (${attempt})"
                                        publish()
                                        delay(waitMs)
                                    }
                                }
                            }
                        }.forEach { it.await() }
                    }
                } finally { ticker.cancel() }

                if (!item.active) return
                if (DownloadEngine.looksLikeErrorPage(file)) throw IOException("The server returned a page instead of the file.")
                if (!DownloadEngine.isComplete(item, file)) {
                    // Unknown-size single-stream downloads finish when the server closes the stream.
                    if (item.segments.size == 1 && item.segments[0].end < 0 && file.length() > 0) { /* done */ }
                    else throw IOException("The file is still incomplete; resuming…")
                }
                export(context, item)
                return
            }
        } catch (paused: PausedSignal) {
            item.status = DlStatus.PAUSED; item.speed = 0
            item.note = "Paused — ${item.progressText()}"
            DownloadStore.save(context, item)
        } catch (changed: ValidatorSignal) {
            item.status = DlStatus.FAILED; item.speed = 0
            item.error = changed.message.orEmpty()
            item.note = "Tap Restart to download it again from the beginning."
            DownloadStore.save(context, item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            item.status = DlStatus.FAILED; item.speed = 0
            item.error = e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            item.note = "Tap Resume to continue from ${item.progressText()}."
            DownloadStore.save(context, item)
        } finally {
            jobs.remove(item.id)
            publish()
            TransferService.stopIfIdle(context)
            schedule()
        }
    }

    private var lastPublish = 0L
    private fun publishThrottled() { val now = System.currentTimeMillis(); if (now - lastPublish > 400) publish() }
    @Synchronized private fun publish() { state.value = state.value.map { it } }

    private fun purgeFinished() {
        val ctx: Context = appContext ?: return
        state.value.filter { it.status == DlStatus.COMPLETED || it.status == DlStatus.CANCELLED }.drop(40).forEach { DownloadStore.delete(ctx, it.id) }
        state.value = state.value.take(80)
    }

    /** Notification text for the foreground service. */
    fun summaryText(): String {
        val list = state.value
        val running = list.filter { it.status == DlStatus.RUNNING }
        if (running.isEmpty()) return "StreamDock downloads"
        val percent = running.map { it.percent() }.average().toInt()
        return "${running.size} downloading • ${percent}% • ${DownloadItem.human(running.sumOf { it.speed })}/s"
    }
    fun hasActive(): Boolean = state.value.any { it.active }
    fun contextOrNull(): Context? = appContext
}
