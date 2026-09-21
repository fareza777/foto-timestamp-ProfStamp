---
name: testing-profstamp-android
description: How to end-to-end test the ProfStamp Android app on the headless emulator via adb (no desktop GUI): launch, drive Compose UI with uiautomator+input taps, capture/screenshot evidence, stage files through MediaStore for the photo picker, and known emulator pitfalls.
---

# Testing ProfStamp on the headless emulator

This box has **no usable desktop** (`computer` tool fails: "enigo init failed"; DISPLAY :0 has no X server). All UI testing goes through adb against the running emulator — use `screencap`/`screenrecord` for visual evidence and `uiautomator dump` for tappable-node coordinates.

## Environment

- AVD `c2pa_test` (Android 34, x86_64). Check first whether it is already running: `adb devices` / `pgrep -f qemu-system`. If a stale `multiinstance.lock` blocks a new start (`FATAL: Running multiple emulators...`), an emulator is probably already up — reuse it.
- Headless start (persistent tty shell): `env -u DISPLAY ~/Android/Sdk/emulator/emulator -avd c2pa_test -no-window -no-audio -gpu off -no-snapshot-save -no-boot-anim -memory 2048`. `/dev/kvm` may need `sudo -n chmod 666`.
- Debug app id is `com.proofstamp.app.debug`, but the launcher component is `com.proofstamp.app.debug/com.proofstamp.app.MainActivity` (activity class has no `.debug` segment — `am start -n` with the wrong class name fails "does not exist").
- Install: `~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Driving the UI

- The Compose UI has no resource-ids/testTags; locate elements by `text=` or `content-desc=` in `uiautomator dump` XML and tap by bounds center (`input tap x y`). Dump takes 2-5 s — batch it with sleeps, and **write dumps to `/data/local/tmp/ui.xml`, not `/sdcard`** (see pitfalls).
- Runtime permission dialogs are `com.android.permissioncontroller` nodes; tap the `permission_allow_foreground_only_button` bounds.
- Bottom nav (Camera/Gallery/Sessions/Verify/Settings) is only shown on non-camera tabs. From the camera screen reach it via the settings (Tune) icon top-right, then tap the tab label.
- Shutter button is the center-bottom clickable node (~540,2128 on 1080x2400); the thumbnail bottom-left opens the newest photo's detail.
- Screenshots: `adb exec-out screencap -p > file.png` (device is 1080x2400). Video: `adb shell "screenrecord --time-limit 150 /data/local/tmp/clip.mp4"` — **record to /data/local/tmp, never /sdcard** — then `kill -2 <pid>` or let the limit expire and `adb pull` AFTER the process exits, else the mp4 has no moov atom.

## Inspecting app data

- Captures: `run-as com.proofstamp.app.debug ls files/captures/` (files are `<photoId>.jpg`; signing leaves a transient `.c2pa.tmp` sibling for a few seconds — poll until gone before judging a capture).
- Room DB: `run-as com.proofstamp.app.debug /system/bin/sqlite3 databases/proofstamp.db 'SELECT id,c2pa,contentHash FROM photos;'` — plain `sqlite3` is not on the run-as PATH, use the absolute path; table is `photos` (plural).
- Share/export "Original" staging: `cache/shared/<id>.jpg` — sha256 against `files/captures/` to prove byte-identical.
- C2PA sanity: pull the jpg (`run-as ... cat ... > out.jpg`) and `grep -a 'c2pa'`/`urn:c2pa` — a signed file is ~60 KB larger than its pre-sign bytes.
- Logcat tags: `C2paManager` (sign failures), `C2paSigner` (identity setup).

## Staging files for the Verify (photo picker) flow

The picker (`ActivityResultContracts.PickVisualMedia`) reads MediaStore only:

1. `adb push f.jpg /sdcard/Pictures/pstest/` then index it: `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/pstest/f.jpg`.
2. Confirm rows: `adb shell content query --uri content://media/external/images/media --projection _id:_data --where "_data LIKE '%pstest%'"`.
3. Picker thumbnails are **indistinguishable when files are near-identical** (a 1-byte-tampered copy looks the same and shares EXIF date-taken). Keep exactly ONE candidate indexed per round: remove others with `content delete --uri content://media/external/images/media/<id>` plus `rm` the file; re-push+rescan later.
4. In the picker, items carry `content-desc="Photo taken on <date>"` — use date-time to confirm which file is which before tapping.

## Known pitfalls

- **GPS EXIF redaction:** the app does not declare `ACCESS_MEDIA_LOCATION`, so MediaProvider serves a *byte-altered* stream for GPS-tagged JPEGs picked through the photo picker (sha256 differs from the file on disk; C2PA reports `assertion.dataHash.mismatch`). A pristine byte-identical GPS capture therefore reports "Modified" in the in-app Verify flow — verify behaviour differences by comparing against a GPS-disabled capture, which is served byte-exact.
- **/sdcard FUSE crash:** under memory pressure (this AVD: 2 CPU / 2 GB) the emulated storage can die with `Transport endpoint is not connected`, killing in-flight `screenrecord` files and uiautomator dumps to /sdcard. Recover with `adb reboot` (~40 s; app data in /data survives). Keep dumps/recordings on /data/local/tmp.
- **"System UI isn't responding" ANR** appears under the same pressure — tap Wait and continue; it is not the app.
- Granting `ACCESS_MEDIA_LOCATION` via `pm grant` fails ("has not requested permission") since the manifest doesn't declare it.
