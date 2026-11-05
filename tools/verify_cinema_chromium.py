#!/usr/bin/env python3
"""Gates for the cinematic redesign and the direct-Chromium browser path.

Two promises are checked structurally:
  1. the bottom navigation is untouched (compact, single-line, auto-sized labels), and
  2. the new Chromium path launches the *installed* browser without handing anything over.
"""
from pathlib import Path
import re, sys

root = Path(__file__).resolve().parents[1]
app = root / 'app/src/main/java/org/streamdock/app'
cinema = (app / 'CinemaUi.kt').read_text()
browser = (app / 'BrowserActivity.kt').read_text()
chromium = (app / 'Chromium.kt').read_text()
main = (app / 'MainActivity.kt').read_text()
tera = (app / 'terabox/TeraBoxController.kt').read_text()

# 1. Bottom navigation must stay exactly as the user approved it.
nav = cinema[cinema.index('fun nav('):cinema.index('fun nav(') + 2600]
for guard in ['maxLines = 1', 'isSingleLine = true', 'setAutoSizeTextTypeUniformWithConfiguration']:
    assert guard in nav, f'bottom navigation lost its compact label guard: {guard}'
assert 'dp(c, 52)' in nav or 'dp(c,52)' in nav, 'bottom navigation height changed (user asked for the compact bar to stay)'
assert 'action(i)' in nav, 'bottom navigation must keep its single tap route'

# 2. The 1.6.0 visual language exists and is applied outside the navigation.
#    (Replaces the 1.4.0 "cinematic" check the user rejected; the same intent is kept:
#     one shared type/backdrop system that every screen uses.)
for helper in ['fun display(', 'fun kicker(', 'fun titlebar(', 'fun backdrop(']:
    assert helper in cinema, f'shared UI helper missing: {helper}'
visuals = (app / 'DockVisuals.kt').read_text()
for feature in ['class GlassSurface', 'class AuroraBackdrop', 'class ChromeText', 'class GlassProgress', 'fun depth(', 'fun pressable(']:
    assert feature in visuals, f'frozen-glass primitive missing: {feature}'
assert 'fonts/manrope-' in visuals, 'bundled Manrope faces must be referenced'
assert 'letterSpacing' in cinema
assert 'CinemaUi.titlebar' in main and 'CinemaUi.display' in main, 'MainActivity must use the cinematic title treatment'
assert 'ChromeText(this,"Browser"' in browser, 'browser header must use the chrome wordmark treatment'
assert 'CinemaUi.backdrop(this@MainActivity)' in main and 'CinemaUi.backdrop(this@BrowserActivity)' in browser, 'pages must use the cinematic backdrop'
# Buttons keep the compact height from the phone-fit fix.
assert 'minHeight = dp(c, 48)' in cinema, 'buttons must stay at the compact 48 dp key height'

# 3. Real Chromium, directly, with nothing handed over.
assert 'CustomTabsIntent' in chromium and 'CustomTabsClient.getPackageName' in chromium
for forbidden in ['putExtra("Cookie"', 'extraHeaders', 'setCookie', 'addJavascriptInterface']:
    assert forbidden not in chromium, f'Chromium launch must not hand over {forbidden}'
assert 'COLOR_SCHEME_LIGHT' in chromium
assert 'openInChromium' in browser and 'toggleChromiumDefault' in browser, 'browser must offer Chromium directly'
assert '"engine","INAPP"' in browser, 'the default engine setting must exist'
# The TeraBox flow is fully in-app (user decision 2026-10-18): no external browser offers,
# the in-app check sheet replaces the old "open it in the browser" step.
assert 'Chromium' not in tera and 'openInBrowser' not in tera, 'TeraBox flows must stay inside the app'
assert 'TeraCheckView' in tera, 'the in-app check sheet must be wired in the TeraBox section'
print('PASS: frozen-glass redesign (bottom bar untouched) and direct-Chromium integration gates.')
print('LIMIT: static gates cannot open Chrome on this machine; device behaviour needs the phone test.')
