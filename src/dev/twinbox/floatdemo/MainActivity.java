package dev.twinbox.floatdemo;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * VMOS 悬浮窗 Demo 主界面：授权 + 开关悬浮球。
 */
public class MainActivity extends Activity {

    private static final int REQ_OVERLAY = 9001;
    private Button mBtn;

    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(48, 48, 48, 48);
        root.setBackgroundColor(0xFF070A12);

        TextView title = new TextView(this);
        title.setText("VMOS 悬浮窗 Demo");
        title.setTextColor(0xFFEDF2FF);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("\n复刻 VMOS Pro 的悬浮球交互：\n\n"
                + "• 球自由拖动，松手停在原地\n"
                + "• 球在右半屏向左拖 → 拉出窗户（画面实时渲染）\n"
                + "• 过半松手全开 / 不足收回（球不再自己移动）\n"
                + "• 窗户开着时向右拖 → 收回窗户\n"
                + "• 点球 = 菜单（窗户开着时=一键收回）\n"
                + "• 窗户里点应用图标有响应\n");
        sub.setTextColor(0xFF8A96B0);
        sub.setTextSize(15);
        sub.setLineSpacing(0, 1.3f);
        root.addView(sub);

        final Button btn = new Button(this);
        mBtn = btn;
        btn.setTextColor(0xFFEDF2FF);
        btn.setBackgroundColor(0xFF1B2540);
        btn.setPadding(48, 28, 48, 28);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // v1.7：服务已在跑 → 停止（回收所有窗口）；未跑 → 授权启动。
                if (FloatingBallService.sRunning) {
                    stopService(new Intent(MainActivity.this, FloatingBallService.class));
                    btn.setText("启动悬浮球");
                    Toast.makeText(MainActivity.this, "悬浮球已停止", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!Settings.canDrawOverlays(MainActivity.this)) {
                    Toast.makeText(MainActivity.this,
                            "请授予「显示在其他应用上层」权限", Toast.LENGTH_LONG).show();
                    try {
                        startActivityForResult(
                                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
                    } catch (Throwable t) {
                        startActivityForResult(
                                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION), REQ_OVERLAY);
                    }
                    return;
                }
                startBall();
            }
        });
        root.addView(btn);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // v1.7：回前台时按服务实际状态刷新按钮（服务可能被系统或别处启停）。
        if (mBtn != null) {
            mBtn.setText(FloatingBallService.sRunning ? "停止悬浮球" : "启动悬浮球");
        }
    }

    private void startBall() {
        Intent i = new Intent(this, FloatingBallService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        if (mBtn != null) {
            mBtn.setText("停止悬浮球");
        }
        Toast.makeText(this, "悬浮球已启动（去别处拖它）", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true); // 直接回桌面体验
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_OVERLAY && Settings.canDrawOverlays(this)) {
            startBall();
        }
    }
}
