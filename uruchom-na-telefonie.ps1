# Buduje Wystaw i instaluje na podlaczonym telefonie. Wynik: build.log i logcat.txt w tym folderze.
$ErrorActionPreference = "Continue"
Set-Location $PSScriptRoot

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$jbr = "C:\Program Files\Android\Android Studio\jbr"
if (-not (Test-Path $sdk)) { Write-Host "Brak Android SDK w $sdk - dokoncz kreator Android Studio (Standard)." -ForegroundColor Red; exit 1 }
if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
"sdk.dir=" + ($sdk -replace '\\','\\' -replace ':','\:') | Set-Content -Encoding ASCII local.properties

$adb = Join-Path $sdk "platform-tools\adb.exe"
Write-Host "Telefony:" ; & $adb devices

Write-Host "Buduje i instaluje (pierwszy raz kilka minut)..." -ForegroundColor Cyan
& .\gradlew.bat installDebug --console=plain *> build.log
if ($LASTEXITCODE -ne 0) { Write-Host "Blad budowania - szczegoly w build.log" -ForegroundColor Red; Get-Content build.log -Tail 30; exit 1 }

Write-Host "Zainstalowano. Uruchamiam aplikacje..." -ForegroundColor Green
& $adb logcat -c
& $adb shell monkey -p pl.wystaw.app -c android.intent.category.LAUNCHER 1 | Out-Null
Write-Host "Zbieram logi do logcat.txt (zamknij okno, aby zakonczyc)."
& $adb logcat -v time AndroidRuntime:E OlxFillService:V Wystaw:V System.err:W *:S > logcat.txt
