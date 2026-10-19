package org.streamdock.app

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit

class DirectHttpDownloaderTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var downloader: DirectHttpDownloader
    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer()
        server.useHttps(serverCerts.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .retryOnConnectionFailure(false).followSslRedirects(false).readTimeout(2, TimeUnit.SECONDS).build()
        downloader = DirectHttpDownloader(client, chunkBytes = 4)
    }
    @After fun close() { server.shutdown() }
    private fun part(body: String, range: String) = MockResponse().setResponseCode(206)
        .setHeader("Content-Type", "video/mp4").setHeader("Content-Range", range).setBody(body)
    private fun fetch(name: String = "media.bin") = temp.root.resolve(name).also {
        downloader.download(server.url("/media").toString(), it, emptyMap(), { false }) { _, _ -> }
    }
    @Test fun rangesAreJoinedExactly() {
        server.enqueue(part("abcd", "bytes 0-3/6")); server.enqueue(part("ef", "bytes 4-5/6"))
        assertEquals("abcdef", fetch().readText())
        assertEquals("bytes=0-3", server.takeRequest().getHeader("Range"))
        assertEquals("bytes=4-5", server.takeRequest().getHeader("Range"))
    }
    @Test fun ignoredInitialRangeIsCompleteSingleResponse() {
        server.enqueue(MockResponse().setBody("abcdef").setHeader("Content-Type", "video/mp4"))
        assertEquals("abcdef", fetch().readText()); assertEquals(1, server.requestCount)
    }
    @Test fun forbiddenIsNotRetriedAndPartialFileRemoved() {
        server.enqueue(MockResponse().setResponseCode(403))
        try { fetch(); fail("Expected 403") } catch (e: HttpMediaException) { assertEquals(403, e.code) }
        assertEquals(1, server.requestCount); assertFalse(temp.root.resolve("media.bin").exists())
    }
    @Test fun rateLimitIsNotRetried() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        try { fetch(); fail("Expected 429") } catch (e: HttpMediaException) { assertEquals(429, e.code) }
        assertEquals(1, server.requestCount)
    }
    @Test fun ignoredContinuationCannotCorruptOutput() {
        server.enqueue(part("abcd", "bytes 0-3/6")); server.enqueue(MockResponse().setBody("abcdef"))
        try { fetch(); fail("Must reject full response on continuation") } catch (_: IOException) { }
        assertFalse(temp.root.resolve("media.bin").exists())
    }
    @Test fun htmlIsNotSavedAsVideo() {
        server.enqueue(MockResponse().setBody("<html>Access denied</html>").setHeader("Content-Type", "text/html"))
        try { fetch(); fail("Must reject HTML") } catch (_: IOException) { }
        assertFalse(temp.root.resolve("media.bin").exists())
    }
    @Test fun changedResourceSizeRejected() {
        server.enqueue(part("abcd", "bytes 0-3/6")); server.enqueue(part("ef", "bytes 4-5/9"))
        try { fetch(); fail("Must reject size change") } catch (_: IOException) { }
        assertFalse(temp.root.resolve("media.bin").exists())
    }
    @Test fun missingRangeRejected() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("abcd"))
        try { fetch(); fail("Must reject missing range") } catch (_: IOException) { }
    }
    @Test fun shortRangeBodyRejected() {
        server.enqueue(part("ab", "bytes 0-3/4"))
        try { fetch(); fail("Must reject truncation") } catch (_: IOException) { }
        assertFalse(temp.root.resolve("media.bin").exists())
    }
    @Test fun cancellationStopsBeforeNetwork() {
        try {
            downloader.download(server.url("/media").toString(), temp.root.resolve("media.bin"), emptyMap(), { true }) { _, _ -> }
            fail("Expected cancellation")
        } catch (_: InterruptedException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun cancellationBetweenReadsDiscardsPartial() {
        server.enqueue(part("abcd", "bytes 0-3/8"))
        var cancelled = false
        try {
            downloader.download(server.url("/media").toString(), temp.root.resolve("media.bin"), emptyMap(), { cancelled }) { _, _ -> cancelled = true }
            fail("Expected cancellation")
        } catch (_: InterruptedException) { }
        assertFalse(temp.root.resolve("media.bin").exists()); assertEquals(1, server.requestCount)
    }
}
