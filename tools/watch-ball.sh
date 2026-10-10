#!/usr/bin/env bash
# 客观验证"松手后悬浮球是否还会自己动"。
#
# 原理：球的位移动画走的是 WindowManager.LayoutParams.x（lp.x）+ updateViewLayout，
# 所以只要按时间采样悬浮窗的 frame，就能看出松手之后坐标是否还在变——
# 不需要截屏，也不依赖无障碍服务。
#
# 用法：
#   tools/watch-ball.sh [采样秒数] [采样间隔秒] [包名]
#   tools/watch-ball.sh 40 0.15
#
# 读法：
#   * 常态球窗口 128×128，期望 frame=[952,792][1080,920]（1080×2376 屏，球贴右缘、y=屏高/3）
#   * 窗户窗口：关窗态 0×0；全开态 1080×2376
#   * 松手后若出现"坐标在若干帧内连续变化后停下"，就是一段动画——这正是 v1.9 的 bug
set -uo pipefail

DUR="${1:-30}"
IV="${2:-0.2}"
PKG="${3:-dev.twinbox.floatdemo}"
ADB="/root/dsh-bin/adb-shell"

end=$(( $(date +%s) + DUR ))
echo "# 采样 ${DUR}s / 每 ${IV}s，包名 $PKG"
while [ "$(date +%s)" -lt "$end" ]; do
  ts=$(date +%H:%M:%S.%3N)
  "$ADB" "dumpsys window windows" 2>/dev/null | tr -d '\r' | awk -v ts="$ts" -v pkg="$PKG" '
    /^  Window #/ {
      if (want) printf "%s  %-12s %s\n", ts, wname, frame
      want = 0; frame = ""
      wname = $0
      sub(/^.*Window\{[^ ]+ [^ ]+ /, "", wname)
      sub(/\}:.*/, "", wname)
      next
    }
    $0 ~ ("package=" pkg) { want = 1 }
    /Frames:/ {
      if (match($0, /frame=\[[0-9-]+,[0-9-]+\]\[[0-9-]+,[0-9-]+\]/))
        frame = substr($0, RSTART, RLENGTH)
    }
    END { if (want) printf "%s  %-12s %s\n", ts, wname, frame }
  '
  sleep "$IV"
done
