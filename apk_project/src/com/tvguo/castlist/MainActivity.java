package com.tvguo.castlist;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 手动启动入口（无界面形态）。
 *
 * 关键设计：本 Activity 启动后台服务后【立即 finish】，绝不留在前台。
 * 原因（logcat 实测）：电视果固件在投屏启动 PlayerUIActivity 时，
 * 由 RootWindowContainer → forceStopPackage 主动清理"有可见 Activity 的第三方 App"。
 * 无界面服务（无 Activity、adj=200）已实测可挺过投屏清理。
 *
 * 因此：
 *   - 开机自启走 BootReceiver（本来就无界面）
 *   - 手动恢复走本 Activity，但启动后立刻退出，同样无界面
 *   - 前台服务的常驻通知承担"运行中"提示，Toast 给出即时反馈
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 拉起前台服务（HTTP 18090 + DLNA 轮询）
        BootReceiver.start(this);

        // 立即退出界面，避免成为"有可见 Activity 的 App"被投屏清理盯上
        Toast.makeText(this, "投屏列表服务已启动（后台运行）", Toast.LENGTH_SHORT).show();
        finish();
    }
}
