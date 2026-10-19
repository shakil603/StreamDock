package org.streamdock.app

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class EngineBoundaryTest {
    @Test fun successfulResultIsUnchanged() { assertEquals(42, EngineBoundary.call("test") { 42 }) }
    @Test fun missingClassIsConverted() {
        val error = NoClassDefFoundError("missing on Android")
        try { EngineBoundary.call("NewPipe") { throw error }; fail("Expected wrapper") }
        catch (e: EngineLoadException) { assertSame(error, e.cause); assertEquals("NewPipe", e.engine) }
    }
    @Test fun nativeLinkFailureIsConverted() {
        try { EngineBoundary.call("native") { throw UnsatisfiedLinkError("ABI") }; fail("Expected wrapper") }
        catch (e: EngineLoadException) { assertTrue(e.cause is UnsatisfiedLinkError) }
    }
    @Test fun staticInitializerFailureIsConverted() {
        try { EngineBoundary.call("NewPipe") { throw ExceptionInInitializerError("init") }; fail("Expected wrapper") }
        catch (e: EngineLoadException) { assertTrue(e.cause is ExceptionInInitializerError) }
    }
    @Test(expected = OutOfMemoryError::class) fun outOfMemoryIsNotSwallowed() {
        EngineBoundary.call("test") { throw OutOfMemoryError("simulated; no allocation") }
    }
    @Test(expected = CancellationException::class) fun cancellationIsNotSwallowed() {
        EngineBoundary.call("test") { throw CancellationException() }
    }
    @Test(expected = IllegalArgumentException::class) fun normalExceptionsAreNotDisguised() {
        EngineBoundary.call("test") { throw IllegalArgumentException("bad input") }
    }
    @Test fun diagnosticUrlsAreRedacted() {
        val value = DiagnosticText.redact("403 at https://media.example/a?token=secret next")
        assertFalse(value.contains("secret")); assertTrue(value.contains("[URL removed]"))
    }
    @Test fun sensitiveHeadersAreRedacted() {
        val value = DiagnosticText.redact("Authorization: Bearer secret\nCookie=session123")
        assertFalse(value.contains("secret")); assertFalse(value.contains("session123"))
    }
}
