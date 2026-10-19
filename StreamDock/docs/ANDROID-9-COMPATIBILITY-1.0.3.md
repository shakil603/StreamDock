# 1.0.3 — confirmed Android 9 URL-codec compatibility fix

## Root cause from the device report

Device: Symphony Z25, Android 9 / API 28, ARM64.

The report showed:

```text
java.lang.NoSuchMethodError:
No static method decode(String, Charset) in java.net.URLDecoder
  at org.schabi.newpipe.extractor.utils.Utils.decodeUrlUtf8(Utils.java:46)
```

This happened during NewPipe's `getServiceByUrl()` lookup, before network extraction or transfer. The analysis path concealed it by falling back to yt-dlp, whereas NewPipe-first download correctly exposed it. It was not an HTTP 403/429 error.

NewPipe v0.26.5's source called Java's newer `(String, Charset)` overload. That method is absent in the Android API 28 SDK and on the reported phone. The old APK's DEX also contained this exact method reference. Java 17 desktop tests could not detect the missing Android method because the desktop JDK provides it. Merely choosing Java 11/17 source compatibility or turning on core-library desugaring was not sufficient for this overload in the previous build.

## Exact fix

The old binary dependency:

```groovy
implementation 'com.github.TeamNewPipe:NewPipeExtractor:v0.26.5'
```

has been **removed**, replaced with:

```groovy
implementation project(':newpipe-extractor')
```

The local module includes 359 upstream Java source files, generated protobuf/timeago Java sources and matching dependencies. Only upstream `Utils.java` changed:

```java
public static String decodeUrlUtf8(final String url) {
    try {
        return URLDecoder.decode(url, StandardCharsets.UTF_8.name());
    } catch (final UnsupportedEncodingException impossible) {
        throw new IllegalStateException("UTF-8 is unavailable", impossible);
    }
}
```

`encodeUrlUtf8()` received the corresponding `URLEncoder.encode(String, String)` fix as well. Both preserve UTF-8 and normal form-encoding semantics. UTF-8 support is mandatory; the checked exception is caught without suppressing malformed escape errors.

Patch: `newpipe-extractor/android-url-codec.patch`.

## About the pasted YoutubeStreamExtractor.java

It is one component of NewPipeExtractor, not a separate complete engine. Its dependent classes are needed too. Replacing only that file would not modify the failing `Utils.decodeUrlUtf8()` call.

The editable module contains:

```text
newpipe-extractor/src/main/java/org/schabi/newpipe/extractor/
  utils/Utils.java
  services/youtube/extractors/YoutubeStreamExtractor.java
```

`YoutubeStreamExtractor.java` and the other upstream Java files remain unchanged from the pinned v0.26.5 source archive. Do not also add the original JAR or paste a duplicate extractor class under app/src: duplicate class definitions would break packaging.

The existing yt-dlp branch for unsupported services is retained; it is not used as a hidden replacement for failed NewPipe transfers.

## Verification specific to the regression

1. **10 source-module behavior tests:** Unicode/Bengali and emoji round trips, spaces/literal plus, empty input, malformed escapes, normal and percent-encoded YouTube IDs, the reported service-lookup path, short URL routing and one Utils class on the classpath.
2. **1 actual Android SDK 28 API-surface check:** ASM reads compiled `Utils.class` and compares both URL codec method references with the SDK's real `android.jar` method signatures. It explicitly verifies the previous Charset overload is absent from that API surface. This does not run a JVM shim pretending to be Android.
3. **Final APK DEX check:** examines all packaged DEX method tables. Old 1.0.2 APK fails this check as expected; 1.0.3 passes, with no `URLDecoder`/`URLEncoder` Charset overload reference remaining.
4. Normal app tests: 36 passed; opt-in live test skipped in the normal suite.
5. Separate patched-source JVM live probe on `zJNosjp9eiw`: metadata resolved, 20 stream entries, MP4 prefix returned HTTP 206.
6. ARM64 APK build and v2 signature verified. Same signing certificate as the previous supplied APK; version code 4, version 1.0.3.

## What remains untested

No emulator or physical Android device execution occurred here. The API 28 checks prove that this particular missing URL-codec overload has been removed and replaced with a supported method; they do not prove all engine/native/UI/download functionality on the phone. Separate-track FFmpeg merging, storage export and unrelated API compatibility still need device testing.

The complete 62,662,987-byte download/5-second host decode recorded in older documentation was performed with 1.0.2 on a desktop JVM, not with 1.0.3 on Android. The 1.0.3 live network check was a prefix probe only.

## Install and test

1. Install the new ARM64 APK over the current app; confirm **v1.0.3** at the bottom.
2. Analyse the same YouTube URL using the normal analysis button, not the manual yt-dlp button.
3. Check that metadata reports **NewPipe**.
4. First try MP4 / Up to 480p, then test other formats/qualities as needed.
5. If another error appears, send the latest diagnostic report. The engine error summary now also includes the missing method message rather than just `NoSuchMethodError`.

## Build requirements

Install Android SDK platforms **35 and 28**. API 35 builds the app; API 28 is used by the regression test only and does not change target/min SDK.

```bash
./gradlew newpipe-extractor:test testDebugUnitTest lintDebug assembleDebug
python3 tools/verify_url_codec_dex.py app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions now runs both checks before uploading the APK. Sources, GPL license, original archive SHA-256 and the exact patch are provided under `newpipe-extractor/`.
