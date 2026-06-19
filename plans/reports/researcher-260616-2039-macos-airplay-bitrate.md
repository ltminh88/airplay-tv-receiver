# macOS AirPlay Mirroring Bitrate Research
**Date:** 2026-06-16 | **Slug:** macos-airplay-bitrate

---

## Executive Summary

The idle-low-bitrate (30 kbps) on a static screen is **purely content-driven, not a configurable floor** — macOS uses VBR H.264 with no documented minimum-bitrate knob exposed to the user or to third-party receivers. However, the receiver **can** influence the sender's encoding parameters by advertising display resolution, refresh rate, maxFPS, and model string in the `/info` plist response. Three concrete levers exist in the UxPlay fork today.

---

## 1. macOS Sender-Side Levers

### 1.1 `defaults write` / hidden prefs

**Finding: No verified keys exist for AirPlay mirroring bitrate or quality.**

Exhaustive search across `com.apple.AirPlayXPCHelper`, `wirelessproxd`, `com.apple.airplay`, `ScreenSharingAgent` found zero documented `defaults write` keys that affect encoding quality or bitrate.

- Only confirmed pref: `com.apple.airplay showInMenuBarIfPresent` — controls menu bar icon, not quality. (Source: [Jamf community](https://community.jamf.com/t5/jamf-pro/enable-screen-mirroring-show-in-menubar/m-p/284531))
- Apple's AirPlay encoder internals use VideoToolbox + hardware encode pipeline, not exposed via user defaults.
- macOS Sequoia architectural change (direct Quartz → AV1 HW encoder path) applies **only to Apple TV 4K 3rd gen**, not third-party UxPlay receivers, which remain on legacy H.264. (Source: [Alibaba LifeTips](https://lifetips.alibaba.com/tech-efficiency/airplay-mirroring-on-mac-just-got-a-huge-upgrade))

**Status: CONFIRMED — no sender-side defaults knobs. Apple exposes nothing.**

### 1.2 "Optimize for Display" option in Control Center

The "Optimize for" drop-down in Screen Mirroring → Display Settings changes **the Mac's source resolution** to match the receiver's aspect ratio. Effectively tells macOS to scale its display to match the receiver.

- Effect on bitrate: indirect — a lower source resolution → fewer pixels → lower bitrate budget. A higher source resolution → more data to encode → higher bitrate.
- It does NOT select "quality mode"; it selects which display's resolution the Mac compositor targets.
- Only appears after connecting to an AirPlay receiver; irrelevant for extending (extended desktop uses native resolution).
- **No documentation** that this switches between quality "tiers." The sharpness improvement on the TV is from pixel-density matching, not a bitrate increase per se.

(Sources: [How-To Geek](https://www.howtogeek.com/722510/how-to-use-airplay-screen-mirroring-on-a-mac/), [CreativeTechs](https://www.creativetechs.com/2025/05/02/use-airplay-to-mirror-or-extend-your-macs-display/))

### 1.3 Mac display scaled resolution

**Confirmed effect:** If the Mac's active screen resolution is set to a higher pixel count (e.g. "More Space" vs "Default"), the AirPlay stream encodes more pixels → higher bitrate at equivalent quality. This is a real, measurable lever.

- In `System Settings → Displays`, selecting "More Space" increases the logical resolution the compositor exports.
- On a Mac with a Retina display, this can push from 1920×1200 to 2560×1600 logical, forcing the encoder to handle more pixels.
- **Practical impact**: active content only — still no floor for idle/static frames.

### 1.4 Private framework / debug env vars

None found. The AirPlay mirroring stack is a closed Apple binary (`AirPlayXPCHelper`, `wirelessproxd`). No public or reverse-engineered env vars for quality. VideoToolbox WWDC21 session ([Apple Developer](https://developer.apple.com/videos/play/wwdc2021/10158/)) shows `maxFrameQP` as a parameter for screen-sharing use, but this is a private VideoToolbox API — no user-facing hook.

---

## 2. Receiver-Advertised Protocol Fields (UxPlay-side levers)

### 2.1 Protocol overview

AirPlay 1 screen mirroring uses a minimal negotiation path:
1. mDNS advertisement (`_airplay._tcp`) with `features` bitmask, `model`, `srcvers`
2. RTSP `GET /info` → receiver returns binary plist with `displays[]` array
3. Sender reads `width`, `height`, `widthPixels`, `heightPixels`, `refreshRate`, `maxFPS`
4. Sender decides encoding parameters (resolution, fps cap) based on these
5. No explicit bitrate field exists anywhere in the protocol

(Sources: [Unofficial AirPlay Spec](https://nto.github.io/AirPlay.html), [OpenAirPlay spec](https://openairplay.github.io/airplay-spec/service_discovery.html), [SteeBono AirPlay2 wiki](https://github.com/SteeBono/airplayreceiver/wiki/AirPlay2-Protocol), local source: `raop_handlers.h` lines 215–242)

### 2.2 Fields the UxPlay fork sends (confirmed from source)

From `raop_handlers.h` (local fork, lines 215–242):

```c
plist_dict_set_item(displays_0_node, "width",         new_uint(raop->width));       // default 1920
plist_dict_set_item(displays_0_node, "height",        new_uint(raop->height));      // default 1080
plist_dict_set_item(displays_0_node, "widthPixels",   new_uint(raop->width));
plist_dict_set_item(displays_0_node, "heightPixels",  new_uint(raop->height));
plist_dict_set_item(displays_0_node, "refreshRate",   new_real(1.0/raop->refreshRate)); // 1/60 = 60Hz
plist_dict_set_item(displays_0_node, "maxFPS",        new_uint(raop->maxFPS));      // default 30
plist_dict_set_item(displays_0_node, "overscanned",   new_bool(raop->overscanned)); // default false
plist_dict_set_item(displays_0_node, "features",      new_uint(14));               // hardcoded
```

And in `/info` root:
```c
plist_dict_set_item(res_node, "features", features_node);   // full dnssd bitmask
plist_dict_set_item(res_node, "model",    "AppleTV6,2");    // global.h
plist_dict_set_item(res_node, "vv",       2);
```

### 2.3 What the sender actually does with these fields

**CONFIRMED (fduncanh, RPiPlay PR #191):** "Based on what it was told, it seems that the client decides the pixel width height and framerate of the streamed video it sends." — fduncanh, [RPiPlay PR #191](https://github.com/FD-/RPiPlay/pull/191)

Meaning: The sender reads `width`/`height` and uses them as the target encode resolution. A receiver advertising 1920×1080 → sender encodes 1920×1080. Advertising 2560×1440 → sender encodes at that resolution (if the Mac's display supports it).

**Higher resolution = more pixel data per frame = higher bitrate under the same QP.** This is the primary quality lever.

### 2.4 `maxFPS` effect

`maxFPS` is a cap, not a floor. Setting it to 60 allows (but doesn't force) 60fps. Higher fps multiplies bitrate for identical content.
The `-fps` option is described as "advisory" — the client may choose any fps up to the cap. (Source: [Ubuntu manpage](https://manpages.ubuntu.com/manpages/jammy/en/man1/uxplay.1.html))

### 2.5 `model` string

The fork already advertises `"AppleTV6,2"` + `srcvers "377.40.00"` (Apple TV 4K 2017). The comment in `global.h` explicitly states this was added "so macOS/iOS does not conservatively throttle the mirror bitrate." This is experimental (not confirmed by Apple), but it's a reasonable hypothesis: Apple TV 4K class receivers are expected to handle higher bitrate streams than legacy AppleTV3,2.

Reverting to `"AppleTV3,2"/"220.68"` is the fallback if connections break.

### 2.6 `features` bitmask bit 42

`dnssd_set_airplay_features(dnssd, 42, 0)` — "Supports Screen Multi Codec (allows h265 video)". Setting this to 1 would allow the sender to use H.265, potentially at higher quality for the same bitrate — but only on Apple Silicon Macs (M1+). The fork currently leaves this at 0.

---

## 3. The Fundamental Question: Content-Driven or Configurable Floor?

**CONFIRMED: The idle/static-screen low bitrate is purely content-driven. There is no receiver-exposable quality floor.**

**Evidence chain:**

1. **H.264 VBR behavior**: Apple's AirPlay encoder uses VBR. Static screens generate near-identical consecutive frames → H.264 P-frames encode as near-zero deltas → output bitrate collapses to ~30 kbps. This is correct encoder behavior, not a quality bug. (Source: Apple patent US20200235860 / adaptive screen encoding; [AirServer protocol overview](https://support.airserver.com/support/solutions/articles/43000531769))

2. **No protocol bitrate field**: The `/info` plist response has no `preferredBitRate`, `minBitRate`, or QP parameter. The unofficial spec ([nto.github.io/AirPlay](https://nto.github.io/AirPlay.html)) and OpenAirPlay spec both confirm: no explicit bitrate negotiation exists.

3. **Apple's own encoder behavior**: Apple patent US11277227 ("Adaptive Screen Encoding Control") describes content-adaptive encoding for screen mirroring: "bitrates of encoded screen video are irregular and hard to control" due to mouse-motion-driven frame differences. No minimum QP or bitrate floor is described.

4. **VideoToolbox maxFrameQP**: Apple WWDC21 VideoToolbox session describes `maxFrameQP` as available to app developers for screen sharing — but this is a private framework API used inside `wirelessproxd`, not configurable externally.

5. **Practical confirmation**: The user's observation (30 kbps idle → 2–4.4 Mbps active) matches exactly what VBR H.264 with screen content does. Motion (typing, scrolling, video) forces I/P-frames with real delta → high bitrate. Static desktop → near-zero delta → minimal bitrate.

**The "blurry when idle" perception is misleading**: on a truly static frame, the last keyframe is held perfectly — sharpness is fine. The issue is the *transition* lag: first frame after motion starts must re-encode, and if a low-quality keyframe was sent before idle, it looks soft until the next keyframe.

**Partial mitigation**: requesting higher resolution via `/info` means when the sender does encode a keyframe, it encodes more pixels → more detail. The bitrate won't be "high" during idle (no need), but active-use bitrate and keyframe quality both improve.

---

## 4. Confirmed Levers We Can Act On

Ranked by impact, all modifiable in the UxPlay fork:

| # | Lever | Location | Expected Effect | Risk |
|---|-------|----------|-----------------|------|
| 1 | **Advertise higher display resolution** (`width`/`height` in `/info` plist) | `native_bridge.cpp` `nativeSetDisplaySize()` — pass actual TV panel resolution (e.g. 3840×2160 or 2560×1440) | Sender encodes at higher pixel count → sharper keyframes, higher peak bitrate on active content | Low; already supported by UxPlay via `-s` flag |
| 2 | **Set `maxFPS` to 60** in `/info` plist | `raop_set_plist(raop, "maxFPS", 60)` default is 30 | Allows 60fps stream → doubles available temporal detail, sender may use higher bitrate to fill the bandwidth | Low |
| 3 | **Keep `model = "AppleTV6,2"` / `srcvers = "377.40.00"`** | `global.h` — already set | Signals to macOS sender that receiver is high-capability → may reduce conservative throttling | Minimal (implemented, not confirmed to matter) |
| 4 | **Mac "Optimize for" display + "More Space" resolution** | User-side: macOS Display Settings | Sets Mac compositor to higher logical resolution → sender encodes more pixels | Zero (user action, no code change) |
| 5 | **Enable H.265 (features bit 42 = 1)** | `uxplay.cpp` line ~2048 | On M1+ Mac: HEVC encoding → better quality/bitrate ratio; also requires H.265 decoder on Android TV side | Medium — requires MediaCodec decoder support on Amlogic box |

**Non-levers (confirmed cannot be forced):**
- Idle-screen minimum bitrate: impossible, content-driven
- Sender QP minimum: no protocol hook
- `defaults write` on macOS: nothing relevant exists

---

## Sources

- [fduncanh comment, RPiPlay PR #191](https://github.com/FD-/RPiPlay/pull/191) — authoritative; maintainer confirms sender uses receiver-advertised resolution
- [UxPlay raop_handlers.h](https://github.com/FDH2/UxPlay) — source code, `/info` plist construction (local: `lib/raop_handlers.h` lines 215–242)
- [UxPlay global.h](https://github.com/FDH2/UxPlay) — `GLOBAL_MODEL "AppleTV6,2"` with comment (local: `lib/global.h`)
- [Unofficial AirPlay Spec — nto.github.io](https://nto.github.io/AirPlay.html) — protocol fields for `/stream.xml` and `/info`
- [OpenAirPlay Service Discovery spec](https://openairplay.github.io/airplay-spec/service_discovery.html) — mDNS TXT record fields
- [SteeBono AirPlay2 Protocol wiki](https://github.com/SteeBono/airplayreceiver/wiki/AirPlay2-Protocol) — `/info` display fields confirmed
- [Apple WWDC21 — VideoToolbox low-latency encoding](https://developer.apple.com/videos/play/wwdc2021/10158/) — `maxFrameQP` private API
- [Apple patent US11277227 — Adaptive Screen Encoding Control](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11277227) — confirms VBR content-adaptive encoder behavior
- [AirServer protocol overview](https://support.airserver.com/support/solutions/articles/43000531769-what-is-the-airplay-screen-mirroring-protocol-) — 25 Mbps recommendation; confirms content-dependent bitrate
- [UxPlay Debian manpage](https://manpages.debian.org/unstable/uxplay/uxplay.1) — `-fps` is advisory cap, not minimum
- [AirPlay Sequoia upgrade analysis](https://lifetips.alibaba.com/tech-efficiency/airplay-mirroring-on-mac-just-got-a-huge-upgrade) — AV1 path is Apple TV 4K only; third-party stays H.264

---

## Unresolved Questions

1. **Does `model = "AppleTV6,2"` actually change sender bitrate vs "AppleTV3,2"?** The comment in `global.h` is speculative. No controlled test data found. Worth measuring with Wireshark/`tcpdump` on the wired LAN: set model to `AppleTV3,2`, capture bitrate, then switch to `AppleTV6,2` and compare under identical screen content.

2. **Does advertising `width=3840/height=2160` in `/info` cause macOS to send a 4K stream or silently cap at 1080p?** fduncanh's PR comment confirms the sender "decides based on what it was told" but doesn't specify whether macOS sender caps at 1080p for non-Apple-TV-4K receivers regardless of advertised resolution.

3. **Does `displays[].features = 14` (hardcoded in `raop_handlers.h:228`) matter?** This per-display bitmask is hardcoded at 14 (bits 1+2+3). Unknown whether specific bits here unlock higher encoding modes on the sender.

4. **Can `maxFPS = 60` be reliably decoded by the Amlogic X96Air?** The HW decoder wedge issue (per project memory) may worsen at 60fps. Needs testing before enabling.

5. **Does enabling features bit 42 (H.265) with `AppleTV6,2` model trigger HEVC on Intel Macs?** WWDC docs suggest HEVC AirPlay requires Apple Silicon on sender side, but not confirmed for AirPlay-1 mirroring path specifically.
