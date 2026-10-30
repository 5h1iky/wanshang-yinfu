#!/system/bin/sh
# pinch.sh —— 用 sendevent 注入一次"双指张开"（软件内问题 ⑤ 的缩放验证用）。
# adb 没有多点触控 API（input 只有单指 tap/swipe），所以只能直接写触摸设备。
# 设备：cyttsp5_mt = /dev/input/event1，协议 B（ABS_MT_SLOT / TRACKING_ID / POSITION_X/Y）
# 用法：sh /data/local/tmp/pinch.sh [中心x] [中心y] [起始半距] [结束半距]
DEV=/dev/input/event1
CX=${1:-186}
CY=${2:-215}
D0=${3:-25}
D1=${4:-150}

set_slot() {  # $1=slot  $2=x  $3=y
  sendevent $DEV 3 47 $1
  sendevent $DEV 3 53 $2
  sendevent $DEV 3 54 $3
}

# 两指落下
sendevent $DEV 3 47 0
sendevent $DEV 3 57 2001
set_slot 0 $((CX - D0)) $CY
sendevent $DEV 3 47 1
sendevent $DEV 3 57 2002
set_slot 1 $((CX + D0)) $CY
sendevent $DEV 1 330 1
sendevent $DEV 0 0 0
sleep 0.05

# 逐步分开（10 步，让 ScaleGestureDetector 拿到连续变化）
i=1
while [ $i -le 10 ]; do
  D=$(( D0 + (D1 - D0) * i / 10 ))
  set_slot 0 $((CX - D)) $CY
  set_slot 1 $((CX + D)) $CY
  sendevent $DEV 0 0 0
  sleep 0.03
  i=$((i + 1))
done
sleep 0.05

# 抬起两指
sendevent $DEV 3 47 0
sendevent $DEV 3 57 4294967295
sendevent $DEV 3 47 1
sendevent $DEV 3 57 4294967295
sendevent $DEV 1 330 0
sendevent $DEV 0 0 0
echo "pinch done: center=($CX,$CY) $D0 -> $D1"
