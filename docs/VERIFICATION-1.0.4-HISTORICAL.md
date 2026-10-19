# StreamDock 1.0.4 — FFmpeg shared-runtime fix verification

Date: 2026-10-10.

## User's on-device evidence

The 1.0.3 report from Symphony Z25 / Android 9 / ARM64 shows successful NewPipe analysis and video/audio download stages, followed by a native-loader error when merging: `libc++_shared.so not found`. These are the user's observed results, not a device run performed in this workspace.

## Root cause and correction

APK inspection located `libc++_shared.so` in the bundled **Python/shared-runtime archive**, not the FFmpeg archive. The old direct-FFmpeg path initialized only FFmpeg, leaving the shared-runtime directory unpopulated on a fresh app state. An LD_LIBRARY_PATH pointing at a nonexistent/unextracted directory cannot resolve it.

1.0.4 initializes the shared archive via `YoutubeDL.init()` before `FFmpeg.init()`. That is initialization/extraction only; it does not run the yt-dlp extractor or change the NewPipe download route. The child process uses a centralized Python-libraries → FFmpeg-libraries → native-bin search path. An on-device `ffmpeg -version` preflight must pass before media transfer for merge/conversion jobs.

The native archives were already bundled correctly. No replacement native binaries, arbitrary C++ runtimes or NDK libraries were downloaded into the app. This is an initialization-order and readiness-check fix.

## Executed in this workspace

- App tests: **45 passed**, 0 failures/errors; 1 opt-in live test skipped.
- Local extractor tests: **11 passed** (including Android API 28 URL-codec signature check).
- Combined normal suite: **56 passed**, 1 intentionally skipped.
- 9 new native-bootstrap/path tests exercise order, failure propagation, missing C++ file detection, invalid files and child search-path order. They use small fixture files; they do NOT execute Android FFmpeg.
- `lintDebug`: **0 errors, 28 warnings**.
- ARM64 `assembleDebug`: **successful**.
- Final APK URL-codec method-reference check: **passed**; the Android 9 fix remains present.
- Native static audit: **79 ELF files inspected** for ARM64. All non-system `DT_NEEDED` dependencies resolved across the shared and FFmpeg archives, with matching ELF architecture.
- Negative static audit without the shared runtime: expected failure, missing `libc++_shared.so`, `libcrypto.so.3`, `libexpat.so.1`, `libandroid-support.so`, `libandroid-posix-semaphore.so`.
- APK v2 signature: **verified**; same certificate as the previous supplied app: `2d3216263b235a504cdb0895aef36c1b0f881dc68fc0f51e14693efa7ec069cc`.
- App package `org.streamdock.app`, version **1.0.4**, version code **5**, min SDK 24, target SDK 35.

APK: `StreamDock-arm64-debug.apk` (current file replaced with 1.0.4).

SHA-256:

```
4cdceb6e4a8026a1c9758932e2a5e8b3bafcd1c9e6275c9c60c2196da7ba4ff4
```

## Evidence

- `tests-1.0.4/app/TEST-*.xml`.
- `tests-1.0.4/extractor/TEST-*.xml`.
- `runtime-tests-build-log.txt`, `build-log.txt`, `lint-results.txt`.
- `ffmpeg-runtime-audit.txt` — both archive roots resolve dependencies.
- `ffmpeg-without-shared-runtime-negative-check.txt` — expected failure modeling the old initialization gap.
- `apk-url-codec-check.txt`, `apk-signature.txt`.
- `FFMPEG-RUNTIME-1.0.4.md` — exact changes, limitations and test instructions.

## Limits

No physical Android device or emulator was executed here. Native symbol resolution, actual Android `ffmpeg -version`, high-resolution merging, audio conversion and SAF export have not been verified on-device in this workspace. Static dependency availability/ABI checks do not prove successful runtime linking or codec operations. The new native preflight will run on the user's phone during the next merge/conversion attempt.

There was no new live YouTube request in this build-validation pass: extraction and download logic are unchanged, and the user's report already establishes they worked in 1.0.3. Historical live JVM download/prefix records remain documented as older-version evidence, not fresh 1.0.4 device tests.

Only ARM64 was built locally. CI now includes the archive dependency audit for every ABI in the assembled universal APK; that workflow has not been run in the user's repository. The static system-library allowlist is not a full Android API/symbol compatibility checker.
