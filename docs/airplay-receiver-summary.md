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
- **Advertised model does NOT control bitrate.** Tested advertising `AppleTV6,2` + srcvers `377.40.00`
  (vs default `AppleTV3,2`/`220.68`) — connected fine, ran 5h stable, but **zero sharpness change**.
  Bitrate is set by the macOS adaptive encoder (network + content), not the receiver's model string.
  Measured on wired LAN: idle/static screen ≈ **19–56 kbps** (soft, faint cursor ghosting), active use
  ramps to **2–4.4 Mbps** (sharp, ~0 drops). The "sharp" experience = sustained activity holding bitrate
  high; it cannot be forced up while the screen is static. Current advertised model: `AppleTV6,2`
  (kept — legacy features bitmap retained so the AirPlay-1 path still works).
- **H.265 mirror CANNOT be triggered from macOS (empirically tested).** The receiver already has full
  HEVC support (decode path in `VideoRenderer.kt`, feature bit 42 set, `DEF_H265_ENABLED=true`). Tested
  with every condition met: `AppleTV6,2` + multicodec bit 42 + Apple-Silicon **MacBook Air M2** sender +
  advertised display `3840×2160`. Result: macOS sent **4K but still H.264, never H.265**, and it was
  **not sharper** — because bitrate stayed adaptive/low (~50 kbps idle) and spreading the same bits over
  4× the pixels gives no detail gain (then downscaled to the 1080p panel). The Amlogic box decoded 4K
  H.264 fine (63fps, ~0 drops). Conclusion: macOS does NOT send HEVC for desktop *mirroring* to
  third-party receivers (the "4K→HEVC" rule is for AirPlay *video* streaming, not mirroring). Reverted
  advertised resolution to 1080p (auto) — 4K = 4× decode cost + heat + wedge risk for zero benefit.
  **This is the absolute ceiling of AirPlay-1 screen mirroring; do not re-try H.265/4K/model levers.**
- **AirPlay-1 sends IDRs rarely** → any dropped/skipped frame ghosts (cursor trails) until the next
  keyframe. Fix: feed every frame in order, never drop mid-GOP.
- **Amlogic HW decoder wedges** under sustained high-motion video (fed continues, output stops).
  A fresh IDR is needed to recover, which only a client reconnect provides.

## Render design (`VideoRenderer.kt`)
- Async `MediaCodec` (setCallback) → renders continuously, independent of input cadence.
- **Never drop an INPUT frame.** Mirror video is TCP (reliable, in-order) → nothing is lost on the wire,
  so any dropped frame is self-inflicted and breaks the H.264 reference chain (→ macroblock corruption
  until macOS's rare next IDR). On backlog, apply **backpressure** (the feed thread waits → TCP throttles
  macOS) instead of dropping. Only a genuine >1.5s decoder wedge hard-resyncs to a keyframe.
- **Decouple decode from display for low latency.** Decode every frame (references intact) but release
  stale frames WITHOUT displaying (`releaseOutputBuffer(index, false)`) when input is backlogged, to
  fast-forward to the newest — keeps input→display latency low during macOS multi-Mbps bursts. The stall
  watchdog keys off decoder OUTPUT (displayed + skipped), not displayed-only, so catch-up ≠ false wedge.
- Keyframe detection: 3-byte AND 4-byte Annex-B start codes; bounded keyframe-resync.
- Color: FULL-range BT.709; input buffer sized to the frame so 1080p keyframes aren't truncated.
- **GL pass (`GlSharpenRenderer`)** applies cheap contrast+saturation; the 5-tap unsharp is off by default
  (Mali GPU can't sustain it at 1080p60 — it throttled display to ~35fps and added lag).

## History note
The earlier "sharpness is hên xui / low bitrate" theory was superseded (2026-07): the real recurring faults
were **macroblock corruption** (from dropping frames on overflow) and **lag** (from the GL unsharp GPU
bottleneck + oversized backpressure buffer). Both are fixed above. See README changelog v0.1.0-tvbox.

## Known limitation
- **Sustained high-motion video (e.g. YouTube fullscreen)** can wedge the Amlogic HW decoder beyond
  watchdog recovery → **disconnect + reconnect** the mirror to clear it. Inherent to this cheap SoC +
  the open-source AirPlay-1 path. (Matching AirScreen here would require AirPlay 2, which is not openly
  implemented.)

## Operating notes
- **Boot auto-start:** implemented (`BootReceiver` + `boot_auto_start` pref, default ON; handles
  BOOT_COMPLETED + QUICKBOOT_POWERON). **BUT the X96Air_P2 ROM does not deliver any boot broadcast to
  this sideloaded app** (verified across reboots) — so on that box the app does NOT auto-start. Fix:
  add it to the box's "auto-start / self-start" allowlist if present, or just open the app once after
  a reboot (then it runs in the background). Works automatically on ROMs that honor boot broadcasts.
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
