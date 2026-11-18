# PodBox – Projektstand

Stand: 18.11.2026, `main` (github.com/RadiatorTwo/PodBox), Modernisierung abgeschlossen
(Etappe 4: `targetSdk` 34). Auf dem Gerät installiert: 64-Bit-Build (arm64-v8a, NDK r27,
APK per Gradle, `targetSdk` 34) als **`com.blueshark.player`** mit "Zugriff auf alle Dateien".
Die alte App `org.rockbox` ist deinstalliert.

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
16. **Zwei Lautstärken, falsche dB-Anzeige:** Rockbox hat seine −99…0 dB gleichmäßig auf die
    100 Android-Stufen verteilt. Die Stufen folgen aber schon einer Kurve (Stufe 1 = −58 dB,
    20 = −40 dB, 60 = −17 dB, 100 = 0 dB), deshalb waren "−79 dB" in Wirklichkeit etwa −39 dB,
    und schon −98 dB war hörbar. Jetzt liest `RockboxPCM` die Kurve per `getStreamVolumeDb()`,
    wählt die passende Android-Stufe und regelt den Rest über den AudioTrack (unter −58 dB).
    −99 dB = Android-Stufe 0 = stumm. Ändert sich die Android-Lautstärke (Rad bei Bildschirm
    aus), übernimmt Rockbox den echten dB-Wert der Stufe. Getestet: Android 0/40/60 →
    Rockbox −99/−28/−17 dB, Rockbox-Schritte landen auf der passenden Stufe.
17. **Kopfhörer: primärer Mixer statt Direktpfad (Wärme):** Die Hersteller-Audio-Policy
    (`libaudiopolicymanagerdefault.so`) zwingt am Kopfhörer jede PCM-Wiedergabe auf den
    primären Mixer (`force non-DIRECT wired PCM uid ... to PRIMARY mixer`), auch `deep_buffer`
    ist dort ausgeschlossen. Direkt zum DAC (`wired_headphone_direct`, DIRECT, 8–384 kHz) darf nur
    die App mit Audio-Fokus (`sys.audio.wired_direct_focus_uid`), und nur wenn ihr Paket auf
    einer fest eingebauten Liste steht: `com.blueshark.hifi`, `.player`, `.musicplay`,
    `.h30music`, `.tapemusic`, `.newsmy411music`. Installiert ist davon nur `com.blueshark.hifi`
    (der "Surfans Hifi Player"). PodBox heißt deshalb jetzt `com.blueshark.player`
    (`PODBOX_APP_ID`, `tools/configure`, `build.gradle`; `RockboxService` ermittelt
    `app_rockbox` per `getDir()`). Gemessen bei Wiedergabe am Kopfhörer, Bildschirm an:
    audioserver 16 % → 1,4 %, Audio-HAL 13 % → 4,8 %, Temperatur in 3 min +5 °C → +2 °C.
    Der Track läuft mit 44,1 kHz/16 Bit ohne Resampling (`wired DIRECT source 44100 ->
    hardware 44100`). Am Lautsprecher bleibt es bei `deep_buffer`.
    Der Original-Player ist mit einem Packer verschlüsselt, steuert die Hardware aber nicht
    selbst: Er liefert PCM_FLOAT in Originalrate, der HAL wandelt nach PCM32. DSD/DoP-Spuren
    (`libBluesharkSacd.so`, `DOP_output`).
18. **Originalrate:** Rockbox gab fest 44,1 kHz aus und rechnete alles andere selbst um. Jetzt
    meldet der Android-Port alle Raten bis 192 kHz (`HW_SAMPR_CAPS SAMPR_CAP_ALL_192` in
    `config/android.h`), mit "Wiedergabefrequenz: Auto" (Standard) läuft jeder Titel in seiner
    eigenen Rate. `sink_set_freq` ruft `RockboxPCM.set_freq()`, das den AudioTrack neu anlegt
    (Puffer skaliert mit der Rate, alter Track wird vom Audio-Thread freigegeben). Getestet am
    Kopfhörer: 44,1/48/88,2/96/176,4/192 kHz gehen unverändert bis zur Hardware
    (`wired DIRECT source N -> hardware N`), 192 kHz ohne Underruns. Last bei 192 kHz: Rockbox
    6 %, audioserver 3,6 %, Audio-HAL 16,6 %. Am Lautsprecher rechnet Android um (ungetestet).
    Die 16-Bit-Grenze ist mit Fix 23 weg.
19. **Falsche Texte nach Feature-Änderung:** `tools/genlang` cachte die String-IDs in
    `apps/lang/english.list` ohne Rücksicht auf die Target-Features. Nach dem Aktivieren von
    `play_frequency` hatten die `.lng`-Dateien deshalb die alte Nummerierung (z. B.
    "Symbole im Zwischenspeicher" statt "Verknüpfungen"). Der Cache speichert jetzt das Target
    und gilt nur bei gleichem Target. Nach Feature-Änderungen `build.sh zip` bauen.
20. **Audioausgabe per AAudio, 32 Bit:** `pcm-android.c` schreibt jetzt selbst über AAudio
    (per `dlopen`, der native Teil ist für API 21 gebaut) als 32-Bit-Integer
    (`AAUDIO_FORMAT_PCM_I32`, Fallback Float). Ein nativer Audio-Thread holt die Daten von
    Rockbox. Der Java-AudioTrack, der Streamer-Thread und das Kopieren über JNI sind weg,
    `RockboxPCM` regelt nur noch Lautstärke und Foreground-Service. Am Kopfhörer landet das als
    `HAL format: PCM_32_BIT` auf dem Direktausgang. Getrennte oder hängende Ausgänge
    (Kopfhörer ausgesteckt, `AAUDIO_ERROR_DISCONNECTED`, 600 ms ohne Abnahme) werden neu
    geöffnet, Rockbox blockiert dann nicht. Seit Fix 23 kommen die Daten von Rockbox selbst
    schon als 32 Bit.
21. **Hardware-Grenze 192 kHz:** Der Direktpfad zum DAC läuft höchstens mit 192 kHz.
    352,8 kHz rechnet der HAL selbst auf 192 kHz um (`source 352800 -> hardware 192000`),
    bei 384 kHz liefert der HAL nur Fehler (`pcm_get_htimestamp fail`) und nimmt keine Daten ab.
    Mit dem DEEP_BUFFER-Flag (Stromspar-Modus) scheitert das Öffnen über 192 kHz ganz
    (`AUD_ASSERT` in `AudioALSAStreamOut.cpp`); ein so geöffneter Ausgang bleibt in Android
    hängen, bis eine andere Rate angefordert wird. Deshalb meldet PodBox nur Raten bis 192 kHz;
    Dateien darüber halbiert Rockbox exakt 2:1 (352,8 -> 176,4, 384 -> 192,
    `audio_guess_frequency()` in `apps/playback.c`), statt wie bisher auf 44,1 kHz zu fallen.
    Rockbox' Resampler filtert dabei nicht (lineare Interpolation), ein Halbband-Filter fehlt
    noch. Während der Tests zeigte Rockbox einmal eine Panic (Text unbekannt), vermutlich durch
    den hängenden 384-kHz-Ausgang; dieser Weg ist jetzt ausgeschlossen.
22. **Lautstärke beim Start auf 100:** Rockbox setzte beim Start zuerst seine eigene gespeicherte
    Lautstärke (nach Datenverlust der Standardwert 0 dB) und gab sie an Android weiter, bevor der
    Android-Wert ankam. Jetzt ignoriert `RockboxPCM` Rockbox-Lautstärken, bis Rockbox den
    Android-Wert übernommen hat, und `apps/misc.c` übernimmt den ersten Android-Wert immer.
23. **32-Bit-Datenpfad von DSP bis Ausgang:** Rockbox hat nach dem DSP alles auf 16 Bit
    gerundet (gedithert), pcmbuf, Crossfade und Mixer arbeiteten mit 16-Bit-Frames. Jetzt
    (`HAVE_PCM_32BIT_PATH` in `config/android.h`):
    - DSP: neue Einstellung `DSP_SET_OUTPUT_DEPTH` (16/32). Bei 32 schreibt er int32-Frames
      (Vollausschlag 2^31) ohne Rundung (`dsp_sample_output.c`). Der Codec-Thread setzt 32 beim
      Start eines Codecs, `DSP_RESET` setzt wieder 16 (Plugins wie midiplay/mpegplayer, die den
      Audio-DSP selbst nutzen, bekommen also weiter 16 Bit).
    - pcmbuf: 8 Byte pro Frame, Crossfade mischt in 64 Bit und begrenzt auf 32 Bit. Die
      Mindestgröße gilt jetzt für 192 kHz (3 s = 4,6 MB), deshalb hat Android jetzt 32 MB
      Audiopuffer statt 8 MB (`memory=32` in `tools/configure`, `build.sh` konfiguriert dann neu).
    - Mixer: Ausgabe int32. Jeder Kanal hat seine Framegröße (`mixer_channel_play_data_fmt()`,
      pcmbuf spielt mit 8, alles andere wie Sprachausgabe, Pieptöne und Plugins weiter mit 4).
      16-Bit-Kanäle werden beim Mischen hochskaliert. Peakmeter rechnet 32-Bit-Kanäle um.
    - `pcm-android.c` übernimmt die int32-Frames ohne Umrechnung (bei voller Lautstärke per
      `memcpy`), Feinabschwächung unter −58 dB in 2.30-Festkomma.
    Gemessen im Treiber (vorübergehendes Diagnose-Log): 16-Bit-FLAC kommt bitgenau an (untere
    16 Bit immer 0), eine 24-Bit/96-kHz-WAV exakt mit ihren 24 Bit (Spitzenwert `0x1FFFFF00`
    = Dateiwert × 256), MP3 (libmad) jetzt mit 29 statt 16 Bit. Ausgang 96 kHz,
    `HAL format: PCM_32_BIT`. Pause/Fortsetzen und Ratenwechsel 96 → 44,1 kHz gehen.
    Last unverändert (Prozess ca. 6 % eines Kerns bei Bildschirm an, Browser).
    Bekannte Grenze: Die Visualisierungs-Plugins (fft, oscilloscope, vu_meter) lesen den
    Wiedergabekanal als 16 Bit und zeigen bei Rockbox-Wiedergabe Unsinn. Ungetestet: Crossfade,
    Sprachausgabe/Pieptöne während der Wiedergabe.
## Modernisierung

| Etappe | Stand |
|---|---|
| 1. NDK r27 (clang) statt r16b (GCC 4.9) | fertig, Commit `9f34d8cf78` |
| 2. arm64-v8a als Standard | fertig, Commit `d182553d70` |
| 3. Gradle statt `android.make` für Java/APK | fertig, Commit `5d86cc83d2` |
| 4. `targetSdk` 34 | fertig, siehe unten |

**Etappe 3, umgesetzt:** Der native Teil bleibt bei `configure` + `make`. `build.sh` ruft
`make libs` und dann `android/gradlew assembleDebug` auf (Gradle 8.9, AGP 8.7.3,
`compileSdk` 34, `minSdk`/`targetSdk` 21). Die `.so` aus `<build>/libs/arm64-v8a` kommen als
`jniLibs` dazu, mit `useLegacyPackaging` (Libs werden beim Installieren entpackt, Rockbox liest
sie aus `nativeLibraryDir`). Signiert wird mit dem PodBox-Keystore, `adb install -r` geht ohne
Neuinstallation. Gradle-Ausgaben liegen in `~/podbox/build-arm64/gradle`. Auf dem Gerät getestet:
Start, Menü, Ja/Nein-Dialog, Browser, FLAC-Wiedergabe mit Album-Art. Der alte Weg geht noch mit
`PODBOX_APK=make`. `targetSdk` ist dabei von 5 (implizit, `minSdkVersion=5`) auf 21 gestiegen.

**Etappe 4, umgesetzt:**
- Speicherzugriff: `MANAGE_EXTERNAL_STORAGE`. Fehlt der Zugriff, öffnet `RockboxActivity` die
  Einstellungsseite ("Zugriff auf alle Dateien") und startet den Service erst nach der Rückkehr
  (`onResume`). Per adb: `adb shell appops set --uid com.blueshark.player MANAGE_EXTERNAL_STORAGE allow`.
  `WRITE_/READ_EXTERNAL_STORAGE` nur noch mit `maxSdkVersion`.
- Foreground-Service mit Typ `mediaPlayback` (+ `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`), Notification per `Notification.Builder` mit Channel
  `playback` (Wichtigkeit niedrig), `POST_NOTIFICATIONS` deklariert. `startForeground()` fängt
  Exceptions ab, damit der Rockbox-Thread nie daran stirbt.
- `android:exported` überall gesetzt (Service `false`, alle Service-Intents sind explizit),
  `PendingIntent.FLAG_IMMUTABLE`, Widget-Broadcasts mit `setPackage` (implizite Broadcasts
  erreichen Manifest-Receiver seit targetSdk 26 nicht mehr), `android:debuggable` entfernt.
- `TelephonyMonitor`: `READ_PHONE_STATE` ist eine Laufzeit-Berechtigung, die das Gerät nie
  bekommt, `SecurityException` wird abgefangen.
- **Fokus-Bug:** Mit targetSdk ≥ 28 bekam `RockboxFramebuffer` keinen Fokus mehr, weil
  `requestFocus()` lief, solange die View noch deaktiviert war; die Tasten kamen dann nicht an.
  Jetzt holt `surfaceChanged()` den Fokus nach `setEnabled(true)`.
- Auf dem Gerät getestet: Einstellungsseite beim ersten Start, Start mit Theme/Sprache aus
  `/sdcard/rockbox/config.cfg`, Browser auf der SD-Karte, Wiedergabe mit Album-Art,
  Notification, Pause (AudioTrack gestoppt, HAL im Standby, Service verlässt den Vordergrund),
  Play aus dem Hintergrund per Medientaste (`Background started FGS: Allowed`, temporär
  freigegeben über `MEDIA_SESSION_CALLBACK`), Plugins (`lua.rock`, `reversi.rock` per `dlopen`
  aus `app_data`, SELinux erlaubt das mit Audit-Hinweis).
- Nicht umgesetzt, erst auf Android 14+ nötig: `RECEIVER_EXPORTED`-Flags bei
  `registerReceiver()` (Gerät läuft mit Android 12).
- Nebenbefund: Das Lua-Spiel `boomshine` bricht ab (`backlight_force_on` fehlt in der
  Android-Lua-API), unabhängig von targetSdk.

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
- Rockbox: DSP, pcmbuf und Mixer mit 32 Bit (Fix 23), Originalrate bis 192 kHz (Fix 18).
  Offen: DSD und Raten über 192 kHz (Hardware-Grenze, Fix 21).
- Größter Rest bei Bildschirm an (Stand 18.11., Direktpfad): Rockbox-Thread ca. 11,6 % und
  surfaceflinger ca. 9,8 %. Der WPS zeichnet wegen der scrollenden Albumzeile ca. 12-mal pro
  Sekunde neu, jedes Mal als Vollbild-Kopie (`RockboxFramebuffer.update()`). Nächster Ansatz:
  nur den geänderten Bereich kopieren/zeichnen, Scrollen ggf. drosseln.
- Mögliche nächste Schritte für Effizienz/Klang (noch nicht entschieden):
  1. ~~Kopfhörer: Originalrate bis 192 kHz~~ erledigt (Fix 18), ~~24-Bit-Ausgabe~~ erledigt
     (Fix 23).
  2. Lautsprecher: Rockbox gibt 48 kHz aus (eigener, billiger Resampler). Klang bei 44,1-kHz-
     Dateien vom User anhören lassen.
  3. Bildschirm-Timeout im Wiedergabe-Bildschirm kurz halten.
- Nach dem RockboxPCM-Umbau per adb getestet: Pause und Play (auch aus dem Hintergrund).
  Track-Wechsel, Lautstärke und Stop (lange Tasten) noch mit den echten Tasten prüfen.

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

- [ ] Testordner `/sdcard/Music/PodBoxTest` (MP3/FLAC/AAC/OGG/Opus) liegt wieder auf dem Gerät,
      nach den Tests löschen und Datenbank aktualisieren. Dazu auf der SD-Karte
      `/storage/FEF0-07FF/Music/0_PodBoxTest` (16-Bit-FLAC, 24-Bit/96-kHz-Sinus-WAV, Fix 23)
- [ ] Leeren Ordner `F:\!Code2\PodBox\rockbox` löschen (war beim Umzug gesperrt)
- [ ] Wiedergabezähler/Statistiken mit der Datenbank testen (sollten nach Fix 11 gehen)
- [ ] Datenbank mit großer Bibliothek testen (Scan-Dauer, Speicher)
- [ ] Optional: Rockbox als Launcher (HOME-Intent), eigenes Theme
