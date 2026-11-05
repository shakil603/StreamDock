package org.streamdock.app.terabox

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.streamdock.app.CinemaUi
import org.streamdock.app.DiagnosticText
import org.streamdock.app.Dock

/** TeraBox share section: paste a public share link, list files, play or save them.
 * No account login, no imported cookies, no third-party downloader websites. */
class TeraBoxController(private val activity: AppCompatActivity) {
    var onWork: ((java.util.UUID) -> Unit)? = null
    var onBrowse: ((String) -> Unit)? = null
    private val prefs = activity.getSharedPreferences("terabox-engine",Activity.MODE_PRIVATE)
    private var engine = TeraEngine(prefs.getString("resolver","").orEmpty())

    init {
        TeraCookies.attach(activity)
    }
    private var transfer = TeraTransfer(engine.client)
    private var share: TeraShare? = null
    private var busy = false
    private var pendingFile: TeraFile? = null
    private var pendingRetry: (() -> Unit)? = null
    private val thumbCache = java.util.Collections.synchronizedMap(java.util.LinkedHashMap<String, android.graphics.Bitmap>(24))
    private val thumbPool = java.util.concurrent.Executors.newFixedThreadPool(2)
    private lateinit var rootView: LinearLayout
    private lateinit var urlField: TextInputEditText
    private lateinit var statusView: TextView
    private lateinit var listView: LinearLayout
    private lateinit var playerHint: TextView
    private lateinit var resolverLabel: TextView
    private lateinit var progressView: ProgressBar
    private val saveFile = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val file = pendingFile; pendingFile = null
        if (result.resultCode == Activity.RESULT_OK && destination != null && file != null) {
            val flags = (result.data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { activity.contentResolver.takePersistableUriPermission(destination,flags) }
            val current = share
            if (current == null) { status("Analyze the share again before saving."); return@registerForActivityResult }
            status("Resolving the original file URL…")
            activity.lifecycleScope.launch {
                try {
                    val media = withContext(Dispatchers.IO) { engine.media(current,file,forSave=true,allowPreview=false) }
                    enqueueSave(file, media, destination)
                } catch (e: Exception) {
                    if (needsVerification(e)) {
                        status("TeraBox requires verification to download the complete file. Opening verification slider…")
                        val wall = e as? TeraWall
                        val dest = destination
                        openCheck(file, wall, onPreviewFallback = {
                            activity.lifecycleScope.launch {
                                try {
                                    val preview = withContext(Dispatchers.IO) { engine.media(current,file,forSave=false,allowPreview=true) }
                                    enqueueSave(file, preview, dest)
                                } catch (pe: Exception) { status("Save preview failed: ${message(pe)}") }
                            }
                        }) {
                            activity.lifecycleScope.launch {
                                try {
                                    val media = withContext(Dispatchers.IO) { engine.media(current,file,forSave=true,allowPreview=false) }
                                    enqueueSave(file, media, dest)
                                } catch (e2: Exception) { status("Save still failed: ${message(e2)}") }
                            }
                        }
                    } else status("Save failed: ${message(e)}")
                }
            }
        }
    }

    fun root(): View {
        rootView = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        rootView.addView(CinemaUi.text(activity,"TERABOX",10f,CinemaUi.red).apply { letterSpacing=.15f })
        rootView.addView(CinemaUi.text(activity,"Play or save a share link",23f))
        rootView.addView(CinemaUi.text(activity,"Public share links only. No account password or cookie import is requested.",11f,CinemaUi.muted))
        val card = CinemaUi.card(activity,rootView)
        card.addView(CinemaUi.text(activity,"SHARE LINK + FILE LIST",11f,CinemaUi.red).apply { letterSpacing=.12f })
        val input = TextInputLayout(activity).apply { hint="Paste a TeraBox share link" }
        CinemaUi.field(activity,input)
        urlField = TextInputEditText(input.context).apply { setSingleLine(); textSize=13.5f; typeface=org.streamdock.app.Dock.regular(activity); setTextColor(org.streamdock.app.Dock.ink); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        input.addView(urlField); card.addView(input)
        card.addView(CinemaUi.button(activity,"Analyze share  →") { analyze() })
        card.addView(CinemaUi.text(activity,"Everything runs inside StreamDock: files list here, Play opens the in-app player, Save uses the in-app download engine. If TeraBox asks for its own check, a compact in-app sheet appears — complete it there and the video continues here.",10f,CinemaUi.muted))
        statusView = CinemaUi.text(activity,"Waiting for a link.",11f,CinemaUi.muted).apply { setTextIsSelectable(true) }
        card.addView(statusView)
        progressView = CinemaUi.progress(activity).apply { max=100; visibility=View.GONE }
        card.addView(progressView,LinearLayout.LayoutParams(-1,ViewGroup.LayoutParams.WRAP_CONTENT))
        listView = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
        card.addView(listView)
        val player_card = CinemaUi.card(activity,rootView)
        player_card.addView(CinemaUi.text(activity,"PLAYER",11f,CinemaUi.red).apply { letterSpacing=.12f })
        playerHint = CinemaUi.text(activity,"Press Play on a file — it opens in the unified player with audio-only and background listening.",11f,CinemaUi.muted)
        player_card.addView(playerHint,LinearLayout.LayoutParams(-1,CinemaUi.dp(activity,72)).apply { topMargin=CinemaUi.dp(activity,8) })
        player_card.addView(CinemaUi.button(activity,"Stop playback",secondary=true) { release() })
        val advanced = CinemaUi.card(activity,rootView)
        advanced.addView(CinemaUi.text(activity,"ADVANCED • OPTIONAL",11f,CinemaUi.red).apply { letterSpacing=.12f })
        val advancedBody = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        advanced.addView(CinemaUi.button(activity,"Resolver settings",secondary=true) {
            advancedBody.visibility = if(advancedBody.visibility==View.GONE) View.VISIBLE else View.GONE
        })
        advanced.addView(advancedBody)
        advancedBody.addView(CinemaUi.text(activity,"Leave empty to resolve directly with TeraBox. For shares that TeraBox walls behind verification, point this at your own resolver service — the project ships one (backend/terabox-api, MIT, see docs/RESOLVER-SETUP.md). Enter its address with or without https://.",11f,CinemaUi.muted))
        val resolver = TextInputLayout(activity).apply { hint="https://resolver.example.com" }
        CinemaUi.field(activity,resolver)
        val resolverField = TextInputEditText(resolver.context).apply { setSingleLine(); textSize=13.5f; typeface=org.streamdock.app.Dock.regular(activity); setTextColor(org.streamdock.app.Dock.ink); inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI; setText(prefs.getString("resolver","")) }
        resolver.addView(resolverField); advancedBody.addView(resolver)
        advancedBody.addView(CinemaUi.button(activity,"Use this resolver",secondary=true) {
            try {
                val value = TeraPolicy.normalize(resolverField.text?.toString().orEmpty())
                TeraPolicy.backend(value)
                prefs.edit().putString("resolver",value).apply()
                cancel(); engine = TeraEngine(value); transfer = TeraTransfer(engine.client)
                resolverField.setText(value)
                resolverLabel.text = if (value.isEmpty()) "Resolver: direct TeraBox resolution." else "Resolver: configured — Analyze share resolves through your service."
            } catch (e: Exception) { status("Resolver rejected: ${message(e)}") }
        })
        resolverLabel = CinemaUi.text(activity,if (engine.backend==null) "Resolver: direct TeraBox resolution." else "Resolver: configured operator service.",11f,CinemaUi.muted)
        advancedBody.addView(resolverLabel)
        rootView.addView(CinemaUi.text(activity,"If TeraBox requires verification for a file, complete its own verification puzzle in the in-app check sheet. Nothing was bypassed.",10f,CinemaUi.muted).apply { setPadding(0,CinemaUi.dp(activity,14),0,0) })
        return rootView
    }
    fun setLink(raw: String) { if (::urlField.isInitialized) urlField.setText(raw.trim()) }
    fun analyze() { if (!::urlField.isInitialized) return; analyzeLink(urlField.text?.toString().orEmpty()) }
    fun analyzeLink(raw: String) {
        if (busy) return
        val source = try { TeraPolicy.validate(raw).toString() } catch (e: Exception) { status("Link rejected: ${message(e)}"); return }
        release(); share = null; listView.removeAllViews(); busy = true; progressView.visibility=View.VISIBLE; progressView.isIndeterminate=true
        status("Resolving the share link directly with TeraBox…")
        activity.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { engine.resolve(source) }
                share = result; render(result)
                status("${result.files.count { !it.directory }} file(s) in this share. Choose Play or Save on a file.")
            } catch (e: Exception) {
                val hint = if (e is TeraTransport) "\nThe app already retried automatically (including HTTP/1.1 and TeraBox mirror hosts). If it keeps happening, switch between Wi-Fi and mobile data — some networks reset connections to TeraBox. Tap Analyze share to try again."
                else "\nTap Analyze share to try again."
                status("Analysis failed: ${message(e)}$hint")
            }
            finally { busy=false; progressView.visibility=View.GONE }
        }
    }
    private fun render(result: TeraShare) {
        listView.removeAllViews()
        val files = result.files.sortedByDescending { !it.directory }
        files.forEach { file ->
            val row = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(0,CinemaUi.dp(activity,10),0,0) }
            val head = LinearLayout(activity).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            val thumb = file.thumb
            if (thumb.isNotBlank() && thumbAllowed(thumb)) {
                val icon = android.widget.ImageView(activity).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    background = Dock.glass(activity, 9f)
                }
                head.addView(icon, LinearLayout.LayoutParams(CinemaUi.dp(activity,52), CinemaUi.dp(activity,52)))
                loadThumb(icon, thumb)
            }
            val meta = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(CinemaUi.dp(activity,10),0,0,0) }
            meta.addView(CinemaUi.text(activity,file.name,12f))
            meta.addView(CinemaUi.text(activity,if (file.directory) "Folder" else size(file.size),10f,CinemaUi.muted))
            head.addView(meta, LinearLayout.LayoutParams(0,-2,1f))
            row.addView(head, LinearLayout.LayoutParams(-1,-2))
            if (file.directory) row.addView(CinemaUi.text(activity,"Open this folder by pasting its own share link.",10f,CinemaUi.muted))
            else {
                val actions = LinearLayout(activity)
                actions.addView(CinemaUi.button(activity,"Play") { play(file) },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                actions.addView(CinemaUi.button(activity,"Save",secondary=true) { save(file) },LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f))
                row.addView(actions)
            }
            listView.addView(row)
        }
        if (files.isEmpty()) listView.addView(CinemaUi.text(activity,"No files were returned for this share.",12f,CinemaUi.muted))
    }

    /* ---- file thumbnails (first-party CDN only; plain HTTPS images, no cookies) ---- */
    private fun thumbAllowed(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host ?: "" }.getOrDefault("")
        return url.startsWith("https://") && (host.contains("terabox") || host.contains("1024tera"))
    }
    private fun loadThumb(target: android.widget.ImageView, url: String) {
        thumbCache[url]?.let { target.setImageBitmap(it); return }
        thumbPool.execute {
            val bitmap = runCatching {
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000; conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", TeraEngine.AGENT)
                conn.inputStream.use { input ->
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                    val sample = (maxOf(bounds.outWidth, bounds.outHeight) / 132).coerceIn(1, 8)
                    val conn2 = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn2.connectTimeout = 8000; conn2.readTimeout = 8000
                    conn2.setRequestProperty("User-Agent", TeraEngine.AGENT)
                    conn2.inputStream.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) }
                }
            }.getOrNull()
            if (bitmap != null) {
                thumbCache[url] = bitmap
                activity.runOnUiThread { runCatching { target.setImageBitmap(bitmap) } }
            }
        }
    }
    private fun play(file: TeraFile) {
        if (busy) return
        val current = share ?: return
        release(); busy=true; progressView.visibility=View.VISIBLE; progressView.isIndeterminate=true
        status("Reading stream for \"${file.name}\" before playback…")
        activity.lifecycleScope.launch {
            try {
                val media = withContext(Dispatchers.IO) { val m=engine.media(current,file,allowPreview=true); engine.probe(m); m }
                startPlayer(file, media)
            } catch (e: Exception) {
                if (needsVerification(e)) {
                    val wall = e as? TeraWall
                    openCheck(file, wall) { play(file) }
                } else status("Playback is not ready: ${message(e)}")
            }
            finally { busy=false; progressView.visibility=View.GONE }
        }
    }
    private fun startPlayer(file: TeraFile, media: TeraMedia) {
        val headers = mapOf("User-Agent" to TeraEngine.AGENT,"Referer" to media.referer)
        org.streamdock.app.player.PlayerActivity.start(
            activity,
            listOf(org.streamdock.app.player.Playable(file.name, if (media.hls) "TeraBox · Preview" else "TeraBox · Full Video", media.url, null, headers, headers, false, media.hls))
        )
        status(if (media.hls) "Playing preview stream of \"${file.name}\" in player." else "Playing full original \"${file.name}\" in player.")
    }
    private fun enqueueSave(file: TeraFile, media: TeraMedia, destination: android.net.Uri) {
        val total = if (media.hls) -1L else file.size
        val id = org.streamdock.app.dl.DownloadCenter.enqueue(
            activity, media.url, media.referer, TeraEngine.AGENT, file.name, mimeFor(file.name), total, destination)
        status(if (media.hls) "Saving 30s preview of \"${file.name}\" to Downloads (Item $id)"
               else "Saving complete file \"${file.name}\" (${size(file.size)}) to Downloads. Item $id")
    }
    private fun save(file: TeraFile) {
        if (busy) return
        pendingFile = file
        val safe = file.name.replace(Regex("[^\\p{L}\\p{N} ._-]"),"_").take(90)
        saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type="application/octet-stream"
            putExtra(Intent.EXTRA_TITLE,safe)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }
    fun cancel() { engine.cancel(); transfer.cancel() }
    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4","m4v","mov" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "aac" -> "audio/aac"; "flac" -> "audio/flac"; "wav" -> "audio/wav"
        "jpg","jpeg" -> "image/jpeg"; "png" -> "image/png"; "pdf" -> "application/pdf"; "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
    fun release() { /* playback lives in the unified player service */ }
    fun status(message: String) { if (::statusView.isInitialized) statusView.text = message }
    private fun message(e: Throwable): String = DiagnosticText.redact(e.message.orEmpty().ifBlank { e.javaClass.simpleName }).take(400)
    /** Verification-required files have no public direct link. Say that plainly and point at the browser,
     *  where the TeraBox page itself can play the file and StreamDock can save the stream it loads. */
    private fun needsVerification(e: Throwable): Boolean =
        e is TeraWall || e is TeraPreviewOnly || message(e).contains("400310") || message(e).contains("preview", ignoreCase = true)
    /**
     * The in-app TeraBox check: TeraBox's own anti-bot step runs inside StreamDock
     * in a compact sheet, then the pending Play/Save is retried automatically.
     * No browser app, no external player, no account login.
     */
    private fun openCheck(
        file: TeraFile,
        wall: TeraWall? = null,
        onPreviewFallback: (() -> Unit)? = null,
        retry: () -> Unit
    ) {
        pendingRetry = retry
        val current = share
        val baseOrigin = current?.origin?.toString()?.removeSuffix("/") ?: "https://www.1024tera.com"
        val reqId = wall?.requestId.orEmpty()
        // IMPORTANT: type MUST be "dragdrop" for the slider puzzle to load; "need_verify_v2" causes HTTP 400
        val checkUrl = if (reqId.isNotBlank()) {
            "$baseOrigin/anticapt?type=dragdrop&requestId=${java.net.URLEncoder.encode(reqId, "UTF-8")}&lang=en&platform=wap"
        } else {
            "$baseOrigin/anticapt?type=dragdrop&lang=en&platform=wap"
        }
        if (checkUrl.isBlank()) { status("Paste a share link first."); return }
        status("Drag the slider puzzle in the sheet to unlock the full original file.")
        var dialog: androidx.appcompat.app.AlertDialog? = null
        var done = false
        val sheet = TeraCheckView(activity, checkUrl) {
            done = true
            dialog?.dismiss()
            TeraCookies.captureAfterCheck(activity)
            status("Verification complete! Unlocking full original inside StreamDock…")
            val action = pendingRetry; pendingRetry = null
            action?.invoke()
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("TeraBox verification")
            .setView(sheet)
            .setNegativeButton("Cancel") { _, _ ->
                pendingRetry = null
                status("Verification cancelled. Tap Play or Save to try again.")
            }
        if (onPreviewFallback != null) {
            builder.setNeutralButton("Use 30s preview") { _, _ ->
                pendingRetry = null
                dialog?.dismiss()
                onPreviewFallback()
            }
        }
        dialog = builder.setOnDismissListener { if (!done) pendingRetry = null }.create()
        dialog?.show()
    }
    private fun size(bytes: Long): String = when { bytes<0 -> "unknown size"; bytes<1024 -> "$bytes B"; bytes<1024*1024 -> "%.1f KB".format(bytes/1024.0); bytes<1024*1024*1024 -> "%.1f MB".format(bytes/1048576.0); else -> "%.2f GB".format(bytes/1073741824.0) }
}
