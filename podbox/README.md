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
| Rad | hoch/runter | Lautstärke, im Seek-Modus Spulen (5 s pro Raste, schnell bis 30 s) |
| Rad-Button | OK, lang: Kontextmenü | Rad umschalten Lautstärke ↔ Seek, lang: Hauptmenü |
| oben | Zurück, lang: Hauptmenü | Browser, lang: Track zurück |
| Mitte | Play/Pause, lang (≥0,6 s): Stop | Play/Pause, lang: Stop |
| unten | Kontextmenü, lang: Quickscreen | Kontextmenü, lang: Track vor |

In den Einstellungen ändert das Rad den Wert.

**Bildschirm aus** (Tasten kommen über die MediaSession, nicht über die Activity):

| Taste | Aktion |
|---|---|
| Rad | Lautstärke (Android-Stream, wird in Rockbox übernommen) |
| oben | Track zurück |
| Mitte | Play/Pause, lang: Stop |
| unten | Track vor |

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
- `Helper/PodBoxMediaSession.java`: MediaSession für die Media-Tasten bei ausgeschaltetem
  Bildschirm. Android 12 schickt dem alten `registerMediaButtonEventReceiver`-Receiver
  Broadcasts ohne KeyEvent, das hat zu NullPointerException-Abstürzen geführt.
- `pcm-android.c`, `system-android.c`: globale statt lokaler JNI-Referenzen. Der
  Java-Audio-Thread rief `stop()` auf einer ungültigen lokalen Referenz auf, das führte zu
  einem CheckJNI-Abbruch bei Pause oder Track-Wechsel.
- Keine Text-Relocations mehr (Android-Dialog „Detected problems with app native
  libraries“): `-fPIC` und `-Wl,-z,text` in `tools/configure`, PC-relative Sprungtabelle in
  `lib/rbcodec/dsp/dsp_arm.S`, Unwarminder/Backtrace für Android deaktiviert
  (`tools/root.make`, `config/android.h`).
- `apps/SOURCES`: Android nutzt den nativen Rockbox-Ja/Nein-Dialog (`gui/yesno.c`) statt
  eines Android-`AlertDialog`. Der Android-Dialog ließ sich nicht mit den Tasten bedienen
  und hatte keinen Timeout. Rad-Button = Ja, jede andere Taste = Nein.
- `apps/tagcache.c`: Datenbank
  - Application-Builds haben weder Dircache noch RAM-Cache, deshalb wurde die Datenbank erst
    beim nächsten Start übernommen. Jetzt holt sich `commit()` per `malloc` einen eigenen
    Puffer und übernimmt sofort.
  - `find_entry_disk()` löst den Pfad auf Android nicht mehr mit `realpath()` auf. `/sdcard`
    ist ein Symlink, deshalb passte kein Eintrag. Folge war, dass „Jetzt aktualisieren“ jede
    Datei doppelt aufnahm und die Laufzeitstatistiken ihre Einträge nie fanden.
  - SD-Karte: `tagcache_build()` hängt auf Android jede Wechselkarte (`/storage/XXXX-XXXX`
    aus `/proc/self/mounts`) an die Scan-Pfade an. `/storage` selbst darf eine App nicht
    auflisten. Die Volume-ID der Karte muss man dafür nicht kennen.
- Größere Darstellung: Schrift 35-Adobe-Helvetica und 32x32-Icons (`apps/settings_list.c`,
  `wps/WPSLIST`). Eigene Statusleiste `wps/cabbiev2.480x640x16.sbs`, 32 px hoch, Icons aus
  `classic_statusbar` 1,5-fach vergrößert (`wps/cabbiev2/sb-*-480x640x16.bmp`, 1-Bit wie die
  Originale). Die eingebaute Statusleiste ist nur 8 px hoch. `wps/wpsbuild.pl`: Ein `sbs` für
  eine bestimmte Auflösung hat Vorrang vor `SBS: no`. WPS an die größere Schrift angepasst.
- `apps/tree.c`: Kann der Browser den Elternordner nicht öffnen (`/storage`,
  `/storage/emulated`), führt Zurück ins Hauptmenü. Vorher fiel Rockbox auf die
  Android-Wurzel `/` zurück.
- NDK r27 (clang) statt r16b (GCC 4.9): `tools/configure` legt Wrapper mit den alten GCC-Namen
  um clang/ld.lld/llvm-* an (`android-toolchain/bin`). Dazu kleine Anpassungen für clangs
  Assembler (`config.h`, `functions.make`, `asl` → `lsl` in ARMv6-Asm) und cRSID ohne
  verschachtelte Funktionen.
- arm64-v8a (`ANDROID_ABI=arm64-v8a`, Standard in `build.sh`). Die Codecs werden aus
  `nativeLibraryDir` geladen (`RockboxService` setzt `ROCKBOX_BINDIR`, `filesystem-app.c`),
  der alte Link `/data/data/org.rockbox/lib` zeigte nach dem Wechsel noch auf `lib/arm`.
- APK per Gradle (`android/build.gradle`, Wrapper 8.9, AGP 8.7) statt `android.make`. Der native
  Teil bleibt bei `configure` + `make`; `make libs` legt librockbox, Codecs und `libmisc.so`
  (= rockbox.zip) nach `<build>/libs/<abi>`, Gradle packt sie als `jniLibs` dazu
  (`useLegacyPackaging`, weil Rockbox die Libs aus `nativeLibraryDir` liest). `package`,
  Version und `uses-sdk` stehen nicht mehr im Manifest; `android.make` setzt sie für den alten
  Weg (`PODBOX_APK=make`) per sed wieder ein.
- Theme `podbox` (Standard): `wps/podbox.480x640x16.{wps,sbs}`, Grafiken aus
  `podbox/theme/mkassets.py`, Inter-Schriften aus `podbox/mkfonts.sh`. Code dazu: Listen-Karte
  (`STYLE_CARD` in `apps/gui/line.c`, `bitmap/list.c`), `%Vs(fill, …)`
  (`skin_parser.c`, `tag_table.c`), Backdrops ohne Dithering (`backdrop.c`), Skin-Bilder nur bei
  Bedarf neu zeichnen (`skin_render.c`). Details in STATUS.md, Abschnitt Design.

## Build

Gebaut wird in WSL2 (Arch Linux), weil das Rockbox-Buildsystem nur unter Linux/Unix läuft.

Einmalig einrichten:

```bash
pacman -S --needed base-devel wget unzip zip jdk17-openjdk python
mkdir -p ~/android && cd ~/android
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p sdk/cmdline-tools && unzip -q commandlinetools-linux-11076708_latest.zip -d sdk/cmdline-tools
mv sdk/cmdline-tools/cmdline-tools sdk/cmdline-tools/latest
yes | sdk/cmdline-tools/latest/bin/sdkmanager --licenses
sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-21" "build-tools;30.0.3" "platform-tools" \
    "ndk;27.2.12479018"
```

Bauen (aus Windows Git-Bash):

```bash
MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh"      # Code
MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh" zip  # + Themes/Plugins
adb install -r podbox.apk
```

Gebaut wird mit NDK r27 (clang) für arm64-v8a, das Build-Verzeichnis liegt in der WSL unter
`~/podbox/build-arm64`, die APK landet im Repo-Root. Andere Varianten über Umgebungsvariablen
(siehe Kopf von `build.sh`): `ANDROID_ABI=armeabi` für 32 Bit, NDK r16b (GCC 4.9) über
`ANDROID_NDK_PATH`.
Die APK baut Gradle (`android/gradlew`, lädt beim ersten Lauf Gradle 8.9 und das Android
Gradle Plugin). Gradle-Ausgaben und -Cache liegen im Build-Verzeichnis (`gradle/`,
`gradle-cache/`), nicht im Quellbaum. Signiert wird mit `~/podbox/podbox.keystore`
(Alias `podbox`), den `build.sh` beim ersten Lauf anlegt.
Releases baut GitHub Actions (`.github/workflows/release.yml`): Ein Tag `v*` pushen, dann baut
der Workflow `build.sh zip` auf Ubuntu und hängt `podbox-<tag>.apk` an ein neues Release.
Manuell gestartet (`workflow_dispatch`) gibt es die APK nur als Workflow-Artefakt. Damit die APK
über die installierte App passt, braucht der Workflow denselben Schlüssel als Secret
`PODBOX_KEYSTORE` (base64 von `~/podbox/podbox.keystore`):
`wsl -d archlinux -e bash -c 'base64 -w0 ~/podbox/podbox.keystore' | gh secret set PODBOX_KEYSTORE`.
`build.sh` nimmt dafür `ANDROID_SDK_PATH`, `PODBOX_KEYSTORE` und `PODBOX_VERSION` aus der Umgebung.
Mit `PODBOX_APK=make` läuft noch der alte Weg über `android.make`; dann signiert das Skript
mit `apksigner` neu, weil Android 12 die alte MD5/SHA1-Signatur ablehnt.

Die Paket-ID ist `com.blueshark.player` (nicht `org.rockbox`). Die Firmware des F20 Pro+ leitet am Kopfhörer nur Pakete von ihrer fest eingebauten Liste auf den direkten Ausgang zum DAC, alle anderen zwingt sie auf den primären Mixer (mehr CPU-Last, Umrechnung auf 48 kHz). Andere ID: `PODBOX_APP_ID=... build.sh` (setzt die Pfade in `tools/configure` und die `applicationId` in Gradle).

Die App zielt auf `targetSdk` 34. Rockbox liest Dateien mit C-`open()`, dafür braucht sie
„Zugriff auf alle Dateien“ (`MANAGE_EXTERNAL_STORAGE`). Beim ersten Start öffnet die App die
passende Einstellungsseite; nach dem Erlauben und Zurückkehren startet Rockbox. Per adb geht es
auch direkt:

```bash
adb shell appops set --uid com.blueshark.player MANAGE_EXTERNAL_STORAGE allow
```

Die repo-lokale Git-Einstellung `core.eol=lf` ist nötig, sonst checkt Git unter Windows
mit CRLF aus und `tools/configure` läuft nicht.

## Upstream

```bash
git fetch upstream
git merge upstream/master
```
