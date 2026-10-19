package org.streamdock.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Parcel
import android.text.InputType
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** A general-purpose multi-tab web browser. No extraction bridge or account-cookie export. */
class BrowserActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var overlay: FrameLayout
    private lateinit var webHolder: FrameLayout
    private lateinit var back: View
    private lateinit var forward: View
    private lateinit var tabButton: TextView
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: TextView
    private var fullscreen: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private val sniper = MediaSniffer()
    private var teraRow: LinearLayout? = null
    private var pendingCapture: MediaSniffer.Found? = null
    private var pendingCaptureAgent: String = ""
    /** A TeraBox/Baidu file link the page itself produced, waiting for the user's save location. */
    private var pendingTeraSave: Pair<String,String>? = null
    private val io = Executors.newSingleThreadExecutor()
    private val chooseInApp = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val found = pendingCapture; val agent = pendingCaptureAgent
        val tera = pendingTeraSave
        pendingCapture = null; pendingCaptureAgent = ""; pendingTeraSave = null
        if (result.resultCode == RESULT_OK && destination != null && tera != null) {
            val id = org.streamdock.app.dl.DownloadCenter.enqueue(this, tera.first, pageUrl, agent.ifBlank { web.settings.userAgentString }, tera.second, "", -1L, destination)
            status.text = "Saving \"${tera.second}\" in the app engine — pause or resume it in Downloads. Item $id"
            return@registerForActivityResult
        }
        if (result.resultCode == RESULT_OK && destination != null && found != null) {
            val flags = (result.data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(destination, flags) }
            val id = org.streamdock.app.dl.DownloadCenter.enqueue(this, found.url, found.page, agent, MediaSniffer.fileName(found.url), "", -1L, destination)
            status.text="Saving in the app (pause/resume in Downloads). Item $id"
        }
    }
    private var teraLabel: TextView? = null
    /** Updated only on the UI thread. WebView methods must never be called from shouldInterceptRequest's thread. */
    @Volatile private var pageUrl: String = ""
    private var failed = false
    private var ready = false
    private val acceptedHelpers = mutableSetOf<String>()
    private val prefs by lazy { getSharedPreferences("browser",MODE_PRIVATE) }
    private var search=BrowserPolicy.Search.GOOGLE
    private data class FileRequest(val url:String,val agent:String,val filename:String,val mime:String?,val page:String)
    private var pendingFile: FileRequest?=null
    private val storagePermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending=pendingFile;pendingFile=null
        if(granted && pending!=null) enqueue(pending)
        else status.text="Storage permission is needed for browser downloads on this Android version. You can open the page externally instead."
    }

    // ---------------------------------------------------------------- tabs
    private class Tab(val id: Long, val web: WebView) {
        var title: String = "New tab"
    }
    private val tabs = mutableListOf<Tab>()
    private var tabIndex = -1

    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(FontFit.context(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.argb(236,0xEA,0xF4,0xFC)
        if (Build.VERSION.SDK_INT >= 26) window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        search=runCatching { BrowserPolicy.Search.valueOf(prefs.getString("search","GOOGLE")!!) }.getOrDefault(BrowserPolicy.Search.GOOGLE)
        buildUi()
        restoreSession()
        onBackPressedDispatcher.addCallback(this,object:OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if(fullscreen!=null) hideFullscreen() else if(findBar.visibility==View.VISIBLE) closeFind()
                else if(ready && web.canGoBack()) web.goBack() else finish()
            }
        })
        val incoming=intent.getStringExtra("url")
        if(incoming!=null) navigate(incoming)
        else if(tabs.isEmpty()) openTab(search.home)
        intent.removeExtra("url")
    }
    private fun dp(n:Int)=CinemaUi.dp(this,n)
    private fun icon(name:String,label:String,action:()->Unit)=ImageButton(this).apply {
        setImageDrawable(DockIcon(name,Dock.ink));contentDescription=label
        background=CinemaUi.shape(radius=22f,c=this@BrowserActivity)
        setPadding(dp(10),dp(10),dp(10),dp(10));setOnClickListener { action() }
        Dock.depth(this,Dock.px(this@BrowserActivity,22f),Dock.px(this@BrowserActivity,3f))
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(web: WebView) {
        web.settings.apply {
            javaScriptEnabled=true;domStorageEnabled=true
            allowFileAccess=false;allowContentAccess=false
            allowFileAccessFromFileURLs=false;allowUniversalAccessFromFileURLs=false
            mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically=false;setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture=true;builtInZoomControls=true;displayZoomControls=false
            useWideViewPort=true;loadWithOverviewMode=true
            textZoom=prefs.getInt("textZoom",100)
            if(prefs.getBoolean("desktopSite",false)) userAgentString=desktopAgent
            if(Build.VERSION.SDK_INT>=26) safeBrowsingEnabled=true
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false)
        web.webViewClient=object:WebViewClient() {
            override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
                val candidate=request.url.toString()
                if(runCatching { BrowserPolicy.https(candidate) }.isFailure) {
                    if(request.isForMainFrame) status.text="Blocked an unsupported or insecure address. Open the website externally if needed."
                    return true
                }
                if(request.isForMainFrame && BrowserPolicy.helper(candidate) && BrowserPolicy.host(candidate) !in acceptedHelpers) { navigate(candidate);return true }
                return false
            }
            override fun onPageStarted(view:WebView,url:String?,favicon:Bitmap?) {
                pageUrl=url.orEmpty()
                failed=false;bar.visibility=View.VISIBLE
                if(!address.hasFocus()) address.setText(url.orEmpty())
                status.text=if(BrowserPolicy.helper(url.orEmpty())) "Third-party web tool • do not provide account cookies" else BrowserPolicy.host(url.orEmpty())
                updateArrows()
                currentTab()?.let { tab ->
                    if(tab.id==currentTab()?.id) {
                        tab.title=BrowserPolicy.host(url.orEmpty())
                        url?.let { recordHistory(it,BrowserPolicy.host(it)) }
                    }
                }
            }
            override fun onPageFinished(view:WebView,url:String?) {
                pageUrl=url.orEmpty()
                bar.visibility=View.GONE;updateArrows();scanPage()
                val page=url.orEmpty()
                val share=runCatching { org.streamdock.app.terabox.TeraPolicy.isShare(page) }.getOrDefault(false)
                teraRow?.visibility=if(share) View.VISIBLE else View.GONE
                if(share) {
                    teraLabel?.text="TeraBox share — three steps:\n1  Complete TeraBox's own check on this page (StreamDock never asks for your password).\n2  Press the page's Play, or its own Download button.\n3  StreamDock saves what the page loads — Play opens here, Download goes to the in-app engine with pause/resume."
                    checkTeraVerify(page)
                }
                val title=view.title
                if(!title.isNullOrBlank()) currentTab()?.let { it.title=title }
                saveSession()
            }
            override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                // Observation only: the request is never modified, answered or replayed.
                // This runs on a WebView background thread, so it must not touch WebView methods
                // (doing that used to terminate the app). Only the volatile page URL is read.
                runCatching {
                    val accept=request.requestHeaders?.get("Accept")
                    sniper.note(request.url.toString(),null,accept,pageUrl)
                }
                return null
            }
            override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError) {
                handler.cancel();failed=true;status.text="Connection blocked: certificate error. No security exception was granted."
            }
            override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError) {
                if(request.isForMainFrame) { failed=true;status.text="This page could not load. Check your connection or open it externally.";bar.visibility=View.GONE }
            }
            override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,response:WebResourceResponse) {
                if(request.isForMainFrame) { failed=true;status.text="Website returned HTTP ${response.statusCode}. Login or browser restrictions may apply." }
            }
            override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean {
                ready=false;webHolder.removeView(view);view.destroy();bar.visibility=View.GONE
                removeTabByWebView(view)
                webHolder.addView(CinemaUi.button(this@BrowserActivity,"Restart browser") { recreate() })
                status.text="The web renderer stopped. Native downloads are separate.";return true
            }
        }
        web.webChromeClient=object:WebChromeClient() {
            override fun onProgressChanged(view:WebView,newProgress:Int) { bar.progress=newProgress;if(newProgress>=100) bar.visibility=View.GONE }
            override fun onReceivedTitle(view:WebView,title:String?) { title?.let { t -> currentTab()?.let { if(it.web===view) it.title=t } ; saveSession() } }
            override fun onPermissionRequest(request:PermissionRequest) { request.deny();status.text="Camera and microphone are unavailable here. Open the page externally to use them." }
            override fun onGeolocationPermissionsShowPrompt(origin:String,callback:GeolocationPermissions.Callback) { callback.invoke(origin,false,false) }
            override fun onShowCustomView(view:View,callback:CustomViewCallback) {
                if(fullscreen!=null) { callback.onCustomViewHidden();return }
                fullscreen=view;fullscreenCallback=callback;overlay.addView(view,FrameLayout.LayoutParams(-1,-1));window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }
            override fun onHideCustomView() { hideFullscreen() }
        }
        web.setDownloadListener { url,agent,disposition,mime,_ ->
            runCatching { confirmDownload(url,agent,disposition,mime) }
                .onFailure { status.text="That download could not be started. Open the page externally if it needs a login." }
        }
    }
    private val desktopAgent="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private fun currentTab(): Tab? = tabs.getOrNull(tabIndex)

    private fun openTab(url: String?, restore: Bundle? = null) {
        val tabWeb = WebView(this)
        configureWebView(tabWeb)
        val tab = Tab(nextTabId(), tabWeb)
        tabs.add(tab)
        switchTo(tabs.size-1, restore)
        if (restore==null && !url.isNullOrBlank()) navigate(url)
        else if (restore==null && url==null) navigate(search.home)
        saveSession()
    }
    private fun nextTabId(): Long {
        var max = prefs.getLong("tabSeq",0L)
        max += 1
        prefs.edit().putLong("tabSeq",max).apply()
        return max
    }
    private fun switchTo(index: Int, restore: Bundle? = null) {
        if (index<0 || index>=tabs.size) return
        if (tabIndex in tabs.indices) {
            runCatching { tabs[tabIndex].web.onPause() }
            webHolder.removeView(tabs[tabIndex].web)
        }
        tabIndex = index
        web = tabs[index].web
        if (restore!=null) runCatching { web.restoreState(restore) }
        webHolder.addView(web, FrameLayout.LayoutParams(-1,-1))
        runCatching { web.onResume() }
        ready = true
        pageUrl = web.url.orEmpty()
        if (!address.hasFocus()) address.setText(pageUrl)
        status.text = if (pageUrl.isBlank()) "New tab" else BrowserPolicy.host(pageUrl)
        updateArrows()
        updateTabButton()
    }
    private fun removeTabByWebView(view: WebView) {
        val i = tabs.indexOfFirst { it.web===view }
        if (i>=0) {
            tabs.removeAt(i)
            if (tabs.isEmpty()) openTab(search.home)
            else switchTo(if (tabIndex>=tabs.size) tabs.size-1 else tabIndex)
        }
    }
    private fun closeTab(index: Int) {
        if (index<0 || index>=tabs.size) return
        val tab = tabs.removeAt(index)
        runCatching { tab.web.stopLoading(); webHolder.removeView(tab.web); tab.web.destroy() }
        if (tabs.isEmpty()) openTab(search.home)
        else switchTo(if (index<=tabIndex) (tabIndex-1).coerceAtLeast(0) else tabIndex)
        saveSession()
    }
    private fun updateTabButton() { tabButton.text = "${tabs.size}" }
    private fun tabsDialog() {
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(8),dp(16),dp(4)) }
        tabs.forEachIndexed { i, tab ->
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4),dp(8),dp(4),dp(8))
                setOnClickListener { switchTo(i); saveSession() }
            }
            val fav = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            SiteIcons.load(this, runCatching { BrowserPolicy.host(tab.web.url.orEmpty()) }.getOrDefault(""), fav, 8f, DockIcon("globe", CinemaUi.muted))
            row.addView(fav, LinearLayout.LayoutParams(dp(26),dp(26)))
            row.addView(TextView(this).apply {
                text = tab.title.ifBlank { "New tab" }; textSize = 13f
                setTextColor(Dock.ink)
                typeface = Dock.medium(this@BrowserActivity)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(10),0,dp(6),0)
            }, LinearLayout.LayoutParams(0,-2,1f))
            row.addView(ImageButton(this).apply {
                setImageDrawable(DockIcon("close", CinemaUi.muted)); contentDescription = "Close tab"
                background = null; setPadding(dp(8),dp(8),dp(8),dp(8))
                setOnClickListener { closeTab(i) }
            }, LinearLayout.LayoutParams(dp(36),dp(36)))
            list.addView(row)
            list.addView(View(this).apply { background = CinemaUi.divider(this@BrowserActivity) }, LinearLayout.LayoutParams(-1,dp(1)))
        }
        val scroll = ScrollView(this).apply { addView(list) }
        AlertDialog.Builder(this).setTitle("Tabs (${tabs.size})")
            .setView(scroll)
            .setPositiveButton("New tab") { _,_-> openTab(null) }
            .setNegativeButton("Close",null).show()
    }

    // ------------------------------------------------------- saved session
    private fun sessionDir() = File(filesDir,"browser-session").apply { mkdirs() }
    private fun writeBundle(file: File, bundle: Bundle) = runCatching {
        val parcel = Parcel.obtain()
        parcel.writeBundle(bundle)
        file.writeBytes(parcel.marshall())
        parcel.recycle()
    }
    private fun readBundle(file: File): Bundle? = runCatching {
        val bytes = file.readBytes()
        val parcel = Parcel.obtain()
        parcel.unmarshall(bytes,0,bytes.size)
        parcel.setDataPosition(0)
        val bundle = parcel.readBundle(javaClass.classLoader)
        parcel.recycle()
        bundle
    }.getOrNull()
    /** Persists every open tab (URL + title + navigation state) so the browsing session survives restarts. */
    private fun saveSession() {
        val snapshot = tabs.map { Triple(it.id, it.web.url.orEmpty(), it.title) }
        val states = HashMap<Long, Bundle>()
        tabs.forEach { tab ->
            val state = Bundle()
            runCatching { tab.web.saveState(state) }   // must run on this (UI) thread
            states[tab.id] = state
        }
        val currentId = currentTab()?.id ?: 0L
        io.execute {
            runCatching {
                val json = JSONObject()
                val array = JSONArray()
                snapshot.forEach { (id,url,title) ->
                    array.put(JSONObject().put("id",id).put("url",url).put("title",title))
                }
                json.put("tabs",array).put("current",currentId)
                File(sessionDir(),"session.json").writeText(json.toString())
                states.forEach { (id, bundle) -> writeBundle(File(sessionDir(),"state_$id.bin"),bundle) }
            }
        }
    }
    private fun restoreSession() {
        val file = File(sessionDir(),"session.json")
        if (!file.exists()) return
        runCatching {
            val json = JSONObject(file.readText())
            val array = json.optJSONArray("tabs") ?: return
            val currentId = json.optLong("current",0L)
            var wanted = 0
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val id = entry.optLong("id")
                val url = entry.optString("url")
                if (url.isBlank()) continue
                val webview = WebView(this)
                configureWebView(webview)
                val tab = Tab(id,webview)
                tab.title = entry.optString("title").ifBlank { BrowserPolicy.host(url) }
                tabs.add(tab)
                val stateFile = File(sessionDir(),"state_$id.bin")
                if (stateFile.exists()) {
                    val bundle = readBundle(stateFile)
                    if (bundle != null) runCatching { webview.restoreState(bundle) } else webview.loadUrl(url)
                } else webview.loadUrl(url)
                if (id==currentId) wanted = tabs.size-1
            }
            if (tabs.isNotEmpty()) switchTo(wanted) else openTab(search.home)
        }.onFailure { openTab(search.home) }
        if (tabs.isEmpty()) openTab(search.home)
    }

    // ------------------------------------------------------- history & bookmarks
    private fun historyFile() = File(filesDir,"browser-history.json")
    private fun recordHistory(url: String, title: String) {
        io.execute {
            runCatching {
                val array = runCatching { JSONArray(historyFile().readText()) }.getOrElse { JSONArray() }
                val entry = JSONObject().put("url",url).put("title",title).put("t",System.currentTimeMillis())
                val next = JSONArray()
                next.put(entry)
                var kept = 1
                for (i in 0 until array.length()) if (kept < 400 && array.getJSONObject(i).optString("url")!=url) {
                    next.put(array.getJSONObject(i)); kept++
                }
                historyFile().writeText(next.toString())
            }
        }
    }
    private fun historyDialog() {
        val entries = runCatching {
            val array = JSONArray(historyFile().readText())
            (0 until array.length()).map { array.getJSONObject(it) }
        }.getOrElse { emptyList() }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle("History").setMessage("Pages you visit appear here.")
                .setPositiveButton("Close",null).show(); return
        }
        val labels = entries.map { "${it.optString("title").ifBlank { BrowserPolicy.host(it.optString("url")) }}\n${it.optString("url")}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("History (${entries.size})")
            .setItems(labels) { _,i -> navigate(entries[i].optString("url")) }
            .setNeutralButton("Clear history") { _,_ ->
                AlertDialog.Builder(this).setTitle("Clear history?")
                    .setMessage("The visited-pages list on this device is deleted. Open tabs and bookmarks are kept.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Clear") { _,_-> io.execute { runCatching { historyFile().delete() } }; status.text="History cleared." }.show()
            }
            .setNegativeButton("Close",null).show()
    }
    private fun bookmarksFile() = File(filesDir,"browser-bookmarks.json")
    private fun bookmarkList(): List<JSONObject> = runCatching {
        val array = JSONArray(bookmarksFile().readText())
        (0 until array.length()).map { array.getJSONObject(it) }
    }.getOrElse { emptyList() }
    private fun addBookmark() {
        val url = if(ready) web.url.orEmpty() else ""
        if (url.isBlank()) { status.text = "Open a page first, then bookmark it."; return }
        io.execute {
            runCatching {
                val array = JSONArray()
                array.put(JSONObject().put("url",url).put("title",web.title.orEmpty().ifBlank { BrowserPolicy.host(url) }))
                bookmarkList().forEach { if (it.optString("url")!=url) array.put(it) }
                bookmarksFile().writeText(array.toString())
            }
        }
        status.text = "Bookmarked this page."
    }
    private fun bookmarksDialog() {
        val entries = bookmarkList()
        if (entries.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Bookmarks")
                .setMessage("No bookmarks yet. Use “Bookmark this page” in the menu.")
                .setPositiveButton("Close",null).show(); return
        }
        val labels = entries.map { "${it.optString("title").ifBlank { BrowserPolicy.host(it.optString("url")) }}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Bookmarks")
            .setItems(labels) { _,i -> navigate(entries[i].optString("url")) }
            .setNeutralButton("Remove all") { _,_ ->
                io.execute { runCatching { bookmarksFile().delete() } }
                status.text = "Bookmarks removed."
            }
            .setNegativeButton("Close",null).show()
    }

    // ------------------------------------------------------- find in page
    private fun openFind() {
        if (!ready) return
        findBar.visibility = View.VISIBLE
        findInput.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findInput,InputMethodManager.SHOW_IMPLICIT)
    }
    private fun closeFind() {
        findBar.visibility = View.GONE
        if (ready) web.clearMatches()
        findInput.setText("")
        findCount.text = ""
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildUi() {
        overlay=FrameLayout(this)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=CinemaUi.backdrop(this@BrowserActivity) }
        overlay.addView(root,FrameLayout.LayoutParams(-1,-1));setContentView(overlay)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        val header=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(3),dp(10),dp(3)) }
        header.addView(ChromeText(this,"Browser",20f,.01f),LinearLayout.LayoutParams(0,-2,1f))
        val engines=Spinner(this).apply {
            adapter=ArrayAdapter(this@BrowserActivity,android.R.layout.simple_spinner_dropdown_item,BrowserPolicy.Search.entries)
            contentDescription="Search engine";minimumHeight=dp(48);setSelection(search.ordinal)
            onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent:AdapterView<*>?) { }
                override fun onItemSelected(parent:AdapterView<*>?,v:View?,position:Int,id:Long) {
                    search=BrowserPolicy.Search.entries[position];prefs.edit().putString("search",search.name).apply()
                }
            }
        CinemaUi.spinnerChrome(this@BrowserActivity,this)
        };header.addView(engines,LinearLayout.LayoutParams(dp(112),dp(44)))
        tabButton=TextView(this).apply {
            text="1";gravity=Gravity.CENTER;textSize=13f;typeface=Dock.bold(this@BrowserActivity)
            setTextColor(Dock.ink);background=CinemaUi.shape(radius=18f,c=this@BrowserActivity)
            contentDescription="Open tabs";setPadding(dp(12),dp(10),dp(12),dp(10))
            Dock.depth(this,Dock.px(this@BrowserActivity,18f),Dock.px(this@BrowserActivity,3f))
            setOnClickListener { tabsDialog() }
        }
        header.addView(tabButton,LinearLayout.LayoutParams(dp(44),dp(42)).apply { leftMargin=dp(5) })
        header.addView(icon("tools","Browser options") { options() },LinearLayout.LayoutParams(dp(42),dp(42)).apply { leftMargin=dp(5) })
        root.addView(View(this).apply { background=CinemaUi.divider(this@BrowserActivity) },LinearLayout.LayoutParams(-1,dp(1)))
        root.addView(header)
        val searchRow=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),0,dp(10),dp(5)) }
        address=EditText(this).apply {
            hint="Search or enter an HTTPS address";setSingleLine();textSize=13.5f;setTextColor(Dock.ink);typeface=Dock.regular(this@BrowserActivity);setHintTextColor(CinemaUi.muted)
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI;imeOptions=EditorInfo.IME_ACTION_GO
            isSaveEnabled=false;setSelectAllOnFocus(true);background=CinemaUi.shape(radius=22f,c=this@BrowserActivity);setPadding(dp(16),0,dp(8),0)
            setOnEditorActionListener { _,action,event ->
                if(action==EditorInfo.IME_ACTION_GO || (event?.keyCode==KeyEvent.KEYCODE_ENTER && event.action==KeyEvent.ACTION_UP)) {
                    submitAddress();true
                } else false
            }
        }
        searchRow.addView(address,LinearLayout.LayoutParams(0,dp(44),1f))
        searchRow.addView(icon("search","Go / search") { submitAddress() },LinearLayout.LayoutParams(dp(44),dp(44)).apply { leftMargin=dp(5) })
        root.addView(searchRow)
        findBar=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE
            setPadding(dp(10),dp(2),dp(10),dp(4));background=CinemaUi.glassPane(this@BrowserActivity,18f,1f)
        }
        findInput=EditText(this).apply {
            hint="Find on page";setSingleLine();textSize=13f;setTextColor(Dock.ink);typeface=Dock.regular(this@BrowserActivity)
            inputType=InputType.TYPE_CLASS_TEXT;imeOptions=EditorInfo.IME_ACTION_SEARCH;background=CinemaUi.shape(radius=18f,c=this@BrowserActivity)
            setPadding(dp(14),0,dp(8),0)
            setOnEditorActionListener { _,action,_ ->
                if(action==EditorInfo.IME_ACTION_SEARCH) { findNext(true);true } else false
            }
        }
        findBar.addView(findInput,LinearLayout.LayoutParams(0,dp(40),1f))
        findCount=CinemaUi.text(this,"",11f,CinemaUi.muted).apply { setPadding(dp(8),0,dp(4),0) }
        findBar.addView(findCount)
        findBar.addView(icon("back","Previous match") { findNext(false) },LinearLayout.LayoutParams(dp(38),dp(38)))
        findBar.addView(icon("forward","Next match") { findNext(true) },LinearLayout.LayoutParams(dp(38),dp(38)).apply { leftMargin=dp(3) })
        findBar.addView(icon("close","Close find") { closeFind() },LinearLayout.LayoutParams(dp(38),dp(38)).apply { leftMargin=dp(3) })
        root.addView(findBar)
        status=CinemaUi.text(this,"HTTPS browsing • websites control their own content",10f,CinemaUi.muted).apply { setPadding(dp(12),dp(2),dp(12),dp(5)) }
        root.addView(status)
        bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=100;progressTintList=android.content.res.ColorStateList.valueOf(Dock.accent);progressBackgroundTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.argb(90,255,255,255)) }
        root.addView(bar,LinearLayout.LayoutParams(-1,dp(2)))
        webHolder=FrameLayout(this);root.addView(webHolder,LinearLayout.LayoutParams(-1,0,1f))
        val teraPanel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),dp(5),dp(12),dp(6));visibility=View.GONE;background=CinemaUi.glassPane(this@BrowserActivity,20f,1f) }
        teraLabel=CinemaUi.text(this,"",11f,CinemaUi.muted);teraPanel.addView(teraLabel)
        teraPanel.addView(CinemaUi.button(this,"Open in the TeraBox engine") {
            val url=if(ready) web.url.orEmpty() else ""
            if(url.isNotBlank()) goToApp(1,url) else status.text="Open a TeraBox share page first."
        })
        teraPanel.addView(CinemaUi.button(this,"Open in Chromium (full Chrome)",secondary=true) { openInChromium() })
        teraRow=teraPanel;root.addView(teraPanel,LinearLayout.LayoutParams(-1,-2))
        val controls=LinearLayout(this).apply { gravity=Gravity.CENTER;setPadding(dp(10),dp(3),dp(10),dp(3)) }
        back=icon("back","Back") { if(ready && web.canGoBack()) web.goBack() };controls.addView(back,LinearLayout.LayoutParams(0,dp(44),1f))
        forward=icon("forward","Forward") { if(ready && web.canGoForward()) web.goForward() };controls.addView(forward,LinearLayout.LayoutParams(0,dp(44),1f))
        controls.addView(icon("reload","Reload / stop") { if(ready) { if(bar.visibility==View.VISIBLE) { web.stopLoading();bar.visibility=View.GONE } else web.reload() } },LinearLayout.LayoutParams(0,dp(44),1f))
        controls.addView(icon("download","Download or play media on this page") { mediaAndDownloads() },LinearLayout.LayoutParams(0,dp(44),1f))
        controls.addView(icon("chrome","Open this page in Chromium (Chrome)") { openInChromium() },LinearLayout.LayoutParams(0,dp(44),1f))
        root.addView(controls)
        root.addView(CinemaUi.nav(this,2) { tab -> if(tab!=2) goToApp(tab) })
        try {
            // WebViews are created per tab in openTab()/restoreSession(); verify the provider works.
            val probe=WebView(this);probe.destroy()
            ready=true
        } catch(_:Exception) {
            ready=false;status.text="Android System WebView is unavailable. Update it or use an external browser."
            webHolder.addView(CinemaUi.button(this,"Open external browser") { external(search.home) })
        }
    }
    private fun findNext(forwardStep: Boolean) {
        if (!ready) return
        val query = findInput.text.toString()
        if (query.isBlank()) return
        web.findAllAsync(query)
        web.setFindListener(object : WebView.FindListener {
            override fun onFindResultReceived(activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
                if (!isDoneCounting) return
                findCount.text = if (numberOfMatches==0) "0/0" else "${activeMatchOrdinal+1}/$numberOfMatches"
            }
        })
        if (!forwardStep) web.findNext(false)
    }
    private fun submitAddress() {
        try { navigate(BrowserPolicy.input(address.text.toString(),search)) }
        catch(e:Exception) { status.text=e.message }
    }
    private fun navigate(raw:String) {
        if(!ready) return
        val url=try { BrowserPolicy.https(raw) } catch(e:Exception) { status.text=e.message;return }
        if(prefs.getString("engine","INAPP")=="CHROMIUM" && Chromium.installed(this)) {
            address.setText(url); pageUrl=url
            status.text="Opening in Chromium (Chrome) — tap the download button later for the in-app capture engine."
            if(!Chromium.open(this,url)) status.text="Chromium could not open this page."
            return
        }
        val host=BrowserPolicy.host(url)
        if(BrowserPolicy.helper(url) && host !in acceptedHelpers) {
            AlertDialog.Builder(this).setTitle("External web tool")
                .setMessage("$host is an independent website, not a StreamDock engine. It may process links you paste, show ads, or stop working. Never give it your TeraBox password or account cookies. No link is submitted automatically.")
                .setNegativeButton("Cancel",null).setPositiveButton("Visit website") { _,_->acceptedHelpers+=host;navigate(url) }.show();return
        }
        address.clearFocus();web.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken,0)
        pageUrl=url;address.setText(url);web.loadUrl(url)
    }
    private fun updateArrows() { back.isEnabled=ready && web.canGoBack();forward.isEnabled=ready && web.canGoForward();back.alpha=if(back.isEnabled) 1f else .35f;forward.alpha=if(forward.isEnabled) 1f else .35f }
    private fun external(raw:String?=if(ready) pageUrl else search.home) {
        val target=runCatching { BrowserPolicy.https(raw.orEmpty()) }.getOrElse { status.text="No HTTPS page to open externally."; return }
        AlertDialog.Builder(this).setTitle("Leave StreamDock?")
            .setMessage("This opens $target in another browser app. Your StreamDock browsing session stays here.")
            .setNegativeButton("Stay",null)
            .setPositiveButton("Open externally") { _,_->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(target)).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    .onFailure { status.text="No external app could open this HTTPS address." }
            }.show()
    }
    private fun sharePage() {
        val url=if(ready) web.url.orEmpty() else ""
        if(url.isBlank()) { status.text="Open a page first."; return }
        runCatching {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type="text/plain";putExtra(Intent.EXTRA_TEXT,url)
            },"Share page"))
        }.onFailure { status.text="No app could share this page." }
    }
    private fun sendPage() {
        val url=if(ready) web.url.orEmpty() else ""
        if(BrowserPolicy.browserOnly(url)) {
            AlertDialog.Builder(this).setTitle("TeraBox page detected")
                .setMessage("Use the Tera tab for TeraBox shares: it lists the files natively without asking for your account. Here in the browser you can also play the page normally; media the page itself loads appears under the video button for Play or Save. Website cookies are never exported to a download.")
                .setNegativeButton("Close",null).setPositiveButton("Open Tera tab") { _,_-> goToApp(1,url) }.show();return
        }
        runCatching { BrowserPolicy.https(url) }.onSuccess { goToApp(0,it) }.onFailure { status.text="Open a supported media page first." }
    }
    private fun goToApp(tab:Int,url:String?=null) {
        if(isFinishing||isDestroyed) return
        saveSession()
        val intent=Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("tab",tab).apply { url?.let { putExtra("browserMediaUrl",it) } }
        runCatching { startActivity(intent) }
            .onSuccess { finish() }
            .onFailure { status.text="Could not switch screens. Use the bottom bar to go Home." }
    }
    private fun options() {
        val items=arrayOf(
            "New tab","Switch tab","Bookmark this page","Bookmarks","History",
            "Find on page","Desktop site","Text size","Share this page",
            "Search engine home","Download or play media on this page","Send page to Home analyzer",
            "Open this page in Chromium (Chrome)","Use Chromium for every page (toggle)","Open externally",
            "Browser engine & updates","Clear website data","About privacy")
        AlertDialog.Builder(this).setTitle("Browser tools").setItems(items) { _,index -> when(index) {
            0->openTab(null)
            1->tabsDialog()
            2->addBookmark()
            3->bookmarksDialog()
            4->historyDialog()
            5->openFind()
            6->toggleDesktopSite()
            7->textSizeDialog()
            8->sharePage()
            9->navigate(search.home);10->mediaAndDownloads();11->sendPage();12->openInChromium();13->toggleChromiumDefault();14->external();15->browserEngine()
            16->AlertDialog.Builder(this).setTitle("Clear website data?").setMessage("Cookies, website storage, cached pages and browser navigation will be cleared. You may be signed out. Downloaded files are not deleted; this does not revoke server sessions on other devices.")
                .setNegativeButton("Cancel",null).setPositiveButton("Clear") { _,_->
                    if(ready) { hideFullscreen();web.stopLoading();web.loadUrl("about:blank");web.clearHistory();web.clearCache(true);web.clearFormData() }
                    CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush();WebStorage.getInstance().deleteAllData();if(!isFinishing && !isDestroyed) recreate() }
                }.show()
            else->AlertDialog.Builder(this).setTitle("Your browser, your choice")
                .setMessage("Search queries go to the selected search provider. Websites receive normal web requests and can store first-party cookies on this device. Third-party cookies, file access, certificate bypass and native JavaScript bridges are disabled. Browsing sessions are never exported to native engines or web-download requests. Media the page itself loads is listed by the download button so you can play or save it; DRM and blob-only players stay inside the website. Some logins, popups, uploads and blob downloads require an external browser. Most sites already expose their own media this way when you press play.")
                .setPositiveButton("Got it",null).show()
        } }.show()
    }
    private fun toggleDesktopSite() {
        val on = !prefs.getBoolean("desktopSite",false)
        prefs.edit().putBoolean("desktopSite",on).apply()
        if(ready) {
            web.settings.userAgentString = if(on) desktopAgent else WebSettings.getDefaultUserAgent(this)
            web.reload()
        }
        status.text = if(on) "Desktop site on — pages load their computer layout." else "Mobile site on."
    }
    private fun textSizeDialog() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(16),dp(12),dp(16),dp(4))
        }
        val label = TextView(this).apply { textSize = 15f; setTextColor(Dock.ink); gravity = Gravity.CENTER }
        var zoom = prefs.getInt("textZoom",100)
        fun render() { label.text = "$zoom%" }
        render()
        row.addView(icon("minus","Smaller text") {
            zoom = (zoom-10).coerceAtLeast(70); prefs.edit().putInt("textZoom",zoom).apply()
            if(ready) web.settings.textZoom = zoom
            render()
        },LinearLayout.LayoutParams(dp(42),dp(42)))
        row.addView(label,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(icon("plus","Larger text") {
            zoom = (zoom+10).coerceAtMost(200); prefs.edit().putInt("textZoom",zoom).apply()
            if(ready) web.settings.textZoom = zoom
            render()
        },LinearLayout.LayoutParams(dp(42),dp(42)))
        AlertDialog.Builder(this).setTitle("Text size").setView(row)
            .setPositiveButton("Done",null).show()
    }
    /** The in-app browser is Chromium, supplied by Android (Android System WebView or Chrome's provider).
     *  Updating that component is the way to get a newer Chromium engine here. */
    private fun browserEngine() {
        val chromiumPkg = Chromium.provider(this)
        val chromiumLine = if (chromiumPkg != null) "Chromium available: ${Chromium.label(this, chromiumPkg)} — use it directly for every feature, with your own browser session."
            else "Chromium (Chrome) is not installed; only the in-app engine is available. Install Chrome to use it directly."
        val defaultLine = "Current setting: links open in " + if(prefs.getString("engine","INAPP")=="CHROMIUM") "Chromium (Chrome)." else "the in-app capture engine."
        val provider = if (Build.VERSION.SDK_INT >= 26) android.webkit.WebView.getCurrentWebViewPackage() else null
        val details = if (provider != null) {
            val label = runCatching { provider.applicationInfo?.loadLabel(packageManager)?.toString() }.getOrNull().orEmpty().ifBlank { provider.packageName }
            "$label ${provider.versionName ?: ""} (${provider.packageName})"
        } else "unknown on this Android version"
        val message = "StreamDock's in-app browser runs on the Chromium engine Android provides: currently $details.\n\n$chromiumLine\n$defaultLine\n\n• The in-app engine is the one that can capture a page's media for the in-app saver.\n• Chromium (Chrome) gives the complete feature set and your own session, but pages it plays cannot be captured by StreamDock — use it for browsing, not for saving streams.\n• Update either engine from the Play Store for current Chromium security fixes."
        val builder = AlertDialog.Builder(this).setTitle("Browser engine").setMessage(message).setNegativeButton("Close",null)
        if (provider != null) builder.setPositiveButton("Update") { _,_->
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${provider.packageName}"))
            val webPage = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${provider.packageName}"))
            runCatching { startActivity(market) }.onFailure { runCatching { startActivity(webPage) }.onFailure { status.text="No store app could open the update page." } }
        }
        builder.show()
    }
    /** Real Chromium, directly: the page opens in the installed Chrome/Chromium Custom Tabs provider,
     *  with the full engine and the user's own browser session. Nothing about this app is handed over. */
    private fun openInChromium() {
        val url = runCatching { BrowserPolicy.https(if(ready) pageUrl else search.home) }.getOrElse { status.text="No HTTPS page to open."; return }
        if (Chromium.open(this, url)) {
            status.text = "Opened in " + (Chromium.provider(this)?.let { Chromium.label(this, it) } ?: "Chromium") + " — full engine, your own session. Back returns here."
        } else {
            AlertDialog.Builder(this).setTitle("Chromium not found")
                .setMessage("No Chromium-based browser (Chrome) is installed, so the full engine is unavailable. Install Chrome from the store, or keep using StreamDock's in-app engine (Android System WebView, same Chromium core but fewer features).")
                .setNegativeButton("Stay",null)
                .setPositiveButton("Get Chrome") { _,_-> runCatching { startActivity(Chromium.storeIntent(this)) }.onFailure { status.text="No store app could open the Chrome page." } }
                .show()
        }
    }
    /** Default-engine switch so the user can choose real Chromium for every page. */
    private fun toggleChromiumDefault() {
        val useChromium = prefs.getString("engine","INAPP") == "CHROMIUM"
        val next = if(useChromium) "INAPP" else "CHROMIUM"
        if(next=="CHROMIUM" && !Chromium.installed(this)) { openInChromium(); return }
        prefs.edit().putString("engine",next).apply()
        status.text = if(next=="CHROMIUM") "Links now open directly in Chromium (Chrome), where the in-app media capture is not available; switch back here when you want to save a page's stream." else "Links now open in the in-app capture engine again."
    }
    /** Reads media URLs the page itself declared (video/source/audio/og:video). No bridge is injected and nothing is posted anywhere. */
    private fun scanPage() {
        if(!ready) return
        val script="""(function(){var out=[];function add(u){if(!u)return;u=String(u);if(u.indexOf('http')!==0)return;if(out.indexOf(u)<0)out.push(u)}
var nodes=document.querySelectorAll('video,audio,source');for(var i=0;i<nodes.length;i++){var n=nodes[i];add(n.currentSrc);add(n.src)}
var metas=document.querySelectorAll('meta[property="og:video"],meta[property="og:video:url"],meta[property="og:video:secure_url"],meta[name="twitter:player:stream"]');for(var j=0;j<metas.length;j++){add(metas[j].content)}
return JSON.stringify(out.slice(0,40));})()"""
        runCatching {
            web.evaluateJavascript(script) { result ->
                val page=pageUrl
                runCatching {
                    val text=result?.trim()?.removeSurrounding("\"")?.replace("\\u002F","/")?.replace("\\/","/") ?: return@evaluateJavascript
                    val array=org.json.JSONArray(text)
                    var added=0
                    for(i in 0 until array.length()) if(sniper.note(array.optString(i),null,null,page)) added++
                    if(added>0 && !isFinishing && !isDestroyed) status.text="Media found on this page: ${sniper.size()} item(s). Use the video button to play or save."
                }
            }
        }
    }
    /** TeraBox answers verification-walled files with `need verify_v2`. When its own page shows that check or a
     *  captcha, say so plainly: the user completes TeraBox's own step on the page (no password goes to StreamDock),
     *  then the page plays and the media button can save the stream that the page loads. */
    private fun checkTeraVerify(page:String) {
        val host=runCatching { BrowserPolicy.host(page) }.getOrDefault("")
        if(!host.contains("terabox") && !host.contains("1024tera") && !host.contains("dubox")) return
        val script="(function(){var t=document.body?document.body.innerText:'';var hasPlay=!!document.querySelector('video,audio,.player,[class*=play]');" +
            "return JSON.stringify({v:/verify_v2|verification|captcha|\\u9a8c\\u8bc1/i.test(t)||/verify/i.test(location.href),p:hasPlay});})()"
        runCatching { web.evaluateJavascript(script) { result ->
            val text=result.orEmpty()
            if(text.contains("\"v\":true")) status.text="TeraBox is showing its own verification step on this page. Complete it here (StreamDock never asks for your password), then press Play — the media button can save the stream the page loads."
            else if(text.contains("\"p\":true")) status.text="Player found. Press Play on the page once; then use the media button to play or save the stream it loads."
        } }
    }
    private fun mediaAndDownloads() {
        scanPage()
        if(sniper.size()>0) showMedia() else showEmptyMedia()
    }
    private fun showEmptyMedia() {
        val page=if(ready) web.url.orEmpty() else ""
        val tera=page.isNotBlank() && runCatching { org.streamdock.app.terabox.TeraPolicy.isShare(page) }.getOrDefault(false)
        val message=if(tera)
            "This is a TeraBox share. Listings work natively (Tera tab), but TeraBox mints direct file links only for a verified session. Press Play on this page: if TeraBox shows its own verification or captcha, complete it right here — StreamDock never asks for your password. Once the page plays, the media button lists the stream it loaded and Save stores it in the in-app engine (HLS segments are fetched in order and can be paused)."
            else "StreamDock lists media the page itself loads or declares. Start playback on the page, then tap again. DRM players and blob-only streams never expose a reusable file link; those stay in the website."
        val builder=AlertDialog.Builder(this).setTitle(if(tera) "Play it on the TeraBox page" else "No media captured yet")
            .setMessage(message)
            .setNegativeButton("Close",null)
        if(tera) builder.setNeutralButton("Reload page") { _,_-> if(ready) { web.reload();status.text="Reloading the share page…" } }
        else builder.setNeutralButton("Send page to Home") { _,_-> sendPage() }
        builder.setPositiveButton("Scan again") { _,_-> scanPage();status.text="Scanning this page for media…" }
        builder.show()
    }
    private fun showMedia() {
        val found=sniper.list()
        if(found.isEmpty()) { showEmptyMedia();return }
        val labels=found.map { "${it.kind.label} • ${MediaSniffer.fileName(it.url)}\n${BrowserPolicy.host(it.url)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Media on this page (${found.size})")
            .setItems(labels) { _,_index-> openCaptured(found[_index]) }
            .setNeutralButton("Clear list") { _,_-> sniper.clear();status.text="Captured media list cleared." }
            .setNegativeButton("Close",null).show()
    }
    private fun openCaptured(found:MediaSniffer.Found) {
        val agent=runCatching { if(ready) web.settings.userAgentString else null }.getOrNull().orEmpty()
        val detail=if(found.kind==MediaSniffer.Kind.STREAM)
            "Streaming playlist (HLS). Save fetches the segments in order inside the app, so you can pause and resume it in Downloads; encrypted (DRM) streams are refused honestly."
            else "StreamDock plays this with the same User-Agent and Referer the page used. Saving runs in the in-app engine (pause/resume in Downloads); website cookies and authorization headers are never attached. Save only media you are allowed to keep."
        AlertDialog.Builder(this).setTitle(MediaSniffer.fileName(found.url))
            .setMessage("${found.kind.label}\n${BrowserPolicy.host(found.url)}\n\n$detail")
            .setNegativeButton("Close",null)
            .setNeutralButton("Save file") { _,_-> saveCaptured(found,agent) }
            .setPositiveButton("Play") { _,_->
                startActivity(Intent(this,MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra("tab",0)
                    .putExtra("browserPlayUrl",found.url)
                    .putExtra("browserPlayReferer",found.page)
                    .putExtra("browserPlayName",MediaSniffer.fileName(found.url))
                    .putExtra("browserPlayAgent",agent))
            }.show()
    }
    /** In-app save: multi-connection, pausable and resumable, with no website cookie attached. */
    private fun saveCaptured(found:MediaSniffer.Found, agent:String) {
        pendingCapture=found; pendingCaptureAgent=agent
        chooseInApp.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type=mimeFor(found.kind)
            putExtra(Intent.EXTRA_TITLE,MediaSniffer.fileName(found.url))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }
    private fun mimeFor(kind:MediaSniffer.Kind)=when(kind){
        MediaSniffer.Kind.STREAM->"application/x-mpegurl";MediaSniffer.Kind.VIDEO->"video/*";MediaSniffer.Kind.AUDIO->"audio/*"
    }
    /** TeraBox (and its Baidu CDN) file links work best with the Referer of the page that produced them,
     *  and they deserve pause/resume — so those go to the in-app engine instead of Android's download service. */
    private fun isTeraFileLink(url:String):Boolean {
        val host=runCatching { BrowserPolicy.host(url) }.getOrDefault("")
        return listOf("terabox","1024tera","dubox","pcs.baidu","baidupcs","baidupan","bcebos","pcsdn").any { host.contains(it) }
    }
    private fun confirmDownload(raw:String,agent:String,disposition:String?,mime:String?) {
        if(runCatching { BrowserPolicy.https(raw) }.isFailure) {
            AlertDialog.Builder(this).setTitle("Open externally to save")
                .setMessage("This site uses a blob, app-specific or insecure download address. StreamDock does not extract these streams.")
                .setNegativeButton("Cancel",null).setPositiveButton("Open page") { _,_->external() }.show();return
        }
        val name=BrowserPolicy.safeName(URLUtil.guessFileName(raw,disposition,mime))
        if(isTeraFileLink(raw)) {
            AlertDialog.Builder(this).setTitle("Save this TeraBox file?")
                .setMessage("$name\n\nTeraBox issued this link to the page itself, so StreamDock saves it with the page as Referer. It goes to the in-app download engine, where you can pause and resume it; nothing is faked and no account cookie is attached.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Choose folder") { _,_->
                    pendingTeraSave = raw to name
                    chooseInApp.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = mime ?: "application/octet-stream"
                        putExtra(Intent.EXTRA_TITLE, name)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    })
                }.show()
            return
        }
        val file=FileRequest(raw,agent,name,mime,pageUrl)
        AlertDialog.Builder(this).setTitle("Download file?")
            .setMessage("$name\n\nAndroid will save this HTTPS file link to your Downloads folder. Website cookies and authorization headers are NOT passed. If the link requires login or fails, open the page externally. Download only files you trust and have permission to use.")
            .setNegativeButton("Cancel",null).setNeutralButton("Open page externally") { _,_->external() }
            .setPositiveButton("Download") { _,_->
                if(Build.VERSION.SDK_INT<=28 && ContextCompat.checkSelfPermission(this,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
                    pendingFile=file;storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else enqueue(file)
            }.show()
    }
    private fun enqueue(file:FileRequest) {
        try { WebDownloads.enqueue(this,file.url,file.filename,file.mime,file.agent,file.page);status.text="Download queued. Track it in Downloads." }
        catch(_:Exception) { status.text="Android could not start this download. Check storage or open the page externally." }
    }
    private fun hideFullscreen() { fullscreen?.let { overlay.removeView(it) };fullscreen=null;fullscreenCallback?.onCustomViewHidden();fullscreenCallback=null;window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) }
    override fun onResume() { super.onResume();if(ready) web.onResume() }
    override fun onPause() { if(ready) { web.onPause();CookieManager.getInstance().flush();saveSession() };super.onPause() }
    override fun onDestroy() {
        hideFullscreen();saveSession()
        if(ready) runCatching { web.stopLoading() }
        tabs.forEach { runCatching { it.web.destroy() } }
        tabs.clear();ready=false
        super.onDestroy()
    }
}
