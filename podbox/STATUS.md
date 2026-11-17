# PodBox – Projektstand

Stand: 17.11.2026 abends, `main` (github.com/RadiatorTwo/PodBox), Etappe 3 (Gradle) fertig,
Wärme-Fix (deep_buffer) committet. Auf dem Gerät installiert: 64-Bit-Build (arm64-v8a, NDK r27,
APK per Gradle) mit dem deep_buffer-Fix. Die Wiedergabe ist pausiert, das Gerät kühlt ab.

## Ziel

Rockbox als Android-App für den Surfans F20 Pro+ (Android 12, 480x640, MT6765),
bedient wie ein echtes Rockbox-Gerät, komplett über die Hardware: Drehrad, Rad-Button und
3 Tasten rechts. Basis ist der originale Rockbox-C-Code mit seinem Android-Port, kein Nachbau.

## Was funktioniert (auf dem Gerät getestet)

| Bereich | Stand |
|---|---|
| Start und Laufzeit | Rockbox startet und läuft stabil unter Android 12, als 64-Bit-App (arm64-v8a) |
| Darstellung | Vollbild ohne Android-Leisten, cabbiev2-Theme in 480x640, Schrift 35 px, 32x32-Icons, eigene 32-px-Statusleiste |
| Wiedergabe | Abspielen, Pause, Track vor/zurück, Lautstärke |
| Formate | WAV, MP3, FLAC, AAC (m4a), Vorbis, Opus spielen ab |
| Album-Art | eingebettet (MP3, FLAC, m4a) und `cover.jpg` im Ordner |
| Datenbank | Neu generieren und Aktualisieren direkt übernommen, keine Duplikate, Browsen und Abspielen |
| SD-Karte | wird automatisch gescannt (185 Titel in < 5 s), Browser startet in `/storage/FEF0-07FF/Music/` |
| Ja/Nein-Dialoge | nativer Rockbox-Dialog, per Tasten bedienbar, mit Timeout |
| Wiedergabe-Bildschirm (WPS) | eigenes Layout für 480x640, Lautstärke-Overlay beim Drehen |
| Hardware-Tasten (App im Vordergrund) | Drehrad, Rad-Button, oben/Mitte/unten mit kurz/lang |
| Bildschirm aus | Rad = Lautstärke, oben/unten = Track, Mitte = Play/Pause/Stop |
| App-Updates | Themes und Fonts werden nach jeder Installation neu entpackt |
| Native Libs | keine text relocations mehr, die Android-Warnung beim Start ist weg |

Die Tests liefen über `adb shell input keyevent`. Mit den echten Tasten hat der User die
Bedienung im Vordergrund und bei ausgeschaltetem Bildschirm bestätigt.

Die Android-Displaysperre ist auf dem Gerät abgeschaltet (`adb shell locksettings set-disabled true`).
Nach dem Einschalten des Bildschirms steht Rockbox direkt vorne.

## Tastenbelegung

Der Hersteller hat die Tasten in `/system/usr/keylayout/Generic.kl` umbelegt. Jede Taste
liefert trotzdem einen eigenen Keycode, ein System-Remap oder Root ist nicht nötig.

| Hardware | Keycode | Listen/Menüs | Wiedergabe-Bildschirm | Bildschirm aus |
|---|---|---|---|---|
| Rad im Uhrzeigersinn / dagegen | VOLUME_UP / VOLUME_DOWN | runter / hoch | Lautstärke | Lautstärke |
| Rad-Button | CAMERA | OK, lang: Kontext | Hauptmenü, lang: Kontext | – |
| oben | MEDIA_PREVIOUS | Zurück, lang: Hauptmenü | Browser, lang: Track zurück | Track zurück |
| Mitte | MEDIA_PLAY_PAUSE | Play/Pause, lang: Stop | Play/Pause, lang: Stop | Play/Pause, lang: Stop |
| unten | MEDIA_NEXT | Kontext, lang: Quickscreen | Kontext, lang: Track vor | Track vor |

## Behobene Probleme des alten Android-Ports

1. **StackOverflowError bei JNI-Aufrufen (ART):** Die Rockbox-Thread-Stacks werden jetzt
   aus dem 64-MB-Java-Thread-Stack geschnitten (`firmware/asm/thread-unix.c`).
2. **Fehlende `rand`/`srand`:** Android-Target von API 19 auf 21 angehoben (`tools/configure`).
3. **Signatur:** Android 12 lehnt MD5/SHA1-Signaturen ab, deshalb signiert `podbox/build.sh`
   mit `apksigner` neu.
4. **Ressourcen nach Update nicht neu entpackt:** Der Port hat Zeitstempel verglichen, und die
   Libs aus der APK tragen immer 1980. Jetzt wird `lastUpdateTime` verglichen (`RockboxService.java`).
5. **Absturz durch Media-Buttons:** Android 12 liefert dem Legacy-Receiver kein KeyEvent.
   Ersetzt durch eine MediaSession (`Helper/PodBoxMediaSession.java`).
6. **CheckJNI-Abbruch bei Pause/Track-Wechsel:** lokale JNI-Referenzen wurden im Audio-Thread
   benutzt. Jetzt globale Referenzen (`pcm-android.c`, `system-android.c`).
7. **Text relocations:** `-fPIC` und `-z text`, PC-relative Sprungtabelle in `dsp_arm.S`,
   Unwarminder/Backtrace für Android deaktiviert.
8. **Touch-Layout:** Zeilenabstand 6 px statt DPI-basiertem Touch-Abstand, Schrift 27 px
   (`apps/settings_list.c`).
9. **Ja/Nein-Dialog:** Der Android-`AlertDialog` ließ sich nicht mit den Tasten bedienen, hatte
   keinen Timeout und brachte die Android-Leisten zurück. Jetzt nutzt Android den nativen
   Rockbox-Dialog (`apps/SOURCES` → `gui/yesno.c`).
10. **Datenbank erst nach Neustart übernommen:** Ohne Dircache/RAM-Cache hatte `commit()` keinen
    Puffer. Jetzt gibt es einen per `malloc` (`apps/tagcache.c`).
11. **Datenbank-Duplikate:** `find_entry_disk()` hat Pfade mit `realpath()` aufgelöst
    (`/sdcard` → `/storage/emulated/0`), gespeichert waren sie unaufgelöst. Deshalb wurde nichts
    gefunden: Jedes Aktualisieren hat alle Dateien neu aufgenommen, und die Statistiken
    (Wiedergabezähler) fanden ihre Einträge nicht. Auf Android wird jetzt nicht mehr aufgelöst.
12. **SD-Karte nicht in der Datenbank:** Gescannt wurde nur `/sdcard`. Jetzt werden alle
    Wechselkarten aus `/proc/self/mounts` mitgescannt (`apps/tagcache.c`). Das Startverzeichnis
    des Browsers steht in `config.cfg` auf `/storage/FEF0-07FF/Music/` (nur auf dem Gerät gesetzt,
    nach einem Zurücksetzen der Einstellungen neu eintragen, ebenso `lang: /.rockbox/langs/deutsch.lng`).
    Fehlt die Karte, fällt Rockbox von selbst auf `/` zurück.
13. **Zurück im Browser landete in `/`:** Die Elternordner `/storage` und `/storage/emulated`
    darf eine App nicht öffnen, Rockbox fiel dann auf die Android-Wurzel zurück. Jetzt gilt ein
    nicht lesbarer Elternordner als oberste Ebene, Zurück führt ins Hauptmenü (`apps/tree.c`).
14. **Codecs unter 64 Bit nicht gefunden:** Der Link `/data/data/org.rockbox/lib` zeigte nach dem
    ABI-Wechsel weiter auf `lib/arm`. `RockboxService` setzt jetzt `ROCKBOX_BINDIR` auf
    `nativeLibraryDir`, `handle_special_dirs()` (`filesystem-app.c`) nutzt das.

15. **Gerät wird bei der Wiedergabe warm:** Android 12 legte den Rockbox-Track auf den
    Low-Latency-Ausgang (`PRIMARY|FAST`, 256-Frame-HAL-Perioden, FastMixer, ca. ½ Kern
    Systemlast). `RockboxPCM` umhüllt den `AudioTrack` jetzt und baut ihn per Builder mit
    `PERFORMANCE_MODE_POWER_SAVING`, damit läuft er über `deep_buffer` (2048 Frames).
    Rockbox selbst braucht nur 2–7 % eines Kerns. Es bleiben ca. 20 % eines Kerns im
    Android-Mixer für das Umrechnen von 44,1 auf 48 kHz (Lautsprecher läuft fest mit 48 kHz).
## Modernisierung

| Etappe | Stand |
|---|---|
| 1. NDK r27 (clang) statt r16b (GCC 4.9) | fertig, Commit `9f34d8cf78` |
| 2. arm64-v8a als Standard | fertig, Commit `d182553d70` |
| 3. Gradle statt `android.make` für Java/APK | fertig, Commit `5d86cc83d2` |
| 4. `targetSdk` ≥ 31 (34 angepeilt) | offen |

**Etappe 3, umgesetzt:** Der native Teil bleibt bei `configure` + `make`. `build.sh` ruft
`make libs` und dann `android/gradlew assembleDebug` auf (Gradle 8.9, AGP 8.7.3,
`compileSdk` 34, `minSdk`/`targetSdk` 21). Die `.so` aus `<build>/libs/arm64-v8a` kommen als
`jniLibs` dazu, mit `useLegacyPackaging` (Libs werden beim Installieren entpackt, Rockbox liest
sie aus `nativeLibraryDir`). Signiert wird mit dem PodBox-Keystore, `adb install -r` geht ohne
Neuinstallation. Gradle-Ausgaben liegen in `~/podbox/build-arm64/gradle`. Auf dem Gerät getestet:
Start, Menü, Ja/Nein-Dialog, Browser, FLAC-Wiedergabe mit Album-Art. Der alte Weg geht noch mit
`PODBOX_APK=make`. `targetSdk` ist dabei von 5 (implizit, `minSdkVersion=5`) auf 21 gestiegen.

**Etappe 4, was bei targetSdk ≥ 31 nötig wird:**
- `android:exported` an allen Activities/Services/Receivern mit Intent-Filter
- `PendingIntent.FLAG_IMMUTABLE` (Widgets, Notification, MediaSession)
- Foreground-Service mit Typ `mediaPlayback` (`FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`), Notification-Channel (`RunForegroundManager.java`)
- `POST_NOTIFICATIONS` (ab 33)
- Speicherzugriff: Ab `targetSdk` 30 gibt es kein `requestLegacyExternalStorage` mehr. Rockbox
  liest Dateien per C-`open()`, dafür braucht es `MANAGE_EXTERNAL_STORAGE` ("Zugriff auf alle
  Dateien", einmal in den Einstellungen erlauben). Das ist der heikelste Punkt, unbedingt
  zuerst testen. Bisher läuft die App mit `targetSdk=21` im Legacy-Modus.
- Nebenbei: `android:debuggable="true"` aus dem Manifest, `versionCode`/`versionName` pflegen

## Wärme und Audio-Hardware (Stand 17.11.)

Messungen (Prozessor-Temperatur `thermal_zone1` = mtktsAP, Akku `thermal_zone0`, je 3 min,
Bildschirm an, Lautsprecher):
- Ohne Rockbox: 48 → 43 °C. Rockbox im Leerlauf: 43 → 40 °C (kühlt weiter ab).
- Wiedergabe vor dem Fix: 40 → 47 °C, Systemlast ca. 8,9 % (audioserver AudioOut 22 %,
  FastMixer 10 %, HAL-writer 15 %).
- Wiedergabe mit deep_buffer: konstant 50–51 °C (Start war schon warm, kein fairer Vergleich),
  Systemlast ca. 7,1 %, davon `AudioOut_15` ca. 20 % eines Kerns (Resampling 44,1 → 48 kHz,
  ca. 7 ms pro 2048-Frame-Block, keine Effektketten). BesLoudness läuft im HAL.
- Die ersten Werte stammen aus einer Testsession (USB, Bildschirm dauernd an, viele
  Installationen). Nächster Schritt: Gerät abgekühlt normal nutzen und Temperatur mitloggen.

Hardware-Decoder bringen nichts: Die `OMX.MTK.AUDIO.DECODER.*` sind Software-Decoder auf
denselben ARM-Kernen, einen `COMPRESS_OFFLOAD`-Ausgang gibt es nicht. Das Dekodieren in
Rockbox kostet ohnehin nur wenige Prozent.

- Dual-DAC ES9018 (laut User), nur am Kopfhörer. Mixport `wired_headphone_direct` (DIRECT, PCM 16/32 Bit, 8–384 kHz) nur für
  Kopfhörer; Hersteller-Property `sys.audio.wired_direct_focus_uid` zeigt auf die App mit Fokus.
  Lautsprecher: `primary`/`deep_buffer`, 44,1/48 kHz, Mixer läuft mit 48 kHz.
- Rockbox: Decoder/DSP/Mixer 16 Bit, Raten bis 192 kHz möglich (`SAMPR_CAP_ALL_192`,
  „Wiedergabefrequenz: Auto“), Android-Port derzeit fest 44,1 kHz. Natives DSD/384 kHz/24 Bit
  nur mit tiefem Umbau.
- Mögliche nächste Schritte für Effizienz/Klang (noch nicht entschieden):
  1. Kopfhörer: Originalrate bis 192 kHz über `wired_headphone_direct` (HW_SAMPR_CAPS
     erweitern, AudioTrack bei Ratenwechsel neu anlegen, Wiedergabefrequenz Auto). Kein Mixer,
     kein Resampling, bester Klang. Braucht eingesteckten Kopfhörer zum Testen.
  2. Lautsprecher: Rockbox gibt 48 kHz aus (eigener, billiger Resampler). Klang bei 44,1-kHz-
     Dateien vom User anhören lassen.
  3. Bildschirm-Timeout im Wiedergabe-Bildschirm kurz halten.
- Nach dem RockboxPCM-Umbau noch nicht getestet: Pause, Track-Wechsel, Lautstärke, Stop.

## Offene Fragen an den User (Stand 17.11.)

- Tasten mit dem Gradle-Build (targetSdk 21): vom User bestätigt.
- ANR bei Taste direkt nach Bildschirm-aus: Mit adb-injizierten Tasten ohne Displaysperre
  reproduzierbar ("Input dispatching timed out, no focused window", Rockbox wird beendet).
  Tritt auch mit dem alten GCC-Build auf. Vermutlich nur ein adb-Artefakt: `PhoneWindowManager`
  gibt *injizierte* Tasten bei aus­geschaltetem Bildschirm an die App, echte gehen an die
  MediaSession. Mit Displaysperre kein ANR. `FLAG_SHOW_WHEN_LOCKED` hilft nicht (verworfen).
  Der User soll mit den echten Tasten testen: Bildschirm aus, sofort "unten", nach 10 s an.
- Klangqualität der clang/arm64-Builds vom User anhören lassen (per adb nicht prüfbar).

## Build-Umgebung

- WSL2 `archlinux` (Standardshell fish, deshalb immer `wsl -d archlinux -e bash ...`)
- NDK r27 (clang 18) unter `~/android/sdk/ndk/27.2.12479018`, arm64-v8a; SDK unter
  `~/android/sdk`, build-tools 30.0.3 + 34.0.0, platforms 21 + 34. 32 Bit und NDK r16b gehen
  weiter, siehe `build.sh`
- Gradle per Wrapper (`android/gradlew`, Distribution in `~/.gradle`); eine lose Gradle-8.9-
  Installation liegt zusätzlich unter `~/podbox/tools/gradle-8.9` (nur zum Erzeugen des Wrappers)
- Build-Verzeichnis `~/podbox/build-arm64`, Keystore `~/podbox/podbox.keystore`
- Bauen: `MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh" [zip]`
- Installieren: `adb install -r podbox.apk`
- Git: `core.eol=lf` ist repo-lokal Pflicht. Remotes: `origin` = GitHub, `upstream` = Rockbox

Details stehen in `podbox/README.md`.

Testen per adb (Hilfsmuster): Rad runter = `KEYCODE_VOLUME_UP`, hoch = `VOLUME_DOWN`,
OK = `CAMERA`, Zurück = `MEDIA_PREVIOUS`; Screenshot mit `adb exec-out screencap -p`.
Bei adb-Befehlen mit Gerätepfaden immer `MSYS_NO_PATHCONV=1`. Lange Tastendrücke lassen sich
per adb nicht auslösen.

## Offene Punkte

- [ ] Modernisierung Etappe 4 (targetSdk), siehe oben
- [ ] Testordner `/sdcard/Music/PodBoxTest` (MP3/FLAC/AAC/OGG/Opus) liegt wieder auf dem Gerät,
      nach den Tests löschen und Datenbank aktualisieren
- [ ] Leeren Ordner `F:\!Code2\PodBox\rockbox` löschen (war beim Umzug gesperrt)
- [ ] Wiedergabezähler/Statistiken mit der Datenbank testen (sollten nach Fix 11 gehen)
- [ ] Datenbank mit großer Bibliothek testen (Scan-Dauer, Speicher)
- [ ] Optional: Rockbox als Launcher (HOME-Intent), eigenes Theme
