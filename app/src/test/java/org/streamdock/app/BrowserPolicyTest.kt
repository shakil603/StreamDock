package org.streamdock.app

import org.junit.Assert.*
import org.junit.Test

class BrowserPolicyTest {
    @Test fun normalAddressDoesNotBecomeSearch() { assertEquals("https://www.youtube.com/watch?v=123",BrowserPolicy.input("www.youtube.com/watch?v=123",BrowserPolicy.Search.GOOGLE)) }
    @Test fun explicitHttpIsUpgradedNotLoadedInCleartext() { assertEquals("https://terasaver.com/",BrowserPolicy.input("http://terasaver.com/",BrowserPolicy.Search.GOOGLE)) }
    @Test fun httpsIsKeptAndSchemeNormalized() { assertEquals("https://example.com/a?q=1",BrowserPolicy.https(" HTTPS://example.com/a?q=1 ")) }
    @Test fun googleSearchIsEncoded() { assertEquals("https://www.google.com/search?q=space+%26+science",BrowserPolicy.input("space & science",BrowserPolicy.Search.GOOGLE)) }
    @Test fun selectedProviderIsUsed() {
        assertTrue(BrowserPolicy.input("film music",BrowserPolicy.Search.DUCKDUCKGO).startsWith("https://duckduckgo.com/?q="))
        assertTrue(BrowserPolicy.input("film music",BrowserPolicy.Search.BING).startsWith("https://www.bing.com/search?q="))
    }
    @Test(expected=IllegalArgumentException::class) fun emptyInputRejected() { BrowserPolicy.input("  ",BrowserPolicy.Search.GOOGLE) }
    @Test fun unsafeSchemesRejected() {
        for(url in listOf("javascript:alert(1)","file:///etc/passwd","content://private/file","data:text/html,hi","intent://x","blob:https://example.com/id")) {
            try { BrowserPolicy.input(url,BrowserPolicy.Search.GOOGLE);fail(url) } catch(_:IllegalArgumentException) { }
        }
    }
    @Test(expected=IllegalArgumentException::class) fun userinfoIsRejected() { BrowserPolicy.https("https://name:secret@example.com/path") }
    @Test(expected=IllegalArgumentException::class) fun navigationCannotDowngradeHttps() { BrowserPolicy.https("http://example.com/file") }
    @Test(expected=IllegalArgumentException::class) fun malformedUrlRejectedWithoutEcho() { BrowserPolicy.https("https://example.com/space here") }
    @Test fun helperDomainsAreExactOrSubdomains() {
        assertTrue(BrowserPolicy.helper("https://www.teraplayer.in/"));assertTrue(BrowserPolicy.helper("https://terasaver.com/"))
        assertFalse(BrowserPolicy.helper("https://terasaver.com.evil.test/"));assertFalse(BrowserPolicy.helper("https://fake-teraplayer.in/"))
    }
    @Test fun teraLinksStayOutOfNativeEngines() {
        for(host in listOf("terabox.com","www.1024terabox.com","1024tera.com","terabox.app","teraboxapp.com","teraboxdl.site")) assertTrue(BrowserPolicy.browserOnly("https://$host/s/1abc"))
        assertFalse(BrowserPolicy.browserOnly("https://youtube.com/watch?v=123"))
        assertFalse(BrowserPolicy.browserOnly("https://terabox.com.attacker.test/"))
    }
    @Test fun refererContainsNoSecretPathOrQuery() {
        assertEquals("https://example.com/",BrowserPolicy.origin("https://example.com/private/share?token=secret#password"))
        assertNull(BrowserPolicy.origin("javascript:cookie"))
    }
    @Test fun filenameCannotEscapeDownloadsDirectory() {
        val safe=BrowserPolicy.safeName("../../nested/evil\\name\u0000.mp4")
        assertFalse(safe.contains('/'));assertFalse(safe.contains('\\'));assertFalse(safe.contains('\u0000'))
        assertFalse(safe.startsWith('.'));assertTrue(safe.endsWith(".mp4"))
    }
    @Test fun longUnicodeFilenameRetainsExtensionAndFitsFilesystem() {
        val name=BrowserPolicy.safeName("বাংলা".repeat(100)+".mp4")
        assertTrue(name.endsWith(".mp4"));assertTrue(name.toByteArray(Charsets.UTF_8).size<=180)
    }
    @Test fun userFilenameKeepsUnicodeCombiningMarks() { assertEquals("বাংলা.mp4",BrowserPolicy.safeName("বাংলা.mp4")) }
    @Test fun emptyFilenameHasFallback() { assertEquals("download",BrowserPolicy.safeName("...   ")) }
}
