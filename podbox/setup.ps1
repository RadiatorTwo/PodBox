# Richtet den SURFANS F20 Pro+ per adb für PodBox ein, z. B. nach einem
# Werksreset. Jeder Schritt lässt sich beliebig oft wiederholen.
#
#   .\setup.ps1                      nur einstellen (PodBox muss installiert sein)
#   .\setup.ps1 -Apk ..\podbox.apk   vorher die APK installieren
#   .\setup.ps1 -Debloat             danach zusätzlich debloat.ps1 ausführen

param(
    [string]$Apk,
    [switch]$Debloat
)

$ErrorActionPreference = 'Stop'

$Pkg      = 'com.blueshark.player'
$Activity = "$Pkg/org.rockbox.RockboxActivity"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'adb nicht im PATH.' }
if (-not ((adb devices) -match "`tdevice$")) { throw 'Kein Gerät per adb verbunden.' }

function Write-Step([string]$Name, $Out) { '{0,-40} {1}' -f $Name, (($Out | Out-String).Trim() -replace '\s+', ' ') }

if ($Apk) {
    if (-not (Test-Path $Apk)) { throw "APK nicht gefunden: $Apk" }
    Write-Step 'APK installieren' (adb install -r $Apk 2>&1)
}

if (-not ((adb shell pm list packages $Pkg) -match "package:$Pkg$")) {
    throw "$Pkg ist nicht installiert (Parameter -Apk)."
}

# Rockbox liest Dateien mit C-open(), dafür "Zugriff auf alle Dateien"
adb shell appops set --uid $Pkg MANAGE_EXTERNAL_STORAGE allow
Write-Step 'Zugriff auf alle Dateien' (adb shell appops get $Pkg MANAGE_EXTERNAL_STORAGE)

# PodBox als Startbildschirm (zurück: Einstellungen -> Standard-Apps)
Write-Step 'Startbildschirm' (adb shell cmd package set-home-activity $Activity 2>&1)

# Keine Displaysperre (nur ohne PIN/Muster möglich)
adb shell locksettings set-disabled true 2>&1 | Out-Null
Write-Step 'Displaysperre aus' ((adb shell locksettings get-disabled) -replace '^true$', 'ja' -replace '^false$', 'NEIN (PIN/Muster gesetzt?)')

# Tasten und Rad bei ausgeschaltetem Bildschirm. Herstellereigenschaft, die
# vendor/etc/init/hw/init.mt6765.rc nach /sys/class/key/key/state schreibt;
# der Werksreset setzt sie zurück, im Einstellungsmenü gibt es sie nicht.
adb shell setprop persist.sys.hw.key 1
Write-Step 'Tasten bei Bildschirm aus' (adb shell cat /sys/class/key/key/state)

# Kein Doze/App-Standby für PodBox (Wiedergabe und Tasten bei Bildschirm aus)
Write-Step 'Akku-Optimierung aus' (adb shell dumpsys deviceidle whitelist +$Pkg 2>&1)

# PodBox starten, damit es nach der Einrichtung vorne steht
adb shell am start -n $Activity 2>&1 | Out-Null
Write-Step 'PodBox gestartet' 'ok'

if ($Debloat) {
    '--- Debloat'
    & (Join-Path $PSScriptRoot 'debloat.ps1')
}
