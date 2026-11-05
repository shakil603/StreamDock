package org.streamdock.compat;

import org.junit.Test;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.Collections;

import static org.junit.Assert.*;

public class UrlCodecCompatibilityTest {
    @Test public void decodeEscapesAndFormPlus() {
        assertEquals("a b+c/✓", Utils.decodeUrlUtf8("a+b%2Bc%2F%E2%9C%93"));
    }
    @Test public void encodeEscapesAndFormPlus() {
        assertEquals("a+b%2Bc%2F%E2%9C%93", Utils.encodeUrlUtf8("a b+c/✓"));
    }
    @Test public void unicodeRoundTrip() {
        final String value = "বাংলা গান + / ? & = 😀";
        assertEquals(value, Utils.decodeUrlUtf8(Utils.encodeUrlUtf8(value)));
    }
    @Test public void emptyStringRoundTrip() {
        assertEquals("", Utils.encodeUrlUtf8(""));
        assertEquals("", Utils.decodeUrlUtf8(""));
    }
    @Test(expected = IllegalArgumentException.class) public void invalidEscapeStillFails() {
        Utils.decodeUrlUtf8("broken%G1");
    }
    @Test public void normalYoutubeIdParsesWithoutNetwork() throws Exception {
        assertEquals("zJNosjp9eiw", YoutubeStreamLinkHandlerFactory.getInstance()
            .getId("https://www.youtube.com/watch?v=zJNosjp9eiw"));
    }
    @Test public void percentEncodedYoutubeIdParsesWithoutNetwork() throws Exception {
        assertEquals("zJNosjp9eiw", YoutubeStreamLinkHandlerFactory.getInstance()
            .getId("https://www.youtube.com/watch?v=%7AJNosjp9eiw"));
    }
    @Test public void reportedServiceLookupPathWorksWithoutNetwork() throws Exception {
        assertEquals("YouTube", NewPipe.getServiceByUrl("https://www.youtube.com/watch?v=zJNosjp9eiw")
            .getServiceInfo().getName());
    }
    @Test public void shortLinkRoutesWithoutNetwork() throws Exception {
        assertEquals("YouTube", NewPipe.getServiceByUrl("https://youtu.be/zJNosjp9eiw")
            .getServiceInfo().getName());
    }
    @Test public void onlyOneUtilsClassIsOnClasspath() throws Exception {
        assertEquals(1, Collections.list(Utils.class.getClassLoader()
            .getResources("org/schabi/newpipe/extractor/utils/Utils.class")).size());
    }
}
