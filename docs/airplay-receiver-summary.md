# Android TV AirPlay Receiver — Final Summary

Fork of `jqssun/android-airplay-server` (GPL-3.0), hardened for low-end Amlogic TV boxes.
Branch: `feat/android-tv-render-fixes`. Validated on **X96Air_P2** (Amlogic `franklin`, Android 9 / API 28).

## What works
- iPhone (iOS) + macOS screen mirroring, full **1920×1080**, hardware H.264 decode.
- **Sharpness on par with AirScreen** once macOS ramps the mirror bitrate up.
- No cursor trails, no permanent freeze in normal desktop use (~98% of frames rendered, near-zero drops).
- Correct colors (not the harsh "fake HDR" look), screen stays awake.

## Key findings (measured)
- **Bitrate ramps over a session:** macOS starts the AirPlay mirror around **~80 kbps** (soft/blurry)
  and ramps up to **~14 Mbps** as the session stabilises — hence "the longer it runs, the sharper it gets."
- **Same stream as AirScreen:** both receive H.264 1920×1080 SDR via the same `amlogic.avc.decoder`.
  AirScreen's edge is proprietary vendor decode tuning, not a higher-quality stream.
- **AirPlay-1 sends IDRs rarely** → any dropped/skipped frame ghosts (cursor trails) until the next
  keyframe. Fix: feed every frame in order, never drop mid-GOP.
- **Amlogic HW decoder wedges** under sustained high-motion video (fed continues, output stops).
  A fresh IDR is needed to recover, which only a client reconnect provides.

## Render design (`VideoRenderer.kt`)
- Async `MediaCodec` (setCallback) → renders continuously, independent of input cadence.
- Feed every frame in order; no mid-GOP dropping.
- Keyframe detection: 3-byte AND 4-byte Annex-B start codes.
- Bounded keyframe-resync (≤2s) so a late/absent IDR never freezes permanently.
- Stall watchdog: restart codec when frames arrive but nothing renders for 3s; capped at 4
  consecutive attempts (a true HW wedge needs reconnect, not restart-thrash).
- Color: FULL-range BT.709; input buffer sized to the frame so 1080p keyframes aren't truncated.

## Known limitation
- **Sustained high-motion video (e.g. YouTube fullscreen)** can wedge the Amlogic HW decoder beyond
  watchdog recovery → **disconnect + reconnect** the mirror to clear it. Inherent to this cheap SoC +
  the open-source AirPlay-1 path. (Matching AirScreen here would require AirPlay 2, which is not openly
  implemented.)

## Operating notes
- **One receiver at a time:** AirScreen and this app both bind AirPlay port 7000 — stop one before the
  other (`adb shell am force-stop com.ionitech.airscreen`).
- **Harsh/oversaturated colors?** Turn OFF the box's SDR→HDR: open
  `com.droidlogic.tv.settings/.display.DisplayActivity` → **SDR to HDR → Off**
  (`adb shell am start -n com.droidlogic.tv.settings/.display.DisplayActivity`).
- **Box sleeps / HDMI drops to "no signal" or CVBS:** the app holds `FLAG_KEEP_SCREEN_ON`; if it still
  happens, the box's display subsystem can degrade after long use — **reboot the box** to clear it
  (`adb reboot`). A fresh boot restores clean 1080p60 mirroring.
- ADB over network resets on reboot: re-toggle Developer options → Network debugging, and note the box
  IP can change via DHCP.

## Build / install
See `docs/build-setup.md`. Quick: `./gradlew assembleDebug` →
`adb install -r app/build/outputs/apk/debug/app-debug.apk`.

## Distribution
GPL-3.0 + reverse-engineered FairPlay → sideload only (not Play-Store eligible). Free, no time limit.
