package dev.twinbox.floatdemo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * VMOS 式"虚拟机画面"（Demo 用模拟内容）。
 *
 * VMOS 原版：VM 帧流（libmci InputVideoPack）→ 宿主 TextureView 渲染，
 * 悬浮窗里的画面是 App 自己的 View——所以球拖动=纯 View 动画，零特权。
 * Demo 用一个自绘 View 模拟"活的 VM 屏幕"：动态背景 + 粒子 + 时钟 +
 * 应用网格（可点）。证明：浮窗里的内容是实时渲染、可交互的。
 */
public class VmScreenView extends View {

    private final Paint mBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mClockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<float[]> mParticles = new ArrayList<>();
    /** v1.9：格式化器与渐变 shader 缓存——旧版每帧各 new 一次（60fps 下纯垃圾）。 */
    private final java.text.SimpleDateFormat mSdf =
            new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
    private RadialGradient mGlow;
    private float mGlowHue = Float.NaN;
    private int mGlowW, mGlowH;

    /** 模拟应用（图标色 + 名字） */
    private static final int[] ICON_COLORS = {
            0xFF5B8CFF, 0xFF2FD4A7, 0xFFFFB45B, 0xFFFF6B81,
            0xFFB58CFF, 0xFF6BE0FF, 0xFF9CE85B, 0xFFFF9E6B,
    };
    private static final String[] APP_NAMES = {
            "抖音", "微信", "相册", "设置",
            "游戏", "浏览器", "音乐", "相机",
    };

    private long mStart;
    private int mTouchIndex = -1;

    public VmScreenView(Context context) {
        super(context);
        mStart = android.os.SystemClock.elapsedRealtime();
        for (int i = 0; i < 18; i++) {
            mParticles.add(new float[]{
                    (float) Math.random(), (float) Math.random(),
                    0.15f + (float) Math.random() * 0.35f, // 速度
                    6 + (float) Math.random() * 22,        // 半径
            });
        }
        setClickable(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // v1.1：心跳放在最前且用 isShown()——窗户窗口 GONE 时（关窗态）
        // isShown()=false 心跳即停，不再对着空 surface 逐帧空重绘（闪屏根因之一）；
        // 放最前是为了 w=0（刚点亮还没 measure）时也不断链。
        if (isShown()) {
            postInvalidateOnAnimation();
        }
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float t = (android.os.SystemClock.elapsedRealtime() - mStart) / 1000f;

        // 动态背景：深色 + 色相缓慢漂移的径向光晕（"活画面"的最直观证据）
        // v1.9：RadialGradient 缓存。旧版每帧 new 一个 shader（60/s 纯垃圾），
        // 现在只在"色相漂移超过 1.5°≈0.19s"或尺寸变化时重建。
        float hue = (t * 8f) % 360f;
        if (mGlow == null || mGlowW != w || mGlowH != h
                || Float.isNaN(mGlowHue) || Math.abs(hue - mGlowHue) >= 1.5f) {
            int glow = Color.HSVToColor(new float[]{hue, 0.55f, 0.30f});
            mGlow = new RadialGradient(
                    w * 0.5f, h * 0.32f, Math.max(w, h) * 0.75f,
                    glow, 0xFF070A12, Shader.TileMode.CLAMP);
            mGlowHue = hue;
            mGlowW = w;
            mGlowH = h;
        }
        mBgPaint.setShader(mGlow);
        canvas.drawRect(0, 0, w, h, mBgPaint);

        // 漂浮粒子
        for (float[] p : mParticles) {
            float px = ((p[0] + t * p[2] * 0.06f) % 1f) * w;
            float py = ((p[1] + t * p[2] * 0.11f) % 1f) * h;
            mIconPaint.setColor(0x305B8CFF);
            mIconPaint.setAlpha(40 + (int) (30 * Math.sin(t + p[3])));
            canvas.drawCircle(px, py, p[3], mIconPaint);
        }

        // 时钟
        String now = mSdf.format(new java.util.Date());
        mClockPaint.setColor(0xFFEDF2FF);
        mClockPaint.setTextSize(Math.min(w, h) * 0.09f);
        mClockPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(now, w / 2f, h * 0.15f, mClockPaint);
        mTextPaint.setColor(0xFF7A86A8);
        mTextPaint.setTextSize(Math.min(w, h) * 0.028f);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("VMOS 浮窗架构 Demo · 画面为实时渲染", w / 2f, h * 0.19f, mTextPaint);

        // 应用网格（4×2）：几何公式与 hitApp 共用 gridTop/cellSize/iconCx/iconCy，
        // v1.9 前两处各写一遍，改一处忘一处就会"看到的位置点不中"。
        float gridTop = gridTop(h);
        float cell = cellSize(w, h);
        for (int i = 0; i < 8; i++) {
            int row = i / 4, col = i % 4;
            float cx = iconCx(w, col);
            float cy = iconCy(gridTop, cell, row);
            if (i == mTouchIndex) {
                mIconPaint.setColor(0x335B8CFF); // 按下高亮
            } else {
                mIconPaint.setColor(0x18556880);
            }
            canvas.drawCircle(cx, cy, cell * 0.32f, mIconPaint);
            mIconPaint.setColor(ICON_COLORS[i]);
            canvas.drawCircle(cx, cy, cell * 0.26f, mIconPaint);
            mLabelPaint.setColor(0xFFC8D3F0);
            mLabelPaint.setTextSize(cell * 0.16f);
            mLabelPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(APP_NAMES[i], cx, cy + cell * 0.72f, mLabelPaint);
        }

        // 底部提示条
        mTextPaint.setColor(0xFF5B6880);
        canvas.drawText("拖左缘圆球可收回窗户 · 点应用有响应", w / 2f, h * 0.94f, mTextPaint);
    }

    // ---------------- 网格几何（onDraw 与 hitApp 共用的唯一来源） ----------------

    /** 网格首行圆心 y 的基准（屏高比例）。 */
    private static float gridTop(int h) {
        return h * 0.30f;
    }

    /** 单元格尺寸。 */
    private static float cellSize(int w, int h) {
        return Math.min(w / 4f, h * 0.16f);
    }

    /** 第 col 列圆心 x。 */
    private static float iconCx(int w, int col) {
        return w * (0.16f + col * 0.225f);
    }

    /** 第 row 行圆心 y。 */
    private static float iconCy(float gridTop, float cell, int row) {
        return gridTop + row * cell * 1.5f + cell * 0.5f;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int w = getWidth();
        final int h = getHeight();
        float gridTop = gridTop(h);
        float cell = cellSize(w, h);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mTouchIndex = hitApp(event.getX(), event.getY(), w, gridTop, cell);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                int idx = hitApp(event.getX(), event.getY(), w, gridTop, cell);
                if (idx >= 0 && idx == mTouchIndex) {
                    android.widget.Toast.makeText(getContext(),
                            "已打开「" + APP_NAMES[idx] + "」（模拟）",
                            android.widget.Toast.LENGTH_SHORT).show();
                }
                mTouchIndex = -1;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mTouchIndex = -1;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }

    private int hitApp(float x, float y, int w, float gridTop, float cell) {
        for (int i = 0; i < 8; i++) {
            int row = i / 4, col = i % 4;
            float cx = iconCx(w, col);
            float cy = iconCy(gridTop, cell, row);
            if (Math.hypot(x - cx, y - cy) < cell * 0.45f) {
                return i;
            }
        }
        return -1;
    }
}
