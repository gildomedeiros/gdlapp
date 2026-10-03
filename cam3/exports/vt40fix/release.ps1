$ErrorActionPreference='Stop'
$projectRoot='C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt'
$releases=Join-Path $projectRoot 'build/releases'
New-Item -ItemType Directory -Force -Path $releases | Out-Null
$previous='C:/Users/gildo/AppData/Local/Temp/vt40-backup-20261003-171544/VT-3.8-before-4.0.apk'
$previousRelease=Join-Path $releases 'VT-3.8.apk'
if(!(Test-Path -LiteralPath $previousRelease)){Copy-Item -LiteralPath $previous -Destination $previousRelease}
$metadata=Get-Content (Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/output-metadata.json') -Raw | ConvertFrom-Json
if($metadata.elements[0].versionName -ne '4.0.1' -or $metadata.elements[0].versionCode -ne 24){throw 'Unexpected APK version; do not publish release copy'}
$built=Join-Path $projectRoot 'SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk'
$release=Join-Path $releases 'VT-4.0.1.apk'
Copy-Item -LiteralPath $built -Destination $release
if((Get-FileHash -LiteralPath $release).Hash -ne (Get-FileHash -LiteralPath $built).Hash){throw 'Release copy verification failed'}
Get-Item -LiteralPath $release | Select-Object FullName,Length
Get-FileHash -LiteralPath $release -Algorithm SHA256

