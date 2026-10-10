$ErrorActionPreference = 'Continue'
$java  = 'C:\Users\Administrator\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin\java.exe'
$javac = 'C:\Users\Administrator\AppData\Roaming\.minecraft\runtime\java-runtime-epsilon\bin\javac.exe'
$libs  = 'D:\.minecraft\libraries'
$proj  = 'D:\dsh-build\dshmusic-12111-fabric'
$build = "$proj\build"
$stage = "$build\stage"
$src   = "$proj\src\dev\dsh\music"

# The 26.3 build's jar is only used as a source of JLayer / mp3spi / tritonus,
# which are plain Java and contain no Minecraft references.
$audioSrc = 'D:\dsh-build\dshmusic-fabric\build\dshmusic-0.5.2-fabric.jar'

$rtCp = @(
    'D:\dsh-build\rtool',
    "$libs\net\fabricmc\fabric-loader\0.19.5\fabric-loader-0.19.5.jar",
    "$libs\org\ow2\asm\asm\9.10.1\asm-9.10.1.jar",
    "$libs\org\ow2\asm\asm-tree\9.10.1\asm-tree-9.10.1.jar",
    "$libs\org\ow2\asm\asm-util\9.10.1\asm-util-9.10.1.jar",
    "$libs\org\ow2\asm\asm-commons\9.10.1\asm-commons-9.10.1.jar",
    "$libs\org\ow2\asm\asm-analysis\9.10.1\asm-analysis-9.10.1.jar"
) -join ';'

# Each pass needs the classpath in THAT pass's SOURCE namespace, or tiny-remapper
# silently skips every member rename and the jar blows up with NoSuchMethodError.
$cpMojang   = "$libs\net\neoforged\minecraft-client-patched\21.11.42\minecraft-client-patched-21.11.42.jar"
$cpOfficial = 'D:\.minecraft\versions\1.21.11\1.21.11.jar'

$mojJar = "$build\dshmusic-mojang.jar"
$midJar = "$build\dshmusic-official.jar"
$finJar = "$build\dshmusic-0.5.2-fabric.jar"

Add-Type -AssemblyName System.IO.Compression.FileSystem

Write-Host '### 1/6 assemble classpath + compile'

# Find the Fabric profile by a mod only it has, so no non-ASCII path is written here.
$verDir = Get-ChildItem 'D:\.minecraft\versions' -Directory |
    Where-Object { Test-Path (Join-Path $_.FullName 'mods\diamondhud-0.5.0-fabric.jar') } |
    Select-Object -First 1
$verJson = Get-Content (Join-Path $verDir.FullName ($verDir.Name + '.json')) -Raw | ConvertFrom-Json

# The runtime's own jars must come FIRST. diamondhud's classpath was good enough for
# diamondhud, but it lists authlib 4.0.43 ahead of the 7.0.61 the game actually loads,
# and 4.0.43 still spells it GameProfile.getName(), so GameProfile.name() vanished.
$runtime = New-Object System.Collections.Generic.List[string]
foreach ($l in $verJson.libraries) {
    if ($l.downloads.artifact.path) {
        $runtime.Add((Join-Path $libs ($l.downloads.artifact.path -replace '/', '\')))
    } else {
        $p = $l.name.Split(':')
        $runtime.Add((Join-Path $libs ($p[0].Replace('.', '\') + "\$($p[1])\$($p[2])\$($p[1])-$($p[2]).jar")))
    }
}
$base = (Get-Content 'D:\dsh-build\diamondhud-fabric\build\cp.txt' -Raw).Trim() -split ';'
$merged = New-Object System.Collections.Generic.List[string]
foreach ($p in $runtime) { if ((Test-Path $p) -and -not $merged.Contains($p)) { $merged.Add($p) } }
foreach ($p in $base)   { if ($p -and (Test-Path $p) -and -not $merged.Contains($p)) { $merged.Add($p) } }
$cp = ($merged -join ';') -replace '\\', '/'
Write-Host ("  classpath {0} entries ({1} from the runtime json, listed first)" -f $merged.Count, $runtime.Count)

$files = Get-ChildItem $src -Filter *.java | Sort-Object Name
if (Test-Path "$build\classes") { Remove-Item "$build\classes" -Recurse -Force }
$argsTxt = @('-nowarn', '-encoding', 'UTF-8', '-d', "$proj/build/classes", '-cp', "`"$cp`"")
foreach ($f in $files) { $argsTxt += ($f.FullName -replace '\\', '/') }
# javac rejects a BOM on an @argfile
[System.IO.File]::WriteAllText("$build\javac.args", ($argsTxt -join "`n"),
    (New-Object System.Text.UTF8Encoding($false)))
& $javac "@$build\javac.args" 2>&1 | Select-Object -First 25
Write-Host ("  javac exit={0}  sources={1}  classes={2}" -f $LASTEXITCODE, $files.Count,
    (Get-ChildItem "$build\classes" -Recurse -File -ErrorAction SilentlyContinue).Count)
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host '### 2/6 stage'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item "$build\classes\*" $stage -Recurse -Force
Copy-Item "$proj\resources\*" $stage -Recurse -Force
foreach ($f in @($mojJar, $midJar, $finJar)) { if (Test-Path $f) { Remove-Item $f -Force } }
[System.IO.Compression.ZipFile]::CreateFromDirectory($stage, $mojJar)
Write-Host ("  mojang jar {0} bytes" -f (Get-Item $mojJar).Length)

Write-Host '### 3/6 pass 1  mojang -> official'
& $java -cp $rtCp RemapTool $mojJar $midJar 'D:\dsh-build\mojmap-official.tiny' mojang official $cpMojang 2>&1 |
    Where-Object { $_ -match 'ERROR|Exception|conflict' } | Select-Object -First 8
Write-Host ("  exit={0} -> {1} bytes" -f $LASTEXITCODE, (Get-Item $midJar).Length)

Write-Host '### 4/6 pass 2  official -> intermediary'
& $java -cp $rtCp RemapTool $midJar $finJar 'D:\dsh-build\inter\mappings.tiny' official intermediary $cpOfficial 2>&1 |
    Where-Object { $_ -match 'ERROR|Exception|conflict' } | Select-Object -First 8
Write-Host ("  exit={0} -> {1} bytes" -f $LASTEXITCODE, (Get-Item $finJar).Length)

Write-Host '### 5/6 merge JLayer / mp3spi / tritonus'
$zout = [System.IO.Compression.ZipFile]::Open($finJar, [System.IO.Compression.ZipArchiveMode]::Update)
$zin  = [System.IO.Compression.ZipFile]::OpenRead($audioSrc)
$copied = 0
$have = @{}
foreach ($e in $zout.Entries) { $have[$e.FullName] = $true }
foreach ($e in $zin.Entries) {
    $n = $e.FullName
    if ($n -like 'dev/*' -or $n -eq 'fabric.mod.json') { continue }
    if ($have.ContainsKey($n)) { continue }
    $dst = $zout.CreateEntry($n)
    $si = $e.Open(); $so = $dst.Open()
    $si.CopyTo($so); $so.Dispose(); $si.Dispose()
    $copied++
}
$zin.Dispose(); $zout.Dispose()
Write-Host ("  merged {0} entries -> {1} bytes" -f $copied, (Get-Item $finJar).Length)

Write-Host '### 6/6 verify'
$bad = @('net/minecraft/resources/', 'net/minecraft/client/gui/GuiGraphics',
         'net/minecraft/client/DeltaTracker', 'net/minecraft/world/item/Items',
         'fromNamespaceAndPath', 'net/minecraft/client/Minecraft',
         'net/minecraft/network/chat/Component', 'net/minecraft/util/Mth')
$z = [System.IO.Compression.ZipFile]::OpenRead($finJar)
$audioFiles = 0
$audioDirty = 0
$devFiles = 0
foreach ($e in $z.Entries | Where-Object { $_.FullName -like '*.class' }) {
    $ms = New-Object System.IO.MemoryStream; $e.Open().CopyTo($ms); $b = $ms.ToArray()
    $txt = [System.Text.Encoding]::ASCII.GetString($b)
    if ($e.FullName -notlike 'dev/dsh/music/*') {
        $audioFiles++
        if ($txt.Contains('net/minecraft/')) { $audioDirty++ }
        continue
    }
    $devFiles++
    $leftover = @()
    foreach ($p in $bad) { if ($txt.Contains($p)) { $leftover += $p } }
    $m = ([regex]::Matches($txt, 'method_')).Count
    $f = ([regex]::Matches($txt, 'field_')).Count
    $c = ([regex]::Matches($txt, 'net/minecraft/class_')).Count
    "{0,-38} class_{1,-3} method_{2,-3} field_{3,-3} {4}" -f $e.Name, $c, $m, $f,
        $(if ($leftover) { '!! LEFTOVER ' + ($leftover -join ',') } else { 'clean' })
}
$z.Dispose()
Write-Host ("  dev classes {0}, bundled audio classes {1} (MC refs inside: {2})" -f $devFiles, $audioFiles, $audioDirty)
