#!/usr/bin/env bash
# Photographs CAS's signed-in screens on the CI emulator (cas-build.yml, screens). Signs in to the throwaway Stremio
# account in CAS_TEST_EMAIL / CAS_TEST_PASSWORD for an auth key, hands it to the debug build's test hook, then walks
# the remote through Home, the rail, TV, Movies and a title page, taking a picture at each. Fails on a crash.
set -euo pipefail
APK="$1"
mkdir -p screens
BODY=$(jq -n --arg e "$CAS_TEST_EMAIL" --arg p "$CAS_TEST_PASSWORD" '{type:"Login",email:$e,password:$p,facebook:false}')
KEY=$(curl -s -m 30 -H 'content-type: application/json' -d "$BODY" https://api.strem.io/api/login | jq -r '.result.authKey // empty')
[ -n "$KEY" ] || { echo "::error::The test account did not sign in to Stremio"; exit 1; }
echo "::add-mask::$KEY"
shot() { adb exec-out screencap -p > "screens/$1.png"; }
key() { adb shell input keyevent "$1"; sleep "${2:-2}"; }
adb install "$APK" > /dev/null
adb logcat -c
adb shell am start -n uk.albyjnr.cas/dev.alder.ProfilesActivity --es testKey "$KEY" > /dev/null
sleep 35; shot 01-home
key KEYCODE_DPAD_DOWN 3; shot 02-home-row2
key KEYCODE_DPAD_LEFT 2; shot 03-rail-open
key KEYCODE_DPAD_DOWN 12; shot 04-tv-rail
key KEYCODE_DPAD_RIGHT 3; shot 05-tv
key KEYCODE_DPAD_DOWN 3; key KEYCODE_DPAD_DOWN 6; shot 06-tv-row3
key KEYCODE_DPAD_LEFT 2; key KEYCODE_DPAD_DOWN 12; key KEYCODE_DPAD_RIGHT 3; shot 07-movies
key KEYCODE_DPAD_CENTER 12; shot 08-title
key KEYCODE_DPAD_DOWN 3; shot 09-title-tabs
key KEYCODE_DPAD_CENTER 6; shot 11-tab-first-open
key KEYCODE_BACK 3; shot 12-tab-closed
key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_RIGHT 2; key KEYCODE_DPAD_CENTER 6; shot 13-tab-details
key KEYCODE_BACK 3; key KEYCODE_DPAD_UP 2; shot 14-back-on-play
key KEYCODE_BACK 3; key KEYCODE_BACK 3; shot 10-back
adb logcat -d | grep -vF "$KEY" > screens/logcat.txt || true
if grep -q "FATAL EXCEPTION" screens/logcat.txt; then echo "::error::CAS crashed on the signed-in walk"; grep -A 20 "FATAL EXCEPTION" screens/logcat.txt | head -40; exit 1; fi
