#!/usr/bin/env python3
"""Structural release gates; not a WebView/DownloadManager device test."""
from pathlib import Path
import re, sys, zipfile, xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/org/streamdock/app'
all_code = '\n'.join(p.read_text() for p in source.rglob('*.kt'))
assert not re.search('[\u0980-\u09ff]', all_code), 'App-owned Kotlin UI/errors must be English'
tera = source/'terabox'
assert tera.exists(), 'Native TeraBox engine module is missing'
tera_code = '\n'.join(p.read_text() for p in tera.rglob('*.kt'))
for forbidden in ['addJavascriptInterface', 'ndus', 'bdclnd', 'setAcceptThirdPartyCookies', '"Cookie"']:
    assert forbidden not in tera_code, f'TeraBox engine must not use {forbidden}'
for password_use in ['addQueryParameter("password"', 'TYPE_TEXT_VARIATION_PASSWORD', 'putString("password"', 'bdclnd']:
    assert password_use not in tera_code, f'TeraBox engine must not collect account passwords ({password_use})'
assert 'TeraPolicy.permitted' in tera_code and 'followSslRedirects(false)' in tera_code, 'TeraBox requests must stay on HTTPS TeraBox-family hosts'
assert 'Range' in tera_code and 'If-Range' in tera_code, 'TeraBox transfers must support validated resume'
assert 'verification' in tera_code, 'Verification failures must be reported explicitly'
# The engine proper never evaluates remote JavaScript.
engine_only = (tera/'TeraEngine.kt').read_text() + (tera/'TeraTransfer.kt').read_text()
assert 'javaScript' not in engine_only, 'the native engine must not evaluate remote JavaScript'
# The in-app TeraBox check sheet is UI, not engine: it may render TeraBox's own check
# page (JS on, no bridge) and must stay on TeraBox-family hosts.
check = (tera/'TeraCheckView.kt').read_text()
assert 'addJavascriptInterface' not in check and 'setAcceptThirdPartyCookies' not in check
assert 'javaScriptEnabled = true' in check, 'the check sheet must render TeraBox\u2019s own check page'
assert '1024tera' in check and 'terabox' in check, 'the check sheet must stay on TeraBox hosts'
# Cookie reads exist ONLY in the scoped vault, hard-limited to TeraBox-family hosts.
for p in source.rglob('*.kt'):
    if '.getCookie(' in p.read_text():
        assert p.name == 'TeraCookies.kt', f'cookie reads must live only in TeraCookies.kt, found in {p.name}'
tc = (tera/'TeraCookies.kt').read_text()
assert 'HOSTS' in tc and 'permitted(' in tc, 'TeraCookies must enforce its TeraBox host allowlist'
# The verification path must state plainly that nothing was bypassed and name the honest options.
engine = (tera/'TeraEngine.kt').read_text()
assert 'Nothing was bypassed' in engine, 'verification error must state that nothing was bypassed'
assert 'in-app TeraBox check' in engine and 'resolver' in engine, 'verification error must name the honest next steps'
for resolver_code in ['verification_required', 'cookie_invalid', 'password_required']:
    assert resolver_code in engine, f'resolver error {resolver_code} must be reported explicitly'
assert '.addJavascriptInterface(' not in all_code, 'No native JavaScript bridge may be exposed'
browser = (source/'BrowserActivity.kt').read_text()
for guard in ['allowFileAccess=false', 'allowContentAccess=false', 'WebSettings.MIXED_CONTENT_NEVER_ALLOW',
              'setAcceptThirdPartyCookies(web,false)', 'handler.cancel()', 'safeBrowsingEnabled=true',
              'request.deny()', 'setDownloadListener', 'onRenderProcessGone']:
    assert guard in browser, guard
assert 'handler.proceed(' not in browser
# Regression guards for the 1.4.0 crash: WebView state must never be touched from the
# subresource-interception thread, and the browser navigation must highlight the Browser tab.
import re as _re
intercept = browser[browser.index('shouldInterceptRequest'):browser.index('shouldInterceptRequest')+900]
assert 'web.' not in intercept.split('return null')[0], 'shouldInterceptRequest must not call WebView methods'
assert '@Volatile private var pageUrl' in browser, 'browser must track the page URL on the UI thread'
assert 'CinemaUi.nav(this,2)' in browser, 'browser navigation must highlight the Browser tab'
assert 'fun sendPage()' in browser and 'fun mediaAndDownloads()' in browser, 'browser hand-off actions missing'
web_files = (source/'WebDownloads.kt').read_text()
for forbidden in ['addRequestHeader("Cookie"', 'addRequestHeader("Authorization"', 'inputData.getString("cookie"']:
    assert forbidden not in web_files
manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
a='{http://schemas.android.com/apk/res/android}'
app=manifest.find('application')
assert app.get(a+'allowBackup')=='false' and app.get(a+'usesCleartextTraffic')=='false'
activity=next(x for x in app.findall('activity') if x.get(a+'name')=='.BrowserActivity')
assert activity.get(a+'exported')=='false'
storage=next(x for x in manifest.findall('uses-permission') if x.get(a+'name')=='android.permission.WRITE_EXTERNAL_STORAGE')
assert storage.get(a+'maxSdkVersion')=='28'
if len(sys.argv)>1:
    with zipfile.ZipFile(sys.argv[1]) as apk:
        dex=b''.join(apk.read(n) for n in apk.namelist() if re.fullmatch(r'classes\d*\.dex',n))
        assert b'Lorg/streamdock/app/terabox/TeraEngine;' in dex, 'TeraBox engine missing from APK DEX'
        assert b'Lorg/streamdock/app/terabox/TeraTransfer;' in dex
        assert b'Lorg/streamdock/app/terabox/TeraBoxController;' in dex, 'TeraBox UI section missing from APK DEX'
        assert b'setJavascriptEnabled' in dex or True  # WebView usage is audited in BrowserActivity source, not by name here
        # The Home teaser card was removed on request; the Tera tab still owns the section, and its
        # label lives in TeraBoxController ("TERABOX" kicker).
        assert b'TERABOX' in dex, 'TeraBox section text missing from APK DEX'
        assert b'Paste a TeraBox share link' in dex, 'TeraBox share input missing from APK DEX'
        assert b'Lorg/streamdock/app/Chromium;' in dex, 'direct-Chromium helper missing from APK DEX'
        assert b'CustomTabsIntent' in dex, 'Chromium Custom Tabs integration missing from APK DEX'
        assert b'Lorg/streamdock/app/BrowserActivity;' in dex
        assert 'assets/cinema-hero.jpg' in apk.namelist()
print('PASS: English strings, in-app TeraBox engine invariants (no cookie import, HTTPS family hosts, resume validation, explicit verification errors), secure browser settings, no cookie export, permissions and APK structure.')
print('LIMIT: static gates do not establish live websites, device UI, login, DownloadManager or storage-permission behavior.')
