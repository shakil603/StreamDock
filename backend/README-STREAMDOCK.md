# Bundled resolver service (optional) — StreamDock notes

This folder contains **Paidguy/terabox-api**, an unmodified MIT-licensed Cloudflare Worker, vendored so the
source archive is self-contained. It is the one open-source path that makes **native Play/Save** work for
TeraBox shares that the anonymous API walls behind `need verify_v2` (code 400310).

- Upstream: <https://github.com/Paidguy/terabox-api>
- Vendored revision: `4d3149a22888018375a933d1c0c0ebc3eef32fb6` (2026-09-21)
- License: MIT — see `LICENSE` in this folder. StreamDock does not modify upstream files; the deployment
  additions in this folder are marked as such below.
- App-side consumer: **Tera tab → Advanced → Resolver settings** (accepts `name.workers.dev` or a full
  HTTPS origin). Response contract the app expects: `GET /api/resolve?link=…` →
  `{"status":"ok","files":[{"fs_id","file_name","size_bytes","isdir","stream_url"}]}`.

## Why a resolver is needed at all

TeraBox lets any anonymous session **list** a share, but refuses to **mint a direct file URL** for shares
it has put behind verification (`errno 400310`, `errmsg: "need verify_v2"`). This was reproduced with a
browser-identical session (real cookies, `sign`, `timestamp`, `jsToken`, the exact `GET /share/download`
form the web player uses) on all five TeraBox origins — see `docs/TERABOX-VERIFY-1.4.0.md`. A resolver
service holds **its own verified TeraBox session** (a `TERABOX_COOKIE` secret on your own Cloudflare
account) and mints links with it. Your password is never entered anywhere in StreamDock, and the app never
imports or exports browser cookies.

## Deploy it (free tier is enough)

**Option A — from a machine with Node.js**

```bash
npm install
npx wrangler login                 # opens Cloudflare's own login page
npx wrangler secret put TERABOX_COOKIE   # paste YOUR OWN TeraBox session cookie when prompted
npm run deploy                     # prints something like https://terabox-api.<you>.workers.dev
```

**Option B — GitHub Actions (no local tooling)**

1. Push this folder to a repository (or fork upstream).
2. Add repository secrets `CLOUDFLARE_API_TOKEN` and `CLOUDFLARE_ACCOUNT_ID` (Cloudflare dashboard →
   My Profile → API Tokens → *Edit Cloudflare Workers* template).
3. Add `TERABOX_COOKIE` as a repository secret too, then run the workflow
   `.github/workflows/deploy-workers.yml` (StreamDock addition) from the Actions tab.

**Then in the app:** Tera tab → *Resolver settings* → enter `terabox-api.<you>.workers.dev` → **Use this
resolver**. Press **Analyze share** again: the file list now arrives with stream URLs and Play/Save work
natively, with the in-app download engine (multi-connection, pause/resume).

## Getting `TERABOX_COOKIE` (your own session, your own secret)

1. Sign in to terabox.com in your browser (the account you choose to use).
2. Open Developer Tools → Network → click any request to `www.terabox.com`.
3. Copy the **Cookie** request header value (it contains things like `ndus=…; PANWEB=1; …`).
4. Store it as the Worker secret. It stays on **your** Cloudflare account, is never sent to StreamDock's
   author, and can be rotated by repeating steps 1–4.

A brand-new TeraBox account session may still be asked for one verification step (that is TeraBox's own
CAPTCHA/SMS flow, performed in your browser); once passed, the session works for the resolver.

## Honest limits

- The service is **yours**: if it is offline, mis-typed, or its session expires, the app reports
  `cookie_invalid` / transport errors — it never falls back to a third-party downloader.
- Only public shares are fetched; password-protected shares are reported as `password_required`.
- StreamDock never sends your share links anywhere except the resolver origin you configured yourself.
- Do not deploy this expecting it to bypass verification: it *is* the verified-session requirement, held
  by a server you control rather than by a stranger.
