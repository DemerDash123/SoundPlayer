#!/usr/bin/env bash
# End-to-end smoke test on an Android emulator: install, scan test music, play, navigate every screen,
# take screenshots and fail on crashes. Results go to ci-out/.
set -u
APK="$1"
PKG=com.demerdash.soundplayer
OUT=ci-out
mkdir -p "$OUT"
UI="python3 ci/ui.py"
FAILS=0
STEP=0
log() { echo "== $*" | tee -a "$OUT/summary.txt"; }
pass() { echo "PASS: $*" | tee -a "$OUT/summary.txt"; }
fail() { echo "FAIL: $*" | tee -a "$OUT/summary.txt"; FAILS=$((FAILS + 1)); }
shot() { STEP=$((STEP + 1)); adb exec-out screencap -p > "$OUT/$(printf %02d $STEP)_$1.png"; }
state() { adb shell dumpsys media_session | grep -m1 -oE "state=PlaybackState \{state=[A-Z_]*\(?[0-9]+" | grep -oE "[0-9]+$"; }
meta() { adb shell dumpsys media_session | grep -m1 -oE "metadata: size=[0-9]+, description=[^,]*" ; }
check() { local o; if o=$("$@" 2>&1); then pass "$*"; else fail "$* :: $(echo "$o" | tail -3 | tr '\n' ' ')"; fi; }

adb wait-for-device
sleep 15
adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell getprop ro.build.version.release | xargs echo "Android" | tee -a "$OUT/summary.txt"

log "install"
adb install -r -g "$APK" | tee -a "$OUT/summary.txt" || fail "install"

log "push test music"
adb shell mkdir -p /sdcard/Music/SoundPlayerTest
for f in ci/fixtures/*; do adb push "$f" "/sdcard/Music/SoundPlayerTest/" > /dev/null; done
for f in ci/fixtures/*; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/SoundPlayerTest/$(basename "$f" | sed 's/ /%20/g')" > /dev/null 2>&1
done
adb shell content call --uri content://media --method scan_volume --arg external_primary > /dev/null 2>&1
sleep 8
adb shell content query --uri content://media/external/audio/media --projection title:artist:album:duration:is_music > "$OUT/mediastore.txt" 2>&1
cat "$OUT/mediastore.txt"

adb logcat -c
log "launch"
adb shell am start -W -n $PKG/.MainActivity | tee -a "$OUT/summary.txt"
sleep 4
shot library_songs
check $UI exists "text:Aurora Lights" 15
check $UI exists "text:ليالي مسقط" 5
if $UI exists "text:Short Clip" 2 > /dev/null; then fail "short clip should be hidden"; else pass "short (<30s) clip hidden"; fi

log "play a song"
$UI tap "text:Aurora Lights" && sleep 3
shot playing_mini_player
S=$(state); echo "state=$S" | tee -a "$OUT/summary.txt"
check test "$S" = "3"
check $UI exists "id:mini_player" 5

log "now playing (UI navigation runs paused so uiautomator can reach idle)"
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
$UI tap "id:mini_title" && sleep 3
shot now_playing
check $UI exists "id:np_title" 5

log "synced lyrics"
$UI tap "id:np_btn_lyrics" && sleep 2
shot lyrics
check $UI exists "contains:Welcome to SoundPlayer" 5
$UI tap "id:np_btn_lyrics"; sleep 1

log "media keys"
B=$(meta); adb shell input keyevent KEYCODE_MEDIA_NEXT; sleep 3; A=$(meta)
echo "before: $B / after: $A" | tee -a "$OUT/summary.txt"
check test "$A" != "$B"
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
S=$(state); echo "state=$S" | tee -a "$OUT/summary.txt"
check test "$S" = "2"
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 2
check test "$(state)" = "3"
shot now_playing_next_track
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2

log "notification"
adb shell dumpsys notification --noredact | grep -A3 "pkg=$PKG" | head -20 > "$OUT/notification.txt"
check grep -q "$PKG" "$OUT/notification.txt"

log "queue"
$UI tap "id:np_btn_queue" && sleep 2
shot queue
check $UI exists "text:Up next" 5
adb shell input keyevent KEYCODE_BACK; sleep 1

log "equalizer"
$UI tap "id:np_btn_eq" && sleep 2
$UI tap "text:Bass Boost" 5; sleep 1
shot equalizer
check $UI exists "text:Bass boost" 5
adb shell input keyevent KEYCODE_BACK; sleep 1

log "speed dialog"
$UI tap "id:np_btn_speed" && sleep 2
shot speed
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 2

log "tabs"
$UI tap "text:Albums" && sleep 2 && shot albums
check $UI exists "text:Northern Sky" 5
$UI tap "text:Northern Sky" && sleep 2 && shot album_detail
check $UI exists "text:Play" 5
adb shell input keyevent KEYCODE_BACK; sleep 1
$UI tap "text:Artists" && sleep 2 && shot artists
$UI tap "text:Folders" && sleep 2 && shot folders
$UI tap "text:Playlists" && sleep 2 && shot playlists
check $UI exists "text:Favorites" 5
$UI tap "text:Songs"; sleep 1

log "search"
$UI tap "id:btn_search" && sleep 2
adb shell input text "Ocean"; sleep 2
shot search
check $UI exists "text:Ocean Drive" 5
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1

log "tab order: Playlists is second"
python3 - <<'PY' | tee -a "$OUT/summary.txt"
import subprocess, xml.etree.ElementTree as ET, re
subprocess.run(["adb","shell","uiautomator","dump","/sdcard/ui.xml"],capture_output=True)
root=ET.fromstring(subprocess.run(["adb","exec-out","cat","/sdcard/ui.xml"],capture_output=True).stdout)
xs={n.get("text"):int(re.findall(r"\d+",n.get("bounds"))[0]) for n in root.iter("node") if n.get("text") in ("Songs","Playlists","Albums")}
print("PASS: tab order" if xs.get("Songs",9e9) < xs.get("Playlists",9e9) < xs.get("Albums",-1) else f"FAIL: tab order {xs}")
PY

log "multi-select songs -> add to new playlist"
$UI tap "text:Songs"; sleep 1
$UI longtap "text:Aurora Lights" && sleep 1
$UI tap "text:Desert Wind" && $UI tap "text:Ocean Drive" && sleep 1
shot multi_select
check $UI exists "text:3 selected" 5
$UI tap "id:sel_add" && sleep 1
$UI tap "contains:New playlist" && sleep 1
adb shell input text "RoadTrip"; sleep 1
$UI tap "text:CREATE" 3 || $UI tap "text:Create" 3; sleep 2
check $UI exists "text:Shuffle all" 5
$UI tap "text:Playlists" && sleep 2
shot playlists_with_new
check $UI exists "text:RoadTrip" 5
$UI tap "text:RoadTrip" && sleep 2
shot new_playlist_detail
check $UI exists "contains:3 songs" 5
check $UI exists "text:Ocean Drive" 5
adb shell input keyevent KEYCODE_BACK; sleep 1
$UI tap "text:Songs"; sleep 1

log "settings + light theme"
$UI tap "id:btn_menu" && sleep 1 && $UI tap "text:Settings" && sleep 2
shot settings
$UI tap "text:Theme" && sleep 1 && $UI tap "text:Light" && sleep 3
adb shell input keyevent KEYCODE_BACK; sleep 2
shot library_light
$UI tap "id:btn_menu" && sleep 1 && $UI tap "text:Settings" && sleep 2
$UI tap "text:Theme" && sleep 1 && $UI tap "text:Dark" && sleep 2
adb shell input keyevent KEYCODE_BACK; sleep 1

log "background playback"
adb shell input keyevent KEYCODE_MEDIA_PLAY; sleep 2
adb shell input keyevent KEYCODE_HOME; sleep 5
check test "$(state)" = "3"
adb shell am start -n $PKG/.MainActivity > /dev/null; sleep 3
shot resumed

log "crash check"
adb logcat -d > "$OUT/logcat.txt"
grep -E "FATAL EXCEPTION|ANR in $PKG|AndroidRuntime" "$OUT/logcat.txt" > "$OUT/crashes.txt"
if grep -q "$PKG" "$OUT/crashes.txt" || grep -q "FATAL EXCEPTION" "$OUT/crashes.txt"; then fail "app crashed (see crashes.txt)"; cat "$OUT/crashes.txt"; else pass "no crashes / ANRs"; fi
adb shell run-as $PKG cat files/last_crash.txt > "$OUT/last_crash.txt" 2>/dev/null
grep -iE "soundplayer|MediaPlayer|PlaybackService|Effects" "$OUT/logcat.txt" | grep -vE "^\s*$" | tail -200 > "$OUT/app_log.txt"

echo "FAILURES: $FAILS" | tee -a "$OUT/summary.txt"
exit $FAILS
