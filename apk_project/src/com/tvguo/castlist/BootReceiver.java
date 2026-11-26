package com.tvguo.castlist;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机自启接收器：收到 BOOT_COMPLETED 后拉起前台服务。
 * Android 8+ 后台启动服务受限，必须用 startForegroundService。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        Log.i("CastList", "BootReceiver action=" + action);
        // 开机广播 + 自定义调试广播都拉起服务。
        // 自定义 action 的意义：可用 adb 无界面拉起（不启动 MainActivity），
        // 用于验证"无可见 Activity 时，投屏是否还会 force-stop 本进程"。
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || "android.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.htc.intent.action.QUICKBOOT_POWERON".equals(action)
                || "com.tvguo.castlist.START".equals(action)) {
            start(ctx);
        }
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, MainService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
            Log.i("CastList", "MainService start requested");
        } catch (Exception e) {
            Log.e("CastList", "start failed: " + e.getMessage());
        }
    }
}
