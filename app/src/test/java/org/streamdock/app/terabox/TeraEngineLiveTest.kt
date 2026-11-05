package org.streamdock.app.terabox

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Optional live check, run only when -PteraShare=<share link> is passed to Gradle.
 *  It uses the real public share and only reports what actually happened — listing, record, download link. */
class TeraEngineLiveTest {
    @Test fun realShareAnalysis() {
        val share = System.getProperty("streamdock.teraShare", "").orEmpty().trim()
        assumeTrue("Optional live TeraBox check (pass -PteraShare=<link>)", share.isNotBlank())
        val engine = TeraEngine("")
        val resolved = engine.resolve(share)
        println("LIVE resolve: files=${resolved.files.size} origin=${resolved.origin} strategy-visible-record=${resolved.record.length() > 0}")
        println("LIVE record keys: ${resolved.record.keys().asSequence().toList()}")
        resolved.files.take(6).forEach { println("LIVE file: name=${it.name} size=${it.size} dir=${it.directory} dlink=${it.direct.isNotEmpty()}") }
        assertTrue("a real share must list at least one file", resolved.files.isNotEmpty())
        val file = resolved.files.firstOrNull { !it.directory }
        if (file != null) {
            val outcome = runCatching { engine.media(resolved, file) }
            println("LIVE download-link: " + outcome.fold(
                { media -> "OK -> ${media.url.take(90)}…" },
                { error -> "NOT AVAILABLE -> ${error.javaClass.simpleName}: ${error.message}" }))
            outcome.getOrNull()?.let { media ->
                val probe = runCatching { engine.probe(media) }
                println("LIVE media probe: " + probe.fold({ "OK (real media bytes verified)" }, { "failed -> ${it.message}" }))
            }
        }
    }
}
