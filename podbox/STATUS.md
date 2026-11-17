# PodBox – Projektstand

Stand: 17.11.2026 auf `main` (github.com/RadiatorTwo/PodBox)

## Ziel

Rockbox als Android-App für den Surfans F20 Pro+ (Android 12, 480x640, MT6765),
bedient wie ein echtes Rockbox-Gerät, komplett über die Hardware: Drehrad, Rad-Button und
3 Tasten rechts. Basis ist der originale Rockbox-C-Code mit seinem Android-Port, kein Nachbau.

## Was funktioniert (auf dem Gerät getestet)

| Bereich | Stand |
|---|---|
| Start und Laufzeit | Rockbox startet und läuft stabil unter Android 12 |
| Darstellung | Vollbild ohne Android-Leisten, cabbiev2-Theme in 480x640, Schrift 35 px, 32x32-Icons, eigene 44-px-Statusleiste |
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
    des Browsers steht in `config.cfg` auf `/storage/FEF0-07FF/Music/` (nur auf dem Gerät gesetzt).
    Fehlt die Karte, fällt Rockbox von selbst auf `/` zurück.
13. **Zurück im Browser landete in `/`:** Die Elternordner `/storage` und `/storage/emulated`
    darf eine App nicht öffnen, Rockbox fiel dann auf die Android-Wurzel zurück. Jetzt gilt ein
    nicht lesbarer Elternordner als oberste Ebene, Zurück führt ins Hauptmenü (`apps/tree.c`).

## Build-Umgebung

- WSL2 `archlinux` (Standardshell fish, deshalb immer `wsl -d archlinux -e bash ...`)
- NDK r16b (GCC 4.9, armeabi-v7a), SDK unter `~/android/sdk`, build-tools 30.0.3, platform 21
- Build-Verzeichnis `~/podbox/build`, Keystore `~/podbox/podbox.keystore`
- Bauen: `MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/f/!Code2/PodBox/podbox/build.sh" [zip]`
- Installieren: `adb install -r podbox.apk`
- Git: `core.eol=lf` ist repo-lokal Pflicht. Remotes: `origin` = GitHub, `upstream` = Rockbox

Details stehen in `podbox/README.md`.

## Offene Punkte

- [ ] Leeren Ordner `F:\!Code2\PodBox\rockbox` löschen (war beim Umzug gesperrt)
- [ ] Modernisierung: arm64-v8a, aktuelles NDK (clang), Gradle statt `android.make`,
      `targetSdk` ≥ 31 (dann Scoped Storage / `MANAGE_EXTERNAL_STORAGE`, Foreground-Service-Typ,
      Notification-Channel nötig)
- [ ] Wiedergabezähler/Statistiken mit der Datenbank testen (sollten nach Fix 11 gehen)
- [ ] Datenbank mit großer Bibliothek testen (Scan-Dauer, Speicher)
- [ ] Optional: Rockbox als Launcher (HOME-Intent), eigenes Theme
