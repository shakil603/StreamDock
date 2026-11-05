package org.streamdock.app
import org.junit.Assert.*
import org.junit.Test
class InputPolicyTest {
    @Test fun acceptsHttps() { assertEquals("https://youtu.be/test", InputPolicy.validate(" https://youtu.be/test ")) }
    @Test(expected = IllegalArgumentException::class) fun rejectsLocalFiles() { InputPolicy.validate("file:///etc/passwd") }
    @Test(expected = IllegalArgumentException::class) fun rejectsCredentials() { InputPolicy.validate("https://user:pass@example.org/video") }
    @Test(expected = IllegalArgumentException::class) fun rejectsPlainHttp() { InputPolicy.validate("http://example.org/video") }
    @Test fun mp4KeepsContainerAndCap() {
        assertEquals("bestvideo[ext=mp4][height<=720]+bestaudio[ext=m4a]/best[ext=mp4][height<=720]", InputPolicy.formatSelector(OutputKind.MP4, 720))
    }
    @Test fun audioHasNoHeightLimit() { assertEquals("bestaudio/best", InputPolicy.formatSelector(OutputKind.MP3, 480)) }
    @Test fun bestHasNoHeightLimit() { assertFalse(InputPolicy.formatSelector(OutputKind.WEBM, 0).contains("height")) }
}
