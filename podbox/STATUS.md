# PodBox – Projektstand

Stand: 18.11.2026 abends, `main` (github.com/RadiatorTwo/PodBox), Modernisierung abgeschlossen
(Etappe 4: `targetSdk` 34). Auf dem Gerät installiert: 64-Bit-Build (arm64-v8a, NDK r27,
APK per Gradle, `targetSdk` 34) als **`com.blueshark.player`** mit "Zugriff auf alle Dateien".
Die alte App `org.rockbox` ist deinstalliert.

## Ziel

Eine gute Musik-App für den Surfans F20 Pro+ (Android 12, 480x640, MT6765), komplett über die
Hardware bedienbar: Drehrad, Rad-Button und 3 Tasten rechts. Basis ist der Rockbox-C-Code mit
seinem Android-Port, aber nur als Vorlage: Umbauten sind erlaubt, wenn sie Klang, Akku oder
Bedienung verbessern. Audio möglichst unverändert (Originalrate/-format) an die Hardware geben.

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
| Rad im Uhrzeigersinn / dagegen | VOLUME_UP / VOLUME_DOWN | runter / hoch | Lautstärke oder Spulen (Seek-Modus) | Lautstärke |
| Rad-Button | CAMERA | OK, lang: Kontext | Rad: Lautstärke ↔ Spulen, lang: Hauptmenü | – |
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
    Gefiltert wird das seit Fix 24 mit einem Halbband-Filter. Während der Tests zeigte Rockbox einmal eine Panic (Text unbekannt), vermutlich durch
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
    Sprachausgabe/Pieptöne während der Wiedergabe. Nachtrag: Crossfade (3 s) zwischen zwei
    352,8-kHz-Testtönen geprüft (lineare Rampe von 0x3FFFFF18 auf 0), Tastenklicks kommen als
    korrekt hochgeschobene 16-Bit-Werte im Mixer an. fft und oscilloscope lesen den 32-Bit-Kanal
    (Plugin-API `mixer_channel_get_frame_bytes()`), pcmbuf-Chunks haben dafür immer 2048 Frames.
24. **Halbband-Filter für exakt 2:1:** Rockbox' Resampler ist eine 4-Punkt-Hermite-Interpolation
    ohne Tiefpass; beim Halbieren der Rate spiegelte sich alles oberhalb der neuen
    Nyquist-Frequenz zurück. Bei exakt doppelter Eingangsrate (352,8 → 176,4, 384 → 192 kHz,
    mit fester Ausgaberate auch 88,2/96 → 44,1/48 kHz) nimmt der Audio-DSP jetzt einen
    Halbband-FIR-Dezimator (`resample.c`, 95 Taps, Kaiser-Fenster, Koeffizienten Q31, 64-Bit-
    Akkumulator): flach (< 0,01 dB) bis 0,216 × Eingangsrate, ab 0,284 × Eingangsrate unter
    −97 dB, Verzögerung 47 Eingangssamples. Gemessen mit 352,8-kHz/24-Bit-Testtönen: 1 kHz
    kommt unverändert an, 120 kHz (würde sonst auf 56,4 kHz gespiegelt) liegt bei −113 dB.
25. **Display nativ per ANativeWindow:** `lcd-android.c` hat bei jeder Aktualisierung den ganzen
    Framebuffer per JNI an Java gegeben; dort wurden 600 KB in ein Bitmap kopiert und per
    Software-Canvas gezeichnet, auch bei Teil-Updates. Jetzt schreibt Rockbox direkt in die
    Surface (`ANativeWindow_lock` mit Dirty-Rechteck, nur die geänderten Zeilen werden kopiert,
    `-landroid`). `RockboxFramebuffer.java` zeichnet nicht mehr. Gemessen im WPS bei Wiedergabe
. **PodBox als Android-Startbildschirm:** `RockboxActivity` hat den Intent-Filter `HOME`, PodBox
    ist auf dem Gerät als Startbildschirm eingestellt (`adb shell cmd package set-home-activity
    com.blueshark.player/org.rockbox.RockboxActivity`, zurück über Einstellungen → Standard-Apps
    oder mit `com.blueshark.sidebarlauncher/.ui.activity.MainActivity`). Nach dem Booten steht PodBox
    direkt da, die Home-Taste (Navigationsleiste per Wischen von unten) führt zu PodBox, die
    Übersicht geht weiter. Neuer Eintrag "Andere Apps" am Ende des Hauptmenüs
    (`apps/hosted/android/launcher.c`, `RockboxService.openOtherLauncher()`): öffnet den
    Startbildschirm, der vor PodBox eingestellt war (gemerkt in den Preferences, sonst
    `sidebarlauncher`); Home führt zurück, Rockbox läuft dabei weiter. Dabei behoben:
    - Kaltstart bei ausgeschaltetem Bildschirm (Android startet den Startbildschirm selbst neu)
      stürzte ab (`BackgroundServiceStartNotAllowedException` in `onCreate`). Jetzt versucht die
      Activity es beim nächsten `onResume`/Fokus erneut.
    - PodBox wurde bei Pause und Bildschirm aus nach ca. 1 min beendet (Android stoppt
      Hintergrund-Services von Apps im Leerlauf, `onDestroy()` ruft `System.exit`). Der Service
      bleibt jetzt im Vordergrund, solange Rockbox läuft, auch in der Pause (stille Notification).
      Kein `START_STICKY` mehr: Der Neustart ohne Oberfläche wurde sofort wieder gestoppt und riss
      die neu gestartete Activity mit.
    Getestet: Neustart des Geräts, Andere Apps → Home, Übersicht, Bildschirm aus 80 s in Pause
    (überlebt), Prozess bei Bildschirm aus beendet (PodBox kommt beim Einschalten wieder).
27. **Bedienung: Seek per Rad, Rad/Taster entprellt, Lautstärke-Skala (19.11.):**
    - **Seek:** Im WPS schaltet der Rad-Button (kurz) das Rad zwischen Lautstärke und Spulen um
      (wie beim iPod), lang öffnet er das Hauptmenü; das Kontextmenü liegt weiter auf "unten".
      Im Seek-Modus steht "<< SEEK >>" in der Kopfzeile (neues Skin-Token `%mS`). Jede Raste
      springt 5 s, schnelles Drehen bis 30 s (Abstand der Rasten < 180/130/110 ms → 10/20/30 s).
      Gesammelt wird, bis das Rad 400 ms ruht, dann wird einmal gesprungen (Anzeige läuft vorher
      über `ff_rewind_count` mit). Nach 5 s ohne Drehen und bei jeder anderen Taste geht das Rad
      zurück auf Lautstärke. Code: `HAVE_WHEEL_SEEK` (`config/android.h`), `wps.c`,
      `ACTION_WPS_WHEELMODE`, `keymap-android.c`. Per adb getestet: 6 Raten = +30 s, Rückkehr
      nach Timeout.
    - **Rad-Fehlrichtung:** Eine Raste gegen die bisherige Richtung innerhalb von 250 ms nach der
      vorigen zählt erst, wenn eine zweite sie bestätigt (dann werden beide gemeldet).
      Nach einer Pause zählt die erste Raste sofort (`wheel_detent()` in `button-android.c`).
    - **Doppelte Tastendrücke:** Eine Taste gilt erst als losgelassen, wenn sie 60 ms oben
      blieb; ein Druck innerhalb dieser Zeit setzt den ersten fort (`button_read_device()`).
      Gilt für Rad-Button, oben und unten (die Mitte läuft als Media-Taste getrennt).
    - **Lautstärke in Stufen statt dB:** 0–30 (0 = stumm, `AUDIOHW_SETTING(VOLUME)` in
      `android_codec.h`, `RockboxPCM.STEPS`). Stufe 1 = leiseste hörbare Android-Stufe
      (−57 dB), 30 = voll, dazwischen gleich große dB-Schritte (ca. 2 dB). Jede Stufe setzt die
      nächstliegende Android-Stufe (von 100), keine digitale Zusatzabschwächung mehr. Ändert
      Android die Lautstärke (Rad bei Bildschirm aus, 1 Android-Stufe pro Raste), übernimmt
      Rockbox die nächstliegende Stufe. Achtung: Android meldet für Stufe 1 −∞ dB
      (`getStreamVolumeDb`); das machte die Rechnung zuerst zu NaN und setzte Android beim Start
      auf 100. Jetzt zählt die leiseste *endliche* Stufe (`streamDbLowest`), die Kurve wird beim
      Start geloggt. Der Balken ist linear in Stufen (`skin_display.c`, vorher "normalisiert":
      unten kaum Bewegung, oben Sprünge), das Lautstärke-Symbol rechnet mit 0..30
      (`skin_tokens.c`). Langsames Drehen (≥ 200 ms pro Raste) = 1 Stufe pro Raste, zügiges
      beschleunigt mit der Länge der Bewegung: ab Raste 3 je 2 Stufen, ab Raste 6 bei < 130 ms
      je 3. Die Länge zählt mit, weil der Treiber bei ca. 10 Raten/s deckelt. Die
      Rasten-Abstände kommen als Button-Daten (`button_queue_post(button, ms)`). Getestet per
      adb: Android 20 → Stufe 10, je Raste +1 Stufe (Android 25/28/32), zurück 28/25/22.
    - Fix: Der Druck des Rad-Buttons (ohne Mapping, `ACTION_UNKNOWN`) beendete den Seek-Modus,
      das Loslassen schaltete ihn wieder ein, verlassen ging nur per Timeout.
    - Rohdaten (19.11., 183 Raten): Der Rad-Treiber liefert höchstens ca. 10 Raten/s (Abstand nie
      unter 98 ms, Median 133 ms, p90 209 ms). 5 einzelne Fehlrichtungen, jeweils 100–115 ms nach
      der vorigen Raste (vom 250-ms-Filter erfasst). Rad-Button: Drücke 115–180 ms, einmal ein
      kompletter zweiter Druck in derselben ms wie das Loslassen (von der Entprellung erfasst).
      Rohdaten aufnehmen: `getevent -lt` auf dem Gerät, das Rad ist
      `odm:rotary@0` (`/dev/input/event2`, KEY_UP/KEY_DOWN), der Rad-Button `mtk-kpd`
      (`/dev/input/event0`, KEY_CAMERA).
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
  Notification, Pause (AudioTrack gestoppt, HAL im Standby; seit Fix 26 bleibt der Service im Vordergrund),
  Play aus dem Hintergrund per Medientaste (`Background started FGS: Allowed`, temporär
  freigegeben über `MEDIA_SESSION_CALLBACK`), Plugins (`lua.rock`, `reversi.rock` per `dlopen`
  aus `app_data`, SELinux erlaubt das mit Audit-Hinweis).
- Nicht umgesetzt, erst auf Android 14+ nötig: `RECEIVER_EXPORTED`-Flags bei
  `registerReceiver()` (Gerät läuft mit Android 12).
- Nebenbefund: Das Lua-Spiel `boomshine` brach ab (`backlight_force_on` fehlte in der
  Android-Lua-API). Behoben: `rocklib.c` registriert `backlight_force_on`/`backlight_use_settings`
  immer (ohne `HAVE_BACKLIGHT` als leere Funktionen), boomshine läuft.

## Design (Stand 19.11.)

Neues Theme `podbox` statt cabbiev2: dunkel, flache Karten mit Schatten, keine runden Ecken,
Akzent sehr helles Grau, Schrift Inter, Symbole Material Symbols Sharp. Entwurf:
https://claude.ai/artifact/SenArV595xHBfNx5s19Xxk. Ist Standard (`settings_list.c`,
`PODBOX_THEME`); `config.cfg` auf dem Gerät enthält keine Theme-Zeilen, also greift es direkt.

- **Schriften:** `podbox/mkfonts.sh` (von `build.sh zip` aufgerufen) baut `convttf` und macht aus
  `podbox/fonts/Inter-*.ttf` kantengeglättete `.fnt` (15–30 px, nicht eingecheckt).
  `buildzip.pl` und `wpsbuild.pl` kopieren sie. UI-Schrift `30-Inter` (32 px hoch). Der User wollte zweimal größeren Text (20 und 24 px waren zu klein).
- **Grafiken:** `python podbox/theme/mkassets.py` (Windows-Python mit Pillow/numpy) erzeugt
  `wps/podbox/*.bmp` und `icons/podbox_icons.32x32.bmp` (eingecheckt). Die Layout-Zahlen stehen
  dort und in `wps/podbox.480x640x16.{wps,sbs}`, beide zusammen ändern. Material Symbols lädt
  das Skript beim ersten Lauf nach `podbox/theme/cache` (nicht eingecheckt).
- **Farbregel:** Alle Flächenfarben sind exakte RGB565-Werte; Verläufe/Schatten dithert das
  Skript selbst. `backdrop.c` dithert auf Android nicht mehr, sonst wären flache Flächen
  zweifarbig gemustert und passten nicht zu Flächen, die der Code füllt.
- **Karten:** Wiedergabe-Karten und Mini-Player sind im Backdrop. Gewählte Listenzeile =
  Selektor "bar (color)", auf Android als Karte mit Schatten gezeichnet (`STYLE_CARD`,
  `apps/gui/line.c`). Zeilenhöhe 64 px (`list padding` 32), Icons 32 px, Icon-Abstand 14 px
  (`ICON_PADDING` in `bitmap/list.c`), schlanke flache Scrollleiste.
- **Skin-Erweiterung:** `%Vs(fill, Hintergrund[, Text])` füllt Textzeilen einfarbig (für Text auf
  Bildern, z. B. Lautstärke-Overlay).
- **Skin-Render:** Bilder eines Viewports werden auf Android nur gezeichnet, wenn der Viewport
  ganz neu aufgebaut oder eine seiner Zeilen neu geschrieben wurde. Upstream malt sie bei jedem
  Refresh und übermalte so den Text auf dem Overlay.
- Hauptmenü-Titel "PodBox" (`root_menu.c`).

Fallstricke beim Skin-Schreiben:
- `%xd(ab)` mit zwei Zeichen heißt Bild `a`, Teilbild `b`. Teilbilder immer als `%xd(name, n)`,
  Bildnamen länger als 2 Zeichen.
- `%Vs(...)` direkt hinter `%Vl(...)` bildet eine eigene leere Zeile (anders als `%Vf`); in die
  Textzeile schreiben.
- `%?fc<...>` wählt nach Codec-Nummer, für den Namen `%fc` direkt.
- Skins ohne Neubau testen: Datei nach `/data/local/tmp` pushen,
  `run-as com.blueshark.player cp ... app_rockbox/rockbox/wps/podbox.{wps,sbs}`, dann
  `am force-stop` und Home. Lädt eine Skin nicht, nimmt Rockbox still die eingebaute.
  `checkwps` bräuchte SDL in WSL.

Offen: Werte in Einstellungslisten (Mockup), Sprache Deutsch, Listentitel bei langen Namen
(wird abgeschnitten), Viewer-Icons noch Tango.

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
- Bildschirm an (Stand 18.11.): Der WPS hat wegen der scrollenden Albumzeile 12,5-mal pro
  Sekunde neu gezeichnet (PodBox 10–11,5 %, surfaceflinger 8–9 % eines Kerns). Jetzt scrollt im
  WPS nichts mehr (kein `%s` in `wps/cabbiev2.480x640x16.wps`, lange Texte werden abgeschnitten),
  es zeichnet nur noch die Spielzeit einmal pro Sekunde: PodBox 4,7 %, surfaceflinger 4,9 %.
- Mögliche nächste Schritte für Effizienz/Klang (noch nicht entschieden):
  1. ~~Kopfhörer: Originalrate bis 192 kHz~~ erledigt (Fix 18), ~~24-Bit-Ausgabe~~ erledigt
     (Fix 23).
  2. ~~Lautsprecher~~: Das Gerät hat keinen.
  3. Bildschirm-Timeout im Wiedergabe-Bildschirm kurz halten.
- Nach dem RockboxPCM-Umbau per adb getestet: Pause und Play (auch aus dem Hintergrund).
  Track-Wechsel, Lautstärke und Stop (lange Tasten) noch mit den echten Tasten prüfen.

## Stand 18.11. abends, nächste Schritte

Audio-Umbau fertig bis auf DSD (vom User zurückgestellt, keine Prio): AAudio 32 Bit, Originalrate
bis 192 kHz, 32-Bit-Pfad DSP→Ausgang (Fix 23), Halbband 2:1 (Fix 24), natives Display (Fix 25).
Alles gepusht (`516aa192a5`). Auf dem Gerät installiert ist dieser Stand.

Vom User getestet (18.11.):
- Das Gerät hat **keinen Lautsprecher**. Der "Lautsprecher"-Pfad in der Android-Audio-Konfiguration
  (`primary`/`deep_buffer`, 48 kHz) gibt es nur in der Software; Hinweise darauf weiter oben
  (Wärme-Messungen, Lautsprecher-Ideen) sind damit gegenstandslos.
- Kopfhörer ausstecken: nichts zu hören, kein Absturz. Wiedereinstecken: kurz Ton, dann einige
  Sekunden Pause, dann geht es weiter (offen, siehe unten).
- Bildschirm aus, sofort "unten", nach 10 s an: kein Absturz (der ANR war ein adb-Artefakt).
- Wärme: wird "nicht mehr ansatzweise so warm".
- Anhören 24-Bit, MP3, Crossfade: in Ordnung.

Vom User zu entscheiden: derzeit nichts offen.

Test-Hilfen: `/data/local/tmp/cpu.sh <s>` (CPU von PodBox/surfaceflinger/audioserver, Werte x10
= Promille eines Kerns) und `/data/local/tmp/thr.sh <s>` (pro Thread) liegen auf dem Gerät,
Aufruf `MSYS_NO_PATHCONV=1 adb shell sh /data/local/tmp/cpu.sh 20`. Bit-Prüfungen liefen über
ein vorübergehendes Spitzenwert-Log in `fill()` von `pcm-android.c` (nicht committet).

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
- Bauen: `MSYS_NO_PATHCONV=1 wsl -d archlinux -e bash "/mnt/c/!Code2/PodBox/podbox/build.sh" [zip]`
- Installieren: `adb install -r podbox.apk`
- Git: `core.eol=lf` ist repo-lokal Pflicht. Remotes: `origin` = GitHub, `upstream` = Rockbox

Details stehen in `podbox/README.md`.

Testen per adb (Hilfsmuster): Rad runter = `KEYCODE_VOLUME_UP`, hoch = `VOLUME_DOWN`,
OK = `CAMERA`, Zurück = `MEDIA_PREVIOUS`; Screenshot mit `adb exec-out screencap -p`.
Bei adb-Befehlen mit Gerätepfaden immer `MSYS_NO_PATHCONV=1`. Lange Tastendrücke lassen sich
per adb nicht auslösen.

## Offene Punkte

- [x] Testordner `/sdcard/Music/PodBoxTest` und SD `Music/0_PodBoxTest` gelöscht (18.11.),
      Datenbank aktualisiert (185 Titel).
- [x] Wiedergabezähler/Statistiken: gehen (18.11. getestet, Titel aus dem Datei-Browser landet nach
      > 15 s unter "Kürzlich gespielt", übersteht Neustart). Voraussetzung: "Laufzeitdaten sammeln"
      (`gather runtime data: on`, Rockbox-Standard ist aus), auf dem Gerät jetzt eingeschaltet.
- [x] Datenbank mit großer Bibliothek: geht (18.11. getestet mit 10000 künstlichen MP3s, 200
      Interpreten × 5 Alben × 10 Titel, ID3v2.3, auf der SD-Karte, plus die echten Titel = 10197
      Einträge, keine Duplikate). "Jetzt aktualisieren" mit 10000 neuen Dateien: 62 s, danach
      ohne Änderungen 39 s + ca. 15 s Nachlauf (ca. 28 % eines Kerns, meist Systemzeit im
      FUSE-Zugriff auf `/storage`). Speicher unverändert (RSS ca. 130 MB, die Datenbank liegt
      nicht im RAM). Künstlerliste und "[Alle Titel]" (10197, sortiert, vollständig) öffnen in
      unter 0,5 s, Kaltstart in unter 2 s. Index 979 KB, Dateinamen 990 KB. Generator:
      Perl-Skript, kleine stille MP3s (40 Frames) mit Tags; die Testdateien sind wieder gelöscht.
      Langsam ist nur das Austragen gelöschter Dateien: Nach dem Löschen der 10000 Dateien
      markierte das Aktualisieren sie im Hintergrund mit ca. 30 Einträgen/s (ca. 6 min,
      `check_deleted_file_refs()` → `delete_entry()` je Datei). Kommt nur bei Massenlöschungen
      vor; bei Bedarf `delete_entry()` bündeln. Die Datenbank-Dateien behalten die 10000
      gelöschten Einträge (Index 979 KB), "Neu generieren" würde sie verkleinern, löscht aber
      die Wiedergabezähler (vorher "Änderungen exportieren").
- [x] PodBox als Launcher (Fix 26)
- [ ] Optional: eigenes Theme
