# PodBox

Music player for the **Surfans F20 Pro+** (Android 12, 480x640, scroll wheel and 3 buttons).
PodBox replaces the home screen and is operated entirely with the hardware controls, like an
iPod: scroll wheel, wheel button and the buttons on the right side, also with the screen off.

- Playback at the original sample rate up to 192 kHz, 32 bit all the way to the DAC, on
  headphones through the device's direct output (no Android mixer, no resampling to 48 kHz)
- WAV, FLAC, MP3, AAC (m4a), Vorbis, Opus and many more formats
- Database across internal storage and SD card (artists, albums, genres, playback statistics)
- Embedded album art or `cover.jpg`, custom theme for 480x640
- Volume in 30 steps, shared with the Android volume
- Seeking with the scroll wheel, playlists, bookmarks, equalizer, crossfade, ReplayGain

## Installation

Connect the device over USB (USB debugging enabled), then run in PowerShell from the repo:

```powershell
.\podbox\setup.ps1 -Release
```

The script downloads the APK of the latest [release](https://github.com/RadiatorTwo/PodBox/releases),
installs it and sets up the device. It can be run any number of times, including after a
factory reset (which reverts all of these settings).

| Setting | via adb |
|---|---|
| All files access | `appops set --uid com.blueshark.player MANAGE_EXTERNAL_STORAGE allow` |
| PodBox as home screen | `cmd package set-home-activity com.blueshark.player/org.rockbox.RockboxActivity` |
| No lock screen | `locksettings set-disabled true` (only without PIN/pattern) |
| Buttons with screen off | `setprop persist.sys.hw.key 1` |
| No battery optimization | `dumpsys deviceidle whitelist +com.blueshark.player` |

Other invocations:

```powershell
.\podbox\setup.ps1                     # settings only, PodBox is already installed
.\podbox\setup.ps1 -Apk .\podbox.apk   # install your own APK (local build)
.\podbox\setup.ps1 -Release -Debloat   # also remove unneeded system apps (debloat.ps1)
```

`debloat.ps1 -Restore` brings the removed system apps back. The main menu entry "Other Apps"
opens the previous home screen; the home gesture returns to PodBox.

## Controls

| Button | Lists and menus | Playback |
|---|---|---|
| Wheel | up/down | volume, in seek mode: seek |
| Wheel button | OK, long: context menu | toggle wheel volume ↔ seek, long: main menu |
| Top | back, long: main menu | previous track, long: file browser |
| Middle | play/pause, long: stop | play/pause, long: stop |
| Bottom | context menu, long: quick screen | next track, long: context menu |

In settings the wheel changes the value.

**Seeking:** In seek mode the header shows "SPULEN" instead of the clock. Each detent jumps
5 s, turning fast up to 30 s. After 5 s without turning, or on any other button, the wheel
goes back to volume.

**Screen off:**

| Button | Action |
|---|---|
| Wheel | volume |
| Top | previous track |
| Middle | play/pause, long: stop |
| Bottom | next track |

## Music

PodBox reads music from internal storage and from any SD card. The database (main menu →
Database) includes both. After adding files, choose Settings → General Settings → Database →
"Update Now". The "Start Directory" entry in a folder's context menu sets where the file
browser starts.

## Device

The package ID is `com.blueshark.player`. On headphones the firmware grants the direct DAC
output only to packages on a built-in list; all others go through the Android mixer (more load,
resampling to 48 kHz, more heat). `com.blueshark.player` is on that list.

The manufacturer remapped the buttons in `/system/usr/keylayout/Generic.kl`:

| Hardware | Scancode | Android keycode |
|---|---|---|
| Wheel clockwise | KEY_UP (103) | VOLUME_UP |
| Wheel counter-clockwise | KEY_DOWN (108) | VOLUME_DOWN |
| Wheel button | KEY_CAMERA (212) | CAMERA |
| Right side, top | KEY_VOLUMEUP (115) | MEDIA_PREVIOUS |
| Right side, middle | KEY_RIGHTALT (100) | MEDIA_PLAY_PAUSE |
| Right side, bottom | KEY_VOLUMEDOWN (114) | MEDIA_NEXT |

With the screen off, the hardware only delivers button events if `persist.sys.hw.key` is 1
(`vendor/etc/init/hw/init.mt6765.rc` writes the value to `/sys/class/key/key/state`). There is
no switch for it in the settings menu, only a quick settings tile in SystemUI. The buttons then
reach PodBox through its MediaSession.

## Build

Builds run in WSL2 (Arch Linux); the build system only runs on Linux.

One-time setup:

```bash
pacman -Syu --needed base-devel wget unzip zip jdk17-openjdk python freetype2 git perl
sed -i 's/^#en_US.UTF-8/en_US.UTF-8/' /etc/locale.gen && locale-gen   # avoids Perl locale warnings
mkdir -p ~/android && cd ~/android
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p sdk/cmdline-tools && unzip -q commandlinetools-linux-11076708_latest.zip -d sdk/cmdline-tools
mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
yes | sdk/cmdline-tools/latest/bin/sdkmanager --licenses
sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-21" "platforms;android-34" "build-tools;30.0.3" \
    "build-tools;34.0.0" "platform-tools" "ndk;27.2.12479018"
# Signing key from the backup, never generate a new one (the APK would not install over the app)
mkdir -p ~/podbox && cp /mnt/c/Users/Radi/podbox/podbox.keystore ~/podbox/
```

The repo-local Git setting `core.eol=lf` is required, otherwise Git on Windows checks out with
CRLF and `tools/configure` fails.

Build and install (from Windows Git Bash):

```bash
MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/c/!Code2/PodBox/podbox/build.sh"      # code
MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/c/!Code2/PodBox/podbox/build.sh" zip  # + themes/plugins
adb install -r podbox.apk    # or: .\podbox\setup.ps1 -Apk .\podbox.apk
```

After changing settings or texts, build with `build.sh zip`.

- Target is arm64-v8a with NDK r27 (clang). `ANDROID_ABI=armeabi` builds 32 bit; other
  variants are listed at the top of `build.sh`.
- The build directory is `~/podbox/build-arm64` inside WSL, the APK ends up in the repo root.
- Gradle builds the APK (`android/gradlew`, Gradle 8.9, AGP 8.7). Output and cache live in the
  build directory, not in the source tree.
- Signing uses `~/podbox/podbox.keystore` (alias `podbox`). If the key is missing, `build.sh`
  creates a new one, and its APKs will not install over an existing PodBox. Always restore the
  backup.
- `PODBOX_APP_ID=... build.sh` builds with a different package ID.

## Releases

Push a `v*` tag; GitHub Actions (`.github/workflows/release.yml`) runs `build.sh zip` and
attaches `podbox-<tag>.apk` to a new release:

```bash
git tag -a v1.2.0 -m "PodBox 1.2.0" && git push origin v1.2.0
```

When started manually (`workflow_dispatch`), the APK is only available as a workflow artifact.

The version name is the tag without `v` (locally `git describe`, e.g. `1.1.0-3-gabc1234`). The
`versionCode` follows from it (`x.y.z` → `x*10000 + y*100 + z`). Local builds after a release
have the same code and install over it.

The workflow signs with the same key, stored as the repo secret `PODBOX_KEYSTORE` (base64):

```bash
wsl -d archlinux -e bash -c 'base64 -w0 ~/podbox/podbox.keystore' | gh secret set PODBOX_KEYSTORE
```

## Development

Project status, fixed issues, measurements and open items are in [STATUS.md](podbox/STATUS.md)
(German).

## License

PodBox is built on the C code of [Rockbox](https://www.rockbox.org) and, like it, is licensed
under the GNU GPL v2 or later (see [docs/COPYING](docs/COPYING)).
