param(
    [Parameter(Mandatory=$true)][string]$Apk,
    [string]$PhoneSerial = 'RFCW21XCSFD',
    [string]$TvSerial = '192.168.1.181:5555',
    [string]$Adb = 'C:\Users\Wokgui\AppData\Local\Android\Sdk\platform-tools\adb.exe'
)
$ErrorActionPreference = 'Stop'
$Apk = (Resolve-Path -LiteralPath $Apk).Path
$ExpectedHash = (Get-FileHash -LiteralPath $Apk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($TvSerial.Contains(':')) { & $Adb connect $TvSerial | Out-Host }
foreach ($DeviceSerial in @($PhoneSerial,$TvSerial)) {
    $DeviceState = (& $Adb -s $DeviceSerial get-state 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $DeviceState -ne 'device') { throw "Appareil inaccessible : $DeviceSerial. Aucun APK n'a été installé." }
}
# Both devices must be reachable before either installation starts. Install over
# the existing package; never uninstall or clear application data.
$Reports = @()
foreach ($DeviceSerial in @($TvSerial,$PhoneSerial)) {
    & $Adb -s $DeviceSerial install -r --user 0 $Apk | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "Installation échouée : $DeviceSerial. Corriger cet appareil avant de poursuivre." }
    $PackagePath = (& $Adb -s $DeviceSerial shell pm path --user 0 fr.wokgui.phototv | Select-Object -First 1).Trim().Replace('package:','')
    $InstalledHash = ((& $Adb -s $DeviceSerial shell sha256sum $PackagePath) -split '\s+')[0].ToLowerInvariant()
    if ($LASTEXITCODE -ne 0 -or $InstalledHash -ne $ExpectedHash) { throw "L'APK installé diffère sur $DeviceSerial." }
    $VersionLines = & $Adb -s $DeviceSerial shell dumpsys package fr.wokgui.phototv | Select-String 'versionName=|versionCode='
    $Reports += [pscustomobject]@{ Appareil=$DeviceSerial; Version=($VersionLines | Out-String).Trim(); SHA256=$InstalledHash }
}
if ($Reports[0].Version -ne $Reports[1].Version) { throw 'Les versions du smartphone et de la télévision diffèrent.' }
$Reports | Format-List | Out-Host
Write-Host 'Même version et même APK vérifiés sur le smartphone et la télévision. Données conservées.'
