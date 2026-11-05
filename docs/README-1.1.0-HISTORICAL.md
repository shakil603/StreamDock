> HISTORICAL: the native TeraBox adapter was removed in 1.2.0 by user request. See the current README and BROWSER-1.2.0.md.

# StreamDock 1.1.0 — NewPipe-first + experimental TeraBox

Kotlin Android app: বাংলা UI, NewPipeExtractor metadata/stream extraction, Media3 preview, **NewPipe direct download**, optional FFmpeg merging/conversion, এবং NewPipe-অসমর্থিত service-এর জন্য yt-dlp।

## 1.1.0: TeraBox (experimental; live validation pending)

আপনার ফোনে 1.0.4 কাজ করছে বলে নিশ্চিত করেছেন। তার NewPipe, native runtime, FFmpeg processor ও Media3 playback code অক্ষত রেখে আলাদা TeraBox পথ যোগ হয়েছে।

- **Public shares:** link/code → paginated folder/file browser → fresh original HTTPS link, server দিলে।
- **My files:** নিজের `terabox.com` Netscape cookies.txt import → authenticated account folder browser → authorized official download flow।
- **Original**, **MP3**, **M4A** output; conversion কেবল local FFmpeg দিয়ে।
- Session ও share-password-bearing task metadata Android Keystore AES-GCM দিয়ে encrypted, `noBackupFilesDir`-এ। WorkManager-এ secret নয়, random task ID যায়। Cookies redirect-এর host/domain/path অনুযায়ী নির্বাচিত হয়; unrelated CDN-এ যায় না।
- কোনো daily/download-count/media-size quota বা speed cap যোগ করা হয়নি। আগের মতো এক সময়ে একটি transfer; storage, Android background limits ও server limits প্রযোজ্য। Resume/batch queue বা TeraBox video-quality transcoding নেই।
- **বাস্তব TeraBox account/share download এখনও test হয়নি।** HTTPS fixture/security tests পাস করা real API compatibility-এর প্রমাণ নয়। Public link-এও server login চাইতে পারে; dlink না দিলে স্পষ্ট error দেখাবে। CAPTCHA, premium, region বা rate limit bypass করবে না।
- TeraBox account flow OpenList-এর **AGPL-3.0** source adaptation; নতুন combined build GPL/AGPL section 13-এর অধীন। পুরোনো components তাদের license-এই আছে।

[TeraBox ব্যবহার, নিরাপত্তা ও acceptance checklist](docs/TERABOX-1.1.0.md) · [বর্তমান পরীক্ষার evidence](docs/VERIFICATION.md)

## ঐতিহাসিক 1.0.4: FFmpeg `libc++_shared.so` runtime fix

ফোনের নতুন রিপোর্টে NewPipe analysis এবং video/audio download সফল হয়েছে, কিন্তু merge-এর সময় FFmpeg প্রয়োজনীয় C++ runtime পায়নি। APK পরীক্ষা করে দেখা গেছে library-টি **bundled Python/shared-runtime archive-এ ছিল**, যা আগের direct-FFmpeg initialization extract করছিল না।

এখন shared/Python archive → FFmpeg archive → file validation → **ফোনে `ffmpeg -version` preflight** এই ক্রমে চলবে। Merge/audio conversion লাগলে media download-এর আগেই এই পরীক্ষা হয়। শুধু bundled runtime initialize হয়—YouTube extraction/download yt-dlp-তে ফেরত যায় না।

- 1.0.4-এর normal tests: **৫৬টি passed** (৪৫ app + ১১ extractor), ১টি opt-in live test skipped।
- ARM64 APK: ৭৯টি native ELF file-এর non-system dependency availability/ABI audit passed। Shared runtime বাদ দিলে check প্রত্যাশামতো fail করে।
- Android 9 URL-codec correction ও তার final-APK check অপরিবর্তিত আছে।
- **এই workspace-এ Android native FFmpeg চালানো হয়নি।** Static native audit বা JVM tests actual phone merge-এর বিকল্প নয়; নতুন preflight ফোনে চলবে।

[সুনির্দিষ্ট fix ও পরীক্ষার ধাপ](docs/FFMPEG-RUNTIME-1.0.4.md)। এই fix 1.1.0-তেও অপরিবর্তিত আছে। আলাদা FFmpeg, Termux বা internet থেকে `.so` file install করতে হবে না।

## আগের 1.0.3: Symphony Z25 / Android 9 compatibility fix

রিপোর্টে পাওয়া নির্দিষ্ট কারণ: Android 9-এর `java.net.URLDecoder`-এ `decode(String, Charset)` নেই। পুরোনো published NewPipe binary dependency সরিয়ে **editable `newpipe-extractor` source module** যুক্ত হয়েছে। তার `Utils.java`-তে decode এবং encode দুটোই পুরোনো Android-সমর্থিত `(String, String)` overload ব্যবহার করে। শুধু `YoutubeStreamExtractor.java` কপি করলে এই error ঠিক হতো না।

- Local extractor regression tests: **১১টি passed**; এর মধ্যে compiled bytecode-এর calls **আসল Android API 28 SDK stubs**-এর সঙ্গে যাচাই করা হয়েছে।
- App tests: **৩৬টি passed**, default live test skipped। মোট **৪৭টি automated test passed**।
- পুরোনো 1.0.2 APK একই DEX check-এ ব্যর্থ; নতুন 1.0.3 APK-তে unsupported URL-codec overload reference **নেই**।
- নতুন patched source দিয়ে আলাদা JVM live probe: metadata + MP4 prefix **HTTP 206**।
- **বাস্তব Android ফোন/emulator-এ নতুন build চালানো হয়নি**। SDK/DEX checks নির্দিষ্ট missing-method error-এর correction যাচাই করে, পুরো device workflow নয়।

[সুনির্দিষ্ট পরিবর্তন ও পরীক্ষা](docs/ANDROID-9-COMPATIBILITY-1.0.3.md)। এই Android 9 correction বর্তমান 1.1.0-তেও আছে। Normal analysis-এর পরে **NewPipe** engine দেখুন।

## 1.0.2 থেকে বজায় থাকা NewPipe download পথ

আগে NewPipe দিয়ে analysis সফল হলেও **সব remote download yt-dlp দিয়ে চলত**। 1.0.2-তে:

- NewPipe-supported URL → download-এর সময় fresh `StreamInfo` → progressive HTTPS stream → OkHttp direct transfer।
- Video-only + audio আলাদা হলে দুটি file download করে **FFmpeg stream-copy merge**।
- MP3/M4A চাইলে NewPipe-এর audio stream download করে প্রয়োজনমতো FFmpeg audio conversion/remux।
- NewPipe-এর unsupported service lookup হলেই শুধু yt-dlp transfer। **NewPipe extraction/download-এ 403/429 হলে yt-dlp-তে চুপচাপ fallback হয় না।**
- Progress ও success/error message-এ **NewPipe / yt-dlp / FFmpeg** এবং current stage দেখায়।
- NewPipe muxed MP4 download-এ Python/yt-dlp initialization লাগে না। Separate-track merge বা audio conversion-এর আগে FFmpeg এবং bundled shared/Python runtime archive দুটোই প্রস্তুত করা হয়; yt-dlp দিয়ে URL extraction হয় না।
- yt-dlp update button এখন executable-এর `--version` output-ও দেখায়; update হয়েছে বলে শুধু অনুমান করে না।
- ভিডিওর background/theme পরিবর্তন করা হয়নি।

## আগের 1.0.2-তে সম্পূর্ণ download-এর ঐতিহাসিক পরীক্ষা

২০২৬-১০-০৯-এ workspace-এর JVM/network environment-এ ব্যবহারকারীর দেওয়া `zJNosjp9eiw` ভিডিও:

1. NewPipe metadata extraction সফল; ২০টি stream entry।
2. Progressive MP4 prefix request: **HTTP 206**।
3. নতুন `DirectHttpDownloader` দিয়ে **সম্পূর্ণ 62,662,987 bytes** file download এবং expected total-এর সঙ্গে length যাচাই।
4. Host FFmpeg inspection-এ **640×360 H.264 video + AAC audio**, duration প্রায় 22:50।
5. প্রথম **৫ সেকেন্ড** audio/video decode: exit code 0। Host FFmpeg একটি nonfatal `Late SEI` warning দিয়েছে; পুরো 22 মিনিট decode বা Android playback পরীক্ষা করা হয়নি।

এটি আপনার ফোন/IP বা অন্য video-তে success guarantee নয়। **Android UI → WorkManager → native FFmpeg → SAF end-to-end device test হয়নি।** High-resolution separate-track merge-ও ফোনে এখনও যাচাই হয়নি।

### Build / automated verification

- ARM64 debug APK build: সফল।
- Current normal suite: app-এর **৭৮টি** এবং local extractor-এর **১১টি**, মোট **৮৯টি passed**; ১টি opt-in live test skipped। এর মধ্যে ৩৩টি নতুন TeraBox test।
- Historical patched-source live prefix probe: 1.0.3-তে passed। Historical full-transfer test 1.0.2-এর। 1.0.4-এ live website test পুনরায় চালানো হয়নি; পরিবর্তন native runtime initialization-এ।
- Android lint: **০ error, ৪৩ warning**।
- APK signature: verified; আগের supplied APK-এর certificate-এর সঙ্গে মেলে।
- বিস্তারিত evidence: [`docs/VERIFICATION.md`](docs/VERIFICATION.md)।

## ফোনে ব্যবহার

1. নতুন `StreamDock-arm64-debug.apk` পুরোনো app-এর ওপর update হিসেবে install করুন। নিচে **v1.1.0** দেখাবে। TeraBox-এর জন্য **TeraBox · Public shares / My files** খুলুন; মূল input-এ পরিচিত TeraBox link দিলেও ওই screen খুলবে।
2. YouTube URL → **লিংক বিশ্লেষণ করুন**।
3. প্রথম পরীক্ষায় **Video · MP4** ও **Up to 480p** বেছে নিতে পারেন। পরীক্ষিত URL-এ এর অধীনে 360p muxed stream আছে, তাই merge প্রয়োজন নেই। এটি সাধারণভাবে সব URL-এ 360p guarantee নয়।
4. **ডাউনলোড ও সেভ করুন** → destination নির্বাচন। Transfer অংশে **NewPipe** লেখা দেখুন।
5. সফল হলে অন্য quality/audio format পরীক্ষা করুন। Higher resolution-এ separate video/audio download + merge লাগতে পারে।
6. ব্যর্থ হলে **সমস্যার রিপোর্ট দেখুন / কপি করুন** দিয়ে report নিন। HTTP 429 দেখলে বারবার retry নয়—বিরতি দিন। HTTP 403 দেখলে অনুমতি/stream restrictions-এর সম্ভাবনা থাকে; engine বদলালেই নিশ্চিত সমাধান হয় না।

> এটি ARM64 debug/test APK; production বা Play Store release নয়। সাধারণত Android 7+ ARM64-compatible ডিভাইস দরকার। Device/16 KB-page native compatibility আলাদাভাবে পরীক্ষা করুন।

## NewPipe / yt-dlp download architecture (unchanged)

```text
Remote URL
  → NewPipe service lookup
      ├─ Supported
      │    → fresh StreamInfo
      │    → progressive HTTPS stream selector
      │    → sequential validated range GETs
      │    → muxed file OR video+audio FFmpeg merge OR audio conversion
      │    → SAF export
      └─ Unsupported service
           → yt-dlp download / processing
           → SAF export
```

Analysis এখনও NewPipe-first, parser/loading failure-এ yt-dlp fallback এবং manual direct-yt-dlp analysis button রাখে। **Analysis button-এর engine আর transfer engine স্বাধীন**: শুধু yt-dlp দিয়ে analysis করলেও NewPipe-supported service-এর download NewPipe দিয়েই চেষ্টা হয়।

### Transfer integrity / cancellation

- 4 MiB পর্যন্ত sequential HTTP ranges; প্রথম request-এর range server উপেক্ষা করে HTTP 200 দিলে পুরো response stream করা হয়।
- 206 হলে Content-Range start/end/total, byte count ও continuation validate হয়। HTML/JSON/manifest response media হিসেবে save করা হয় না। Unexpected content encoding reject হয়।
- Missing/truncated/inconsistent range হলে partial staging file মুছে error দেয়।
- Explicit 403/429-তে direct downloader automatic retry বা engine switching করে না। Proxy/cookie/token workarounds নেই।
- Active media HTTP call এবং native FFmpeg process cancel করার ব্যবস্থা আছে। Metadata extraction-এর blocking HTTP operation timeout পর্যন্ত কিছু সময় চলতে পারে।
- Staging app-private storage-এ; সফল output তৈরি হওয়ার পরে SAF destination-এ copy। Destination copy চলাকালে failure/cancel হলে destination partial থাকতে পারে।

## সীমাবদ্ধতা

- NewPipe direct transfer বর্তমানে **progressive HTTPS URL resources** সমর্থন করে। HLS/DASH manifest, OTF, post-live/live recording, torrent এবং inline manifest এই পথে unsupported। Adaptive video/audio যদি আলাদা progressive files হয়, সেগুলো merge করা হয়।
- Selected container/resolution-এ complete video+audio পাওয়া না গেলে error; silent video বা renamed incompatible container তৈরি করা হয় না।
- MP4/WEBM video transcode হয় না; source codecs copy হয়। MP3/M4A-তে প্রয়োজনমতো 192 kbps audio encoding; matching source audio codec হলে remux।
- Audio language/track selection UI নেই; compatible high-bitrate track ব্যবহার হয়।
- Preview dropdown download-এর exact format ID নির্বাচন করে না। Download container ও height cap অনুযায়ী fresh stream বেছে নেয়।
- YouTube IP limits, permission restrictions, expired stream URLs এবং website changes এখনও failure ঘটাতে পারে। SABR-only content হলে NewPipe progressive stream নাও দিতে পারে।
- NewPipe-supported site-এর extraction/download failure এখন yt-dlp-তে fallback করে না; diagnostic error দেখায়। Unsupported services-এর yt-dlp branch-এ bundled version পুরোনো থাকতে পারে—update button-এর actual version output দেখুন। **এই release-এ bundled yt-dlp-কে latest বলে দাবি করা হচ্ছে না।**
- এক সময়ে একটি transfer; playlists/bulk queue নেই। NewPipe/yt-dlp-এর cookies/login UI নেই; TeraBox screen-এ শুধু নিজের authorized session import আছে। DRM/paywall bypass নেই।
- Rotation-এ preview metadata আবার analyse করতে হতে পারে; WorkManager transfer app lifecycle থেকে আলাদা। Force-stop/reboot/low storage-এ completion/resume guarantee নেই।
- Full media + intermediate files রাখার মতো storage লাগবে। Progress network bytes থেকে আনুমানিক; merge/conversion পুরো সময়ের percentage নয়।
- Debug signing, native ABI/16 KB-page checks, current store policy, privacy/license compliance, full localization ও production security review বাকি।

## Build

| Component | Version |
|---|---|
| JDK | 17 |
| Gradle wrapper | 8.11.1; distribution checksum pinned |
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.1.20 |
| min / compile / target SDK | 24 / 35 / 35 |
| NewPipeExtractor | local editable v0.26.5 + Android URL-codec compatibility patch |
| youtubedl-android library + ffmpeg | 0.18.1 |
| Media3 | 1.6.1 |

Android Studio-তে project open, JDK 17 ও SDK 35 নির্বাচন করুন। Regression test-এর জন্য SDK Platform **28**-ও install করুন।

```bash
chmod +x gradlew
./gradlew newpipe-extractor:test testDebugUnitTest lintDebug assembleDebug
python3 tools/verify_url_codec_dex.py app/build/outputs/apk/debug/app-debug.apk
python3 tools/verify_ffmpeg_runtime.py app/build/outputs/apk/debug/app-debug.apk
```

Default universal APK-তে ARM64, ARMv7, x86, x86_64 থাকে এবং প্রায় 203 MiB হতে পারে। শুধু ARM64:

```bash
./gradlew -PtargetAbi=arm64-v8a assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`। Windows-এ `gradlew.bat`। SDK path-এর `local.properties` commit করবেন না।

কম RAM-এর environment:

```bash
./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx700m -XX:MaxMetaspaceSize=384m -XX:ReservedCodeCacheSize=64m -XX:+UseSerialGC -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  -PtargetAbi=arm64-v8a newpipe-extractor:test testDebugUnitTest lintDebug assembleDebug
```

Native libraries prebuilt AAR-এ আছে; সাধারণ app build-এ NDK/FFmpeg/Python source compile দরকার নেই।

### Opt-in live probe — CI-তে default off

শুধু নিজের/অনুমতিপ্রাপ্ত ভিডিওর URL ব্যবহার করুন:

```bash
./gradlew testDebugUnitTest --tests '*NewPipeLiveProbeTest' \
  -PliveUrl='https://www.youtube.com/watch?v=VIDEO_ID'
```

`-PliveDownload=true` যোগ করলে available muxed stream-এর full transfer চেষ্টা করে; পরিচিত size 100 MiB-এর মধ্যে হতে হবে। Output `app/build/live-probe/media.mp4`, source ZIP/APK-তে অন্তর্ভুক্ত নয়। এটি JVM test, Android/native merge test নয়।

## GitHub Actions

ZIP-এর `StreamDock` ফোল্ডারের ভেতরের সব source ও hidden `.github` repository root-এ push করুন। `main`/`master` push/PR বা manual workflow-তে extractor regression tests + app tests + lint + universal debug build + APK codec-reference check + native dependency audit চলে। Artifact **StreamDock-debug-APK**, retention ৭ দিন। কোনো repository-তে push করা হয়নি; workflow source দেওয়া হয়েছে।

## গুরুত্বপূর্ণ ফাইল

- `newpipe-extractor/`: editable upstream source, original license, source archive checksum ও exact compatibility patch।
- `newpipe-extractor/src/main/java/org/schabi/newpipe/extractor/utils/Utils.java`: Android 9 URL-codec fix।
- `newpipe-extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java`: extractor class; upstream থেকে অপরিবর্তিত।
- `tools/verify_url_codec_dex.py`: packaged APK-তে incompatible URL codec call ফিরলে CI fail করে।
- `ExtractorRepository.kt`: metadata / preview extraction।
- `TransferWorker.kt`: foreground transfer, routing, SAF export, engine/stage progress।
- `NewPipeTransfer.kt`: fresh extraction → direct tracks → processing।
- `PipeDownloadPlan.kt`: container, quality cap ও compatible audio selection।
- `DirectHttpDownloader.kt`: byte-range transfer, integrity checks, cancellation।
- `NativeMediaProcessor.kt`: on-device preflight ও local-file FFmpeg merge/audio conversion।
- `NativeRuntime.kt`: shared-runtime initialization order, native file checks এবং common loader search path।
- `tools/verify_ffmpeg_runtime.py`: packaged ELF dependency/ABI audit; এটি Android execution test নয়।
- `EngineBoundary.kt`, `Diagnostics.kt`: recoverable engine-loading failures ও local diagnostic reports।
- `.github/workflows/build-apk.yml`: build pipeline।
- `docs/ANALYSIS-HOTFIX-1.0.1.md`: আগের crash patch-এর ঐতিহাসিক notes।

## Privacy ও লাইসেন্স

Reports app-private storage-এ থাকে; automatic upload হয় না। Copy করার আগে review করুন—device model/Android/ABI/timestamps ও third-party error text থাকে। URLs/common sensitive headers best-effort redact হয়।

Original application source: **GPL-3.0-or-later**; নতুন TeraBox adaptation **AGPL-3.0-only**। Combined-work distribution-এ section 13 প্রযোজ্য। `LICENSE`, `LICENSE-AGPL-3.0` ও `THIRD_PARTY_NOTICES.md` দেখুন। Binary public distribute করার আগে exact third-party corresponding sources/notices/build information ও প্রযোজ্য licensing obligations পূরণ করুন। শুধু upstream link সব ক্ষেত্রে যথেষ্ট নয়।

শুধু নিজের, public-domain বা অনুমতিপ্রাপ্ত media download করুন এবং service terms ও applicable আইন মেনে চলুন।
