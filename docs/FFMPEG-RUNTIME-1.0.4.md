# 1.0.4 — FFmpeg shared-runtime initialization fix

## What the phone report establishes

Symphony Z25, Android 9/API 28, ARM64, app 1.0.3:

- NewPipe analysis succeeded.
- Video and audio transfer stages completed far enough to reach merge.
- The child FFmpeg executable failed at Android dynamic linking, before merging:

```text
CANNOT LINK EXECUTABLE .../libffmpeg.so:
library "libc++_shared.so" not found
```

The reported Android URLDecoder compatibility issue is no longer the failure shown in this report. This is not a YouTube HTTP error.

## Root cause confirmed by inspecting the supplied APK

The pinned wrapper distributes dependencies in two different archives:

```text
lib/arm64-v8a/libffmpeg.so             executable
lib/arm64-v8a/libffmpeg.zip.so         FFmpeg libraries
lib/arm64-v8a/libpython.zip.so         Python AND shared support libraries
    usr/lib/libc++_shared.so
    usr/lib/libcrypto.so.3
    usr/lib/libandroid-support.so
    ...
```

The previous `DockApp.initFfmpeg()` initialized only the FFmpeg archive. The child process search path named the Python library directory, but naming a directory does not extract its contents. When no previous yt-dlp initialization had extracted that archive, the shared C++ runtime was missing on disk.

This was an application initialization error. The original APK already contained the correct matching native runtime; downloading an arbitrary `libc++_shared.so`, adding a random NDK runtime, installing Termux, or changing the YouTube extractor is not the appropriate fix.

## Exact changes

1. `DockApp.initFfmpeg()` now prepares the bundled shared runtime through `YoutubeDL.init()` **before** `FFmpeg.init()`, then verifies key native files.
2. Calling that initializer extracts bundled files and sets up the wrapper. It does **not** execute yt-dlp, launch Python for extraction, or request a YouTube URL. NewPipe remains the downloader for its supported services.
3. `NativeRuntimePaths` centralizes checks and the child environment:
   - `python/usr/lib`
   - `ffmpeg/usr/lib`
   - app `nativeLibraryDir`
4. The executable, `libc++_shared.so`, `libavcodec.so.61`, and `libavformat.so.61` must exist and have an ELF header.
5. `NativeMediaProcessor.prepare()` actually runs bundled **FFmpeg `-version`** on the phone using that environment.
6. NewPipe plans requiring merge/audio processing call prepare **before media-byte transfer**. Local video conversion calls prepare before copying the input. Fresh NewPipe metadata extraction still precedes planning, because the app needs to know whether merging is necessary.
7. A failed preflight stops the transfer early and records the actual native-loader output. A muxed video download needing no FFmpeg skips this initialization.
8. No native binaries, codecs, NewPipe stream selection, or media filters were replaced. The Android 9 URL-codec patch remains in place.

## Verification performed here

- 9 new JVM tests cover initializer ordering, failure propagation, the old missing-C++ file layout, correct path ordering, and invalid/incomplete runtime files. The ELF fixtures in these tests are intentionally minimal: these are logic/file-check tests, not native execution.
- All 56 normal automated tests passed (45 app + 11 extractor). The opt-in live URL test was skipped in the normal suite.
- ARM64 APK built and signature verified; same certificate as prior versions.
- Android URL-codec regression check still passes against the APK DEX.
- `tools/verify_ffmpeg_runtime.py` traverses packaged FFmpeg `DT_NEEDED` dependencies and checks ELF architecture, resolving relative archive aliases.
  - Both archive search roots: **79 ELF files inspected**, non-system dependencies available.
  - Omitting the shared/Python root: expected failure, missing `libc++_shared.so`, `libcrypto.so.3`, `libexpat.so.1`, `libandroid-support.so` and `libandroid-posix-semaphore.so`.
- The same APK archives were present before this fix. The fix is extracting and searching them correctly, NOT newly including an absent binary.

## Limits

No physical Android device/emulator was executed in this workspace. Static ELF dependency availability does not prove dynamic symbol/API compatibility or successful merging. The new `-version` preflight runs on the user's phone when the operation is attempted; no successful on-phone preflight or merge is claimed yet.

The 79-file check covers ARM64 in the supplied APK. CI runs the same check for every ABI included in a universal build. System-library availability is modeled with a public Android-library allowlist; system symbols and Android linker namespaces are not emulated.

Codec-specific processing can still fail after a successful executable preflight. Downloads can still fail from network/permission/storage issues. The preflight is intended to catch loader failures before transferring large media files, not to guarantee every operation.

## Try the update

1. Install the updated ARM64 APK over the current app; verify **v1.0.4** in the UI.
2. Analyse the same URL, choose the previously failing quality/format and start the transfer.
3. The first merge-required transfer may spend time extracting the shared runtime.
4. Diagnostics should show `FFmpeg preflight passed` before `NewPipe: video download…` / `audio download…`.
5. If it fails, copy the latest diagnostic report. Native startup failures should now occur before those media transfers.

Uninstalling or installing external native libraries is not required. This build uses the same signing certificate. First-time runtime extraction needs additional app-private storage.
