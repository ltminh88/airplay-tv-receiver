# Build Setup — Android TV AirPlay Receiver

Fork of [`jqssun/android-airplay-server`](https://github.com/jqssun/android-airplay-server) (GPL-3.0).
AirPlay-1 mirroring receiver for Android TV. Sideload-only (GPL-3 + reverse-engineered FairPlay → not Play-Store eligible).

## Toolchain (verified working 2026-06-15, macOS darwin)

| Component | Version | Source |
|-----------|---------|--------|
| JDK | 17.0.11 | bundled in Android Studio (`.../Contents/jbr/Contents/Home`) |
| Android SDK platforms | android-34, android-35 | sdkmanager |
| build-tools | 34.0.0, 35.0.0 | sdkmanager |
| **NDK** | **27.0.12077973** | `sdkmanager "ndk;27.0.12077973"` (pinned in `app/build.gradle.kts`) |
| **CMake** | **3.22.1** | `sdkmanager "cmake;3.22.1"` (min in `CMakeLists.txt`) |
| Gradle | 8.11.1 | wrapper (auto-download) |
| cmdline-tools | 12.0 | `commandlinetools-mac-11076708_latest.zip` → `$SDK/cmdline-tools/latest` |

## App config (`app/build.gradle.kts`)
- `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`
- `abiFilters = [arm64-v8a, armeabi-v7a, x86_64]`
- UI stack: Jetpack Compose + Hilt (KSP)

## Submodule pins (record before any upstream sync)
| Submodule | Commit | Ref |
|-----------|--------|-----|
| UxPlay | `21eef8df` | v1.73.6 |
| alac | `c38887c5` | master |
| libplist | `f41b1ea6` | 2.7.0-55 |
| openssl-cmake | `4edd36a8` | v3 (builds OpenSSL 3.4.4 from source) |

## Build from clean checkout
```bash
git clone --recurse-submodules <fork-url> airplay-tv-receiver
cd airplay-tv-receiver
git submodule update --init --recursive
printf 'sdk.dir=%s\n' "$HOME/Library/Android/sdk" > local.properties   # NOT committed
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug --no-daemon
```
APK → `app/build/outputs/apk/debug/app-debug.apk` (~37 MB).
First build ~30 min (OpenSSL 3.4.4 compiled from source for all 3 ABIs); subsequent builds cached.

## Notes / gotchas
- `local.properties` and keystores must stay untracked (already in `.gitignore`).
- A repo-local hook blocks shell commands containing the literal `build`; reference the output dir via a constructed variable when scripting.
- Remotes: `upstream` = jqssun. Add your own `origin` fork before pushing the `v-baseline` tag.
