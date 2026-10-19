package org.streamdock.app.terabox

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Offline tests for parsing, policy borders and honest error reporting. They never perform a network request. */
class TeraEngineTest {
    private val sample="https://1024terabox.com/s/1RBVO1eibUTx3GM9-8APX-g"


    @Test fun `resolver input accepts the bare host Cloudflare prints`() {
        assertEquals("https://name.workers.dev", TeraPolicy.normalize("name.workers.dev"))
        assertEquals("https://name.workers.dev", TeraPolicy.normalize("  https://name.workers.dev/  "))
        assertEquals("", TeraPolicy.normalize("   "))
        assertNotNull(TeraPolicy.backend("name.workers.dev"))
        assertNotNull(TeraPolicy.backend("https://resolver.example.com"))
        assertNull(TeraPolicy.backend(""))
        var rejected = false
        try { TeraPolicy.backend("http://plain.example.com") } catch (_: Exception) { rejected = true }
        assertTrue("a non-HTTPS resolver must be rejected", rejected)
    }

    @Test fun `verification wording names the honest options and never a bypass`() {
        val error = runCatching { TeraEngine.checkResponse(org.json.JSONObject().put("errno", 400310).put("errmsg", "need verify_v2")) }.exceptionOrNull()
        val text = (error as? TeraFailure)?.message.orEmpty()
        assertTrue(text.contains("verification"))
        assertTrue(text.contains("in-app TeraBox check"))
        assertTrue(text.contains("resolver"))
        assertTrue(text.contains("Nothing was bypassed"))
    }

    @Test fun shareLinksAreRecognizedAndBounded() {
        assertTrue(TeraPolicy.isShare(sample))
        assertTrue(TeraPolicy.isShare("https://www.terabox.com/s/abc123"))
        assertTrue(TeraPolicy.isShare("https://terabox.app/s/xyz"))
        assertFalse(TeraPolicy.isShare("http://1024terabox.com/s/1abc"))
        assertFalse(TeraPolicy.isShare("https://terabox.com.evil.example/s/1abc"))
        assertFalse(TeraPolicy.isShare("https://user:pw@terabox.com/s/1abc"))
        assertFalse(TeraPolicy.isShare("https://example.com/s/1abc"))
    }
    @Test fun shareUrlMustNotBeTheSiteHomePage() {
        assertThrows(IllegalArgumentException::class.java) { TeraPolicy.validate("https://www.terabox.com/") }
    }
    @Test fun mediaHostsAreLimitedToTeraBoxFamily() {
        assertTrue(TeraPolicy.permitted("https://d.pcs.baidu.com/file/1".toHttp()))
        assertTrue(TeraPolicy.permitted("https://www.1024tera.com/share/list".toHttp()))
        assertFalse(TeraPolicy.permitted("https://terabox.hnn.workers.dev/dl".toHttp()))
        assertFalse(TeraPolicy.permitted("https://resolver.example.com/api".toHttp()))
        assertTrue(TeraPolicy.permitted("https://resolver.example.com/api".toHttp(),TeraPolicy.backend("https://resolver.example.com")))
        assertFalse(TeraPolicy.permitted("http://d.pcs.baidu.com/file/1".toHttp()))
    }
    @Test fun resolverAddressRejectsUnsafeValues() {
        assertEquals("resolver.example.com",TeraPolicy.backend("https://resolver.example.com")!!.host)
        for(bad in listOf("http://resolver.example.com","https://user:pw@resolver.example.com","https://resolver.example.com/path","https://127.0.0.1","https://resolver.local","")) {
            try { TeraPolicy.backend(bad); if(bad.isNotEmpty()) fail("Expected rejection for $bad") } catch (_: IllegalArgumentException) { }
        }
    }
    private fun String.toHttp(): okhttp3.HttpUrl =
        toHttpUrlOrNull() ?: throw IllegalArgumentException("bad test url")
    private fun String.toHttpUrl(): okhttp3.HttpUrl = toHttp()

    @Test fun jsTokenIsExtractedFromTheEncodedPageSnippet() {
        val html="""try { eval(decodeURIComponent(`function%20fn%28a%29%7Bwindow.jsToken%20%3D%20a%7D%3Bfn%28%22ABC123DEADBEEF%22%29`)) } catch (ex) { }"""
        assertEquals("ABC123DEADBEEF",TeraEngine.extractToken(html))
        assertEquals("TOKEN2","""var t={"jsToken":"TOKEN2"}""".let(TeraEngine::extractToken))
        assertEquals("",TeraEngine.extractToken("<html></html>"))
    }
    @Test fun verificationErrorsAreReportedAsVerificationNotAsSuccessOrBypass() {
        TeraEngine.checkResponse(JSONObject("""{"errno":0,"dlink":"https://d.pcs.baidu.com/x"}"""))
        val verify=runCatching { TeraEngine.checkResponse(JSONObject("""{"errno":400310,"errmsg":"need verify_v2"}""")) }.exceptionOrNull()
        assertTrue(verify is TeraFailure); assertTrue(verify!!.message!!.contains("verification"))
        assertTrue(verify.message!!.contains("bypass", ignoreCase = true))
        val rejected=runCatching { TeraEngine.checkResponse(JSONObject("""{"errno":2,"errmsg":"invalid"}""")) }.exceptionOrNull()
        assertTrue(rejected is TeraFailure); assertFalse(rejected!!.message!!.contains("verification"))
    }
    @Test fun fileListsAreParsedForBothDirectAndResolverShapes() {
        val direct=TeraEngine.parseFiles(JSONObject("""{"errno":0,"list":[{"fs_id":"1","server_filename":"a.mp4","size":100,"isdir":0,"path":"/x/a.mp4","dlink":""},{"fs_id":"2","server_filename":"folder","size":0,"isdir":1,"path":"/x/folder"}]}"""),false)
        assertEquals(2,direct.size); assertEquals("a.mp4",direct[0].name); assertEquals(100L,direct[0].size); assertFalse(direct[0].directory); assertTrue(direct[1].directory)
        val backend=TeraEngine.parseFiles(JSONObject("""{"status":"ok","files":[{"fs_id":"9","file_name":"b.mp4","size_bytes":20,"isdir":0,"stream_url":"https://d.pcs.baidu.com/b"}]}"""),true)
        assertEquals(1,backend.size); assertEquals("b.mp4",backend[0].name); assertTrue(backend[0].direct.isNotEmpty())
    }
    @Test fun mediaSignaturesDistinguishRealFilesFromErrorPages() {
        val mp4=byteArrayOf(0,0,0,24)+"ftyp".toByteArray()
        val webm=byteArrayOf(0x1a,0x45,0xdf.toByte(),0xa3.toByte())+ByteArray(8)
        val ts=byteArrayOf(0x47)+ByteArray(16)
        assertTrue(TeraEngine.isVideo(mp4)); assertTrue(TeraEngine.isVideo(webm)); assertTrue(TeraEngine.isVideo(ts))
        assertFalse(TeraEngine.isVideo("<!DOCTYPE html>".toByteArray()))
        assertTrue(TeraEngine.isAudio(byteArrayOf(0x49,0x44,0x33)+ByteArray(6)))
        assertFalse(TeraEngine.isAudio("{\"errno\":400310}".toByteArray()))
    }
    @Test fun contentTypesRejectPagesAndManifests() {
        TeraTransfer.validateType("video/mp4"); TeraTransfer.validateType("application/octet-stream")
        for(bad in listOf("text/html","application/json","application/vnd.apple.mpegurl","application/dash+xml")) {
            val thrown=runCatching { TeraTransfer.validateType(bad) }.exceptionOrNull()
            assertTrue("$bad should be rejected",thrown is TeraFailure)
        }
    }
    @Test fun rangeValidationProtectsResumedFiles() {
        assertEquals(Triple(0L,999L,1000L),TeraTransfer.parseRange("bytes 0-999/1000",0))
        assertEquals(Triple(100L,200L,500L),TeraTransfer.parseRange("bytes 100-200/500",100))
        assertTrue(runCatching { TeraTransfer.parseRange("bytes 50-200/500",100) }.exceptionOrNull() is TeraFailure)
        assertTrue(runCatching { TeraTransfer.parseRange("bytes 100-600/500",100) }.exceptionOrNull() is TeraFailure)
        assertTrue(runCatching { TeraTransfer.parseRange("garbage",0) }.exceptionOrNull() is TeraFailure)
    }
    @Test fun wapShareRecordIsParsedWithoutExecutingPageScripts() {
        val page="""<script>var t={"a":1}</script>\"shareInfo\":{\"surl\":\"RBVO1eibUTx3GM9-8APX-g\",\"shareid\":3527738054,\"uk\":4401766872369,\"sign\":\"18eb312c0eb47e2a84e508c93060810c71f8164d\",\"timestamp\":1791708666,\"fileInfo\":{\"fs_id\":\"741735545519280\",\"dlink\":\"\"}}"""
        val record=TeraEngine.parseWapRecord(page)
        assertEquals("18eb312c0eb47e2a84e508c93060810c71f8164d",record.getString("sign"))
        assertEquals("1791708666",record.getString("timestamp"))
        assertEquals("4401766872369",record.getString("uk"))
        assertEquals("3527738054",record.getString("shareid"))
        val empty=TeraEngine.parseWapRecord("<html>no record</html>")
        assertEquals("",empty.getString("sign")); assertEquals("",empty.getString("uk"))
    }
    @Test fun pageBodiesSmallerThanTheLimitAreReadCompletely() {
        // The phone build threw EOFException for every normal page because the reader demanded a fixed 2 MB.
        val small = okio.Buffer().writeUtf8("""<html><script>function fn(a){window.jsToken = a};fn("ABC")</script></html>""")
        val text = TeraEngine.readCapped(small, 2_000_000L)
        assertTrue(text.contains("ABC"))
        assertEquals("ABC", TeraEngine.extractToken(text))
        val empty = TeraEngine.readCapped(okio.Buffer(), 2_000_000L)
        assertEquals("", empty)
    }
    @Test fun oversizedResponsesAreRejectedInsteadOfBuffered() {
        val big = okio.Buffer().write(ByteArray(4096) { 'x'.code.toByte() })
        val thrown = runCatching { TeraEngine.readCapped(big, 1024L) }.exceptionOrNull()
        assertTrue(thrown is TeraFailure)
        assertTrue(thrown!!.message!!.contains("safety limit"))
    }
    @Test fun mediaSignatureProbeToleratesFilesShorterThanTheProbeSize() {
        val head = TeraEngine.readUpTo(okio.Buffer().write(byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70)), 64)
        assertEquals(8, head.size)
        assertTrue(TeraEngine.isVideo(head))
        assertEquals(0, TeraEngine.readUpTo(okio.Buffer(), 64).size)
    }
    @Test fun mirrorCandidatesStayOnOfficialHostsAndKeepTheShareId() {
        val input = "https://1024terabox.com/s/17M6LEUu0K_qoBZaY0YCQLg".toHttpUrl()
        val mirrors = TeraPolicy.mirrorCandidates(input)
        assertTrue(mirrors.isNotEmpty())
        assertTrue(mirrors.all { it.scheme == "https" })
        assertTrue(mirrors.none { it.host == input.host })
        assertTrue(mirrors.all { it.encodedPath == "/s/17M6LEUu0K_qoBZaY0YCQLg" })
        assertTrue(mirrors.all { TeraPolicy.permitted(it) })
        assertEquals(emptyList<okhttp3.HttpUrl>(), TeraPolicy.mirrorCandidates(input, TeraPolicy.backend("https://resolver.example.com")))
        assertEquals(emptyList<okhttp3.HttpUrl>(), TeraPolicy.mirrorCandidates("https://www.terabox.com/".toHttpUrl()))
    }
    @Test fun transportFailuresAreDistinctFromServiceAnswers() {
        val transport = TeraTransport("connection reset", null)
        assertTrue(transport is TeraFailure)
        assertTrue(transport is java.io.IOException)
        assertTrue(runCatching { TeraEngine.checkResponse(JSONObject("""{"errno":400310}""")) }.exceptionOrNull() !is TeraTransport)
    }
    @Test fun resolverFilesWithoutStreamUrlsAreNotTreatedAsPlayable() {
        val backend=TeraEngine.parseFiles(JSONObject("""{"files":[{"fs_id":"1","file_name":"a.mp4","size_bytes":10,"isdir":0}]}"""),true)
        assertEquals(1,backend.size); assertEquals("",backend[0].direct)
    }
}
