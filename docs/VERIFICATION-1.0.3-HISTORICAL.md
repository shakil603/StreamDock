# StreamDock 1.0.3 — Android 9 URL-codec verification

Date: 2026-10-10.

## Confirmed device-reported root cause

Symphony Z25 / Android 9 / API 28 reported `NoSuchMethodError` for:

```
java.net.URLDecoder.decode(String, java.nio.charset.Charset)
```

NewPipe's Utils used this unavailable overload. This failure occurred before the network request, explaining both analysis fallback and direct-download failure. It is distinct from YouTube HTTP errors.

## Modification

The published NewPipeExtractor v0.26.5 JAR dependency was removed and replaced with an editable local `newpipe-extractor` Java-library module from its pinned source archive. Of 359 imported upstream Java files, only **Utils.java** differs from the archive. Its encode and decode UTF-8 helpers now invoke the supported **(String, String)** overloads. `YoutubeStreamExtractor.java` is unchanged and available for editing in the module.

Source archive SHA-256: `693c1e23cfaae77af3693af2441261ab89d55a89b11a35d36ecdec03dbb4e7c8`.

## Executed checks

- Local extractor: **11 regression tests passed** (10 behavioral/classpath/link-routing tests + 1 Android API 28 bytecode/API-surface test).
- App: **36 tests passed**; 1 opt-in live test skipped in the normal suite.
- Total normal suite: **47 passed**, 0 failures/errors, 1 skipped.
- Actual compiled Utils bytecode was checked against URL codec signatures in **Android SDK Platform 28's android.jar**. Both invocations match public static methods present on that platform.
- Final APK method tables scanned: **no URLDecoder/URLEncoder Charset-overload references remain**; both String-overload references are present.
- Previous supplied **1.0.2 APK fails the same binary check**, as expected. This is a real before/after regression check, not only a source-text match.
- Current local module's live JVM probe on `zJNosjp9eiw`: metadata resolved, **20 stream entries**, MP4 prefix **HTTP 206**, server-declared total 62,662,987 bytes.
- `lintDebug`: **0 errors / 28 warnings**.
- `assembleDebug -PtargetAbi=arm64-v8a`: successful.
- APK v2 signature: verified. Certificate SHA-256 matches previous builds: `2d3216263b235a504cdb0895aef36c1b0f881dc68fc0f51e14693efa7ec069cc`.
- Package `org.streamdock.app`, version **1.0.3**, version code **4**, min SDK **24**, target SDK **35**.

APK file: `StreamDock-arm64-debug.apk` (replaced with the 1.0.3 binary).

APK SHA-256:

```
545af4c97b75a12c15cc467af94238960f3492fc7b1cdf296830f44843ae01bd
```

## Evidence

- `tests-1.0.3/extractor/TEST-*.xml`.
- `tests-1.0.3/app/TEST-*.xml`.
- `apk-url-codec-check.txt` — current APK passes.
- `previous-apk-codec-negative-check.txt` — old APK fails with the exact unsupported descriptors.
- `newpipe-patched-live-prefix-1.0.3.xml` — current patched source live prefix probe.
- `build-log.txt`, `lint-results.txt`, `apk-signature.txt`.
- `ANDROID-9-COMPATIBILITY-1.0.3.md` — detailed change/install notes.
- `newpipe-extractor/android-url-codec.patch` and `UPSTREAM.md` — source provenance and exact patch (relative to project root).

## Limits — do not confuse these checks with phone execution

No physical Android device or emulator was run in this workspace. API 28 SDK signature checks and final APK inspection directly validate removal of the reported missing URL-codec methods, but do not guarantee the entire engine/UI/native/download workflow on the Symphony phone. Other runtime API issues, IP restrictions, HTTP 403/429, storage export, lifecycle behavior and FFmpeg merging remain possible.

The recorded complete 360p download and 5-second host decode in older evidence were performed using 1.0.2 on a desktop JVM. They are historical evidence, NOT a fresh 1.0.3 Android test. The 1.0.3 live probe fetched a stream prefix only.

GitHub Actions is updated to install SDK Platform 28, run the local extractor regression tests, then check the assembled APK before upload. The workflow has not been executed in the user's repository. Only the ARM64 variant was built locally.
