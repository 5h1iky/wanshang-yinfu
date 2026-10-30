# watch-ui.ps1 —— 手表真机验证助手（2026-10-01 建，软件内问题第一批验证用）
# ⚠️ 本文件必须带 UTF-8 BOM（Windows PowerShell 5.1 无 BOM 时按 GBK 读，中文注释会解析失败）
#
# 为什么需要它：
#   ① uiautomator dump **只导出"当前活动窗口"** —— 两个弹窗叠在一起时，dump 里只看得到上面那个，
#      所以"屏幕上到底有几个弹窗"不能靠 dump 数节点，要靠窗口列表（见下）。
#   ② 弹窗与 Activity 的窗口在 dumpsys window 里有明确区别：
#         Activity 本身 = ty=BASE_APPLICATION + fillxfill
#         我们自绘的弹窗 = ty=APPLICATION + fmt=TRANSPARENT + 小 frame（DyDialog 的 92% 宽）
#      → 数"ty=APPLICATION 且 isVisible=true 的 com.dywatch.app 窗口"就是弹窗个数（可证伪的数值）。
#
# 用法：
#   powershell -File tools\watch-ui.ps1                      # 数弹窗 + 列出可见文本（含中心坐标）
#   powershell -File tools\watch-ui.ps1 -TapX 282 -TapY 330  # 先点这个坐标再量
param(
    [string]$Serial = $env:WATCH_SERIAL,
    [int]$TapX = -1,
    [int]$TapY = -1,
    [int]$SettleMs = 1500
)

# 本机 adb 路径：可用环境变量 ADB 覆盖
$adb = if ($env:ADB) { $env:ADB } else { "E:\sdk\platform-tools\adb.exe" }

# 未指定序列号时取第一台在线设备（原脚本曾写死某台手表的序列号）
if (-not $Serial) {
    $Serial = (& $adb devices) | Select-String -Pattern '\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] } | Select-Object -First 1
    if (-not $Serial) { Write-Error "没有在线设备：请用 USB 连上手表，或用 -Serial 指定序列号"; exit 1 }
}
$tmp = Join-Path $env:TEMP "dywatch-verify"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

if ($TapX -ge 0 -and $TapY -ge 0) {
    & $adb -s $Serial shell input tap $TapX $TapY | Out-Null
    Start-Sleep -Milliseconds $SettleMs
}

# ---------- 1. 弹窗计数（窗口列表口径）----------
& $adb -s $Serial shell dumpsys window windows > "$tmp\win.txt" 2>&1
$raw = (Get-Content "$tmp\win.txt") -join "`n"
$pops = @()
$acts = @()
foreach ($b in ($raw -split '(?=Window #\d+ Window\{)')) {
    if ($b -notmatch 'Window #\d+ Window\{(\w+) u0 ([^}]+)\}:') { continue }
    $id = $Matches[1]; $title = $Matches[2]
    if ($title -notlike 'com.dywatch.app*') { continue }
    $attrsLine = (($b -split "`n") | Where-Object { $_ -match 'mAttrs=' } | Select-Object -First 1)
    $ty = '?'
    if ($attrsLine -match 'ty=(\w+)') { $ty = $Matches[1] }
    $visible = ($b -match 'isVisible=true')
    $frame = ''
    if ($b -match 'mFrame=(\[[^\]]+\]\[[^\]]+\])') { $frame = $Matches[1] }
    $rec = [PSCustomObject]@{ Id = $id; Ty = $ty; Visible = $visible; Frame = $frame }
    if (-not $visible) { continue }
    if ($ty -eq 'APPLICATION') { $pops += $rec } elseif ($ty -eq 'BASE_APPLICATION') { $acts += $rec }
}

"== 弹窗数（ty=APPLICATION 且可见）= $($pops.Count) =="
foreach ($p in $pops) { "   弹窗 window={0} frame={1}" -f $p.Id, $p.Frame }
"   Activity 窗口数 = $($acts.Count)"
if ($pops.Count -gt 1) { "   ⚠️ 异常：同时有 $($pops.Count) 个弹窗（判据要求 <= 1）" }

# ---------- 2. 当前活动窗口的可见文本（含中心坐标，便于点按）----------
& $adb -s $Serial shell uiautomator dump /sdcard/ui.xml | Out-Null
& $adb -s $Serial pull /sdcard/ui.xml "$tmp\ui.xml" 2>$null | Out-Null
try {
    [xml]$x = Get-Content "$tmp\ui.xml" -Encoding UTF8
    "== 可见文本节点（当前活动窗口）=="
    $x.SelectNodes("//node") | Where-Object { $_.text -or $_.'content-desc' } | ForEach-Object {
        $txt = ($_.text + $_.'content-desc') -replace "`r?`n", ' / '
        if ($txt.Length -gt 44) { $txt = $txt.Substring(0, 44) + [char]0x2026 }
        $b = $_.bounds
        $cx = 0; $cy = 0
        if ($b -match '\[(\d+),(\d+)\]\[(\d+),(\d+)\]') {
            $cx = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
            $cy = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
        }
        "   {0,-30} | 中心({1},{2}) | {3}" -f $txt, $cx, $cy, $b
    }
} catch {
    "   （ui.xml 解析失败：$($_.Exception.Message)）"
}
