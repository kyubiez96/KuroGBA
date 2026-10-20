# KuroGBA

**Game Boy Advance, distilled.** A lightweight, premium-feeling GBA emulator
for Android — dark UI, zero ads, zero trackers, zero network permission.
Bring your own ROMs, tap, play.

![Platform](https://img.shields.io/badge/Android-26%2B-3DDC84)
![Core](https://img.shields.io/badge/core-mGBA%20(libretro)-7C6CFF)
![License](https://img.shields.io/badge/license-MPL--2.0-blue)

---

## Features (v1)

- **File-picker library** — open any `.gba` through the system file picker
  (Storage Access Framework); recent games are remembered. No ROM is ever
  bundled with the app.
- **Accurate core** — the [mGBA](https://mgba.io) libretro core, the same
  core used by RetroArch, built from a pinned upstream commit.
- **Save states** — quick slot + 5 slots per game, stored in app-private
  storage.
- **Battery saves (SRAM)** — in-game saves are detected automatically and
  persisted per game, with a "Battery saved" indicator.
- **Fast-forward** — 2× / 4× with clean audio muting and resync.
- **Cheats** — GameShark / CodeBreaker codes, one per line, applied through
  the core's cheat engine.
- **Touch controls** — slide-friendly 8-way D-pad, A/B/L/R, Start/Select,
  with adjustable opacity and haptic ticks.
- **Integer-scaled rendering** — crisp pixels, correct 3:2 aspect, letterboxed.
- **Lightweight** — two ABIs (`arm64-v8a`, `armeabi-v7a`), one small native
  host library, no analytics, no ads, **no `INTERNET` permission at all**.

### Deferred to later versions

Bluetooth/USB gamepads · screen filters & shaders · rewind · per-game
settings · GB/GBC support · cover-art library grid.

## No-ROM policy

KuroGBA ships **without any games** and never will. ROMs stay on your
device, are opened read-only through the file picker, and are loaded into
memory — the app contains no commercial ROM and the repository's
`.gitignore` hard-blocks `*.gba` and friends. Dump your own cartridges.

## Architecture

```
┌────────────────────────────────────────────────────┐
│ Kotlin UI (library, game screen, touch controls)   │
└───────────────▲───────────────────────┬────────────┘
        frames (RGB565)           JNI   │  input, states,
                │                       ▼  SRAM, cheats
┌───────────────┴────────────────────────────────────┐
│ libkurogba.so — tiny C libretro host               │
│  • dlopen()es the core, drives retro_run()         │
│  • command queue: all state ops run on the emu     │
│    thread between frames (libretro thread contract)│
│  • audio ring buffer → AudioTrack                  │
└───────────────▲────────────────────────────────────┘
                │ retro_* API
┌───────────────┴────────────────────────────────────┐
│ libmgba_libretro.so — mGBA core (git submodule,    │
│ pinned) built with CMake -DBUILD_LIBRETRO=ON       │
└────────────────────────────────────────────────────┘
```

## Building

Requirements: JDK 17, Android SDK (platform 34), NDK r26, CMake 3.22.

```bash
git clone --recurse-submodules https://github.com/kyubiez96/KuroGBA.git
cd KuroGBA
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/26.3.11579264"
bash tools/build-native.sh        # builds core + host into app/src/main/jniLibs/
./gradlew assembleRelease         # → app/build/outputs/apk/release/app-release.apk
```

GitHub Actions builds the same APK on every push and uploads it as the
`KuroGBA-release-apk` artifact; pushing a `v*` tag also attaches the APK to
a GitHub Release.

### Signing (sideload builds)

Release builds are signed with the standard Android **debug key** — the
same trick as KuroPatch v2 — so a CI-built APK can be sideloaded directly
and rebuilt byte-compatibly from source by anyone. The keystore is
generated locally/CI-side and is **never committed** (see `app/build.gradle.kts`,
override the path with the `KUROGBA_KEYSTORE` env var). This is a sideload
distribution, not a Play Store one.

## License & credits

KuroGBA is licensed under the **Mozilla Public License 2.0** (see
[LICENSE](LICENSE)).

The emulation core is **[mGBA](https://github.com/mgba-emu/mgba)** by
Jeffrey Pfau (Vicki Pfau) and contributors, also MPL-2.0, vendored as a
git submodule from the [libretro fork](https://github.com/libretro/mgba)
at a pinned commit — no core code is modified. Game Boy Advance is a
trademark of Nintendo; this project is not affiliated with Nintendo or
with the mGBA project.
