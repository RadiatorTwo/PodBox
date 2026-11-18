# Debloat für den SURFANS F20 Pro (Android 12) per adb.
#
#   .\debloat.ps1            entfernt alles unten Gelistete
#   .\debloat.ps1 -Restore   holt die System-Apps zurück
#
# System-Apps werden nur für Nutzer 0 entfernt (pm uninstall --user 0) und
# lassen sich mit -Restore oder "cmd package install-existing <pkg>" wiederholen.
# Nutzer-Apps ($UserApps) werden komplett deinstalliert - nicht wiederholbar.
# Ein Werksreset stellt alle System-Apps wieder her.
#
# Bewusst NICHT entfernt: com.google.android.gms, com.android.vending,
# com.android.phone, com.android.systemui, Launcher, Setup-Wizard,
# com.android.inputmethod.latin (aktive Tastatur).

param([switch]$Restore)

$SystemApps = @(
    # Sprachsynthese/-erkennung - Gerät hat kein Mikrofon
    'com.google.android.tts'
    # Test-, Debug- und Werksmenüs
    'com.android.egg'
    'com.android.traceur'
    'com.teksun.factorytest'
    'com.mediatek.lbs.em2.ui'
    # Keine Kamera, kein Drucker
    'com.android.cameraextensions'
    'com.android.printspooler'
    'com.android.bips'
    'com.google.android.printservice.recommendation'
    # Google-Extras
    'com.google.android.feedback'
    'com.google.android.apps.restore'
    'com.google.android.gms.location.history'
    'com.google.android.contactkeys'
    'com.google.android.verifier'
    # Bildschirmschoner, Live-Wallpaper, Lesezeichen
    'com.android.dreams.basic'
    'com.android.wallpaper.livepicker'
    'com.android.bookmarkprovider'
    'com.android.providers.partnerbookmarks'
    # Telefon/SIM-Extras - keine SIM
    'com.android.emergency'
    'com.android.cellbroadcastreceiver'
    'com.google.android.cellbroadcastreceiver'
    'com.android.calllogbackup'
    # AOSP-Standard-Apps
    'com.android.calendar'
    'com.google.android.syncadapters.calendar'
    'com.android.gallery3d'
    'com.android.calculator2'
    'com.android.deskclock'
    'com.android.music'
    'com.android.chrome'
    # Zweite Tastatur (Gboard); AOSP-Tastatur bleibt aktiv
    'com.google.android.inputmethod.latin'
)

# Vom Nutzer installierte Streaming-Apps - PodBox ersetzt sie
$UserApps = @(
    'com.spotify.music'
    'com.aspiro.tidal'
    'com.apple.android.music'
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'adb nicht im PATH.' }
if (-not ((adb devices) -match "`tdevice$")) { throw 'Kein Gerät per adb verbunden.' }

function Write-Result([string]$Pkg, [string]$Msg) { '{0,-48} {1}' -f $Pkg, $Msg }

if ($Restore) {
    foreach ($p in $SystemApps) {
        Write-Result $p ((adb shell cmd package install-existing $p 2>&1) -join ' ')
    }
    return
}

$installed = (adb shell pm list packages) -replace '^package:', ''

foreach ($p in $SystemApps) {
    if ($p -notin $installed) { Write-Result $p 'schon entfernt'; continue }
    # Daten und Updates in /data zuerst löschen, sonst bleibt der Speicher belegt
    adb shell pm clear $p 2>&1 | Out-Null
    adb shell pm uninstall-system-updates $p 2>&1 | Out-Null
    Write-Result $p ((adb shell pm uninstall -k --user 0 $p 2>&1) -join ' ')
}

foreach ($p in $UserApps) {
    if ($p -notin $installed) { Write-Result $p 'nicht installiert'; continue }
    Write-Result $p ((adb uninstall $p 2>&1) -join ' ')
}

'--- Aufräumen'
adb shell 'rm -rf /data/local/tmp/*'
adb shell pm trim-caches 999G
adb shell df -h /data
