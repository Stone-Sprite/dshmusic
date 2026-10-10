$ErrorActionPreference = 'Stop'
$finJar = 'D:\dsh-build\dshmusic-12111-fabric\build\dshmusic-0.5.2-fabric.jar'
Add-Type -AssemblyName System.IO.Compression.FileSystem

# Locate the Fabric profile by a file only it has: the folder name is non-ASCII and a
# BOM-less .ps1 would be read as ANSI, mangling any literal we wrote.
$verDir = Get-ChildItem 'D:\.minecraft\versions' -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'mods\diamondhud-0.5.0-fabric.jar') } |
    Select-Object -First 1
if (-not $verDir) { throw 'profile mods folder not found' }
$target = Join-Path $verDir.FullName 'mods\dshmusic-0.5.2-fabric.jar'
Write-Host ("profile : " + $verDir.Name)
Copy-Item $finJar $target -Force
Get-Item $target | ForEach-Object { "installed: {0:N0} bytes  {1}" -f $_.Length, $_.LastWriteTime }

Write-Host ''
Write-Host '--- structure ---'
$z = [System.IO.Compression.ZipFile]::OpenRead($target)
$top = @{}
foreach ($e in $z.Entries) {
    $p = $e.FullName.Split('/')[0]
    if (-not $top.ContainsKey($p)) { $top[$p] = 0 }
    $top[$p]++
}
foreach ($k in ($top.Keys | Sort-Object)) { "  {0,-14} {1} entries" -f $k, $top[$k] }
foreach ($n in @('fabric.mod.json', 'assets/dshmusic/icon.png',
                 'META-INF/services/javax.sound.sampled.spi.AudioFileReader')) {
    $hit = $z.Entries | Where-Object { $_.FullName -eq $n }
    "  {0,-58} {1}" -f $n, $(if ($hit) { 'present' } else { 'MISSING' })
}
$e = $z.Entries | Where-Object { $_.FullName -eq 'fabric.mod.json' }
$ms = New-Object System.IO.MemoryStream; $e.Open().CopyTo($ms)
$meta = [System.Text.Encoding]::UTF8.GetString($ms.ToArray()) | ConvertFrom-Json
"  fabric.mod.json -> id={0} version={1} env={2} entry={3}" -f $meta.id, $meta.version,
    $meta.environment, ($meta.entrypoints.client -join ',')
"  depends: " + (($meta.depends.PSObject.Properties | ForEach-Object { "$($_.Name)$($_.Value)" }) -join ' ')

Write-Host ''
Write-Host '--- what the loader-facing classes call now ---'
foreach ($name in @('dev/dsh/music/MusicMod.class', 'dev/dsh/music/MusicHud.class',
                     'dev/dsh/music/MusicClient.class')) {
    $e = $z.Entries | Where-Object { $_.FullName -eq $name }
    $ms = New-Object System.IO.MemoryStream; $e.Open().CopyTo($ms); $b = $ms.ToArray()
    $txt = [System.Text.Encoding]::ASCII.GetString($b)
    "== $name  ($($b.Length) bytes)"
    foreach ($p in @('class_2960', 'method_60655', 'HudElementRegistry', 'addLast',
                     'HudElement', 'class_332', 'class_9779', 'render',
                     'ClientTickEvents', 'ClientReceiveMessageEvents',
                     'ClientModInitializer', 'class_310', 'method_1551',
                     'displayClientMessage', 'net/minecraft/resources/')) {
        $c = ([regex]::Matches($txt, [regex]::Escape($p))).Count
        if ($c -gt 0) { "     {0,-30} x{1}" -f $p, $c }
    }
    # method names that should NOT still be readable
    $leak = @()
    foreach ($p in @('fromNamespaceAndPath', 'getInstance', 'displayClientMessage',
                     'getGuiScaledWidth', 'holdingStick', 'drawString')) {
        if ($txt.Contains($p) -and $p -ne 'holdingStick') { $leak += $p }
    }
    if ($leak) { "     !! Mojang method names still present: " + ($leak -join ', ') }
}
$z.Dispose()
