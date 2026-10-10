# 修复报告（v1.9 → v1.11）

基线：`VMOS-Float-Demo-v1.8-source`（versionCode 9 / 1.8）
结果：versionCode 12 / **1.11**，已编译通过并产出签名 APK。

## v1.11（加真机诊断仪表）

真机用 `dumpsys window` 采样球窗口 frame 的结论：**采样只能证明"球最后停在哪"，证不了
"停下的那一刻手指在哪"**——拖动期间球窗口是 `lp.x + translationX`（跟手靠 translation，
frame 里只有 lp.x），所以"松手停在 935"和"动画途经 935"在 frame 上长得一样，
0.6s 的采样周期也抓不住 200ms 级的竞态。

于是加了一组毫秒级诊断日志（tag `FloatDemo`，`TRACE` 常量可关），覆盖所有可能移动球的入口：

| 日志 | 位置 | 能回答什么 |
|---|---|---|
| `DOWN raw=(x,y) ballX=… winOpen=…` | 手势按下 | 按下瞬间的真实球位 |
| `UP moved=… winDrag=… ballX=…` | 手势抬起 | 这一下手势被判成了拖动/拉窗/点击 |
| `settle -> x=… y=…` | 落点结算 | 手指离开时球到底停在哪（y 变化=发生了新的 settle） |
| `animBallTo A -> B winOpen=… gesture=… dragging=…` | 每次球的动画 | **谁在动球**，当时的窗口与手势状态 |
| `openWin / closeWin / retractWin ballX=…` | 三个入口 | 是哪一个入口发起的 |
| `winAnim end …` | 窗户动画收尾 | 收尾时球是否被动过 |
| `applyBallLayout 失败…` | 缩回异常 | 全屏窗口兜底是否真的触发过 |

取日志：`adb-shell "logcat -d -s FloatDemo"`（只读，已实测可用）。

## v1.10（真机反馈修复）

真机反馈：**悬浮球在拖动结束后还会自己出现一段移动动画**。这是 v1.9 引入的回归，
两条路径都会在"用户没有开/关窗意图"时动画移动球：

| # | 根因 | 修法 | 位置 |
|---|---|---|---|
| A | v1.8 的"球回把手位"自动同步：窗户动画收尾时若球不在把手位就把球 `animBallTo` 过去。v1.9 把 `mBallAnim` 句柄复位后，这段原本失效的代码被**激活**了 | 整段删除。它要修的"球与窗户左缘脱钩"其实无害：窗户全开时球的 x 不参与几何计算，点球/右拉收窗都会把球带回把手位（自愈） | `FloatingBallService.java:679` |
| B | 拉窗没过半的中止路径走的是 `closeWin()`，它会把球动画滑回右缘。v1.9 把拉窗入口放宽到"球在右半屏"后，**一次普通的水平拖球**也会被当成拉窗 → 每次松手球都自己滑一次 | 新增 `retractWin()`：只把窗户缩回去，球停在松手处 | `:582`、`:666` |

修完后的不变量：**只有"用户明确开窗 / 关窗"两条路径会动画移动球**
（`openWin()` → 球到左缘；`closeWin()` → 球到右缘，由"拉窗过半""点球一键收回""菜单关闭窗户"触发）；
其余任何情况球都停在松手处。`animBallTo` 的调用点已收敛到这两处。

## v1.9（首轮，11 类问题）

| # | 级别 | 问题（v1.8） | 修法 | 位置 |
|---|---|---|---|---|
| 1 | 🔴 崩溃 | minSdk 24 却无条件 `new NotificationChannel` / `new Notification.Builder(ctx, channelId)`（都是 API 26），Android 7.x 一启动服务就 `NoClassDefFoundError` | 加 `SDK_INT >= O` 分支，旧版走单参 Builder；`stopForeground` 去掉恒真的 `>= N` 判断 | `FloatingBallService.java:236`、`:862` |
| 2 | 🟠 交互断链 | v1.8 删掉松手贴边后，拉窗入口仍要求"贴右缘 16px 内"，球停在中间/左半屏就再也拉不出窗户 | 改为半屏判据 `canOpen = !mWinOpen && mStartX >= mScreenW/2`；横向门槛 20px → 20dp | `:505` |
| 3 | 🟠 全屏吞触摸 | 拖动时球窗口扩成全屏且可触摸，缩回失败被 `catch (Throwable ignore)` 吞掉，可永久留下吞掉全屏触摸的空窗口 | 新增看门狗：扩窗即挂，保证一定缩回；缩回失败回滚尺寸并重试（不撒谎）；窗户"熄灭"失败同样兜底 | `:87`、`:327`、`:615` |
| 4 | 🟡 死代码 + 抢手势 | `animBallTo` 跑完不复位 `mBallAnim`，`mBallAnim == null` 永远为假 → v1.8 的"球回把手位"同步是死代码；若真触发又会与按住球的手指抢球（`mDownX/mStartX` 未同步 → 坐标跳变） | 动画 `onAnimationEnd` 里复位句柄；同步逻辑补 `!mGestureActive && !mDragging` 守卫（新增 `mGestureActive`） | `:728`、`:692` |
| 5 | 🟡 旋转失效 | 屏幕尺寸只在 `onCreate` 取一次，旋转/折叠/分屏后贴边判定、`setWinProgress`、clamp 全用旧值 | 新增 `refreshMetrics()`（DisplayManager 真实尺寸）+ `applyMetricsChange()`；`onConfigurationChanged` 与 `DisplayListener` 双通道触发 | `:181`、`:225` |
| 6 | 🟡 菜单错位 | 菜单定位用硬编码 `mw=440, mh=430` **px** 估计（xxhdpi 上仅 ~147dp），"永不压球 / 不越界"的前提失效 | 先 `measure()` 再定位，留白与间距 dp 化 | `:792` |
| 7 | ⚪ 性能 | `VmScreenView.onDraw` 每帧 `new SimpleDateFormat` + `new RadialGradient`（60/s 垃圾） | 缓存格式化器；shader 仅在色相漂移 ≥1.5° 或尺寸变化时重建 | `VmScreenView.java:92`、`:112` |
| 8 | ⚪ 重复代码 | `onDraw` 与 `hitApp` 各写一遍网格公式（改一处忘一处 = "看得到点不中"） | 抽成 `gridTop/cellSize/iconCx/iconCy` 唯一来源 | `VmScreenView.java:152` |
| 9 | ⚪ 构建 | 写死 `/home/z/android-sdk`、ECJ、keystore 绝对路径 + 明文口令；`java ... \|\| true` 丢掉退出码；aapt2 未传 min/target SDK | 环境变量 + 自动探测；keystore 缺失自动生成；以退出码判定并兼容 javac/ECJ 两种错误格式；补 `--min-sdk-version/--target-sdk-version` | `build.sh:47`、`:100`、`:123` |
| 10 | ⚪ 交付 | 无 launcher 图标；无版本控制 | 新增 `res/drawable/ic_launcher.xml` 并在 manifest 引用；`git init` + 首次提交 + `.gitignore` | `AndroidManifest.xml`、`.gitignore` |
| 11 | ⚪ 文案 | 类注释与主界面说明仍在描述 v1.8 已删除的"松手贴边" | 按实际行为重写三处文案 | `FloatingBallService.java:24`、`MainActivity.java:41` |

**保留不改（附理由）**
- `targetSdk` 仍为 28：这是真机验证过的组合；升到 33/34 需要通知运行时权限与前台服务类型，已在 manifest 里预置声明并写明升级路线，不擅自改行为。
- `getDefaultDisplay()/getRealMetrics()` 的 deprecation 警告：`WindowMetrics` 需要 API 30，而 minSdk 是 24，保留旧 API 才能一套代码通吃。
- 悬浮球尺寸 `BALL=128px` 未 dp 化：这是作者调过的手感值，不属于缺陷，改动会改变观感。
- overlay "不消费则穿透下层" 依赖系统既定行为，未改造（改造需要每帧 binder，违背零 binder 设计）。

> 表中行号是各自修改当时的快照；v1.10 之后文件行号有位移。

## 验证

```
$ ANDROID_SDK_ROOT=/opt/android-sdk ./build.sh
== 1/5 资源编译链接（minSdk=24 targetSdk=28）
== 2/5 编译 Java          （javac 17，0 error，仅 3 条已知 deprecation 警告）
== 3/5 d8 出 dex
== 4/5 打包
== 5/5 签名
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
```

- 产物：`build/VMOS-Float-Demo.apk`（25 KB，package `dev.twinbox.floatdemo`，versionCode 11 / 1.10，minSdk 24 / targetSdk 28）
- dex 内含 `MainActivity` / `FloatingBallService` / `VmScreenView` / `R` 系列
- manifest 校验：图标、`foregroundServiceType=specialUse`、四个权限均按预期落地
- `zipalign -c -v 4` 校验通过
- 真机状态：手机已装 versionCode 10 / 1.9（本次 v1.10 与原 v1.8 无关，是同一把新签名库签的，可直接覆盖安装）

**未验证（需要真机操作反馈）**：手势手感、"半开窗户下方触摸穿透"——容器里没有屏幕，只能静态推导。

## 签名说明

- v1.9 / v1.10 用的是新建签名库 `build/twinbox.keystore`（口令取自 `KS_PASS`，默认值仅供本地调试）；
  手机上现在装的 1.9 就是这把钥匙签的，所以 **v1.10 可以直接覆盖安装**。
- 最初的 v1.8 是 `/home/z/my-project/vmclone/twinbox2/build/twinbox.keystore` 签的。
  若以后要回到原来的签名身份（例如与 `dev.twinbox.app` 双生盒共用签名），请在有原签名库的机器上：

```bash
KS=/home/z/my-project/vmclone/twinbox2/build/twinbox.keystore KS_PASS=<原口令> ./build.sh
```
