param([Parameter(Mandatory=$true)][string]$Destination, [ValidateSet('Aim', 'Replay')][string]$Report = 'Replay')
$ErrorActionPreference = 'Stop'
$shell = New-Object -ComObject Shell.Application
$folder = $shell.NameSpace(17)
foreach ($name in @('Galaxy A54 5G', 'Internal storage', 'Download', 'FM', 'logs')) {
    $item = $folder.Items() | Where-Object { $_.Name -eq $name } | Select-Object -First 1
    if (!$item) {
        if ($name -in @('Galaxy A54 5G', 'Internal storage')) {
            throw 'Unlock the Galaxy A54 5G, connect it over USB, and select File transfer on the phone. Then try again.'
        }
        throw "Cannot find '$name' in Galaxy A54 5G > Internal storage > Download > FM > logs."
    }
    $folder = $item.GetFolder
}

$logs = @(foreach ($entry in $folder.Items()) {
    if ($entry.IsFolder) { continue }
    $fileName = $entry.ExtendedProperty('System.FileName')
    if (!$fileName) { $fileName = [IO.Path]::GetFileName($entry.Path) }
    if ($fileName -notmatch '^FM-[^\\/]+\.jsonl$') { $fileName = $entry.Name }
    if ($fileName -match '^FM-[^\\/]+\.jsonl$') {
        $modified = $entry.ExtendedProperty('System.DateModified')
        if (!$modified) { $modified = $entry.ExtendedProperty('System.DateCreated') }
        if (!$modified) { $modified = $entry.ModifyDate }
        [pscustomobject]@{ Item = $entry; Name = $fileName; Modified = [datetime]$modified }
    }
})
$logs = @($logs | Sort-Object Modified, Name -Descending)
if (!$logs.Count) { throw 'No FM JSONL logs found on the phone.' }

$pattern = if ($Report -eq 'Aim') { '"button"\s*:\s*"Aim at Surfer HS"|"event"\s*:\s*"aim_' } else { '"button"\s*:\s*"Replay viewpoints \(HS\)"|"event"\s*:\s*"hs_' }
$local = $shell.NameSpace($Destination)
foreach ($log in $logs) {
    $fileName = $log.Name
    $target = Join-Path $Destination $fileName
    $expectedSize = $log.Item.ExtendedProperty('System.Size')
    if ($null -eq $expectedSize) { $expectedSize = $log.Item.Size }
    $local.CopyHere($log.Item, 1044)
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 200
        $file = Get-Item -LiteralPath $target -ErrorAction SilentlyContinue
        $complete = $file -and $file.Length -eq $expectedSize
    } until ($complete -or [DateTime]::UtcNow -ge $deadline)
    if (!$complete) { throw "The phone did not finish copying $fileName. Check the USB connection and try again." }

    $matchesReport = Select-String -LiteralPath $target -Quiet -Pattern $pattern
    if ($matchesReport) {
        Write-Output $fileName
        exit 0
    }
    Remove-Item -LiteralPath $target
}
if ($Report -eq 'Aim') { throw 'No Aim at Surfer HS log found on the phone.' }
throw 'No HS replay log found on the phone.'