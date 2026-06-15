# Baseline Validation — Phase 01

Tag: `v-baseline` · Date: 2026-06-15 · Upstream: jqssun/android-airplay-server @ UxPlay v1.73.6

## Build validation (DONE — automated)
- [x] `./gradlew assembleDebug` → **BUILD SUCCESSFUL in 30m 22s**, exit 0
- [x] APK produced: `app-debug.apk` (~37 MB)
- [x] Native libs packaged per ABI (arm64-v8a, armeabi-v7a, x86_64):
  - `libairplay_native.so` (UxPlay core + JNI bridge)
  - `libcrypto.so` (OpenSSL 3.4.4, from source)
  - `libc++_shared.so`
- [x] Manifest sane: pkg `io.github.jqssun.airplay`, label "AirPlay Server", `MainActivity`

## Baseline already present in upstream (reduces later phases)
- **Android TV ready:** `LEANBACK_LAUNCHER` category + `leanback`/`touchscreen` declared `not-required`
  → app already appears on the Android TV home screen. Phase-04 = refine, not build-from-zero.
- **Permissions already declared:** `CHANGE_WIFI_MULTICAST_STATE`, `WAKE_LOCK`,
  `FOREGROUND_SERVICE` (+ `_CONNECTED_DEVICE`, `_MEDIA_PLAYBACK`), `POST_NOTIFICATIONS`,
  `SYSTEM_ALERT_WINDOW`, `RECEIVE_BOOT_COMPLETED` → de-risks phase-02 (mDNS multicast) & phase-05 (FGS/power).

## Device validation (real hardware — 2026-06-15)

**Target box:** X96Air_P2_2GB · SoC `franklin` (Amlogic) · **Android 9 (SDK 28)** · primary ABI **armeabi-v7a (32-bit)** · IP 192.168.1.13 (adb-over-network :5555).

- [x] `adb install -r app-debug.apk` → **Success** (armeabi-v7a lib used)
- [x] App launches, **no crash**, no `UnsatisfiedLinkError` (pid alive) → native libs load OK
- [x] `AirPlayService: Server started on port 7000` (confirmed listening in /proc/net/tcp)
- [x] mDNS registered BOTH services via **Android NsdManager**:
  - `_airplay._tcp` "Android AirPlay" :7000
  - `_raop._tcp` "7CA7B08C3294@Android AirPlay"
  - TXT: `srcvers=220.68`, `features=0x5A7FFEE6,0x400`, `pk=…`, `model=AppleTV3,2`, `deviceid=7c:a7:b0:8c:32:94`
- [ ] iPhone/Mac mirror end-to-end (video frames + audio) — capturing logcat during user mirror attempt

### KEY CORRECTION for phase-02
Plan assumed jqssun bundles a **C mDNS**. Reality: it uses **Android `NsdManager`** (`NsdServiceManager.kt`), and registration **succeeded** on Android 9 / SDK 28. The research-flagged NsdManager TXT bug is API<33 — but here TXT registered fine. → Phase-02 mDNS decision must be re-evaluated against this working baseline, not the assumption.

### Box constraint for phase-03
SDK 28 (Android 9) < API 30 → **`KEY_LOW_LATENCY` unavailable** on this box. Phase-03 low-latency path must degrade gracefully (async MediaCodec API 28 + AAudio API 26 still available).

## Mirror test #1 — flicker (2026-06-15)
**Symptom (user):** screen flickers/blinks during mirroring ("nhấp nháy màn").
**Connection:** OK — `Client connected (1)`, pairing/fp-setup succeeded, `Video size: 498.0x1080.0` (portrait iPhone mirror).
**Benign noise:** `OMXNodeInstance ... amlogic.avc.decoder ... UnsupportedSetting` (config-query rejection); `AuthPII` logs = box's Play Services, unrelated.
**Note:** `screencap` shows black for the mirror region — SurfaceView is a HW overlay plane, not captured. Visual flicker only observable on HDMI; diagnose via logs + code.

**Root-cause hypothesis #1 (highest confidence):** `VideoRenderer.drainOutput()` uses NTP-scheduled `releaseOutputBuffer(idx, wallTs)`. Amlogic `franklin` / Android 9 OMX does not reliably honor render-at-timestamp → frames held/dropped/duplicated → flicker.
**Fix applied:** default `scheduled_output_buffer_release` → **false** (immediate render `releaseOutputBuffer(idx, true)`). Files: `Prefs.kt`, `VideoRenderer.kt`. Scheduling kept as advanced toggle.
**run-as note:** writing `shared_prefs/settings.xml` externally failed (Permission denied — Amlogic run-as quirk); used code-default change + incremental rebuild instead.
**Verification:** PENDING user re-mirror after reinstall.

**Fallback hypotheses if flicker persists:** (2) `KEY_PRIORITY=0` realtime confusing Amlogic OMX; (3) `enforceSdr` COLOR_* hints on decoder input; (4) Compose `aspectRatio().fillMaxSize()` surface relayout on portrait. Test by toggling each.

### How to run the device test
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
adb connect <tv-box-ip>:5555          # or USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat | grep -iE "airplay|raop|AndroidRuntime"   # watch while mirroring
```

## Known limitation (do not attempt to fix)
DRM-protected content (Netflix, Apple TV+, etc.) renders **black** — FairPlay content decryption is proprietary. This is inherent to all open-source AirPlay receivers.

## Unresolved (carried to next phases)
- iOS 18 `features` bitmap correctness on a real device (phase-02)
- HW low-latency H.264/HEVC support on the specific target box (phase-03)
- Real-world A/V latency numbers (phase-06)
