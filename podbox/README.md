# PodBox

Rockbox als Android-App, speziell für den **Surfans F20 Pro+** (Android 12, 480x640,
Drehrad + 3 Tasten). Fork von [Rockbox](https://github.com/Rockbox/rockbox), aufgebaut auf
dessen Android-Port (RaaA). Die Bedienung läuft komplett über die Hardware-Tasten.

## Gerät

| | |
|---|---|
| Android | 12 (SDK 31), MT6765, arm64-v8a / armeabi-v7a |
| Display | 480x640, 180 dpi |

Der Hersteller hat die Tasten in `/system/usr/keylayout/Generic.kl` umbelegt. Jede Taste
liefert trotzdem einen eigenen Keycode. Die App wertet sie im Vordergrund selbst aus,
ein System-Remap ist nicht nötig.

| Hardware | Scancode | Android-Keycode |
|---|---|---|
| Drehrad im Uhrzeigersinn | KEY_UP (103) | VOLUME_UP |
| Drehrad gegen den Uhrzeigersinn | KEY_DOWN (108) | VOLUME_DOWN |
| Rad-Button | KEY_CAMERA (212) | CAMERA |
| Taste rechts oben | KEY_VOLUMEUP (115) | MEDIA_PREVIOUS |
| Taste rechts Mitte | KEY_RIGHTALT (100) | MEDIA_PLAY_PAUSE |
| Taste rechts unten | KEY_VOLUMEDOWN (114) | MEDIA_NEXT |

## Tastenbelegung (iPod-artig)

| Taste | Listen/Menüs | Wiedergabe-Bildschirm (WPS) |
|---|---|---|
| Rad | hoch/runter | Lautstärke |
| Rad-Button | OK, lang: Kontextmenü | Hauptmenü, lang: Kontextmenü |
| oben | Zurück, lang: Hauptmenü | Browser, lang: Track zurück |
| Mitte | Play/Pause, lang (≥0,6 s): Stop | Play/Pause, lang: Stop |
| unten | Kontextmenü, lang: Quickscreen | Kontextmenü, lang: Track vor |

In den Einstellungen ändert das Rad den Wert.

## Änderungen gegenüber Rockbox

- `firmware/asm/thread-unix.c`: Unter ART führte jeder JNI-Aufruf aus einem Rockbox-Thread
  zu einem `StackOverflowError`, weil die Thread-Stacks außerhalb des Java-Thread-Stacks
  lagen. Jetzt werden die Stacks aus dem 64-MB-Stack des Java-Threads „Rockbox thread“
  geschnitten (`RockboxService.java`).
- `tools/configure`: Android-Target auf API 21 angehoben (`rand`/`srand` fehlen in API 19).
- `firmware/target/hosted/android/`: Hardware-Tasten und Drehrad, gemerkte Tastendrücke.
- `apps/keymaps/keymap-android.c`: Keymap für Drehrad und Tasten.
- `android/src/org/rockbox/RockboxFramebuffer.java`: kurz/lang für Play/Pause.
- `RockboxActivity.java`: Vollbild (Status- und Navigationsleiste ausgeblendet, immersive sticky).
- `RockboxService.java`: Themes/Fonts werden nach jedem APK-Update neu entpackt. Bisher wurde
  der 1980-Zeitstempel der Libs verglichen, deshalb kamen Updates nie an.
- `apps/settings_list.c`: Android-Standards für 480x640: Schrift 27-Adobe-Helvetica,
  Zeilenabstand 6 px statt des Touch-Abstands aus der DPI.
- `wps/WPSLIST`, `wps/cabbiev2.480x640x16.wps`, `backdrops/cabbiev2.480x640x16.bmp`,
  `wps/cabbiev2/backdrop-480x640x16.bmp`: cabbiev2-Theme für 480x640, ohne Touch-Regionen
  (verwendet die 480x800-Grafiken).

## Build

Gebaut wird in WSL2 (Arch Linux), weil das Rockbox-Buildsystem nur unter Linux/Unix läuft.

Einmalig einrichten:

```bash
pacman -S --needed base-devel wget unzip zip jdk17-openjdk python
mkdir -p ~/android && cd ~/android
wget https://dl.google.com/android/repository/android-ndk-r16b-linux-x86_64.zip
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q android-ndk-r16b-linux-x86_64.zip
mkdir -p sdk/cmdline-tools && unzip -q commandlinetools-linux-11076708_latest.zip -d sdk/cmdline-tools
mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
yes | sdk/cmdline-tools/latest/bin/sdkmanager --licenses
sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-21" "build-tools;30.0.3" "platform-tools"
```

Bauen (aus Windows Git-Bash):

```bash
MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh"      # Code
MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh" zip  # + Themes/Plugins
adb install -r podbox.apk
```

Das Build-Verzeichnis liegt in der WSL unter `~/podbox/build`, die APK landet im Repo-Root.
Das Skript signiert die APK mit `apksigner` neu, weil Android 12 die alte MD5/SHA1-Signatur
aus `android.make` ablehnt.

Die repo-lokale Git-Einstellung `core.eol=lf` ist nötig, sonst checkt Git unter Windows
mit CRLF aus und `tools/configure` läuft nicht.

## Upstream

```bash
git fetch upstream
git merge upstream/master
```

## TODO

- Bedienung bei ausgeschaltetem Bildschirm (MediaSession / VolumeProvider)
- Modernisierung: arm64, aktuelles NDK (clang), Gradle, targetSdk ≥ 31
