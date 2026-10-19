# StreamDock 1.0.2 — verification report

Date: 2026-10-09. This report distinguishes actual network/file tests from untested Android/native behavior.

## Build and offline automated tests

- Actual Kotlin compilation/dependency resolution and ARM64 `assembleDebug`: **successful**.
- Normal `testDebugUnitTest`: **36 passed**, 0 failures/errors, 1 opt-in live test skipped.
  - InputPolicy: 7.
  - EngineBoundary/redaction: 9.
  - PipeTrackSelector: 9.
  - DirectHttpDownloader over local mock HTTPS server: 11.
- Mock HTTPS coverage: sequential ranges, full HTTP 200 response, 403/429 without retry, range mismatch, changed total, missing range, HTML response, truncated response, cancellation before network and during reading.
- `lintDebug`: **0 errors, 28 warnings**; no error baseline suppression.
- APK signature verification: **passed**, v2 signature.
- Same debug signing certificate as previous supplied APK: `2d3216263b235a504cdb0895aef36c1b0f881dc68fc0f51e14693efa7ec069cc`.
- Package `org.streamdock.app`, version code **3**, version **1.0.2**, min SDK 24, target SDK 35.

APK: `StreamDock-arm64-debug.apk` (the current 1.0.2 file replaces the older file with the same name).

SHA-256:

```
581f65c29b6d3ed0bd4e6e0b6ff2c0dfe6bfaa2841c7ff15e225ca35e7b4a041
```

## Actual user-supplied URL test

URL ID: `zJNosjp9eiw`. The user explicitly asked to try downloading it through NewPipe.

The opt-in `NewPipeLiveProbeTest` ran on the workspace JVM, not Android:

1. NewPipeExtractor v0.26.5 resolved metadata and **20 stream entries**.
2. Progressive MP4 request returned **HTTP 206**, `Content-Range: bytes 0-65535/62662987`.
3. `DirectHttpDownloader` — the same class used by the application — downloaded the **entire 62,662,987-byte file** via sequential range requests. Actual output length matched the server-declared total.
4. Host FFmpeg identified **640×360 H.264 video + HE-AAC stereo audio**, duration **22:50.16**.
5. A **5-second** host decode of video and audio completed with exit code **0**. It emitted a nonfatal `Late SEI is not implemented` warning. This is not a full-duration decode test or a test of the Android-bundled FFmpeg.

The downloaded video is NOT included in the deliverables. It was used only for the requested verification.

## Evidence files

- `build-log.txt`.
- `tests-1.0.2/TEST-*.xml` — normal suite; live test intentionally skipped without an explicit URL.
- `newpipe-live-prefix.xml` — separate live prefix probe result.
- `newpipe-live-full-download.xml` — separate opt-in full progressive download result.
- `live-file-decode-check.txt` — host FFmpeg inspection and 5-second decode output.
- `lint-results.txt`.
- `apk-signature.txt`.

## Not verified / remaining risks

- **No Android device or emulator end-to-end execution** in this workspace. UI, WorkManager, notification, SAF export and phone/IP behavior remain to be verified on-device.
- Adaptive high-resolution video+audio merging, MP3/M4A processing and native FFmpeg initialization without Python have not been executed on Android. The code compiles; the tested complete transfer was a muxed 360p file, needing no merge.
- The entire 22-minute file has not been decoded; only complete byte transfer and a 5-second decode were checked.
- One URL/network result does not guarantee all videos/sites/regions will work. HTTP 403/429 may still occur on the user's network.
- NewPipe direct transfer supports progressive HTTPS files only, not HLS/DASH/OTF manifests or live recording. Separate progressive audio/video tracks are supported by the code path.
- No latest-version claim is made for the bundled yt-dlp; NewPipe's download branch does not execute it. Unsupported-service yt-dlp transfers still depend on that engine's actual installed version.
- 16 KB-page devices, other ABIs, background interruption, storage failure and release/store/license requirements still require appropriate validation.

## Routing design

Only a NewPipe **unsupported service lookup** chooses yt-dlp. A supported service's extraction/HTTP/download error does not silently retry via yt-dlp. 403/429 terminate with an explicit error; there is no proxy rotation, token generation or authentication bypass.

Upstream reference reviewed: https://github.com/yt-dlp/yt-dlp/issues/12482 describes WEB-client adaptive-format URLs being removed in favor of SABR. It does not establish that every 403/429 is the same bug or that NewPipe always succeeds.
