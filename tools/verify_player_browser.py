#!/usr/bin/env python3
"""Static gates for the 1.7.0 unified player and the upgraded browser.

These checks read sources (and, when an APK path is passed, the DEX/resources).
They do not emulate playback, notifications or browsing on a device.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
source = root / 'app/src/main/java/org/streamdock/app'

# ---------------------------------------------------------------- unified player
player = (source / 'player' / 'PlayerActivity.kt').read_text()
service = (source / 'player' / 'PlaybackService.kt').read_text()
assert 'class PlayerActivity' in player
for feature in [
    'MediaController',                       # one engine, attached by the screen
    'setTrackTypeDisabled(C.TRACK_TYPE_VIDEO',  # audio-only mode
    'REPEAT_MODE_ONE', 'REPEAT_MODE_ALL',    # repeat
    'setPlaybackSpeed',                      # speed
    'seekToNextMediaItem', 'seekToPreviousMediaItem',  # queue navigation
    'currentPosition - 10_000', 'currentPosition + 10_000',  # ±10s
    'screenBrightness',                      # brightness gesture
    'STREAM_MUSIC',                          # volume gesture
    'SCREEN_ORIENTATION_SENSOR_LANDSCAPE',      # rotate
    'POST_NOTIFICATIONS',                    # runtime notice permission on 33+
]:
    assert feature in player, f'player lost: {feature}'

assert 'class PlaybackService : MediaSessionService' in service, 'background playback service missing'
for feature in ['MediaSession', 'setHandleAudioBecomingNoisy', 'setWakeMode', 'ACTION_STOP',
                'startForeground', 'FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK', 'MergingMediaSource']:
    assert feature in service, f'playback service lost: {feature}'

manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
assert 'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK' in manifest
assert 'android:name=".player.PlaybackService"' in manifest
assert 'android:foregroundServiceType="mediaPlayback"' in manifest
manifest_root = ET.parse(root / 'app/src/main/AndroidManifest.xml').getroot()
android_attr = '{http://schemas.android.com/apk/res/android}'
playback_service = next((node for node in manifest_root.findall('.//service')
                         if node.get(android_attr + 'name') == '.player.PlaybackService'), None)
assert playback_service is not None, 'playback service missing from manifest'
assert playback_service.get(android_attr + 'exported') == 'true', 'MediaSessionService must be discoverable by Media3/system controllers'
service_actions = {node.get(android_attr + 'name') for node in playback_service.findall('.//intent-filter/action')}
assert 'androidx.media3.session.MediaSessionService' in service_actions, 'Media3 SessionToken cannot resolve PlaybackService without its service action'
assert 'android:name=".player.PlayerActivity"' in manifest

# Playback must live in the service: no private ExoPlayer left in the screens.
for screen in ['MainActivity.kt', 'terabox/TeraBoxController.kt']:
    code = (source / screen).read_text()
    assert 'ExoPlayer.Builder' not in code, f'{screen} must use the unified player'
assert 'player.PlayerActivity.start' in (source / 'MainActivity.kt').read_text()
assert 'player.PlayerActivity.start' in (source / 'terabox/TeraBoxController.kt').read_text()

# ---------------------------------------------------------------- real site icons
icons = (source / 'SiteIcons.kt').read_text()
for feature in ['assets.open("sites/', '/favicon.ico', '/apple-touch-icon.png',
                'icons.duckduckgo.com', 'BitmapShader']:
    assert feature in icons, f'favicon system lost: {feature}'
assets = root / 'app/src/main/assets/sites'
assert (assets / 'youtube.com.png').exists() and (assets / 'soundcloud.com.png').exists()
assert (assets / 'terabox.com.png').exists(), 'TeraBox brand icon missing'

# ---------------------------------------------------------------- in-app TeraBox pipeline
tera = source / 'terabox'
engine = (tera / 'TeraEngine.kt').read_text()
controller = (tera / 'TeraBoxController.kt').read_text()
assert 'TeraWall' in engine and 'TeraWall' in controller, 'the verification wall must open the in-app check, not a browser'
assert 'addQueryParameter("fid"' in engine, 'the streaming manifest must use fid (fid_list answers 31023)'
assert '__INITIAL_STATE__' in engine, 'WAP page-state dlink extraction missing'
assert 'M3U8_AUTO_' in engine, 'streaming quality ladder missing'
assert 'TeraCheckView' in controller, 'the in-app check sheet must be wired to Play/Save'
for external in ['Chromium', 'openInBrowser', 'ACTION_VIEW']:
    assert external not in controller, f'TeraBox flows must not push external browsers ({external})'

# ---------------------------------------------------------------- browser
browser = (source / 'BrowserActivity.kt').read_text()
for feature in [
    'class Tab', 'tabsDialog', 'restoreSession', 'saveSession',      # tabs + session
    'browser-history.json', 'browser-bookmarks.json',                # history + bookmarks
    'findAllAsync', 'findNext',                                      # find on page
    'desktopSite', 'desktopAgent',                                   # desktop site
    'textZoom',                                                      # text size
    'Intent.ACTION_SEND',                                            # share
]:
    assert feature in browser, f'browser lost: {feature}'

# Session restore must survive process death: state bundles + a session index on disk.
assert 'browser-session' in browser and 'saveState' in browser and 'restoreState' in browser

if len(sys.argv) > 1:
    with zipfile.ZipFile(sys.argv[1]) as apk:
        names = '\n'.join(apk.namelist())
        for asset in ['assets/sites/youtube.com.png', 'assets/sites/soundcloud.com.png',
                      'assets/sites/terabox.com.png']:
            assert asset in names, f'{asset} not packaged'
        dex = b''.join(apk.read(n) for n in apk.namelist() if re.fullmatch(r'classes\d*\.dex', n))
        for cls in [b'Lorg/streamdock/app/player/PlayerActivity;',
                    b'Lorg/streamdock/app/player/PlaybackService;',
                    b'Lorg/streamdock/app/SiteIcons;']:
            assert cls in dex, cls
        assert b'MediaSessionService' in dex or b'androidx/media3/session/MediaSessionService' in dex

print('PASS: unified player (audio-only + full controls), background playback service, real site icons, browser tabs/session/history/bookmarks/find/desktop.')
print('LIMIT: static gates do not play media, show notifications or render pages; device testing still required.')
