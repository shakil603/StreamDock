package org.streamdock.app.terabox

import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object TeraPolicy {
    private val shares = setOf("terabox.com", "terabox.app", "1024terabox.com", "1024tera.com", "teraboxapp.com", "teraboxshare.com", "terasharelink.com", "terasharefile.com", "teraboxlink.com", "4funbox.com", "mirrobox.com", "nephobox.com")
    private val media = shares + setOf("teraboxcdn.com", "teraboxcdn.net", "baidupcs.com", "pcs.baidu.com", "baidubcr.com", "dubox.com")
    private fun matches(host: String, roots: Set<String>) = roots.any { host == it || host.endsWith(".$it") }
    fun isShare(raw: String) = runCatching { val u=raw.toHttpUrl(); u.isHttps && u.port==443 && u.username.isEmpty() && u.password.isEmpty() && matches(u.host, shares) }.getOrDefault(false)
    fun validate(raw: String): HttpUrl {
        require(isShare(raw)) { "Enter a supported HTTPS TeraBox share link." }
        val u=raw.toHttpUrl()
        require(u.encodedPath.contains("/s/") || !u.queryParameter("surl").isNullOrBlank()) { "Paste a share link, not the TeraBox home page." }
        return u
    }
    /** Same share on the other official TeraBox hosts, used only when the connection to the first host dies. */
    fun mirrorCandidates(input: HttpUrl, backend: HttpUrl? = null): List<HttpUrl> {
        if (backend != null) return emptyList()
        val shareId = input.pathSegments.dropWhile { it != "s" }.getOrNull(1)?.takeIf { it.isNotBlank() }
        val surl = input.queryParameter("surl")?.takeIf { it.isNotBlank() }
        val hosts = shares.filter { it.contains('.') && it !in setOf("1024terabox.com", "teraboxapp.com", "mirrobox.com", "nephobox.com", "4funbox.com", "teraboxlink.com", "terasharelink.com", "terasharefile.com") }
            .map { if (it.startsWith("www.")) it else "www.$it" }
        return hosts.filter { it != input.host }.mapNotNull { host ->
            runCatching {
                when {
                    shareId != null -> HttpUrl.Builder().scheme("https").host(host).addPathSegments("s/$shareId").build()
                    surl != null -> HttpUrl.Builder().scheme("https").host(host).addPathSegments("sharing/link").addQueryParameter("surl", surl).build()
                    else -> null
                }
            }.getOrNull()
        }
    }
    fun permitted(url: HttpUrl, backend: HttpUrl? = null): Boolean = url.isHttps && url.port==443 && url.username.isEmpty() && url.password.isEmpty() &&
        (matches(url.host,media) || (backend != null && url.host==backend.host))
    fun backend(raw: String): HttpUrl? {
        val value=normalize(raw)
        if(value.isBlank()) return null
        val u=value.toHttpUrl()
        require(u.isHttps && u.port==443 && u.username.isEmpty() && u.password.isEmpty() && u.encodedPath=="/" && u.query==null && u.fragment==null) { "Resolver must be an HTTPS origin without credentials or a path." }
        require(u.host.contains('.') && !u.host.matches(Regex("[0-9.]+")) && !u.host.endsWith(".local") && !u.host.endsWith(".localhost")) { "Resolver must use a public hostname." }
        return u
    }
    /** Accepts "name.workers.dev" as well as a full origin, because that is what Cloudflare shows after deploy. */
    fun normalize(raw: String): String {
        val value=raw.trim().trimEnd('/')
        if(value.isBlank()) return ""
        return if(value.contains("://")) value else "https://$value"
    }
}

open class TeraFailure(message: String) : IOException(message)
/** TeraBox's own anti-bot wall: the files are listed but no direct link is signed until
 *  TeraBox's own check is completed. The app answers this with the in-app TeraBox check. */
class TeraWall(message: String) : TeraFailure(message)
/** A transport-level failure: the connection died (reset, EOF, broken pipe, timeout) rather than the
 *  service answering. Only this kind is retried and re-tried on TeraBox mirror hosts. */
class TeraTransport(message: String, cause: Throwable?) : TeraFailure(message)
data class TeraFile(val id: String, val name: String, val size: Long, val directory: Boolean, val path: String, val direct: String = "", val md5: String = "")
data class TeraShare(val original: String, val origin: HttpUrl, val shortId: String, val token: String, val record: JSONObject, val files: List<TeraFile>, val backend: Boolean)
data class TeraMedia(val url: String, val referer: String, val file: TeraFile)

/** No imported accounts or third-party cookie pools. Session cookies are scoped by OkHttp and RAM-only. */
class TeraEngine(backendAddress: String = "") {
    val backend = TeraPolicy.backend(backendAddress)
    private val cookies = mutableListOf<Cookie>()
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS)
        .callTimeout(90,TimeUnit.SECONDS).retryOnConnectionFailure(false).followSslRedirects(false)
        .cookieJar(object : CookieJar {
            @Synchronized override fun saveFromResponse(url: HttpUrl, incoming: List<Cookie>) {
                incoming.forEach { c -> cookies.removeAll { it.name==c.name && it.domain==c.domain && it.path==c.path }; cookies.add(c) }
                cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
                while(cookies.size>128) cookies.removeAt(0)
            }
            @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val jar = cookies.filter { it.matches(url) && it.expiresAt>System.currentTimeMillis() }
                // TeraBox verification cookies minted by the in-app check sheet (scoped to
                // TeraBox hosts only, see TeraCookies). The jar merge keeps OkHttp happy.
                val extra = TeraCookies.headerFor(url.toString())
                if(extra.isBlank()) return jar
                val parsed = extra.split(";").mapNotNull { part ->
                    val eq=part.indexOf('=')
                    if(eq<=0) null else runCatching {
                        Cookie.Builder().name(part.substring(0,eq).trim()).value(part.substring(eq+1).trim())
                            .domain(url.host).path("/").build()
                    }.getOrNull()
                }
                return jar.filter { j -> parsed.none { it.name==j.name } } + parsed
            }
        })
        .addNetworkInterceptor { chain ->
            if(!TeraPolicy.permitted(chain.request().url,backend)) throw TeraFailure("Unrecognized media host or insecure redirect. No request was sent to it.")
            chain.proceed(chain.request())
        }.build()
    fun cancel() = client.dispatcher.cancelAll()
    private fun request(url: HttpUrl, body: RequestBody? = null): Request = Request.Builder().url(url)
        .header("User-Agent",AGENT).header("Accept-Encoding","identity").apply { if(body!=null) post(body) }.build()
    /** Chunked, size-capped read. Okio's readByteArray(n) throws EOFException unless it gets exactly n bytes,
     *  which is what made every normal page look like a failure. This reads until EOF (or the cap) instead. */
    private fun fetchOnce(url: HttpUrl, payload: RequestBody?, forceHttp1: Boolean, closeConnection: Boolean): Pair<String,HttpUrl> {
        val builder=Request.Builder().url(url).header("User-Agent",AGENT).header("Accept-Encoding","identity")
        if(closeConnection) builder.header("Connection","close")
        if(payload!=null) builder.post(payload)
        val chosen=if(forceHttp1) clientHttp1 else client
        return chosen.newCall(builder.build()).execute().use { r ->
            if(!r.isSuccessful) throw TeraFailure("TeraBox HTTP ${r.code}. No automatic engine switch was made.")
            val b=r.body ?: throw TeraFailure("Empty server response.")
            readCapped(b.source(),2_000_000L) to r.request.url
        }
    }
    /** One request with bounded retries: a dead connection is retried, then over HTTP/1.1 with a fresh
     *  connection. Only transport errors are retried; a real service answer is never re-requested. */
    private fun text(url: HttpUrl, body: RequestBody? = null): Pair<String,HttpUrl> {
        val plans=listOf(false to false,true to true)
        var last: IOException? = null
        for(round in 0 until 2) {
            for((http1,close) in plans) {
                try { return fetchOnce(url,body,http1,close) }
                catch(e: TeraFailure) { throw e }
                catch(e: IOException) {
                    last=e
                    if(round==0 && !http1) continue
                    runCatching { Thread.sleep(if(http1) 700L else 300L) }
                }
            }
        }
        throw TeraTransport("The connection to TeraBox was reset before the answer arrived (${last?.javaClass?.simpleName ?: "network error"}). Tried twice, including HTTP/1.1 with a fresh connection.",last)
    }
    private val clientHttp1: OkHttpClient by lazy { client.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build() }
    private fun json(url: HttpUrl, body: RequestBody? = null): JSONObject {
        val raw=text(url,body).first
        val obj=try { JSONObject(raw) } catch(_: Exception) { throw TeraFailure("Server returned a page instead of metadata.") }
        checkResponse(obj)
        return obj
    }
    fun resolve(raw: String, directory: String? = null): TeraShare {
        val input=TeraPolicy.validate(raw)
        if(backend!=null) {
            val obj=json(backend.newBuilder().addPathSegments("api/resolve").addQueryParameter("link",input.toString()).build())
            val files=parseFiles(obj,true)
            if(files.isEmpty()) throw TeraFailure("Resolver returned no files.")
            return TeraShare(input.toString(),backend,"","",obj,files,true)
        }
        var transport: TeraTransport? = null
        for(candidate in listOf(input)+TeraPolicy.mirrorCandidates(input)) {
            try { return resolveOn(candidate,input.toString(),directory) }
            catch(t: TeraTransport) { transport=t }
        }
        throw transport ?: TeraFailure("Analysis failed.")
    }
    private fun resolveOn(input: HttpUrl, original: String, directory: String? = null): TeraShare {
        val (html,page)=text(input)
        val id=page.queryParameter("surl") ?: input.queryParameter("surl") ?: input.pathSegments.dropWhile { it!="s" }.getOrNull(1)?.removePrefix("1")
            ?: throw TeraFailure("Share ID was not found.")
        val token=extractToken(html)
        val origin=page.newBuilder().encodedPath("/").query(null).fragment(null).build()
        val files=mutableListOf<TeraFile>(); var record=JSONObject()
        var p=1
        while(true) {
            val u=api(origin,"share/list",id,token).addQueryParameter("page",p.toString()).addQueryParameter("num","100")
                .addQueryParameter("root",if(directory==null) "1" else "0")
            directory?.let { u.addQueryParameter("dir",it) }
            val result=json(u.build()); if(p==1) record=result
            val batch=parseFiles(result,false); files.addAll(batch)
            if(batch.size<100) break
            if(++p>100) throw TeraFailure("Folder exceeded the metadata safety limit. Open a smaller shared folder.")
        }
        if(files.isEmpty()) throw TeraFailure("No files were returned for this share.")
        return TeraShare(original,origin,id,token,record,files,false)
    }
    private fun api(origin: HttpUrl, path: String, id: String, token: String) = origin.newBuilder().encodedPath("/$path")
        .addQueryParameter("app_id","250528").addQueryParameter("web","1").addQueryParameter("channel","dubox")
        .addQueryParameter("clienttype","0").addQueryParameter("shorturl",id).apply { if(token.isNotEmpty()) addQueryParameter("jsToken",token) }
    /** The WAP share page embeds the share record (sign/timestamp/uk/shareid) in its HTML. Read as text only. */
    internal fun wapRecord(share: TeraShare): JSONObject {
        val url=share.origin.newBuilder().addPathSegments("wap/share/filelist").addQueryParameter("surl",share.shortId).build()
        return parseWapRecord(text(url).first)
    }
    private fun mintHint(share: TeraShare): String {
        if(backend!=null) return " The configured resolver also reported an error; check its server status."
        return "\nTeraBox listed the files but refused to sign a direct link in this session. Open the in-app TeraBox check to complete TeraBox's own step, or enter a resolver under Advanced. Nothing was bypassed."
    }
    private data class MintRecord(val sign:String,val timestamp:String,val uk:String,val shareid:String)
    private fun makeLogId() = System.currentTimeMillis().toString(16).padEnd(20,'0')
    /** Every first-party source of the four signed fields, in the order TeraBox's own clients use them:
     *  the listing record, /share/tplconfig (AList/OpenList lineage), /api/shorturlinfo, the WAP page. */
    private fun mintRecord(share: TeraShare): MintRecord {
        fun field(k:String)=share.record.optString(k).ifBlank { if(k=="shareid") share.record.optString("share_id") else "" }
        var sign=field("sign"); var timestamp=field("timestamp"); var uk=field("uk"); var shareid=field("shareid")
        if(sign.isBlank()||timestamp.isBlank()) {
            runCatching {
                for(channel in listOf("dubox","chunlei")) {
                    val u=share.origin.newBuilder().encodedPath("/share/tplconfig")
                        .addQueryParameter("fields","sign,timestamp").addQueryParameter("channel",channel)
                        .addQueryParameter("web","1").addQueryParameter("app_id","250528").addQueryParameter("clienttype","0")
                        .addQueryParameter("surl",share.shortId).build()
                    val obj=runCatching { JSONObject(text(u).first) }.getOrNull() ?: continue
                    if(obj.optInt("errno",1)==0) {
                        val data=obj.optJSONObject("data") ?: continue
                        if(sign.isBlank()) sign=data.optString("sign")
                        if(timestamp.isBlank()) timestamp=data.optString("timestamp")
                        if(sign.isNotBlank()&&timestamp.isNotBlank()) break
                    }
                }
            }
        }
        if(uk.isBlank()||shareid.isBlank()||sign.isBlank()||timestamp.isBlank()) {
            runCatching {
                val obj=json(api(share.origin,"api/shorturlinfo",share.shortId,share.token).addQueryParameter("root","1").build())
                if(uk.isBlank()) uk=obj.optString("uk")
                if(sign.isBlank()) sign=obj.optString("sign")
                if(timestamp.isBlank()) timestamp=obj.optString("timestamp")
                if(shareid.isBlank()) shareid=obj.optString("shareid").ifBlank { obj.optString("share_id") }
            }
        }
        if(sign.isBlank()||timestamp.isBlank()||uk.isBlank()||shareid.isBlank()) {
            runCatching {
                val rec=wapRecord(share)
                if(sign.isBlank()) sign=rec.optString("sign")
                if(timestamp.isBlank()) timestamp=rec.optString("timestamp")
                if(uk.isBlank()) uk=rec.optString("uk")
                if(shareid.isBlank()) shareid=rec.optString("shareid")
            }
        }
        return MintRecord(sign,timestamp,uk,shareid)
    }
    /** `/share/download` in both forms TeraBox has shipped: POST (Baidu-lineage) and GET (current web player).
     *  Returns "" on ordinary refusals and throws TeraWall only when TeraBox demanded its own check. */
    private fun downloadMint(share: TeraShare, file: TeraFile, rec: MintRecord): String {
        if(rec.sign.isBlank()||rec.timestamp.isBlank()||rec.uk.isBlank()||rec.shareid.isBlank()) return ""
        var wall: TeraWall? = null
        for(attempt in 0..1) {
            try {
                val builder=api(share.origin,"share/download",share.shortId,share.token)
                    .addQueryParameter("sign",rec.sign).addQueryParameter("timestamp",rec.timestamp)
                if(attempt==1) builder.addQueryParameter("shareid",rec.shareid).addQueryParameter("dp-logid",makeLogId())
                    .addQueryParameter("nozip","0").addQueryParameter("encrypt","0").addQueryParameter("product","share")
                    .addQueryParameter("uk",rec.uk).addQueryParameter("primaryid",rec.shareid)
                    .addQueryParameter("fid_list","[${file.id}]").addQueryParameter("type","nolimit")
                val body=FormBody.Builder().add("encrypt","0").add("product","share").add("uk",rec.uk)
                    .add("primaryid",rec.shareid).add("fid_list","[${file.id}]").add("type","nolimit").build()
                val result=try { if(attempt==0) json(builder.build(),body) else json(builder.build()) }
                catch(e: TeraWall) { wall=e; continue }
                val link=extractDlink(result)
                if(link.isNotBlank()) return link
            } catch(e: TeraWall) { wall=e }
            catch(_: TeraFailure) { /* ordinary refusal: the next form or strategy may still work */ }
        }
        wall?.let { throw it }
        return ""
    }
    private fun extractDlink(o: JSONObject): String =
        o.optString("dlink").ifBlank { o.optJSONArray("list")?.optJSONObject(0)?.optString("dlink").orEmpty() }
            .ifBlank { o.optJSONArray("info")?.optJSONObject(0)?.optString("dlink").orEmpty() }
            .ifBlank { o.optJSONArray("dlink")?.optJSONObject(0)?.optString("dlink").orEmpty() }
    /** The web player's own HLS manifest endpoint (fid, not fid_list — the wrong shape answered 31023).
     *  The manifest plays in the in-app player and saves segment-by-segment. */
    private fun streamingManifest(share: TeraShare, file: TeraFile, rec: MintRecord): String {
        if(rec.sign.isBlank()||rec.timestamp.isBlank()||rec.uk.isBlank()||rec.shareid.isBlank()) return ""
        for(type in listOf("M3U8_AUTO_720","M3U8_AUTO_480","M3U8_AUTO_1080")) {
            runCatching {
                val u=api(share.origin,"share/streaming",share.shortId,share.token)
                    .addQueryParameter("uk",rec.uk).addQueryParameter("shareid",rec.shareid)
                    .addQueryParameter("fid",file.id).addQueryParameter("sign",rec.sign)
                    .addQueryParameter("timestamp",rec.timestamp).addQueryParameter("type",type)
                    .addQueryParameter("esl","1").addQueryParameter("isplayer","1").build()
                val body=text(u).first
                if(body.trimStart().startsWith("#EXTM3U")) return u.toString()
            }
        }
        return ""
    }
    /** Signed dlinks the mobile share page embeds in its own page state — works even when the JSON
     *  endpoints are behind the check wall (the vendored resolver's WAP strategy). */
    private fun wapDlink(share: TeraShare, file: TeraFile): String {
        val url=share.origin.newBuilder().addPathSegments("wap/share/filelist").addQueryParameter("surl",share.shortId).build()
        return runCatching {
            parseWapStateFiles(text(url).first).firstOrNull { it.id==file.id && it.direct.isNotBlank() }?.direct.orEmpty()
        }.getOrDefault("")
    }
    /** Ladder of every first-party link source. The browser is not involved at any step. */
    fun media(share: TeraShare, file: TeraFile): TeraMedia {
        require(!file.directory) { "Open the folder first." }
        var link=file.direct
        if(link.isBlank() && share.backend) throw TeraFailure("Resolver returned metadata but no stream URL.")
        if(link.isBlank()) link=wapDlink(share,file)
        val rec=runCatching { mintRecord(share) }.getOrDefault(MintRecord("","","",""))
        var walled=false
        if(link.isBlank()) {
            link=try { downloadMint(share,file,rec) } catch(e: TeraWall) { walled=true; "" }
        }
        if(link.isBlank()) {
            val manifest=runCatching { streamingManifest(share,file,rec) }.getOrDefault("")
            if(manifest.isNotBlank()) {
                val target=runCatching { manifest.toHttpUrl() }.getOrElse { throw TeraFailure("Invalid media URL.") }
                if(!TeraPolicy.permitted(target,backend)) throw TeraFailure("Unrecognized media URL. Download blocked.")
                return TeraMedia(manifest,share.origin.toString(),file)
            }
        }
        if(link.isBlank()) {
            if(walled) throw TeraWall("TeraBox asked for its own verification before signing this file (code 400310). Open the in-app TeraBox check to complete it — then the file plays inside StreamDock. Nothing was bypassed.")
            throw TeraFailure("TeraBox returned no downloadable link for this file. Try the in-app TeraBox check, or configure a resolver under Advanced."+mintHint(share))
        }
        val target=runCatching { link.toHttpUrl() }.getOrElse { throw TeraFailure("Invalid media URL.") }
        if(!TeraPolicy.permitted(target,backend)) throw TeraFailure("Unrecognized media URL. Download blocked.")
        return TeraMedia(target.toString(),share.origin.toString(),file)
    }
    /** Read a small real byte range before enabling playback. Metadata is never reported as playback success.
     *  HLS manifests are validated by their playlist header instead of media bytes. */
    fun probe(media: TeraMedia) {
        client.newCall(Request.Builder().url(media.url).header("User-Agent",AGENT).header("Referer",media.referer)
            .header("Range","bytes=0-4095").header("Accept-Encoding","identity").build()).execute().use { r ->
            if(r.code!=200 && r.code!=206) throw TeraFailure("Media HTTP ${r.code}. Playback is not ready.")
            val source=r.body?.source() ?: throw TeraFailure("Empty media body.")
            val data=readUpTo(source,64)
            val head=String(data,Charsets.ISO_8859_1)
            if(head.contains("#EXTM3U")) return
            TeraTransfer.validateType(r.header("Content-Type").orEmpty())
            if(!isVideo(data) && !isAudio(data)) throw TeraFailure("The response is not a recognized media file. Use Download to inspect or save it.")
        }
    }
    companion object {
        const val AGENT="Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        /** Read the whole body up to `limit` bytes; EOF is a normal end, not an error. */
        internal fun readCapped(source: BufferedSource, limit: Long): String {
            val buffer=Buffer()
            while(buffer.size<limit) {
                val read=source.read(buffer,minOf(64*1024L,limit-buffer.size))
                if(read==-1L) break
            }
            if(!source.exhausted()) throw TeraFailure("The server response was larger than the safety limit.")
            return buffer.readUtf8()
        }
        /** Read at most `max` bytes (used for the media signature probe). */
        internal fun readUpTo(source: BufferedSource, max: Long): ByteArray {
            val buffer=Buffer()
            while(buffer.size<max) {
                val read=source.read(buffer,max-buffer.size)
                if(read==-1L) break
            }
            return buffer.readByteArray()
        }
        fun extractToken(html: String): String {
            val decoded=runCatching { URLDecoder.decode(html,"UTF-8") }.getOrDefault(html)
            return Regex("fn\\([\"']([A-Za-z0-9]+)[\"']\\)").find(decoded)?.groupValues?.get(1)
                ?: Regex("jsToken[\"']?\\s*[:=]\\s*[\"']([^\"']+)").find(decoded)?.groupValues?.get(1).orEmpty()
        }
        fun checkResponse(o: JSONObject) {
            val code=o.optInt("errno",0)
            if(code!=0) {
                val verify=code in setOf(400310,400210,4000020,400141) || o.optString("errmsg").contains("verify",true)
                if(verify) throw TeraWall("TeraBox requires its own verification (code $code) before it signs a file link. Open the in-app TeraBox check, or set a resolver in Advanced. Nothing was bypassed.")
                throw TeraFailure("TeraBox rejected this request (code $code). Check that the share is public and still available.")
            }
            val resolverCode=o.optString("code").ifBlank { o.optJSONObject("error")?.optString("code").orEmpty() }
            if(o.optString("status")=="error" || (o.has("error") && !o.isNull("error"))) throw TeraFailure(when(resolverCode) {
                "verification_required" -> "The resolver service needs a verified TeraBox session for this share (verification_required). See docs/RESOLVER-SETUP.md; no bypass was attempted."
                "cookie_invalid" -> "The resolver service's TeraBox session has expired (cookie_invalid). Its operator must refresh the session secret."
                "password_required" -> "This share is password-protected, so the resolver cannot list it."
                else -> "Resolver reported an error. Check the operator's server status; no file was downloaded."
            })
        }
        fun parseFiles(obj: JSONObject, backend: Boolean): List<TeraFile> {
            val a=obj.optJSONArray(if(backend) "files" else "list") ?: return emptyList()
            return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                TeraFile(o.optString("fs_id"),o.optString(if(backend) "file_name" else "server_filename","file"),
                    o.optLong(if(backend) "size_bytes" else "size",-1),o.optInt("isdir")==1,o.optString("path"),
                    o.optString(if(backend) "stream_url" else "dlink"),o.optString("md5"))
            } }
        }
        /** The WAP share page embeds its record as escaped JSON. Parsed as text; no JavaScript is executed. */
        fun parseWapRecord(raw: String): JSONObject {
            val flat=raw.replace("\\u002F","/").replace("\\/","/").replace("\\\"","\"")
            fun field(key: String, pattern: String): String =
                Regex("\""+key+"\"\\s*:\\s*\"?"+pattern).find(flat)?.groupValues?.get(1).orEmpty()
            return JSONObject().apply {
                put("sign",field("sign","([0-9a-fA-F]{16,})"))
                put("timestamp",field("timestamp","(\\d{10})"))
                put("uk",field("uk","(\\d{6,})"))
                put("shareid",field("shareid","(\\d{6,})"))
            }
        }
        /** Slice a balanced JSON object/array (string-aware) out of the page source. */
        internal fun sliceBalanced(source: String, from: Int): String? {
            val open=source[from]
            if(open!='{'&&open!='[') return null
            val close=if(open=='{')'}' else ']'
            var depth=0; var inString=false; var escaped=false
            for(i in from until source.length) {
                val ch=source[i]
                if(inString) {
                    if(escaped) escaped=false
                    else if(ch=='\\') escaped=true
                    else if(ch=='"') inString=false
                    continue
                }
                if(ch=='"') inString=true
                else if(ch==open) depth++
                else if(ch==close) { depth--; if(depth==0) return source.substring(from,i+1) }
            }
            return null
        }
        /** The mobile share page embeds the whole share record — signed dlinks included — in its
         *  page state. Deep-collected by shape so a restructured payload still works. */
        fun parseWapStateFiles(html: String): List<TeraFile> {
            val out=mutableListOf<TeraFile>()
            for(marker in listOf("window.__INITIAL_STATE__","__INITIAL_STATE__","window.__REDUX_STATE__")) {
                val at=html.indexOf(marker); if(at<0) continue
                val eq=html.indexOf('=',at+marker.length); if(eq<0) continue
                val start=(eq+1 until html.length).firstOrNull { html[it]=='{'||html[it]=='[' } ?: continue
                val raw=sliceBalanced(html,start) ?: continue
                val graph=runCatching { JSONObject().put("root", JSONObject(raw)) }.getOrNull()
                    ?: runCatching { org.json.JSONArray(raw) }.getOrNull()
                    ?: continue
                collectStateFiles(graph,out,0)
                if(out.isNotEmpty()) break
            }
            return out
        }
        private fun collectStateFiles(value: Any?, out: MutableList<TeraFile>, depth: Int) {
            if(depth>8||value==null) return
            when(value) {
                is org.json.JSONArray -> {
                    val direct=(0 until value.length()).mapNotNull { value.optJSONObject(it) }.filter { looksLikeStateFile(it) }
                    if(direct.isNotEmpty()) direct.forEach { out+=mapStateFile(it) }
                    else (0 until value.length()).forEach { collectStateFiles(value.opt(it),out,depth+1) }
                }
                is JSONObject -> {
                    if(looksLikeStateFile(value)) out+=mapStateFile(value)
                    else runCatching { value.keys() }.getOrNull()?.forEach { collectStateFiles(value.opt(it),out,depth+1) }
                }
            }
        }
        private fun looksLikeStateFile(o: JSONObject) = (o.has("server_filename")||o.has("filename")) && o.has("fs_id")
        private fun mapStateFile(o: JSONObject) = TeraFile(
            o.opt("fs_id")?.toString() ?: "",
            o.optString("server_filename", o.optString("filename","file")),
            o.optLong("size",-1),
            o.optString("isdir")=="1"||o.optInt("isdir",0)==1,
            o.optString("path"),
            o.optString("dlink"),
            o.optString("md5")
        )
        fun isAudio(b: ByteArray): Boolean = b.size>=4 && (b[0]==0x49.toByte()&&b[1]==0x44.toByte()&&b[2]==0x33.toByte()) ||
            (b.size>=4 && b[0]==0x4f.toByte()&&b[1]==0x67.toByte()&&b[2]==0x67.toByte()&&b[3]==0x53.toByte()) ||
            (b.size>=4 && b[0]==0x52.toByte()&&b[1]==0x49.toByte()&&b[2]==0x46.toByte()&&b[3]==0x46.toByte()) ||
            (b.size>=2 && b[0]==0xff.toByte() && (b[1].toInt() and 0xe0)==0xe0) ||
            (b.size>=12 && String(b,4,4,Charsets.US_ASCII)=="ftyp")
        fun isVideo(b: ByteArray): Boolean = b.size>=8 && (String(b,4,4,Charsets.US_ASCII) in setOf("ftyp","moov","mdat","free") ||
            (b[0]==0x1a.toByte() && b[1]==0x45.toByte() && b[2]==0xdf.toByte() && b[3]==0xa3.toByte()) || b[0]==0x47.toByte())
    }
}
