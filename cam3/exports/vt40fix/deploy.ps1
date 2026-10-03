$ErrorActionPreference='Stop'
$targetRoot='C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt'
$stagedRoot='C:/Users/gildo/gdlapp/cam3/exports/vt40fix/files'
$manifest=Get-Content 'C:/Users/gildo/gdlapp/cam3/exports/vt40fix/manifest.json' -Raw | ConvertFrom-Json
$backupRoot=Join-Path $env:TEMP ('vt401-backup-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
foreach($entry in $manifest){
    $target=Join-Path $targetRoot $entry.path
    if($entry.originalSha256){if(!(Test-Path -LiteralPath $target) -or (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLower() -ne $entry.originalSha256){throw "Working tree changed since staging: $target"}}
    elseif(Test-Path -LiteralPath $target){throw "New file appeared since staging: $target"}
    if((Get-FileHash -LiteralPath (Join-Path $stagedRoot $entry.path) -Algorithm SHA256).Hash.ToLower() -ne $entry.stagedSha256){throw 'Staged file changed since review'}
}
New-Item -ItemType Directory -Path $backupRoot | Out-Null
foreach($entry in $manifest){
    $target=Join-Path $targetRoot $entry.path
    if(Test-Path -LiteralPath $target){$backup=Join-Path $backupRoot $entry.path;New-Item -ItemType Directory -Force -Path (Split-Path $backup) | Out-Null;Copy-Item -LiteralPath $target -Destination $backup}
}
$oldApk=Join-Path $targetRoot 'SampleCode-V5/android-sdk-v5-sample/build/outputs/apk/debug/sample-debug.apk'
if(Test-Path -LiteralPath $oldApk){Copy-Item -LiteralPath $oldApk -Destination (Join-Path $backupRoot 'previous-debug.apk')}
foreach($entry in $manifest){
    $target=Join-Path $targetRoot $entry.path
    New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null
    Copy-Item -LiteralPath (Join-Path $stagedRoot $entry.path) -Destination $target
}
Write-Output "Applied $($manifest.Count) files. Original files and previous APK backed up at $backupRoot"

