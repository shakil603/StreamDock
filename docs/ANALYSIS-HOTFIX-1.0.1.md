# 1.0.1 — Analysis crash mitigation and diagnostics

## Reported symptom

Pressing **লিংক বিশ্লেষণ করুন** with a YouTube URL exits the app. The user clarified that the black video/background was a separate video-player problem and is already resolved. No theme, video filters, codecs or download format selection were changed in this patch.

## What is known vs unknown

The original analysis handler caught `Exception` only. Java class-loading failures such as `NoClassDefFoundError`, `ExceptionInInitializerError` and `UnsatisfiedLinkError` derive from `LinkageError`, not `Exception`, so that handler could not contain them.

This is a confirmed code-level error-handling gap, **not a confirmed diagnosis of the user's crash**. There is no device model, Android version, failing URL or crash trace yet. Neither this issue nor this patch has been reproduced/validated on the user's phone. A successful compile is not evidence of working YouTube extraction/download.

## Changes

- Wrap recoverable engine linkage failures into `EngineLoadException`; do not swallow `OutOfMemoryError`, `StackOverflowError`, `ThreadDeath` or cancellation.
- Lazy NewPipe initialization within the guarded background analysis path, not application startup.
- Primary engine runtime/parser/loading failure can trigger yt-dlp fallback. CAPTCHA/content-unavailable signals remain failures, not attempts to evade restrictions.
- Run fallback outside the primary `try/catch` to prevent accidentally running fallback twice.
- Add **বিকল্প: শুধু yt-dlp দিয়ে বিশ্লেষণ** for a user-selected diagnostic alternative that skips NewPipe entirely.
- Add local, bounded diagnostic events with engine phase, Java exception stack traces and Android/device/ABI information.
- Add **সমস্যার রিপোর্ট দেখুন / কপি করুন**: preview the report before copying it to share here.
- Save uncaught Java exceptions best-effort, then delegate to the normal Android crash handler. This does not resume an unsafe/crashed process or guarantee capture of native crashes, ANRs or OS process kills.
- Redact URLs and common credential header lines; do not log raw metadata JSON, stream headers or user-entered URLs. No telemetry/upload service is used.
- Version code 2 / version name 1.0.1; dependencies remain pinned as before.

## Try on the affected phone

1. Install the new ARM64 APK as an update to the previously supplied APK. It uses the same debug signing certificate; verify the version at the bottom is **v1.0.1**. Do not uninstall first unless Android reports a signing conflict, since uninstall deletes app-local state.
2. Paste the same YouTube URL and press **লিংক বিশ্লেষণ করুন**.
3. If it displays an error, optionally test **বিকল্প: শুধু yt-dlp দিয়ে বিশ্লেষণ** once.
4. If extraction still fails, scroll down to **সমস্যার রিপোর্ট দেখুন / কপি করুন**, review the report and copy/paste it into the chat. If the app exits, reopen it first; the last report remains on-device when it could be saved.
5. If there is no useful report, provide phone model, Android version, YouTube URL and whether the last visible status said NewPipe or yt-dlp. A native crash/OS kill may require Android logcat rather than the Java diagnostic log.

## Privacy

Reports include device model, Android version, ABI, timestamps and error text. No report is uploaded automatically. Review before sharing; redaction is a best-effort safeguard, not a guarantee that every possible third-party error message is free of personal data.
