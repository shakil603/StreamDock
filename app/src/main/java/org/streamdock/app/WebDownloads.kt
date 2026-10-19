package org.streamdock.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import java.util.UUID

/** Browser file downloads belong to Android DownloadManager, not NewPipe/yt-dlp.
 * Never attach Cookie or Authorization headers to a system-managed redirect chain. */
object WebDownloads {
    data class Entry(val id: Long, val name: String, val status: Int, val reason: Int, val bytes: Long, val total: Long)
    private fun manager(c: Context)=c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private fun prefs(c: Context)=c.getSharedPreferences("web-downloads",Context.MODE_PRIVATE)
    @Synchronized fun enqueue(c: Context, url: String, filename: String, mime: String?, agent: String, page: String): Long {
        val address=BrowserPolicy.https(url)
        val safe=BrowserPolicy.safeName(filename)
        val request=DownloadManager.Request(Uri.parse(address)).setTitle(safe).setDescription("StreamDock • Browser file")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"StreamDock_${UUID.randomUUID().toString().take(8)}_$safe")
            .addRequestHeader("User-Agent",agent)
        BrowserPolicy.origin(page)?.let { request.addRequestHeader("Referer",it) }
        mime?.takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) }?.let { request.setMimeType(it) }
        val id=manager(c).enqueue(request)
        val ids=prefs(c).getStringSet("ids",emptySet()).orEmpty().toMutableSet();ids+=id.toString()
        prefs(c).edit().putStringSet("ids",ids).apply()
        return id
    }
    fun count(c: Context)=prefs(c).getStringSet("ids",emptySet()).orEmpty().size
    fun entries(c: Context, limit: Int): List<Entry> {
        val ids=prefs(c).getStringSet("ids",emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.sortedDescending().take(limit)
        if(ids.isEmpty()) return emptyList()
        val result=mutableListOf<Entry>()
        manager(c).query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { row ->
            fun number(key: String)=row.getLong(row.getColumnIndexOrThrow(key))
            while(row.moveToNext()) result+=Entry(number(DownloadManager.COLUMN_ID),row.getString(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)).orEmpty(),
                number(DownloadManager.COLUMN_STATUS).toInt(),number(DownloadManager.COLUMN_REASON).toInt(),number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
        }
        return result.sortedByDescending { it.id }
    }
    fun open(c: Context,id: Long,share: Boolean=false) {
        val manager=manager(c)
        val uri=manager.getUriForDownloadedFile(id) ?: error("The file is not available. It may have been moved or deleted.")
        val mime=manager.getMimeTypeForDownloadedFile(id) ?: "application/octet-stream"
        val intent=if(share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(if(share) Intent.createChooser(intent,"Share downloaded file") else intent)
    }
    @Synchronized fun remove(c: Context,id: Long) {
        manager(c).remove(id)
        val ids=prefs(c).getStringSet("ids",emptySet()).orEmpty().toMutableSet();ids-=id.toString()
        prefs(c).edit().putStringSet("ids",ids).apply()
    }
    fun state(entry: Entry): String = when(entry.status) {
        DownloadManager.STATUS_SUCCESSFUL -> "Saved to Downloads"
        DownloadManager.STATUS_FAILED -> "Failed • Android download code ${entry.reason}. Try opening the page externally."
        DownloadManager.STATUS_PAUSED -> "Paused by Android • ${entry.reason}"
        DownloadManager.STATUS_RUNNING -> "Downloading"
        else -> "Queued"
    }
}
