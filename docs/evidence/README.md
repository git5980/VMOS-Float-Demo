# 真机验证证据

设备：OnePlus PKR110 / Android 16 (SDK 36)，屏幕 1080×2376，density 3.0
版本：v1.12（versionCode 13），含 v1.10 的修复
日志：`v1.12-trace.log`（应用自己写的外部文件，取法见下）

```bash
adb-shell "cat /sdcard/Android/data/dev.twinbox.floatdemo/files/trace.log"
```

## 本次会话（16:54:34 起，约 20 秒）

| 指标 | 值 |
|---|---|
| 手势按下次数 (DOWN) | 16 |
| 其中拉窗/收窗手势 (winDrag=true) | 8 |
| **球的自动位移动画 (animBallTo)** | **4，全部紧跟在 openWin / closeWin 之后** |
| 缩回失败告警 (applyBallLayout 失败) | 0 |

4 次 `animBallTo` 明细（都是"用户明确开/关窗"的把手动画）：

```
16:54:48.679 animBallTo  86 -> 0     ← openWin（拉窗过半）
16:54:50.451 animBallTo 782 -> 952   ← closeWin（收窗过半）
16:54:51.922 animBallTo 135 -> 0     ← openWin
16:54:53.292 animBallTo 984 -> 952   ← closeWin
```

## 对照：v1.9 在同一段操作里会动球多少次

判据取 v1.9 那段被激活的"球回把手位"同步——窗户动画收尾且未被取消时，只要球不在
把手位就把球 `animBallTo` 过去。本会话里满足该条件的时刻：

```
16:54:37.908  ballX=891  → v1.9 会把球从 891 动画到 952
16:54:39.362  ballX=813  → …
16:54:40.433  ballX=740
16:54:40.975  ballX=740
16:54:42.628  ballX=851
16:54:43.561  ballX=851
16:54:44.393  ballX=853
16:54:45.215  ballX=853
16:54:46.063  ballX=854
16:54:46.884  ballX=859
16:54:47.616  ballX=886
```

共 **11 次**（v1.12 里这 11 次全部没发生）。

其中最典型的一次就是用户描述的"拖动结束后球又动了"：

```
16:54:42.270 DOWN raw=(1043,1025) ballX=952        ← 按下球（贴右缘）
16:54:42.397 UP   moved=true winDrag=true          ← 右半屏向左拖了一点点
16:54:42.398 settle -> x=851                        ← 手指松开，球就地停在 851
16:54:42.399 retractWin ballX=851                   ← v1.10 修复：只收窗，不动球
16:54:42.628 winAnim end target=0 cancelled=false ballX=851
                 ↑ v1.9 在这里会把球 851 → 952 滑出去（本次没有 animBallTo）
```
