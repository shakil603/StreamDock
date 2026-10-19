# 1.5.0 (versionCode 17)

## 1. "Every build still says 1.4.0" — fixed

The Tools footer was a hard-coded string (`STREAMDOCK / 1.4.0`), so **every** build since code 10 showed
1.4.0 on the phone even though the versionCode kept rising. That is why the newest features looked like
they were never shipped.

- `versionName` is now **1.5.0**, `versionCode` **17**.
- The footer reads the *installed* version at runtime (`packageManager.getPackageInfo(...)`), so it can
  never go stale again. On the phone: **Tools → bottom line reads "STREAMDOCK / 1.5.0"**.

## 2. TeraBox download path — made concrete and one-tap

TeraBox refuses to mint a direct file URL for an anonymous session (`errno 400310`, `need verify_v2`) — no
honest client can bypass that (full proof table in `docs/TERABOX-VERIFY-1.4.0.md`). What *does* work is
TeraBox's own page with its own session. Code 17 makes that path pull its weight:

1. **Step card on the share page.** When a TeraBox share opens in the in-app browser the page card now
   reads, in order:
   *1 Complete TeraBox's own check on this page (StreamDock never asks for your password).*
   *2 Press the page's Play, or its own Download button.*
   *3 StreamDock saves what the page loads.*
2. **The page's own Download button now lands in the in-app engine.** TeraBox/Baidu CDN file links
   (`pcs.baidu`, `baidupcs`, `bcebos`, `pcsdn`, plus the TeraBox hosts) are no longer handed to Android's
   download service with no Referer. The browser offers *"Save in app"* → folder picker → the file goes to
   the **in-app download engine** with the page as Referer: multi-connection when the CDN proves ranges,
   pause/resume, container verification on export (code 16 rules) — and the TeraBox row also explains the
   single-connection case instead of failing silently.
3. **Play path unchanged and still honest:** if the page plays, the media button lists the stream and the
   HLS saver stores it.

## 3. What is still true

- A verification-walled share cannot be resolved *natively* without a verified session. The app ships the
  MIT resolver (`backend/terabox-api/`, `backend/README-STREAMDOCK.md`) for people who want native
  Play/Save with their own resolver session; the app-side switch is under **Advanced**.
- No cookie import, no cookie export, no CAPTCHA solving, no fake "Saved" — those gates still run in CI
  (`tools/verify_browser_release.py`).
