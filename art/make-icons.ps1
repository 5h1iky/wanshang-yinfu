# Icon adaptation script (2026-09-27)
# Input : art/icon_raw.jpg  (user-made, 1187x1227, square corners)
# Output: mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png       (rounded corners)
#         mipmap-{...}/ic_launcher_round.png                            (circular)
#
# Design decisions:
#  - The bottom ~1/4 of the source is a grey bar with the word "wear" (part of the user's
#    design -> keep it).
#  - Source is not square (1187x1227). Strategy: crop extra height from the TOP (the logo
#    has ~100px of empty black above it), so the "wear" bar keeps its original bottom margin.
#  - Corner radius 22% of size (standard rounded-square look).
#  - Anti-aliased edges via TextureBrush + FillPath (SetClip would give hard jaggies).
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads UTF-8-without-BOM as ANSI and
#       mangles non-ASCII bytes, which can break parsing.

Add-Type -AssemblyName System.Drawing

# Paths are derived from this script's own location (2026-10-02, code audit low-severity item):
# they used to be hardcoded to D:\dev\dywatch, so a clone elsewhere could not regenerate icons.
# $PSScriptRoot = <repo>\art  ->  repo root is its parent.
$repoRoot = Split-Path -Parent $PSScriptRoot
$srcPath = Join-Path $PSScriptRoot "icon_raw.jpg"
$outRoot = Join-Path $repoRoot "app\src\main\res"
Write-Host ("repo: {0}" -f $repoRoot)

$src = [System.Drawing.Bitmap]::FromFile($srcPath)
$w = $src.Width
$h = $src.Height
Write-Host ("source: {0}x{1}" -f $w, $h)

# ---- 1. Analysis: grey-bar seam + brightest content extent ----
$seam = -1
for ($y = 0; $y -lt $h; $y++) {
    $p = $src.GetPixel(20, $y)
    if ((($p.R + $p.G + $p.B) / 3) -gt 18) { $seam = $y; break }
}
Write-Host ("seam (left column, first non-black row): {0}" -f $seam)

$textTop = -1
$textBottom = -1
for ($y = 0; $y -lt $h; $y++) {
    $found = $false
    for ($x = 0; $x -lt $w; $x += 5) {
        $p = $src.GetPixel($x, $y)
        if ($p.R -gt 190 -and $p.G -gt 190 -and $p.B -gt 190) { $found = $true; break }
    }
    if ($found) {
        if ($textTop -lt 0) { $textTop = $y }
        $textBottom = $y
    }
}
Write-Host ("bright content rows: {0}..{1} ; padding below = {2}" -f $textTop, $textBottom, ($h - 1 - $textBottom))

# ---- 2. Build square master image ----
$square = $w
$needCrop = $h - $square
Write-Host ("square={0} needCrop={1} textTop={2} w={3} h={4}" -f $square, $needCrop, $textTop, $w, $h)

$mode = "squash"
if ($needCrop -gt 0 -and $textTop -ge ($needCrop + 10)) { $mode = "crop-top" }
if ($needCrop -le 0) { $mode = "already-square" }
Write-Host ("strategy: {0}" -f $mode)

$base = New-Object -TypeName System.Drawing.Bitmap -ArgumentList $square, $square
$gb = [System.Drawing.Graphics]::FromImage($base)
$gb.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$gb.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
if ($mode -eq "crop-top") {
    # Shift up then draw unscaled: canvas clips the bottom 40px automatically.
    $gb.TranslateTransform(0, -$needCrop)
    $gb.DrawImageUnscaled($src, 0, 0)
} else {
    $gb.DrawImage($src, 0, 0, $square, $square)
}
$gb.Dispose()
$base.Save((Join-Path $PSScriptRoot "icon_square.png"), [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "master square saved: art/icon_square.png"

# ---- 3. Per-density export ----
$densities = [ordered]@{
    "mipmap-mdpi"    = 48
    "mipmap-hdpi"    = 72
    "mipmap-xhdpi"   = 96
    "mipmap-xxhdpi"  = 144
    "mipmap-xxxhdpi" = 192
}
$radiusFactor = 0.22

function New-RoundedPath([int]$size, [double]$factor) {
    $path = New-Object -TypeName System.Drawing.Drawing2D.GraphicsPath
    $r = [int]($size * $factor)
    $d = $r * 2
    $path.AddArc(0, 0, $d, $d, 180, 90)
    $path.AddArc($size - $d, 0, $d, $d, 270, 90)
    $path.AddArc($size - $d, $size - $d, $d, $d, 0, 90)
    $path.AddArc(0, $size - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function New-CirclePath([int]$size) {
    $path = New-Object -TypeName System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse(0, 0, $size, $size)
    $path.CloseFigure()
    return $path
}

function Export-Icon([System.Drawing.Bitmap]$master, [int]$size, [bool]$round, [string]$outPath) {
    $canvas = New-Object -TypeName System.Drawing.Bitmap -ArgumentList $size, $size
    $g = [System.Drawing.Graphics]::FromImage($canvas)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::Transparent)

    $scaled = New-Object -TypeName System.Drawing.Bitmap -ArgumentList $size, $size
    $gs = [System.Drawing.Graphics]::FromImage($scaled)
    $gs.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gs.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gs.DrawImage($master, 0, 0, $size, $size)
    $gs.Dispose()

    $path = New-RoundedPath $size $radiusFactor
    if ($round) { $path = New-CirclePath $size }
    $brush = New-Object -TypeName System.Drawing.TextureBrush -ArgumentList $scaled
    $g.FillPath($brush, $path)
    $brush.Dispose()
    $path.Dispose()
    $g.Dispose()
    $scaled.Dispose()
    $canvas.Save($outPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $canvas.Dispose()
}

foreach ($k in $densities.Keys) {
    $size = $densities[$k]
    $dir = Join-Path $outRoot $k
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Export-Icon $base $size $false (Join-Path $dir "ic_launcher.png")
    Export-Icon $base $size $true  (Join-Path $dir "ic_launcher_round.png")
    Write-Host ("  {0}: ic_launcher.png + ic_launcher_round.png @ {1}px" -f $k, $size)
}
$base.Dispose()
$src.Dispose()

# ---- 4. Self check: content present in middle, corners transparent ----
$fails = 0
function Test-Icon([string]$path, [string]$label) {
    $bmp = [System.Drawing.Bitmap]::FromFile($path)
    $s = $bmp.Width
    $corner = $bmp.GetPixel(1, 1)
    $mid = $bmp.GetPixel([int]($s / 2), [int]($s / 2))
    $bright = 0
    for ($y = 0; $y -lt $s; $y += 2) {
        for ($x = 0; $x -lt $s; $x += 2) {
            $p = $bmp.GetPixel($x, $y)
            if ($p.R -gt 190 -and $p.G -gt 190 -and $p.B -gt 190) { $bright++ }
        }
    }
    $bmp.Dispose()
    $ok = ($corner.A -lt 40) -and ($bright -gt 5) -and ($mid.A -gt 200)
    $flag = "FAIL"
    if ($ok) { $flag = "OK" }
    Write-Host ("  [{0}] {1} {2}px cornerA={3} midA={4} brightPx={5}" -f $flag, $label, $s, $corner.A, $mid.A, $bright)
    if (-not $ok) { $script:fails++ }
}
Test-Icon (Join-Path $outRoot "mipmap-xxhdpi\ic_launcher.png") "rounded-xxhdpi"
Test-Icon (Join-Path $outRoot "mipmap-xxhdpi\ic_launcher_round.png") "round-xxhdpi"
Test-Icon (Join-Path $outRoot "mipmap-mdpi\ic_launcher.png") "rounded-mdpi"
Test-Icon (Join-Path $outRoot "mipmap-xxxhdpi\ic_launcher.png") "rounded-xxxhdpi"
if ($fails -gt 0) { Write-Host "SELF-CHECK FAILED"; exit 1 }
Write-Host "SELF-CHECK PASSED"
