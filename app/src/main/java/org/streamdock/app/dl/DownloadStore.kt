package org.streamdock.app.dl

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Small JSON store next to each partial file. Writes are atomic; the partial file is never deleted
 *  unless the user cancels the item or the export has completed. */
object DownloadStore {
    private fun root(context: Context): File = File(context.filesDir, "inapp-downloads").apply { mkdirs() }
    fun dir(context: Context, id: String): File = File(root(context), id).apply { mkdirs() }
    private fun meta(context: Context, id: String): File = File(dir(context, id), "meta.json")

    @Synchronized fun save(context: Context, item: DownloadItem) {
        item.updatedAt = System.currentTimeMillis()
        val target = meta(context, item.id)
        val tmp = File(target.parentFile, "meta.json.tmp")
        tmp.writeText(item.toJson().toString())
        if (!tmp.renameTo(target)) { target.writeText(item.toJson().toString()); tmp.delete() }
    }
    @Synchronized fun load(context: Context, id: String): DownloadItem? =
        runCatching { DownloadItem.from(JSONObject(meta(context, id).readText())) }.getOrNull()

    @Synchronized fun all(context: Context): List<DownloadItem> =
        root(context).listFiles().orEmpty().filter { it.isDirectory }
            .mapNotNull { load(context, it.name) }
            .sortedByDescending { it.updatedAt }

    @Synchronized fun purge(context: Context, keep: Int = 60) {
        all(context).drop(keep).forEach { runCatching { dir(context, it.id).deleteRecursively() } }
    }

    @Synchronized fun delete(context: Context, id: String) {
        runCatching { dir(context, id).deleteRecursively() }
    }
}
