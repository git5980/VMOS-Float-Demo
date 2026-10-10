package dev.twinbox.floatdemo;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * VMOS 悬浮窗功能 Demo（架构复刻）。
 *
 * 交互（v1.14 最终形态）：
 *  - **窗户与球绑死**：窗户左缘 = 球的左边，露出量 = 屏宽 − 球的 x；
 *  - 球跟手拖动（拖动中半透明），**可上下移动**；球被限制在完全可见范围内，
 *    永不出现"被推出屏外/被裁剪=消失"；
 *  - 球在右半屏向左拖 → 窗户跟手拉出（内容实时渲染）；过半松手全开、
 *    不足收回——两种情况都是**窗户带着球走**，球不会自己单独跑一段；
 *  - 窗户开着时向右拖 → 窗户收回（跟手），过半=全收；
 *  - 点球：窗户开着=一键收回（窗户合、球随边缘回右缘）；没开=弹菜单。
 *
 * 技术（逆向 VMOS 3.1.4 得到的架构）：
 *  - 球和窗户都是 TYPE_APPLICATION_OVERLAY 悬浮窗；
 *  - 窗户内容 = App 自己的 View（VMOS 是 VM 帧流渲染进 TextureView，
 *    Demo 用自绘 VmScreenView 模拟——同为"进程内渲染"，所以拖动/缩放/
 *    开合全是 View 层动画，零 task 切换；准确说不是"零特权"，而是
 *    只依赖用户手动授予的 SYSTEM_ALERT_WINDOW，不需要 root / VM 特权）；
 *  - 拖动跟手用 translationX/Y（App 进程渲染，零 binder），松手才结算；
 *    贴边/开合收尾动画走 updateViewLayout（每帧一次 IPC，仅 200ms 量级）。
 *
 * v1.9 修复（本轮）：
 *  - minSdk 24 上无判断调用 API 26 的 NotificationChannel/Builder → 7.x 启动即崩；
 *  - v1.8 删掉松手贴边后，"必须贴右缘 16px"的拉窗入口让球停在中间时彻底拉不出窗；
 *  - 拖动期球窗口扩成全屏且可触摸，缩回失败会被 catch 吞掉 → 全屏吞触摸；
 *  - mBallAnim 跑完不复位为 null，"球回把手位"同步逻辑成死代码，真触发时抢手势；
 *  - 屏幕尺寸只在 onCreate 取一次，旋转/折叠后所有几何失效；
 *  - 手势阈值与菜单用硬编码 px（高 DPI 上过小）。
 *
 * v1.10 修复（用户反馈"拖动结束后球又出现位移动画"）：
 *  - 删掉 v1.8 的"球回把手位"自动同步（v1.9 复位动画句柄后它被激活了，
 *    会在窗户动画收尾时无用户意图地挪球）；
 *  - 拉窗没过半的中止路径改为 retractWin()（只收窗不动球），不再 closeWin()。
 *  ⇒ 现在只有"用户明确开窗/关窗"这两条路径会动画移动球，其余情况球一律停在松手处。
 *
 * v1.14 修复（按用户明确规格重做绑定关系）：
 *  - 球为主动方：窗户露出量 = 屏宽 − 球的 x（球即窗户左缘，两者绑死）；
 *  - 拖动时可上下移动（旧版拉窗只改 x，球在竖直方向不跟手）；
 *  - 球的 x 一律 clamp 在"整个球都在屏内"（旧版夹到屏宽−8，球会被推出屏外=消失）；
 *  - 开合动画期间由露出量反推球的 x：球被窗户左缘带着走，永不脱钩，
 *    也不再有"球自己跑一段"的观感；
 *  - 删除球的独立动画（animBallTo / mBallAnim），"动画与手势抢球"整类竞态消失。
 */
public class FloatingBallService extends Service {

    private WindowManager mWm;
    private int mScreenW, mScreenH;

    private View mBall;                  // 悬浮球
    private WindowManager.LayoutParams mBallLp;
    private LinearLayout mMenu;          // 球菜单
    private WindowManager.LayoutParams mMenuLp;
    private FrameLayout mWin;            // 窗户（含 VmScreenView）
    private WindowManager.LayoutParams mWinLp;
    private VmScreenView mVmView;

    private float mDownX, mDownY;
    private int mStartX, mStartY;
    private boolean mDragging, mWinDrag;
    private boolean mWinOpen;
    private boolean mMenuEatenOnDown;       // v1.2：本手势按下时是否已吸收关闭过菜单
    private boolean mExpanded;              // v1.5：拖动中球窗口是否已扩成全屏（防 translation 被裁剪）
    private boolean mDeadGesture;           // v1.7：多指污染的手势（后续 MOVE 忽略，UP 只复位）
    private boolean mGestureActive;         // v1.9：手指仍按着（含未起拖/死亡手势），自动同步必须让位
    private android.animation.ValueAnimator mWinAnim;  // v1.2：窗户展开量动画（可打断）
    private int mSlop;                      // v1.2：系统 touchSlop（点击/拖动判定门槛）
    private int mWinDragSlop;               // v1.9：进入拉窗/收窗模式的横向门槛（dp 换算，旧版硬编码 20px）
    private int mMenuMargin, mMenuGap;      // v1.9：菜单贴边留白 / 与球的垂直间距（dp 换算）
    private android.os.Handler mHandler;    // v1.9：看门狗用的主线程 Handler
    private android.hardware.display.DisplayManager mDm; // v1.9：屏幕尺寸变化（旋转/折叠/分屏）监听
    private static final int BALL = 128; // dp 无关，直接 px（Demo）
    private static final String CHANNEL_ID = "demo";
    private static final long EXPAND_WATCHDOG_MS = 2500;  // v1.9：扩窗后的最长存活时间
    private static final long SHRINK_RETRY_MS = 500;      // v1.9：缩回失败的重试间隔
    static volatile boolean sRunning = false;   // v1.7：供主界面切换 启动/停止 按钮

    /**
     * v1.11：诊断日志开关。只在真机上定位"球为什么动了"时开，正式使用可关。
     * 日志同时走 Log.d（tag FloatDemo）与**外部文件落盘**——实测 OnePlus / ColorOS
     * (Android 16) 会把第三方应用的应用级日志吞掉，logcat 里一条都看不到，
     * 所以真正的证据在文件里：
     *   /sdcard/Android/data/dev.twinbox.floatdemo/files/trace.log
     * 取法：adb-shell "cat /sdcard/Android/data/dev.twinbox.floatdemo/files/trace.log"
     */
    private static final String TAG = "FloatDemo";
    private static final boolean TRACE = true;
    private static final String TRACE_FILE = "trace.log";
    private static final long TRACE_MAX = 512 * 1024;   // 超过就轮转成 trace.log.1
    private final java.text.SimpleDateFormat mTimeFmt =
            new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US);

    private void trace(String s) {
        if (!TRACE) {
            return;
        }
        android.util.Log.d(TAG, s);
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            java.io.File f = new java.io.File(dir, TRACE_FILE);
            if (f.length() > TRACE_MAX) {
                java.io.File old = new java.io.File(dir, TRACE_FILE + ".1");
                if (old.exists()) {
                    old.delete();
                }
                f.renameTo(old);
            }
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(mTimeFmt.format(new java.util.Date()) + " " + s + "\n");
            w.close();
        } catch (Throwable ignore) {
            // 日志失败绝不能影响功能
        }
    }

    /**
     * v1.9 看门狗：拖动会把球窗口扩成全屏，而它此时是可触摸的——一旦
     * updateViewLayout 缩回失败（旧版 7 处 catch(Throwable ignore) 直接吞掉，
     * 无任何补救），就剩一个覆盖全屏、吞掉所有触摸的空窗口，用户只能靠通知栏
     * 回来停服务。这里保证"扩过就一定会被缩回"。
     */
    private final Runnable mBallLayoutWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!mExpanded) {
                return;
            }
            if (mDragging || mGestureActive) {   // 手势还按着，别插手，稍后再看
                mHandler.postDelayed(this, 1000);
                return;
            }
            applyBallLayout();                   // 失败时内部会自己排下一次重试
        }
    };

    /** v1.9：屏幕尺寸变化时按新尺寸重摆窗口（旋转 / 折叠展开 / 分屏）。 */
    private final android.hardware.display.DisplayManager.DisplayListener mDisplayListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                        applyMetricsChange();
                    }
                }
            };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sRunning = true;
        mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mWm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mSlop = Math.max(12, android.view.ViewConfiguration.get(this).getScaledTouchSlop());
        // v1.9：手势门槛与菜单留白改成 dp 换算——旧版固定 20px/16px 在 xxhdpi 上
        // 只有 ~7dp，竖拖时一点横向抖动就被抓进窗户模式（v1.4 那个回归的根因）。
        float d = getResources().getDisplayMetrics().density;
        mWinDragSlop = Math.round(20 * d);
        mMenuMargin = Math.round(12 * d);
        mMenuGap = Math.round(20 * d);
        // 先拿 DisplayManager：refreshMetrics() 优先用它，拿不到再退回 WindowManager
        mDm = (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        refreshMetrics();
        if (mDm != null) {
            mDm.registerDisplayListener(mDisplayListener, mHandler);
        }
        startForeground(9, buildNotification());
        buildWindow();
        buildBall();
        // v1.12：会话分隔线，便于在追加式日志里切分"这一次启动到下一次启动"
        trace("=== start v" + appVersion() + " screen=" + mScreenW + "x" + mScreenH
                + " density=" + d + " slop=" + mSlop + " dragSlop=" + mWinDragSlop);
    }

    /** v1.12：日志里记版本号，免得对不上装的哪一版。 */
    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    /** v1.9：从 DisplayManager 取真实屏幕尺寸（含系统栏），旋转/折叠后也准。 */
    private void refreshMetrics() {
        int w = 0, h = 0;
        try {
            android.view.Display disp = mDm == null ? null
                    : mDm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            if (disp == null && mWm != null && mWm.getDefaultDisplay() != null) {
                disp = mWm.getDefaultDisplay();
            }
            if (disp != null) {
                android.util.DisplayMetrics m = new android.util.DisplayMetrics();
                disp.getRealMetrics(m);
                w = m.widthPixels;
                h = m.heightPixels;
            }
        } catch (Throwable ignore) {
            // 老设备/异常 display 服务：退回应用可见尺寸
        }
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics m = getResources().getDisplayMetrics();
            w = m.widthPixels;
            h = m.heightPixels;
        }
        mScreenW = w;
        mScreenH = h;
    }

    /**
     * v1.9：屏幕尺寸变化后重摆窗口（旋转 / 折叠展开 / 分屏）。
     * 旧版尺寸只在 onCreate 取一次，变化后贴边判定、setWinProgress、clamp 全用旧值，
     * 窗户会滑到屏幕外或露出错误宽度。
     */
    private void applyMetricsChange() {
        refreshMetrics();
        if (mBall == null || mWin == null) {
            return;
        }
        hideMenu();   // 菜单位置是按旧尺寸算的，直接收掉
        // 动画目标来自旧尺寸，先取消，避免滑向错误坐标
        if (mWinAnim != null) {
            mWinAnim.cancel();
            mWinAnim = null;
        }
        // 球：把显示位折叠回 LP 并 clamp 进新屏（旋转后可能整个在屏外），不主动挪位（保持 v1.8 语义）
        mBallLp.width = mExpanded ? mScreenW : BALL;
        mBallLp.height = mExpanded ? mScreenH : BALL;
        mBallLp.x = Math.max(0, Math.min(mScreenW - BALL, mBallLp.x + (int) mBall.getTranslationX()));
        mBallLp.y = Math.max(0, Math.min(mScreenH - BALL, mBallLp.y + (int) mBall.getTranslationY()));
        mBall.setTranslationX(0);
        mBall.setTranslationY(0);
        mBall.setAlpha(1f);
        if (mWinOpen) {
            mBallLp.x = 0;
            mBallLp.y = Math.max(0, Math.min(mScreenH - BALL, mBallLp.y));
        }
        try {
            mWm.updateViewLayout(mBall, mBallLp);
        } catch (Throwable ignore) {
        }
        // 窗户：开着就按新尺寸重铺，否则彻底熄灭（动画被取消不能停在半空）
        if (mWinOpen) {
            mWinLp.width = mScreenW;
            mWinLp.height = mScreenH;
            setWinProgress(mScreenW);
            setWinActive(true);
        } else {
            setWinProgress(0);
            setWinActive(false);
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyMetricsChange();
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // v1.9 修 7.x 崩溃：NotificationChannel / Builder(context, channelId) 都是
        // API 26 才有的类型，而本应用 minSdk=24。旧版在这里无条件 new，Android 7.x
        // 一启动服务就 NoClassDefFoundError（startForegroundService 那边反而做了 26 判断）。
        final Notification.Builder b;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "浮窗Demo",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            @SuppressWarnings("deprecation")
            Notification.Builder legacy = new Notification.Builder(this);
            b = legacy;
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return b.setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("VMOS 浮窗 Demo")
                .setContentText("球在右半屏向左拖拉出窗户；点球开菜单")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    // ---------------------------------------------------------------- 窗户

    private void buildWindow() {
        mWin = new FrameLayout(this);
        mWin.setBackgroundColor(0xFF06080D);
        mVmView = new VmScreenView(this);
        mWin.addView(mVmView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        // v1.1：关窗态必须"真正不存在"——0x0 + GONE + NOT_TOUCHABLE。
        // 旧版 OPAQUE 全屏窗常驻：内容 translation 移走了但 surface 还盖着全屏，
        // 没画东西的不透明面 = 一块黑（桌面中间黑屏的根因）；且格式必须
        // TRANSLUCENT，部分拉出时露出的区域才透出桌面而不是黑底。
        mWinLp = new WindowManager.LayoutParams(
                0, 0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        mWinLp.gravity = Gravity.TOP | Gravity.START;
        mWinLp.x = 0;
        mWinLp.y = 0;
        mWin.setVisibility(View.GONE);
        // 全关态：内容整体平移出屏（窗户贴着右墙）
        mWin.setTranslationX(mScreenW);
        try {
            mWm.addView(mWin, mWinLp);
        } catch (Throwable t) {
            Toast.makeText(this, "窗户创建失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 窗户点亮/熄灭（v1.1 修黑屏+闪屏的核心）。
     * 熄灭 = 0x0 + 根视图 GONE（ViewRootImpl 对 GONE 不建 surface）+
     * FLAG_NOT_TOUCHABLE：关窗态零像素、零触摸遮挡、零合成负担，
     * VmScreenView 的 isShown() 也随之变 false，逐帧重绘心跳自然停。
     * 点亮前必须先 setWinProgress 摆好 translation，防止全屏内容闪现一帧。
     *
     * v1.9：返回是否推送成功。熄灭失败同样会留下"全屏 + 可触摸"的窗口，
     * 所以失败不再静默——挂看门狗重试（与球窗口同一套兜底思路）。
     */
    private boolean setWinActive(boolean on) {
        try {
            if (on) {
                mWinLp.width = mScreenW;
                mWinLp.height = mScreenH;
                mWinLp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                mWin.setVisibility(View.VISIBLE);
            } else {
                mWin.setVisibility(View.GONE);
                mWinLp.width = 0;
                mWinLp.height = 0;
                mWinLp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            mWm.updateViewLayout(mWin, mWinLp);
        } catch (Throwable t) {
            if (!on && mHandler != null) {
                mHandler.removeCallbacks(mWinLayoutWatchdog);
                mHandler.postDelayed(mWinLayoutWatchdog, SHRINK_RETRY_MS);
            }
            return false;
        }
        if (!on && mHandler != null) {
            mHandler.removeCallbacks(mWinLayoutWatchdog);
        }
        return true;
    }

    /** v1.9：窗户"熄灭"失败时的兜底——全屏可触摸窗口必须消失（同球窗口看门狗）。 */
    private final Runnable mWinLayoutWatchdog = new Runnable() {
        @Override
        public void run() {
            if (mWinOpen || mWinLp == null || mWinLp.width == 0) {
                return;
            }
            if (mWinAnim != null || mDragging || mGestureActive) {
                mHandler.postDelayed(this, 1000);   // 动画/手势还要用它，稍后再看
                return;
            }
            if (!setWinActive(false)) {
                mHandler.postDelayed(this, SHRINK_RETRY_MS);
            }
        }
    };

    /**
     * 窗户露出量 = 屏幕宽 − 球的 x（球即窗户左缘，两者绑死）。
     *
     * v1.14：开合动画期间在这里反推球的位置，让球**始终贴在窗户左缘**上——
     * 观感是"窗户在开合、球是它的把手被带着走"，而不是球自己单独跑一段；
     * 也保证球不会被推出屏外（x 被 clamp 在完全可见范围内）。
     * 拖动中（mDragging）球是主动方，由手指驱动，这里不插手。
     */
    private void setWinProgress(int amount) {
        final int reveal = Math.max(0, Math.min(mScreenW, amount));
        if (!mDragging && !mGestureActive && mBall != null && mBallLp != null) {
            setBallX(clampBallX(mScreenW - reveal));
        }
        mWin.setTranslationX(mScreenW - reveal);
    }

    /** 球的 x 只允许落在"整个球都在屏内"的范围（旧版允许到屏宽−8，球会被推出屏外=消失）。 */
    private int clampBallX(int x) {
        return Math.max(0, Math.min(mScreenW - BALL, x));
    }

    /** 直接摆球的 x（无动画），用于"窗户带着球走"。 */
    private void setBallX(int x) {
        mBallLp.x = clampBallX(x);
        mBall.setTranslationX(0);
        try {
            mWm.updateViewLayout(mBall, mBallLp);
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- 球

    private void buildBall() {
        FrameLayout ballRoot = new FrameLayout(this);
        TextView face = new TextView(this);
        face.setBackgroundResource(R.drawable.bg_ball);
        face.setText("◫");
        face.setTextColor(0xFF8FA8E8);
        face.setTextSize(26);
        face.setGravity(Gravity.CENTER);
        // v1.6 修"拖动还是消失"（v1.5 几何 bug）：扩窗成全屏后根视图(FrameLayout)
        // 会跟着变成全屏大，face 若是 CENTER 会被重新布局到全屏窗口的"正中央"
        // ——窗口原点还在球原位置，中央=屏幕外 → 整个拖动过程球不可见。
        // 改 TOP|START：face 恒钉在窗口原点=球的视觉位置，translation 数学才成立。
        // （128×128 窗口时 TOP|START 与 CENTER 完全等价，常态视觉零变化。）
        ballRoot.addView(face, new FrameLayout.LayoutParams(BALL, BALL, Gravity.TOP | Gravity.START));
        mBall = ballRoot;
        mBallLp = new WindowManager.LayoutParams(
                BALL, BALL,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        mBallLp.gravity = Gravity.TOP | Gravity.START;
        mBallLp.x = mScreenW - BALL;
        mBallLp.y = mScreenH / 3;
        mBall.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });
        try {
            mWm.addView(mBall, mBallLp);
        } catch (Throwable t) {
            Toast.makeText(this, "悬浮球创建失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // v1.14：球已无独立动画（位置完全由窗户露出量反推），只需打断窗户动画。
                if (mWinAnim != null) {
                    mWinAnim.cancel();
                    mWinAnim = null;
                    // v1.7 修 P1：开关窗动画被按下打断后不能停在半空（窗户露一半、
                    // touchable 全屏区域残留）。按当前目标态重启"完成动画"，
                    // 让窗户自己滑到全开/全收，不打扰本次手势。
                    if (mWinOpen) {
                        startWinAnim(mScreenW);
                    } else {
                        startWinAnim(0);
                    }
                }
                // v1.5 保险：若上次手势异常中断导致窗口停在扩展态，先按当前
                // 显示位结算缩回 BALL×BALL。
                // v1.9：判据补上 mExpanded——缩回失败过的话 width 可能已被复原，
                // 只看 width 会漏掉"窗口其实还全屏"的状态。
                if (mExpanded || mBallLp.width != BALL) {
                    settleBall();
                }
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mStartX = mBallLp.x;
                mStartY = mBallLp.y;
                mDragging = false;
                mWinDrag = false;
                mDeadGesture = false;
                mGestureActive = true;   // v1.9：手势期间禁止窗口动画自动同步球
                // 注意：这里不再写 mExpanded=false——它由 applyBallLayout()
                // 在"缩回真的成功"时才清，否则会把失败的窗口状态抹成已缩回。
                // v1.2 修"要再点一下才行"：菜单若盖着球，触摸全被菜单窗口吃掉
                // （球收不到事件）。按下球即关菜单；标记"本手势已吸收菜单"，
                // 松手时不重弹（点一下=关菜单，与 VMOS 语义一致）。
                mMenuEatenOnDown = mMenu != null && mMenu.getParent() != null;
                if (mMenuEatenOnDown) {
                    hideMenu();
                }
                trace("DOWN raw=(" + (int) event.getRawX() + "," + (int) event.getRawY()
                        + ") ballX=" + mBallLp.x + " ballY=" + mBallLp.y
                        + " winOpen=" + mWinOpen + " expanded=" + mExpanded);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                // v1.5：多指落下=手势作废（防 pointer 0 交换导致坐标突变瞬移），
                // 按当前显示位立即结算；手势状态一并复位，后续 MOVE 走正常判定。
                // v1.7 修 P3：复位还不够——原手指（或换了 pointer 0 的新手指）继续
                // MOVE 会相对老 mDownX 重新超 slop "复活"成新拖动，坐标跳变。
                // 置"死亡手势"标志：本手势后续 MOVE 一律忽略，UP 只做复位。
                settleBall();
                mDragging = false;
                mWinDrag = false;
                mDeadGesture = true;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mDeadGesture) {
                    return true; // v1.7：多指污染的手势不再复活
                }
                float dx = event.getRawX() - mDownX;
                float dy = event.getRawY() - mDownY;
                if (!mDragging && (Math.abs(dx) > mSlop || Math.abs(dy) > mSlop)) {
                    mDragging = true;
                    hideMenu();
                }
                if (!mDragging) {
                    return true;
                }
                // v1.5 修"上下移动球消失"：球窗口只有 BALL×BALL，translation
                // 超出窗口 surface 的部分会被裁剪——旧版往下拖 >128px 球就完全
                // 移出窗口="消失"；松手结算 lp 又"闪现"。修法：拖动一开始就把
                // 窗口扩成全屏（原点不动，视觉零跳变，仅此一次 binder），
                // 之后 translation 在全屏 surface 内随便移、永不裁剪、仍零 binder。
                if (!mExpanded) {
                    mExpanded = true;
                    mBallLp.width = mScreenW;
                    mBallLp.height = mScreenH;
                    try {
                        mWm.updateViewLayout(mBall, mBallLp);
                        // v1.9：扩窗后挂看门狗。全屏窗口是可触摸的，一旦缩回失败
                        // （异常被吞、进程卡住、手势永远收不到 UP），整屏触摸就没了。
                        mHandler.removeCallbacks(mBallLayoutWatchdog);
                        mHandler.postDelayed(mBallLayoutWatchdog, EXPAND_WATCHDOG_MS);
                    } catch (Throwable t) {
                        // 扩窗失败不影响跟手（球仍在 BALL×BALL 窗口里，只是会被裁剪）
                        mExpanded = false;
                    }
                }
                // v1.3 修"先往下拖一下再拉，窗户没反应"：方向判定不能只在跨越
                // touchSlop 的那一瞬间锁死——手指自然弧线（先下后左）跨过 slop
                // 时 dx 还是 0，旧版就此永久落入自由移动模式，之后拉到头窗户
                // 也不跟手，还得松手贴左缘后再拖第二次才出来。改为持续判定：
                // 本手势未进窗户模式前，每次 MOVE 重新判；一旦切入不再退出。
                if (!mWinDrag) {
                    // v1.4 修"移动悬浮球变得困难"（v1.3 回归）：持续重判把移动球也
                    // 劫持了——贴右缘起步时任何 >20px 左漂（竖拖的横向抖动、斜
                    // 拖）都被抓进窗户模式。入口再加两道门：
                    // 1) 横向主导：|dx|>|dy| 才算拉窗。竖直/陡斜拖动=移动球；
                    //    弧线手势（先下后左）进入左拉段后 |dx| 必然反超 |dy|，
                    //    照样能拉出窗户，两不误。
                    // 2) dockedLeft 只认 mWinOpen：球停在左缘但窗户关着时往右拖
                    //    =移动球，不再被误判成"收窗"闪出近乎全屏的窗户。
                    // v1.9 修 v1.8 的断链：v1.8 删掉松手贴边后，球可以停在屏幕任意处，
                    // 而"必须贴右缘 16px 内"的旧判据让球停在中间/左半屏时就再也拉不出
                    // 窗户了（得先手动把球拖回右缘）。几何上"球即窗户左缘"，球在右半屏
                    // 向左拉一定拉得出窗户（只是露出量小），故改为半屏判据；同时把固定
                    // 20px 门槛换成 20dp——旧值在 xxhdpi 上只有 ~7dp，太容易误触发。
                    boolean horizDom = Math.abs(dx) > Math.abs(dy);
                    boolean canOpen = !mWinOpen && mStartX >= mScreenW / 2;
                    boolean canClose = mWinOpen;
                    if (horizDom && ((canOpen && dx < -mWinDragSlop)
                            || (canClose && dx > mWinDragSlop))) {
                        mWinDrag = true;
                    }
                }
                if (mWinDrag) {
                    // v1.7 修 P2：手势接管窗户=动画立即让位（DOWN 重启的完成动画
                    // 每帧 setWinProgress，不 cancel 会和手指打架）。
                    if (mWinAnim != null) {
                        mWinAnim.cancel();
                        mWinAnim = null;
                    }
                    // v1.14：球 = 窗户左缘，跟手（translation 零 binder）。
                    // x 夹在"整个球都在屏内"，旧版夹到屏宽−8 会让球被推出屏外=看起来消失。
                    int nx = clampBallX(mStartX + (int) dx);
                    if (mWinLp.width == 0) {
                        // 拉出首帧：先摆好 translation 再点亮，防全屏内容闪现一帧
                        setWinProgress(mScreenW - nx);
                        setWinActive(true);
                    }
                    // v1.14：拉窗时也允许上下移动（用户要求：拖动时球能上下走）
                    int ty = (int) Math.max(-mStartY,
                            Math.min(mScreenH - BALL - mStartY, dy));
                    mBall.setTranslationX(nx - mStartX);
                    mBall.setTranslationY(ty);
                    setWinProgress(mScreenW - nx);
                } else {
                    // 自由移动（clamp 屏内；窗口已扩全屏，永不裁剪）
                    int tx = (int) Math.max(-mStartX, Math.min(mScreenW - BALL - mStartX, dx));
                    int ty = (int) Math.max(-mStartY, Math.min(mScreenH - BALL - mStartY, dy));
                    mBall.setTranslationX(tx);
                    mBall.setTranslationY(ty);
                }
                mBall.setAlpha(0.55f);
                return true;
            }
            case MotionEvent.ACTION_UP:
                return endGesture(event);
            case MotionEvent.ACTION_CANCEL:
                // v1.5：系统取消手势（如被系统手势抢占）也要干净结算，
                // 否则球卡在半透明+translation 未归零的脏状态。
                return endGesture(event);
            default:
                return false;
        }
    }

    /**
     * 手势收尾（UP/CANCEL/多指共用）：按球当前显示位结算进 LP，同时把窗口
     * 缩回 BALL×BALL（拖动开始扩过屏的话）。旧显示位 = lp(左上角) + translation，
     * 新显示位 = 新 lp + 0 translation —— 数学上同一位置，视觉无缝。
     */
    private boolean endGesture(MotionEvent event) {
        mGestureActive = false;   // v1.9：手指已离开，允许自动同步接手
        // v1.7：死亡手势（多指污染）只做复位结算，不触发点击/贴边/开关窗——
        // 否则 moved 判定相对老 mDownX 失真，可能意外收窗/弹菜单。
        if (mDeadGesture) {
            settleBall();
            mDeadGesture = false;
            mDragging = false;
            mWinDrag = false;
            mMenuEatenOnDown = false;
            return true;
        }
        boolean moved = Math.abs(event.getRawX() - mDownX) > mSlop
                || Math.abs(event.getRawY() - mDownY) > mSlop;
        trace("UP moved=" + moved + " winDrag=" + mWinDrag + " ballX=" + mBallLp.x
                + " winOpen=" + mWinOpen);
        settleBall();
        if (mWinDrag) {
            mWinDrag = false;
            // v1.14：必须先清 mDragging 再开关窗——setWinProgress 里的"窗户带球走"
            // 逻辑以 !mDragging 为前提，旧代码这条分支提前 return，标志残留为 true
            // 会让球在收尾动画里不跟窗户边缘走。
            mDragging = false;
            mMenuEatenOnDown = false;
            if (mBallLp.x < mScreenW / 2) {
                openWin();
            } else {
                // v1.14：拉窗没过半 = 窗户收回。球由窗户左缘带着一起回右缘
                // （两者绑死，不存在"球自己跑一段"——球的移动就是窗户在合）。
                closeWin();
            }
            return true;
        }
        if (!moved) {
            if (mWinOpen) {
                closeWin();          // 全开态点球：一键收回
            } else if (!mMenuEatenOnDown) {
                toggleMenu();        // VMOS 式功能菜单（本手势若已关过菜单则不重弹）
            }
        }
        // v1.14：球没有独立动画；松手后球是否移动只取决于窗户是否需要开合，
        // 需要时由窗户左缘带着走（两者绑死）。
        mDragging = false;
        mMenuEatenOnDown = false;
        return true;
    }

    /** 结算：显示位写回 LP + 窗口缩回 BALL×BALL + translation 归零（一次 binder）。 */
    private void settleBall() {
        mBallLp.x = mBallLp.x + (int) mBall.getTranslationX();
        mBallLp.y = mBallLp.y + (int) mBall.getTranslationY();
        mBall.setTranslationX(0);
        mBall.setTranslationY(0);
        mBall.setAlpha(1f);
        trace("settle -> x=" + mBallLp.x + " y=" + mBallLp.y + " expanded=" + mExpanded);
        applyBallLayout();
    }

    /**
     * v1.9：把 mBallLp 一次性推到 WMS（位置 + 尺寸），并保证拖动期扩成的
     * 全屏窗口一定缩回 BALL×BALL——它是"全屏可触摸窗口"的唯一出口。
     *
     * 旧版这里 `catch (Throwable ignore)` 把缩回失败直接吞掉：一旦失败就永久留下
     * 一个覆盖全屏、吞掉所有触摸的空窗口（用户只能靠通知栏回来停服务）。现在失败
     * 会回滚尺寸（不撒谎）、保留 mExpanded，并挂看门狗重试到成功为止。
     *
     * 注意：无论是否处于扩展态都要推一次——settle 会把 translation 折进 lp.x/y，
     * 那次位置变更同样需要 updateViewLayout 才生效。
     *
     * @return 是否确认推送成功
     */
    private boolean applyBallLayout() {
        final boolean wasExpanded = mExpanded;
        final int oldW = mBallLp.width, oldH = mBallLp.height;
        mBallLp.width = BALL;
        mBallLp.height = BALL;
        try {
            mWm.updateViewLayout(mBall, mBallLp);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "applyBallLayout 失败，挂看门狗重试: " + t);
            mBallLp.width = oldW;    // 回滚：窗口其实还是原样，状态别撒谎
            mBallLp.height = oldH;
            if (wasExpanded) {
                mHandler.removeCallbacks(mBallLayoutWatchdog);
                mHandler.postDelayed(mBallLayoutWatchdog, SHRINK_RETRY_MS);
            }
            return false;
        }
        mExpanded = false;
        mHandler.removeCallbacks(mBallLayoutWatchdog);
        return true;
    }

    /** 窗户全开：球到左缘，窗户铺满。 */
    /**
     * 窗户全开：露出量动画到满屏，球被窗户左缘带着走到左缘（x=0）。
     * v1.14：球没有任何独立动画，它的位置完全由窗户露出量决定（两者绑死）。
     */
    private void openWin() {
        trace("openWin  ballX=" + mBallLp.x);
        mWinOpen = true;
        if (mWinLp.width == 0) {
            setWinProgress(0);
            setWinActive(true);
        }
        startWinAnim(mScreenW);
    }

    /**
     * 窗户收回：露出量动画到 0，球被窗户左缘带着回到右缘（x=屏宽−球宽）；
     * 动画结束彻底熄灭窗口（surface 销毁）。
     */
    private void closeWin() {
        trace("closeWin ballX=" + mBallLp.x);
        mWinOpen = false;
        startWinAnim(0);
    }



    /** 窗户展开量动画（amount: 0=全收, screenW=全开）。存字段便于按下时打断。 */
    private void startWinAnim(int targetAmount) {
        if (mWinAnim != null) {
            mWinAnim.cancel();
        }
        final android.animation.ValueAnimator a = android.animation.ValueAnimator.ofInt(
                (int) (mScreenW - mWin.getTranslationX()), targetAmount);
        a.setDuration(220);
        a.setInterpolator(new android.view.animation.DecelerateInterpolator());
        a.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(android.animation.ValueAnimator anim) {
                setWinProgress((Integer) anim.getAnimatedValue());
            }
        });
        // v1.2：收回动画自然跑完才熄灭窗口；被用户按下打断（cancel）则不熄灭
        // ——用户可能正要反向拉出，MOVE 里的 width==0 重点亮路径仍兜底。
        final boolean[] cancelled = {false};
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(android.animation.Animator animation) {
                cancelled[0] = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                trace("winAnim end target=" + targetAmount + " winOpen=" + mWinOpen
                        + " cancelled=" + cancelled[0] + " ballX=" + mBallLp.x);
                if (!cancelled[0] && !mWinOpen) {
                    setWinActive(false);
                }
                // v1.10 删掉 v1.8 的"球回把手位"自动同步（原样保留在 git 历史里）。
                // 它的本意是修"球和窗户左缘脱钩"，但代价是：只要窗户动画收尾时球不在
                // 把手位，球就会**在没有用户意图的情况下自己滑一段**——v1.9 把
                // mBallAnim 句柄复位后这段死代码被激活，表现就是"拖动结束后球又动了
                // 一下"（正是 v1.8 当初想消掉的毛病）。而它要修的那个脱钩其实无害：
                // 窗户全开时球的 x 不参与任何几何计算，点球/右拉收窗都会重新把球带回
                // 把手位，属于自愈状态。所以这里彻底不做自动移动——球只在
                // openWin/closeWin 这两个"用户明确开/关窗"的入口被动画移动到把手位。
            }
        });
        mWinAnim = a;
        a.start();
    }

    // ---------------------------------------------------------------- 菜单（VMOS 式）

    private void toggleMenu() {
        if (mMenu != null && mMenu.getParent() != null) {
            hideMenu();
            return;
        }
        mMenu = new LinearLayout(this);
        mMenu.setOrientation(LinearLayout.VERTICAL);
        mMenu.setBackgroundResource(R.drawable.bg_menu);
        mMenu.setPadding(18, 12, 18, 12);
        final String[][] items = {
                {"🏠 模拟 Home", "回到 VM 桌面（模拟）"},
                {"◀ 模拟返回", "返回上一级（模拟）"},
                {"□ 模拟多任务", "最近任务（模拟）"},
                {"✕ 关闭窗户", null},
                {"🞂 退出 Demo", "exit"},
        };
        for (final String[] it : items) {
            TextView row = new TextView(this);
            row.setText(it[0]);
            row.setTextColor(0xFFC8D3F0);
            row.setTextSize(15);
            row.setPadding(28, 20, 28, 20);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideMenu();
                    if (it[1] == null) {
                        closeWin();
                    } else if ("exit".equals(it[1])) {
                        stopSelf();
                    } else {
                        Toast.makeText(FloatingBallService.this, it[1], Toast.LENGTH_SHORT).show();
                    }
                }
            });
            mMenu.addView(row);
        }
        mMenuLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        mMenuLp.gravity = Gravity.TOP | Gravity.START;
        // v1.2 修"要再点一下才行"：旧位置 x=球x-80，菜单宽 200+，球在屏幕
        // 中部时菜单必然盖住球——菜单窗口可触摸，把本该给球的事件全吃了。
        // 改为垂直错开：球在上半屏菜单出下方，下半屏菜单出上方，永不重叠。
        // v1.9：先真测量再定位。旧版写死 mw=440/mh=430 px 作"保守估计"（按 5 行
        // 菜单实测约 410 得出），但那是 px——在 xxhdpi 上 440px≈147dp，而菜单真实
        // 宽度更大，"永不重叠"的前提和越界 clamp 就都失效了。留白/间距一并 dp 化。
        int avail = Math.max(1, mScreenW - mMenuMargin * 2);
        mMenu.measure(
                View.MeasureSpec.makeMeasureSpec(avail, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(mScreenH, View.MeasureSpec.AT_MOST));
        int mw = Math.max(1, mMenu.getMeasuredWidth());
        int mh = Math.max(1, mMenu.getMeasuredHeight());
        int mx = Math.max(mMenuMargin,
                Math.min(mScreenW - mw - mMenuMargin, mBallLp.x + BALL / 2 - mw / 2));
        int my = mBallLp.y > mScreenH / 2
                ? mBallLp.y - mh - mMenuGap
                : mBallLp.y + BALL + mMenuGap;
        my = Math.max(mMenuMargin, Math.min(mScreenH - mh - mMenuMargin, my));
        mMenuLp.x = mx;
        mMenuLp.y = my;
        // 点菜单外任意处（含球、桌面）→ 关菜单（ACTION_OUTSIDE 不消费真实触摸）
        mMenu.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    hideMenu();
                    return true;
                }
                return false;
            }
        });
        try {
            mWm.addView(mMenu, mMenuLp);
        } catch (Throwable t) {
            Toast.makeText(this, "菜单失败: " + t, Toast.LENGTH_SHORT).show();
        }
    }

    private void hideMenu() {
        if (mMenu != null && mMenu.getParent() != null) {
            try {
                mWm.removeView(mMenu);
            } catch (Throwable ignore) {
            }
        }
        mMenu = null;
    }

    @Override
    public void onDestroy() {
        sRunning = false;
        super.onDestroy();
        if (mHandler != null) {
            mHandler.removeCallbacks(mBallLayoutWatchdog);   // v1.9：别让看门狗在服务死后还跑
            mHandler.removeCallbacks(mWinLayoutWatchdog);
        }
        if (mDm != null) {
            try {
                mDm.unregisterDisplayListener(mDisplayListener);
            } catch (Throwable ignore) {
            }
        }
        hideMenu();
        if (mWinAnim != null) {
            mWinAnim.cancel();
        }
        for (View v : new View[]{mBall, mWin}) {
            if (v != null && v.getParent() != null) {
                try {
                    mWm.removeView(v);
                } catch (Throwable ignore) {
                }
            }
        }
        // v1.9：原来的 SDK_INT >= N 判断恒真（minSdk 就是 24），去掉冗余分支。
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
}
