package org.streamdock.app.dl

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** One byte range of a file. `end` is inclusive; `end < 0` means "until EOF" (unknown total). */
data class Segment(val start: Long, val end: Long, var done: Long = 0L) {
    val length: Long get() = if (end < 0) -1 else end - start + 1
    val remaining: Long get() = if (end < 0) -1 else (length - done).coerceAtLeast(0)
    fun nextOffset(): Long = start + done
    fun toJson() = JSONObject().put("s", start).put("e", end).put("d", done)
    companion object { fun from(o: JSONObject) = Segment(o.getLong("s"), o.getLong("e"), o.getLong("d")) }
}

enum class DlStatus { WAITING, RUNNING, PAUSED, EXPORT_FAILED, COMPLETED, FAILED, CANCELLED }

/** FILE = byte-range download; HLS = playlist whose segments are fetched in order and appended. */
enum class DlMode { FILE, HLS }

/** A resumable download. The partial file on disk is the source of truth for progress, so a paused or
 *  interrupted item keeps every byte it already received and continues with a Range request later. */
class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    var url: String,
    var referer: String = "",
    var agent: String = "",
    var name: String,
    var mime: String = "",
    var total: Long = -1L,
    var validator: String? = null,
    var acceptRanges: Boolean = false,
    val segments: MutableList<Segment> = mutableListOf(),
    var destUri: String = "",
    var status: DlStatus = DlStatus.WAITING,
    var note: String = "",
    var error: String = "",
    var speed: Long = 0L,
    var exported: Boolean = false,
    var mode: DlMode = DlMode.FILE,
    val hlsSegments: MutableList<String> = mutableListOf(),
    val hlsSizes: MutableList<Long> = mutableListOf(),
    var hlsDone: Int = 0,
    var hlsDuration: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
) {
    val downloaded: Long get() = if (mode == DlMode.HLS) hlsSizes.sum() else segments.sumOf { it.done }
    val active: Boolean get() = status == DlStatus.RUNNING || status == DlStatus.WAITING
    fun percent(): Int = when {
        mode == DlMode.HLS && hlsSegments.isNotEmpty() -> ((hlsDone * 100L) / hlsSegments.size).toInt().coerceIn(0, 100)
        total > 0 -> ((downloaded * 100) / total).toInt().coerceIn(0, 100)
        else -> 0
    }
    fun progressText(): String {
        if (mode == DlMode.HLS) {
            val minutes = (hlsDuration / 60.0)
            val length = if (minutes >= 1) "${"%.0f".format(minutes)} min" else "${hlsDuration.toInt()} s"
            return "${hlsDone}/${hlsSegments.size} segments  •  ${percent()}%  •  ${human(downloaded)}" + if (hlsDuration > 0) " of ~$length" else ""
        }
        val got = human(downloaded)
        return when {
            total <= 0 -> "$got downloaded"
            else -> "$got / ${human(total)}  •  ${percent()}%"
        }
    }
    fun speedText(): String = if (speed > 0) "${human(speed)}/s" else ""
    fun connectionsText(): String = when {
        mode == DlMode.HLS -> "stream segments"
        segments.size > 1 -> "${segments.count { it.remaining != 0L }} connections"
        else -> "single connection"
    }
    fun complete(): Boolean = if (mode == DlMode.HLS) hlsSegments.isNotEmpty() && hlsDone >= hlsSegments.size
        else segments.isNotEmpty() && segments.all { it.remaining == 0L }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("referer", referer); put("agent", agent); put("name", name); put("mime", mime)
        put("total", total); put("validator", validator ?: JSONObject.NULL); put("acceptRanges", acceptRanges)
        put("destUri", destUri); put("status", status.name); put("note", note); put("error", error)
        put("exported", exported); put("createdAt", createdAt); put("updatedAt", updatedAt)
        put("segments", JSONArray().apply { segments.forEach { put(it.toJson()) } })
        put("mode", mode.name); put("hlsDone", hlsDone); put("hlsDuration", hlsDuration)
        put("hlsSegments", JSONArray().apply { hlsSegments.forEach { put(it) } })
        put("hlsSizes", JSONArray().apply { hlsSizes.forEach { put(it) } })
    }

    companion object {
        fun from(o: JSONObject): DownloadItem {
            val segs = o.optJSONArray("segments")?.let { a -> (0 until a.length()).map { Segment.from(a.getJSONObject(it)) }.toMutableList() } ?: mutableListOf()
            val status = runCatching { DlStatus.valueOf(o.optString("status", "PAUSED")) }.getOrDefault(DlStatus.PAUSED)
            val hlsSegs = o.optJSONArray("hlsSegments")?.let { a -> (0 until a.length()).map { a.optString(it) }.toMutableList() } ?: mutableListOf()
            val hlsSz = o.optJSONArray("hlsSizes")?.let { a -> (0 until a.length()).map { a.optLong(it) }.toMutableList() } ?: mutableListOf()
            return DownloadItem(
                id = o.optString("id"), url = o.optString("url"), referer = o.optString("referer", ""),
                agent = o.optString("agent", ""), name = o.optString("name", "file"), mime = o.optString("mime", ""),
                total = o.optLong("total", -1L), validator = o.optString("validator").takeIf { it.isNotBlank() && it != "null" },
                acceptRanges = o.optBoolean("acceptRanges", false), segments = segs, destUri = o.optString("destUri", ""),
                // RUNNING does not survive a process restart: the item waits in the queue instead of pretending to run.
                status = if (status == DlStatus.RUNNING) DlStatus.WAITING else status,
                note = o.optString("note", ""), error = o.optString("error", ""), exported = o.optBoolean("exported", false),
                createdAt = o.optLong("createdAt", System.currentTimeMillis()), updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                mode = runCatching { DlMode.valueOf(o.optString("mode", "FILE")) }.getOrDefault(DlMode.FILE),
                hlsSegments = hlsSegs, hlsSizes = hlsSz,
                hlsDone = o.optInt("hlsDone", 0), hlsDuration = o.optDouble("hlsDuration", 0.0),
            )
        }
        fun human(bytes: Long): String = when {
            bytes < 0 -> "unknown"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1048576.0)
            else -> "%.2f GB".format(bytes / 1073741824.0)
        }
    }
}
