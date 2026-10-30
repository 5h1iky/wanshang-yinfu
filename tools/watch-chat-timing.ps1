param([string]$Serial = $env:WATCH_SERIAL)
# 本机 adb 路径与设备序列号都可用环境变量覆盖
$adb = if ($env:ADB) { $env:ADB } else { 'E:\sdk\platform-tools\adb.exe' }
if (-not $Serial) { $Serial = (& $adb devices) | Select-String -Pattern '\s+device$' | ForEach-Object { ($_.Line -split '\s+')[0] } | Select-Object -First 1 }
if (-not $Serial) { Write-Error '没有在线设备：请连上手表'; exit 1 }
$s = $Serial
& $adb -s $s shell am force-stop com.dywatch.app
& $adb -s $s logcat -c
Start-Sleep 2
& $adb -s $s shell am start -n com.dywatch.app/.MainActivity | Out-Null
Start-Sleep 6
& $adb -s $s shell input tap 274 132   # 主屏"聊天"
$t0=Get-Date
for($i=0;$i -lt 16;$i++){
  Start-Sleep 15
  $el=[int]((Get-Date)-$t0).TotalSeconds
  & $adb -s $s shell uiautomator dump /sdcard/ui.xml | Out-Null
  & $adb -s $s pull /sdcard/ui.xml "$env:TEMP\wt.xml" | Out-Null
  try {
    [xml]$x=Get-Content "$env:TEMP\wt.xml" -Encoding UTF8
    $texts=($x.SelectNodes('//node') | ForEach-Object { $_.GetAttribute('text') } | Where-Object { $_ -and $_ -match '正在|卡在|会话|加载|失败|重试' }) -join ' | '
  } catch { $texts='(dump 失败)' }
  "{0,4}s: {1}" -f $el, $texts
}
Write-Output '--- 关键日志 ---'
& $adb -s $s logcat -d -s dywatch | Select-String 'conversations|页面加载完成|补丁|桥注入|ready|auth' | Select-Object -Last 12
