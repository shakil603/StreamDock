package org.streamdock.app

import android.Manifest
import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.*
import org.streamdock.app.dl.DownloadCenter
import org.streamdock.app.dl.DownloadItem
import org.streamdock.app.dl.DlStatus
import org.streamdock.app.dl.TransferService
import org.streamdock.app.terabox.TeraBoxController
import org.streamdock.app.terabox.TeraPolicy
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val mint = CinemaUi.red
    private val red = Color.rgb(244,64,78)
    private val muted = CinemaUi.muted
    private val paper = CinemaUi.paper
    private lateinit var url: TextInputEditText
    private lateinit var status: TextView
    private lateinit var transferStatus: TextView
    private lateinit var engineStatus: TextView
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var streams: Spinner
    private lateinit var kinds: Spinner
    private lateinit var qualities: Spinner
    private lateinit var details: LinearLayout
    private lateinit var localLabel: TextView
    private lateinit var playerWell: FrameLayout
    private lateinit var analyze: MaterialButton
    private lateinit var directAnalyze: MaterialButton
    private lateinit var progress: ProgressBar
    private var media: MediaDetails? = null
    private var localUri: Uri? = null
    private var pending: Data? = null
    private var workId: UUID? = null
    private var exported: Uri? = null
    private var updating = false
    private var selectedTab = 0
    private lateinit var pages: FrameLayout
    private lateinit var navHolder: LinearLayout
    private val pageViews = mutableListOf<View>()
    private lateinit var webFiles: LinearLayout
    private var webLimit = 15
    private var webQueryRunning = false
    private var lastWebEntries: List<WebDownloads.Entry>? = null
    private var pendingDlExport: DownloadItem? = null
    private val chooseDlFolder = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        val item = pendingDlExport; pendingDlExport = null
        if (result.resultCode == RESULT_OK && destination != null && item != null) {
            val flags = (result.data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(destination, flags) }
            item.destUri = destination.toString()
            DownloadCenter.export(this, item)
        }
    }
    private lateinit var tera: TeraBoxController
    private lateinit var dlList: LinearLayout
    private lateinit var dlSummary: TextView
    private lateinit var statActive: TextView
    private lateinit var statPaused: TextView
    private lateinit var statDone: TextView
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val chooseLocal = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            localUri = uri
            localLabel.text = "Video selected ✓ • Choose MP3 or M4A in Local Studio."
            kinds.setSelection(2)
        }
    }
    private val saveFile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val destination = result.data?.data
        if (result.resultCode == RESULT_OK && destination != null && pending != null) {
            val flags = (result.data?.flags ?: 0) and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            runCatching { contentResolver.takePersistableUriPermission(destination, flags) }
            val data = Data.Builder().putAll(pending!!).putString("destination", destination.toString()).build()
            val request = OneTimeWorkRequestBuilder<TransferWorker>().setInputData(data)
                .addTag("streamdock-transfer").build()
            workId = request.id
            getPreferences(MODE_PRIVATE).edit().putString("work", request.id.toString()).apply()
            WorkManager.getInstance(this).enqueueUniqueWork("transfer-" + request.id, ExistingWorkPolicy.KEEP, request)
            observe(request.id)
            selectTab(2)
        }
        pending = null
    }
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(FontFit.context(newBase)) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags() != "en")
            androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(androidx.core.os.LocaleListCompat.forLanguageTags("en"))
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        applyGlassChrome()
        buildUi()
        val incoming = intent.getStringExtra("browserMediaUrl") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        url.setText(savedInstanceState?.getString("url") ?: incoming)
        localUri = savedInstanceState?.getString("local")?.let(Uri::parse)
        if (localUri != null) localLabel.text = "Video selected ✓"
        savedInstanceState?.getBundle("pending")?.let {
            pending = Data.Builder().putString("url", it.getString("url") ?: "")
                .putString("kind", it.getString("kind") ?: "MP4")
                .putInt("height", it.getInt("height")).apply {
                    it.getString("local")?.let { local -> putString("local", local) }
                }.build()
        }
        getPreferences(MODE_PRIVATE).getString("work", null)?.let { runCatching { observe(UUID.fromString(it)) } }
        if (incoming.isNotBlank() && TeraPolicy.isShare(incoming)) {
            selectTab(1); tera.setLink(incoming); tera.analyze()
        }
        selectTab(savedInstanceState?.getInt("tab") ?: intent.getIntExtra("tab",0))
        intent.getStringExtra("browserMediaUrl")?.let { url.setText(it); status.text="Link ready. Analyze it to find supported streams." }
        playCaptured(intent)
        intent.removeExtra("browserMediaUrl"); intent.removeExtra(Intent.EXTRA_TEXT)
        migrateRetiredTeraBox()
        lifecycleScope.launch {
            this@MainActivity.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                while (true) { if (selectedTab == 3) { refreshWebFiles(); renderDownloads() }; kotlinx.coroutines.delay(1200) }
            }
        }
        DownloadCenter.init(this)
        renderDownloads()
        if (Build.VERSION.SDK_INT >= 33) permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    /** Pale glass chrome: dark system-bar icons, transparent status bar, pale navigation bar. */
    private fun applyGlassChrome() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.argb(236, 0xEA, 0xF4, 0xFC)
        if (Build.VERSION.SDK_INT >= 26) {
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        if (Build.VERSION.SDK_INT >= 29) window.isStatusBarContrastEnforced = false
    }
    private fun dp(value: Int) = CinemaUi.dp(this,value)
    private fun text(value: String,size: Float=14f,color: Int=CinemaUi.paper)=CinemaUi.text(this,value,size,color)
    private fun button(label: String,action: ()->Unit)=CinemaUi.button(this,label,action=action)
    private fun card(root: LinearLayout)=CinemaUi.card(this,root)
    private fun kicker(label: String)=CinemaUi.kicker(this,label)
    private fun spinner(items: List<String>)=Spinner(this).apply {
        adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items);minimumHeight=dp(46)
        CinemaUi.spinnerChrome(this@MainActivity,this)
    }
    private fun browse(address: String?=null) {
        releasePlayer()
        startActivity(Intent(this,BrowserActivity::class.java).apply { address?.let { putExtra("url",it) } })
    }
    private fun buildUi() {
        val shell=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;background=CinemaUi.backdrop(this@MainActivity) }
        pages=FrameLayout(this);shell.addView(pages,LinearLayout.LayoutParams(-1,0,1f))
        navHolder=LinearLayout(this).apply { setPadding(dp(8),0,dp(8),dp(8)) };shell.addView(navHolder)
        setContentView(shell)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { view,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        fun page():LinearLayout {
            val scroll=ScrollView(this).apply { isFillViewport=true }
            val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(15),dp(13),dp(15),dp(22));background=CinemaUi.backdrop(this@MainActivity) }
            scroll.addView(root);pages.addView(scroll,FrameLayout.LayoutParams(-1,-1));pageViews+=scroll;return root
        }
        val home=page()
        val brand=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL }
        brand.addView(ChromeText(this,"StreamDock",28f,.005f),LinearLayout.LayoutParams(0,-2,1f))
        val badge=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL
            background=CinemaUi.glassPane(this@MainActivity,15f,1f); setPadding(dp(10),dp(5),dp(11),dp(5))
            Dock.depth(this,Dock.px(this@MainActivity,15f),Dock.px(this@MainActivity,3f))
        }
        badge.addView(View(this).apply {
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setColor(Dock.accent) }
            elevation=Dock.px(this@MainActivity,2f)
        },LinearLayout.LayoutParams(dp(5),dp(5)).apply { rightMargin=dp(6) })
        badge.addView(text("FREE",8.5f,CinemaUi.muted).apply { letterSpacing=.16f;typeface=Dock.bold(this@MainActivity) })
        brand.addView(badge);home.addView(brand)
        home.addView(text("Your media, kept. · Any site · Any format",11f,CinemaUi.muted))
        home.addView(View(this).apply { background=CinemaUi.divider(this@MainActivity) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(11) })
        val searchBar=LinearLayout(this).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL;background=CinemaUi.shape(radius=24f,c=this@MainActivity)
            setPadding(dp(16),0,dp(5),0);Dock.depth(this,Dock.px(this@MainActivity,24f),Dock.px(this@MainActivity,6f))
        }
        val search=EditText(this).apply { hint="Search the web or enter a site";setSingleLine();textSize=13.5f;setTextColor(Dock.ink);typeface=Dock.regular(this@MainActivity);setHintTextColor(muted);background=null;isSaveEnabled=false;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        fun submitSearch() {
            val engine=runCatching { BrowserPolicy.Search.valueOf(getSharedPreferences("browser",MODE_PRIVATE).getString("search","GOOGLE")!!) }.getOrDefault(BrowserPolicy.Search.GOOGLE)
            runCatching { BrowserPolicy.input(search.text.toString(),engine) }.onSuccess { browse(it) }.onFailure { Toast.makeText(this,it.message,Toast.LENGTH_SHORT).show() }
        }
        search.setOnEditorActionListener { _,action,_ -> if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { submitSearch();true } else false }
        searchBar.addView(search,LinearLayout.LayoutParams(0,dp(46),1f))
        searchBar.addView(ImageButton(this).apply { setImageDrawable(DockIcon("search",mint));setBackgroundColor(Color.TRANSPARENT);contentDescription="Search the web";setPadding(dp(12),dp(12),dp(12),dp(12));setOnClickListener { submitSearch() } },LinearLayout.LayoutParams(dp(44),dp(44)))
        home.addView(searchBar,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(14);bottomMargin=dp(14) })
        val hero=FrameLayout(this).apply {
            background=Dock.glass(this@MainActivity,26f);clipToOutline=true
            Dock.depth(this,Dock.px(this@MainActivity,26f),Dock.px(this@MainActivity,10f))
        }
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP;contentDescription=null;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        runCatching { assets.open("dock-hero.jpg").use { image.setImageDrawable(android.graphics.drawable.Drawable.createFromStream(it,null)) } }
        hero.addView(image,FrameLayout.LayoutParams(-1,-1))
        hero.addView(View(this).apply {
            background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(
                Color.argb(70,255,255,255),Color.argb(170,255,255,255),Color.argb(246,255,255,255),Color.argb(252,255,255,255)))
        },FrameLayout.LayoutParams(-1,-1))
        val copy=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(14),dp(13),dp(13)) }
        copy.addView(CinemaUi.kicker(this,"Discover"))
        copy.addView(CinemaUi.display(this,"Save what\nyou watch.",26f).apply { setLineSpacing(0f,.95f) })
        copy.addView(text("Paste a link — StreamDock does the rest.",11.5f,CinemaUi.muted))
        copy.addView(button("Explore the web") { browse() },LinearLayout.LayoutParams(dp(168),dp(44)).apply { topMargin=dp(9) })
        hero.addView(copy,FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM))
        home.addView(hero,LinearLayout.LayoutParams(-1,dp(206)))
        home.addView(kicker("Quick Access").apply { setPadding(0,dp(16),0,dp(5)) })
        val sites=listOf(Triple("YouTube","youtube.com","https://www.youtube.com/"),Triple("TeraBox","terabox.com",""),Triple("SoundCloud","soundcloud.com","https://soundcloud.com/"))
        val siteRow=LinearLayout(this)
        sites.forEach { (label,host,link) ->
            val tile=CinemaUi.tile(this,20f,1f,7).apply {
                setPadding(dp(13),dp(12),dp(13),dp(12))
                contentDescription=if(link.isBlank()) "Open the native TeraBox engine" else "Open $label"
                setOnClickListener { if(link.isBlank()) selectTab(1) else browse(link) }
                val badge=ImageView(this@MainActivity).apply {
                    scaleType=ImageView.ScaleType.FIT_CENTER
                    background=GradientDrawable().apply {
                        cornerRadius=Dock.px(this@MainActivity,11f)
                        setColor(Color.argb(26,12,22,32))
                    }
                    elevation=Dock.px(this@MainActivity,3f)
                    SiteIcons.load(this@MainActivity,host,this,11f,DockIcon("globe",Dock.inkFaint))
                }
                addView(badge,LinearLayout.LayoutParams(dp(34),dp(34)))
                addView(CinemaUi.display(this@MainActivity,label,13.5f).apply {
                    setPadding(0,dp(9),0,0);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                })
                addView(text(if(link.isBlank()) "In-app engine" else "Website",9.5f,CinemaUi.muted).apply { maxLines=1 })
            }
            siteRow.addView(tile,LinearLayout.LayoutParams(0,dp(114),1f).apply { setMargins(dp(4),dp(4),dp(4),dp(4)) })
        }
        home.addView(siteRow)
        val input=card(home)
        input.addView(kicker("Download a Link"))
        input.addView(text("A little more offline.",18f))
        val layout=TextInputLayout(this).apply { hint="Paste a media URL" }
        CinemaUi.field(this@MainActivity,layout)
        url=TextInputEditText(layout.context).apply { setSingleLine();textSize=13.5f;typeface=Dock.regular(this@MainActivity);setTextColor(Dock.ink);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        layout.addView(url);input.addView(layout)
        analyze=button("Analyze link  →") { extract() };input.addView(analyze)
        status=text("NewPipe for supported sites. TeraBox shares use the built-in TeraBox engine.",11f,muted).apply { setTextIsSelectable(true) };input.addView(status)
        details=card(home).apply { visibility=View.GONE }
        details.addView(kicker("Preview & Save"))
        title=text("",18f);details.addView(title);meta=text("",11f,muted);details.addView(meta)
        // The well is a poster for the unified player screen (video + audio-only live there).
        playerWell=FrameLayout(this).apply {
            background=GradientDrawable().apply {
                colors=intArrayOf(0xFF0B1520.toInt(),0xFF050A0F.toInt())
                cornerRadius=Dock.px(this@MainActivity,18f)
            }
            clipToOutline=true
            outlineProvider=object:android.view.ViewOutlineProvider() {
                override fun getOutline(v:View,o:android.graphics.Outline) { o.setRoundRect(0,0,v.width,v.height,Dock.px(this@MainActivity,18f)) }
            }
            elevation=Dock.px(this@MainActivity,4f)
        }
        val wellPlay=CinemaUi.button(this,"▶  Open the player",true) { preview() }
        playerWell.addView(wellPlay,FrameLayout.LayoutParams(dp(190),dp(44),android.view.Gravity.CENTER))
        details.addView(playerWell,LinearLayout.LayoutParams(-1,dp(140)).apply { topMargin=dp(8);bottomMargin=dp(4) })
        streams=spinner(emptyList());details.addView(streams)
        val playRow=LinearLayout(this)
        playRow.addView(CinemaUi.button(this,"Play preview",true) { preview() },LinearLayout.LayoutParams(0,-2,1f).apply { rightMargin=dp(6) })
        playRow.addView(CinemaUi.button(this,"Listen (audio only)",true) { preview(audioOnly=true) },LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(6) })
        details.addView(playRow)
        kinds=spinner(OutputKind.entries.map { it.label });details.addView(kinds)
        qualities=spinner(listOf("Best available","Up to 1080p","Up to 720p","Up to 480p"));details.addView(qualities)
        details.addView(button("Download & save") { chooseDestination(false) })
        details.addView(text("Only available containers and progressive streams can be saved. Audio ignores the video quality cap.",11f,muted))
        home.addView(text("Built for your media. Not for bypassing restrictions.",10f,paper).apply { setPadding(0,dp(20),0,0);letterSpacing=.04f })

        // Page indexes intentionally match bottom navigation (Browser is its own Activity).
        val teraPage=page()
        tera=TeraBoxController(this)
        tera.onWork={ id -> observe(id); selectTab(4) }
        // transfer notices (for example "saved as .mkv because the codecs are not MP4-native")
        // are read from the worker output and shown verbatim; they are never invented here.
        teraPage.addView(tera.root())
        page().addView(text("Open Browser from the navigation bar."))
        val downloads=page()
        downloads.addView(CinemaUi.titlebar(this,"04 / Collection","Downloads","Less waiting. More watching."))
        
        val inapp=card(downloads)
        inapp.addView(kicker("In-App Downloads"))
        inapp.addView(text("One or many at once. Pause and resume whenever you want.",17f))
        val statStrip=LinearLayout(this)
        fun statColumn(caption:String):TextView {
            val column=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER;setPadding(0,dp(2),0,dp(2)) }
            val value=CinemaUi.display(this,"0",22f)
            column.addView(value)
            column.addView(text(caption,8f,muted).apply { letterSpacing=.18f })
            statStrip.addView(column,LinearLayout.LayoutParams(0,-2,1f))
            return value
        }
        statActive=statColumn("ACTIVE"); statPaused=statColumn("PAUSED"); statDone=statColumn("FINISHED")
        inapp.addView(statStrip)
        inapp.addView(View(this).apply { background=CinemaUi.divider(this@MainActivity) },LinearLayout.LayoutParams(-1,dp(1)).apply { topMargin=dp(8) })
        dlSummary=text("No downloads yet.",11f,muted).apply { setTextIsSelectable(true) };inapp.addView(dlSummary)
        dlList=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };inapp.addView(dlList)
        val dlActions=LinearLayout(this)
        dlActions.addView(button("Pause all") { DownloadCenter.pauseAll(this); renderDownloads() },LinearLayout.LayoutParams(0,-2,1f))
        dlActions.addView(CinemaUi.button(this,"Resume all",true) { DownloadCenter.resumeAll(this); renderDownloads() },LinearLayout.LayoutParams(0,-2,1f))
        inapp.addView(dlActions)
        val queue=card(downloads);queue.addView(kicker("Native Transfer"))
        transferStatus=text("No active transfer.",13f,muted).apply { setTextIsSelectable(true) };queue.addView(transferStatus)
        progress=CinemaUi.progress(this).apply { max=100 }
        queue.addView(progress,LinearLayout.LayoutParams(-1,dp(8)))
        queue.addView(button("Open saved file") { exported?.let { uri ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,contentResolver.getType(uri) ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                .onFailure { transferStatus.text="No installed app could open this file." }
        } ?: run { transferStatus.text="Complete a download or conversion first." } })
        queue.addView(CinemaUi.button(this,"Cancel native transfer",true) { workId?.let { WorkManager.getInstance(this).cancelWorkById(it) } })
        downloads.addView(kicker("Browser Files").apply { setPadding(0,dp(20),0,dp(4)) })
        downloads.addView(text("Managed by Android • saved in Downloads. Signed links may expire; login-required files may need an external browser.",11f,muted))
        webFiles=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };downloads.addView(webFiles)
        downloads.addView(CinemaUi.button(this,"Show more browser downloads",true) { webLimit+=15;lastWebEntries=null;refreshWebFiles() })
        downloads.addView(CinemaUi.button(this,"Open Android downloads",true) { runCatching { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) }.onFailure { transferStatus.text="Your device has no downloads viewer. Use the Files app." } })

        val tools=page()
        tools.addView(CinemaUi.titlebar(this,"05 / Control Room","Tools & settings","Engines, quality of life and privacy."))
        val local=card(tools);local.addView(kicker("Local Studio"));local.addView(CinemaUi.display(this,"Video → Audio",18f))
        localLabel=text("Turn a video on your device into MP3 or M4A.",12f,muted);local.addView(localLabel)
        val localFormats=spinner(listOf("MP3 • 192 kbps","M4A • 192 kbps"));local.addView(localFormats)
        local.addView(CinemaUi.button(this,"Choose a video",true) { chooseLocal.launch(arrayOf("video/*")) })
        local.addView(button("Convert & save audio") { kinds.setSelection(if(localFormats.selectedItemPosition==0) 2 else 3);chooseDestination(true) })
        val engine=card(tools);engine.addView(kicker("Engine Room"));engine.addView(CinemaUi.display(this,"Power, without the clutter.",17f))
        directAnalyze=CinemaUi.button(this,"Analyze pasted link with yt-dlp",true) { selectTab(0);extract(directYtDlp=true) };engine.addView(directAnalyze)
        engine.addView(CinemaUi.button(this,"Update yt-dlp engine",true) { updateEngine() })
        engineStatus=text("Bundled engines ready to initialize on demand.",11f,muted);engine.addView(engineStatus)
        engine.addView(CinemaUi.button(this,"View / copy diagnostics",true) { showDiagnostics() })
        engine.addView(text("NewPipe handles supported services first. A failed NewPipe download never silently switches engines. FFmpeg runs locally.",11f,muted))
        val dlEngine=card(tools)
        dlEngine.addView(kicker("Download Engine"));dlEngine.addView(CinemaUi.display(this,"Parallel connections",18f))
        dlEngine.addView(text("More connections can lift throttled speeds a lot; the server and network still set the ceiling. StreamDock cannot promise a fixed multiplier.",11f,muted))
        val conn=spinner(listOf("1 connection","2 connections","4 connections","6 connections","8 connections","12 connections","16 connections"))
        conn.setSelection(listOf(1,2,4,6,8,12,16).indexOf(DownloadCenter.connectionsPerFile).takeIf { it >= 0 } ?: 2)
        dlEngine.addView(conn)
        val concurrent=spinner(listOf("1 file at a time","2 files at a time","3 files at a time","4 files at a time","5 files at a time"))
        concurrent.setSelection((DownloadCenter.maxConcurrent - 1).coerceIn(0, 4))
        dlEngine.addView(concurrent)
        dlEngine.addView(CinemaUi.button(this,"Apply download settings",true) {
            val connections=listOf(1,2,4,6,8,12,16)[conn.selectedItemPosition]
            val files=concurrent.selectedItemPosition + 1
            DownloadCenter.settings(this,files,connections)
            engineStatus.text="Download engine: $connections connection(s) per file, $files file(s) at once."
        })
        dlEngine.addView(text("Paused or interrupted downloads keep every received byte and continue from that point, even after the app is closed.",11f,muted))
        val web=card(tools);web.addView(kicker("Browser & Privacy"))
        web.addView(CinemaUi.button(this,"Open browser",true) { browse() })
        web.addView(text("In the browser, the download button lists media the page itself loaded, so you can play or save it from any platform. Pages that use DRM or blob-only players stay in the website.",11f,muted))
        web.addView(text("Choose Google, DuckDuckGo or Bing in Browser. TeraBox share links use the built-in engine on the Tera tab; no account login or cookie import is requested there.",12f,muted))
        web.addView(text("External tools are independent websites—not endorsed or guaranteed by StreamDock. Never provide them account cookies or passwords.",12f,muted))
        // Read the installed version instead of writing it here: the footer used to say "1.4.0" for
        // every build, which made new builds look like the same old one on the phone.
        val installedVersion = runCatching {
            packageManager.getPackageInfo(packageName,0).versionName.orEmpty().ifBlank { "unknown" }
        }.getOrDefault("unknown")
        tools.addView(CinemaUi.display(this,"StreamDock / $installedVersion",13f,mint).apply { setPadding(0,dp(25),0,dp(3));letterSpacing=.12f })
        tools.addView(text("Open-source • GPL-3.0-or-later\nEnglish interface • Android 7+\nOnly download your own or authorized media. Website content and Android system dialogs control their own language. No affiliation with the linked platforms.",11f,muted))
        selectTab(0)
    }
    private fun selectTab(tab:Int) {
        if(tab==2) { browse();return }
        selectedTab=if(tab in listOf(0,1,3,4)) tab else 0
        pageViews.forEachIndexed { index,view -> view.visibility=if(index==selectedTab) View.VISIBLE else View.GONE }
        navHolder.removeAllViews();navHolder.addView(CinemaUi.nav(this,selectedTab) { selectTab(it) },LinearLayout.LayoutParams(-1,-2))
        if(selectedTab!=0) releasePlayer()
        if(selectedTab!=1) tera.release()
        if(selectedTab==3) { refreshWebFiles(); renderDownloads() }
    }
    private fun refreshWebFiles() {
        if(webQueryRunning) return
        webQueryRunning=true
        lifecycleScope.launch {
            try {
                val entries=withContext(Dispatchers.IO) { WebDownloads.entries(this@MainActivity,webLimit) }
                if(entries==lastWebEntries) return@launch
                lastWebEntries=entries;webFiles.removeAllViews()
                if(entries.isEmpty()) webFiles.addView(text("No browser downloads yet. Use a website's download button to get started.",13f,muted))
                entries.forEach { entry ->
                    val row=card(webFiles);row.addView(text(entry.name,16f))
                    val size=android.text.format.Formatter.formatFileSize(this@MainActivity,entry.bytes.coerceAtLeast(0))
                    val total=if(entry.total>0) android.text.format.Formatter.formatFileSize(this@MainActivity,entry.total) else "unknown size"
                    row.addView(text("${WebDownloads.state(entry)}\n$size / $total",11f,muted))
                    if(entry.status==android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        row.addView(button("Open file") { runCatching { WebDownloads.open(this@MainActivity,entry.id) }.onFailure { Toast.makeText(this@MainActivity,"No app could open this file, or it was moved.",Toast.LENGTH_LONG).show() } })
                        row.addView(CinemaUi.button(this@MainActivity,"Share file",true) { runCatching { WebDownloads.open(this@MainActivity,entry.id,true) }.onFailure { Toast.makeText(this@MainActivity,"This file cannot be shared.",Toast.LENGTH_SHORT).show() } })
                    }
                    row.addView(CinemaUi.button(this@MainActivity,if(entry.status==android.app.DownloadManager.STATUS_SUCCESSFUL) "Delete file" else "Cancel / remove",true) {
                        androidx.appcompat.app.AlertDialog.Builder(this@MainActivity).setTitle("Remove this download?").setMessage("Android will cancel this entry and delete its downloaded file. Exported copies elsewhere are not affected.")
                            .setNegativeButton("Keep",null).setPositiveButton("Remove") { _,_->lifecycleScope.launch {
                                withContext(Dispatchers.IO) { WebDownloads.remove(this@MainActivity,entry.id) };lastWebEntries=null;refreshWebFiles()
                            } }.show()
                    })
                }
            } catch(_:CancellationException) { throw CancellationException() }
            catch(_:Exception) { webFiles.removeAllViews();webFiles.addView(text("Android's download service is unavailable. Try again later.",12f,muted)) }
            finally { webQueryRunning=false }
        }
    }
    /** One row per in-app download: name, progress, speed, pause/resume/cancel and export controls. */
    private fun renderDownloads() {
        if (!::dlList.isInitialized) return
        val items = DownloadCenter.items.value
        val running = items.count { it.status == DlStatus.RUNNING || it.status == DlStatus.WAITING }
        val paused = items.count { it.status == DlStatus.PAUSED }
        val finished = items.count { it.status == DlStatus.COMPLETED }
        statActive.text = running.toString()
        statPaused.text = paused.toString()
        statDone.text = finished.toString()
        dlSummary.text = when {
            items.isEmpty() -> "Nothing here yet. Saving a TeraBox file or a browser-captured video starts the first one."
            else -> "Total speed ${DownloadItem.human(items.sumOf { it.speed })}/s • everything continues from where it stopped."
        }
        dlList.removeAllViews()
        items.take(12).forEach { item ->
            val row = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(10),0,dp(4)) }
            row.addView(text(item.name,15f))
            val statusLine = buildString {
                append(when (item.status) {
                    DlStatus.RUNNING -> item.progressText()
                    DlStatus.WAITING -> "Queued — ${item.progressText()}"
                    DlStatus.PAUSED -> "Paused — ${item.progressText()}"
                    DlStatus.COMPLETED -> "Saved to your folder"
                    DlStatus.EXPORT_FAILED -> "Downloaded — choose a folder to finish"
                    DlStatus.FAILED -> "Stopped — ${item.progressText()}"
                    DlStatus.CANCELLED -> "Cancelled"
                })
                if (item.speed > 0) append("  •  ${item.speedText()}")
                append("  •  ${item.connectionsText()}")
            }
            row.addView(text(statusLine,11f,muted))
            if (item.status == DlStatus.RUNNING || item.status == DlStatus.WAITING || item.status == DlStatus.PAUSED)
                row.addView(text(item.note.ifBlank { "" },10f,muted))
            if (item.error.isNotBlank()) row.addView(text(item.error,10f,red))
            if (item.status != DlStatus.COMPLETED && item.status != DlStatus.CANCELLED) {
                val progressBar = CinemaUi.progress(this).apply {
                    max=100; progress=item.percent()
                }
                row.addView(progressBar,LinearLayout.LayoutParams(-1,dp(6)))
            }
            val actions = LinearLayout(this)
            when (item.status) {
                DlStatus.RUNNING, DlStatus.WAITING -> actions.addView(CinemaUi.button(this,"Pause",true) { DownloadCenter.pause(this,item.id); renderDownloads() },LinearLayout.LayoutParams(0,-2,1f))
                DlStatus.PAUSED, DlStatus.FAILED -> actions.addView(button("Resume") { DownloadCenter.resume(this,item.id); renderDownloads() },LinearLayout.LayoutParams(0,-2,1f))
                DlStatus.EXPORT_FAILED -> actions.addView(button("Choose folder") { pendingDlExport=item; chooseDlFolder.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type="application/octet-stream"; putExtra(Intent.EXTRA_TITLE,item.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }) },LinearLayout.LayoutParams(0,-2,1f))
                DlStatus.COMPLETED -> actions.addView(button("Open file") {
                    val uri = item.destUri.let(Uri::parse)
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,contentResolver.getType(uri) ?: item.mime.ifBlank { "application/octet-stream" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        .onFailure { runCatching { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT).setDataAndType(uri,"*/*")) } }
                },LinearLayout.LayoutParams(0,-2,1f))
                else -> { }
            }
            if (item.status == DlStatus.FAILED) actions.addView(CinemaUi.button(this,"Restart",true) { DownloadCenter.restart(this,item.id); renderDownloads() },LinearLayout.LayoutParams(0,-2,1f))
            if (item.status != DlStatus.COMPLETED) actions.addView(CinemaUi.button(this,"Cancel",true) {
                androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Cancel this download?")
                    .setMessage("The partial ${item.progressText()} will be deleted. Nothing else is affected.")
                    .setNegativeButton("Keep downloading",null).setPositiveButton("Cancel download") { _,_-> DownloadCenter.cancel(this,item.id); renderDownloads() }.show()
            },LinearLayout.LayoutParams(0,-2,1f))
            row.addView(actions)
            dlList.addView(row)
        }
        if (items.isEmpty()) dlList.addView(text("Save something and it appears here with pause, resume and speed.",11f,muted))
    }
    private fun migrateRetiredTeraBox() {
        val prefs=getSharedPreferences("migrations",MODE_PRIVATE)
        if(prefs.getBoolean("browser-only-120",false)) return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                WorkManager.getInstance(this@MainActivity).cancelAllWorkByTag("terabox").result.get()
                java.io.File(noBackupFilesDir,"terabox").deleteRecursively()
                val store=java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if(store.containsAlias("streamdock.terabox.v1")) store.deleteEntry("streamdock.terabox.v1")
                deleteSharedPreferences("terabox.TeraBoxActivity")
                prefs.edit().putBoolean("browser-only-120",true).apply()
            }.onFailure { Diagnostics.stage("Retired TeraBox data cleanup deferred; will retry on next launch") }
        }
    }
    override fun onNewIntent(intent:Intent) {
        super.onNewIntent(intent);setIntent(intent)
        if (playCaptured(intent)) { intent.removeExtra("browserPlayUrl");intent.removeExtra("browserMediaUrl");intent.removeExtra(Intent.EXTRA_TEXT);return }
        val incoming = intent.getStringExtra("browserMediaUrl") ?: intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (incoming.isNotBlank()) {
            url.setText(incoming)
            if (TeraPolicy.isShare(incoming)) { selectTab(1); tera.setLink(incoming); tera.analyze() }
            else { media=null;details.visibility=View.GONE;status.text="Link ready. Choose Analyze to find supported streams." }
        }
        selectTab(intent.getIntExtra("tab",0));intent.removeExtra("browserMediaUrl");intent.removeExtra(Intent.EXTRA_TEXT)
    }
    /** Plays media captured by the built-in browser: same User-Agent/Referer the page used, no cookies attached. */
    private fun playCaptured(intent: Intent): Boolean {
        val raw = intent.getStringExtra("browserPlayUrl") ?: return false
        val playUrl = runCatching { InputPolicy.validate(raw) }.getOrElse { status.text="The captured address is not a valid HTTPS media link."; return true }
        val agent = intent.getStringExtra("browserPlayAgent").orEmpty()
        val headers = buildMap {
            put("User-Agent", agent.ifBlank { "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36" })
            intent.getStringExtra("browserPlayReferer")?.takeIf { it.startsWith("https://") }?.let { put("Referer", it) }
        }
        val name = intent.getStringExtra("browserPlayName").orEmpty().ifBlank { playUrl.substringAfterLast('/').substringBefore('?') }
        val item = org.streamdock.app.player.Playable(name, "Captured by the built-in browser", playUrl, null, headers, headers, false)
        org.streamdock.app.player.PlayerActivity.start(this, listOf(item))
        status.text = "Playing in the player screen — audio-only and background listening are available there."
        return true
    }
    private fun showDiagnostics() {
        val report = Diagnostics.report()
        val text = TextView(this).apply {
            this.text = report; textSize = 12f; setPadding(dp(16), dp(12), dp(16), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(text) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Diagnostic report — review before sharing")
            .setView(scroll)
            .setPositiveButton("Copy report") { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("StreamDock diagnostic report", report))
                Toast.makeText(this, "Report copied. Review it before sharing.", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Close", null).show()
    }
    private fun analysisFailure(error: Throwable) {
        Diagnostics.error("Analysis failed", error)
        status.text = "Analysis failed: ${error.javaClass.simpleName}\n${DiagnosticText.redact(error.message.orEmpty()).take(500)}\nOpen Tools to copy a diagnostic report."
    }
    private fun extract(directYtDlp: Boolean = false) {
        if (updating || !analyze.isEnabled) return
        val source = try { InputPolicy.validate(url.text.toString()) } catch (e: Exception) { status.text = e.message; return }
        if (TeraPolicy.isShare(source)) { selectTab(1); tera.setLink(source); tera.analyze(); intent.removeExtra(Intent.EXTRA_TEXT); return }
        if (BrowserPolicy.browserOnly(source)) {
            media = null; details.visibility = View.GONE
            browse(source); intent.removeExtra(Intent.EXTRA_TEXT)
            return
        }
        analyze.isEnabled = false; directAnalyze.isEnabled = false
        status.text = if (directYtDlp) "Finding streams with yt-dlp…" else "Finding streams with NewPipe → yt-dlp…"
        lifecycleScope.launch {
            try {
                media = null; details.visibility = View.GONE; releasePlayer()
                val info = withContext(Dispatchers.IO) {
                    ExtractorRepository(application as DockApp).extract(source, directYtDlp)
                }
                media = info; title.text = info.title
                meta.text = "${info.author}  ·  ${info.duration / 60}:${(info.duration % 60).toString().padStart(2, '0')}  ·  ${info.engine}"
                streams.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, info.previews.map { it.label })
                details.visibility = View.VISIBLE
                status.text = info.notice ?: "✓ Metadata and streams found with ${info.engine}."
                if (info.previews.isEmpty()) status.append(" Preview unavailable; you can try downloading.")
            } catch (e: CancellationException) { throw e
            } catch (e: LinkageError) { analysisFailure(e)
            } catch (e: Exception) { analysisFailure(e)
            } finally { analyze.isEnabled = true; directAnalyze.isEnabled = true }
        }
    }
    /** Opens the unified player: video and audio-only share one screen, one control set. */
    private fun preview(audioOnly: Boolean = false) {
        val info = media ?: return
        if (info.previews.isEmpty()) { status.text = "No previewable stream in these results."; return }
        val selectedIndex = streams.selectedItemPosition.coerceIn(0, info.previews.size - 1)
        val ordered = listOf(info.previews[selectedIndex]) + info.previews.filterIndexed { i, _ -> i != selectedIndex }
        val items = ordered.map { s ->
            org.streamdock.app.player.Playable(
                title = info.title,
                subtitle = "${info.author} · ${s.label}",
                videoUrl = s.url,
                audioUrl = s.audioUrl,
                headers = s.headers,
                audioHeaders = s.audioHeaders,
                preferAudio = audioOnly
            )
        }
        org.streamdock.app.player.PlayerActivity.start(this, items, 0, 0L, audioOnly)
    }
    private fun chooseDestination(local: Boolean) {
        if (updating) { Toast.makeText(this,"Wait for the engine update to finish.",Toast.LENGTH_SHORT).show(); return }
        val kind = OutputKind.entries[kinds.selectedItemPosition]
        if (local && localUri == null) { localLabel.text = "Choose a local video first."; return }
        if (local && kind !in listOf(OutputKind.MP3, OutputKind.M4A)) { localLabel.text = "Select Audio · MP3 or Audio · M4A first."; return }
        if (!local && media == null) { status.text = "Analyze a media link first."; return }
        val heights = listOf(0, 1080, 720, 480)
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) { WorkManager.getInstance(this@MainActivity).getWorkInfosByTag("streamdock-transfer").get().count { !it.state.isFinished } }
            if (running >= 3) { transferStatus.text = "Three extractor transfers are already running. This one is queued and starts automatically." }
            pending = Data.Builder().putString("url", media?.source ?: "")
                .putString("kind", kind.name).putInt("height", heights[qualities.selectedItemPosition])
                .apply { if (local) putString("local", localUri.toString()) }.build()
            val name = (if (local) "Converted audio" else media!!.title).replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(90)
            val mime = when (kind) { OutputKind.MP4 -> "video/mp4"; OutputKind.WEBM -> "video/webm"; OutputKind.MP3 -> "audio/mpeg"; OutputKind.M4A -> "audio/mp4" }
            saveFile.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = mime
                putExtra(Intent.EXTRA_TITLE, "$name.${kind.extension}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        }
    }
    private fun observe(id: UUID) {
        workId = id
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info == null || workId != id) return@observe
            val percent = info.progress.getInt("percent", 0)
            progress.isIndeterminate = info.state == WorkInfo.State.RUNNING && percent == 0
            progress.progress = if (info.state == WorkInfo.State.SUCCEEDED) 100 else percent
            transferStatus.text = when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    exported = info.outputData.getString("destination")?.let(Uri::parse)
                    val container = info.outputData.getString("container").orEmpty()
                    val notice = info.outputData.getString("notice").orEmpty()
                    buildString {
                        append("✓ ${info.outputData.getString("engine") ?: "Transfer"} • Saved successfully")
                        if (container.isNotBlank()) append(" • $container")
                        append(".")
                        if (notice.isNotBlank()) append(" Note: ").append(notice).append(".")
                    }
                }
                WorkInfo.State.FAILED -> "Failed: ${info.outputData.getString("error")}"
                WorkInfo.State.CANCELLED -> "Cancelled. Remove any incomplete destination file if necessary."
                WorkInfo.State.RUNNING -> "${info.progress.getString("stage") ?: "Preparing…"} $percent%"
                else -> "Queued…"
            }
        }
    }
    private fun updateEngine() {
        if (updating || !analyze.isEnabled) return
        lifecycleScope.launch {
            val active = withContext(Dispatchers.IO) { WorkManager.getInstance(this@MainActivity).getWorkInfosByTag("streamdock-transfer").get().any { !it.state.isFinished } }
            if (active) { engineStatus.text = "Wait for the running transfers to finish before updating yt-dlp."; return@launch }
            updating = true; analyze.isEnabled = false; directAnalyze.isEnabled = false; engineStatus.text = "Updating yt-dlp engine…"
            try {
                val result = withContext(Dispatchers.IO) {
                    (application as DockApp).initNative()
                    val update = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                    val request = com.yausername.youtubedl_android.YoutubeDLRequest(emptyList<String>())
                    request.addOption("--version")
                    val actualVersion = YoutubeDL.getInstance().execute(request).out.trim()
                    Diagnostics.stage("yt-dlp update: $update; executable version: $actualVersion")
                    "$update · installed yt-dlp: $actualVersion"
                }
                engineStatus.text = "Engine update: $result"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { Diagnostics.error("Engine update failed", e); engineStatus.text = "Update failed: ${DiagnosticText.redact(e.message.orEmpty()).take(500)}"
            } finally { updating = false; analyze.isEnabled = true; directAnalyze.isEnabled = true }
        }
    }
    private fun releasePlayer() { /* the unified player lives in PlaybackService; nothing to release here */ }
    override fun onStop() { if (::tera.isInitialized) tera.release(); super.onStop() }
    override fun onSaveInstanceState(outState: Bundle) {
        val raw = url.text.toString()
        val safeUrl = if (BrowserPolicy.browserOnly(raw)) "" else raw
        outState.putInt("tab",selectedTab)
        outState.putString("url", safeUrl); outState.putString("local", localUri?.toString())
        pending?.let { data -> outState.putBundle("pending", Bundle().apply {
            putString("url", data.getString("url")); putString("kind", data.getString("kind"))
            putString("local", data.getString("local")); putInt("height", data.getInt("height", 0))
        }) }
        super.onSaveInstanceState(outState)
    }
}
