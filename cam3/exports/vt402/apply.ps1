$ErrorActionPreference='Stop'
$root='C:/Users/gildo/.codex/worktrees/vt-28/gdlapp/vt'
$stage='C:/Users/gildo/gdlapp/cam3/exports/vt402/files'
$entries=Get-Content 'C:/Users/gildo/gdlapp/cam3/exports/vt402/manifest.json' -Raw | ConvertFrom-Json
foreach($entry in $entries){
 $target=Join-Path $root $entry.path
 if($entry.originalSha256){if((Get-FileHash -LiteralPath $target).Hash.ToLower() -ne $entry.originalSha256){throw "File changed since staging: $target"}}
 elseif(Test-Path -LiteralPath $target){throw "New file already exists: $target"}
 if((Get-FileHash -LiteralPath (Join-Path $stage $entry.path)).Hash.ToLower() -ne $entry.stagedSha256){throw 'Staged file changed'}
}
foreach($entry in $entries){$target=Join-Path $root $entry.path;New-Item -ItemType Directory -Force -Path (Split-Path $target) | Out-Null;Copy-Item -LiteralPath (Join-Path $stage $entry.path) -Destination $target}
Write-Output "Applied $($entries.Count) files"
