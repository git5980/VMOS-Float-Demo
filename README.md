# VMOS 浮窗 Demo

复刻 VMOS Pro「悬浮球 + 拉出式窗户」交互的 Android 演示工程。

纯 View 实现，**不需要 root、不需要虚拟机、不需要任何系统特权**——只依赖用户手动授予的
`SYSTEM_ALERT_WINDOW`。全部交互逻辑在 `FloatingBallService.java` 一个文件里。

| | |
|---|---|
| 包名 | `dev.twinbox.floatdemo` |
| 版本 | versionCode 21 / versionName 1.18 |
| SDK | minSdk 24（Android 7.0）/ targetSdk 28 |
| 权限 | `SYSTEM_ALERT_WINDOW`、`FOREGROUND_SERVICE` |

## 交互模型

**悬浮球就是窗户的把手**：

```
窗户露出量 = 屏宽 − 球的 x         （两者双向绑死，任何时刻窗户左缘 ≡ 球的左边）
```

- **球只跟手**：可以上下、斜着拖；球的可见位置恒等于它的逻辑位置
- **拉出窗户**：球在右半屏向左拖，窗户从右边缘被拉出，拉多少出多少
- **松手判定**：露出量过半 → 窗户自己滑到全开（球贴左缘）；不足 → 收回去（球贴右缘）
- **两种情况都是"窗户带着球走"**，球没有独立动画
- **竖直拖动**只移动球，窗户不介入
- **点球**：窗户开着 = 一键收回；没开 = 弹出功能菜单

## 技术要点

- 球和窗户都是 `TYPE_APPLICATION_OVERLAY` 悬浮窗，拖动跟手在 App 进程内完成，
  不做 task 切换、不跨进程取帧
- **球窗口就是球本身**（128×128，位置=球的真实位置）：不用 `translationX/Y` 跟手，
  因此不存在"内容被窗口画布裁掉 = 球消失"，也不存在"清 translation 与搬窗口之间的错位帧"
- 窗户开合时由露出量反推球的 x，球被窗户左缘带着走，**永不脱钩**
- 关窗态是"真正不存在"：窗口 0×0 + 根视图 GONE + `FLAG_NOT_TOUCHABLE`，
  零像素、零触摸遮挡、零合成负担，逐帧重绘心跳也随之停止
- 手势判定：横向进入门槛 = 系统 `touchSlop`；`ACTION_POINTER_DOWN` 判为"死亡手势"
- 屏幕尺寸变化（旋转 / 折叠 / 分屏）会重算并重摆窗口
- 诊断日志（tag `FloatDemo`）同时写 `Log.d` 与 **外部文件**：
  `Android/data/dev.twinbox.floatdemo/files/trace.log`（满 512 KB 轮转）。
  写成文件是因为部分 ROM（如 ColorOS）会吞掉第三方应用的 `Log.d`

## 构建

不用 Gradle，一条脚本走 `aapt2 → javac/ECJ → d8 → zipalign → apksigner`：

```bash
ANDROID_SDK_ROOT=/path/to/android-sdk ./build.sh
# 产物：build/VMOS-Float-Demo.apk
```

- 需要 JDK（17 验证过）、Android SDK 的 `build-tools` 与 `platforms`（默认 34.0.0 / android-34）
- `aapt2` 只有 x86-64 版本，在 **arm64 Linux / Termux** 上会自动回退到旧版 `aapt`（Debian/Ubuntu 有 arm64 包）
- 签名库不存在时自动生成；想沿用原有签名（才能覆盖安装）就显式传：
  `KS=/path/to.keystore KS_PASS=口令 ./build.sh`
- 缺工具时脚本会明确告诉你缺哪个、怎么装

## 代码结构

```
src/dev/twinbox/floatdemo/
  FloatingBallService.java   悬浮球 + 窗户 + 菜单的全部逻辑（核心）
  MainActivity.java          授权页：申请悬浮窗权限、启停服务
  VmScreenView.java          自绘"虚拟机画面"（模拟内容：动态背景/粒子/时钟/应用网格）
res/drawable/                球底、菜单底、启动图标
tools/watch-ball.sh          用 dumpsys window 采样球窗口坐标的验证脚本
docs/evidence/               真机 trace 日志与对照分析
FIXES.md                     v1.9 → v1.18 逐版修复报告（现象/根因/修法/证据）
```

## 说明与限制

- **本工程不含虚拟化**：`VmScreenView` 是自绘的模拟画面。真实的 VMOS 是 VM 帧流
  （libmci）渲染进宿主 `TextureView`，本 Demo 只复刻**悬浮窗与手势层**这一半
- `targetSdk` 保持 28（真机验证过的组合）。升到 33/34 需要通知运行时权限、
  前台服务类型等，`AndroidManifest.xml` 里已预置声明并注明路线
- 关窗态依赖"overlay 窗口不消费则触摸穿透到下层"这一系统行为

## 许可

仅供学习与交流。
