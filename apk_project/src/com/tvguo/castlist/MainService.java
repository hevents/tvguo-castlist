package com.tvguo.castlist;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

/**
 * 前台服务：APP 的核心载体。
 * 启动后常驻通知（绕过 Android 后台限制），并拉起两个线程：
 *   1) WebServer  —— 局域网 HTTP 服务（列表页 + 下载接口）
 *   2) PollThread —— 每 10 秒轮询本机 DLNA，抓 CurrentURI 入库
 */
public class MainService extends Service {
    private static final String CHANNEL_ID = "castlist_channel";
    private WebServer webServer;
    private Poller poller;
    private Thread pollThread;
    private volatile boolean running = false;
    /** 只做一次自动重播：static 防止 onCreate 多次调用（如广播重复启动）重复切源 */
    private static boolean resumeDone = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("CastList", "MainService onCreate");
        startForegroundNotify();
        running = true;

        // 1) 启动 HTTP 服务线程
        webServer = new WebServer(this);
        new Thread(webServer, "castlist-http").start();

        // 2) 启动 DLNA 轮询线程（传入 webServer，抓到新片时自动触发下载）
        poller = new Poller(this, webServer);
        pollThread = new Thread(poller, "castlist-poll");
        pollThread.start();

        // 3) v11 开机自动重播：延时等 DLNA/网络起来后，把最近下载的本地文件投回电视。
        //    v16: 受网页开关控制（默认开）；关闭时连线程都不起，彻底不打扰。
        //    仅在本次进程内执行一次；若开机后用户已在投新片则跳过，避免打断。
        if (!resumeDone && Store.getInstance(getApplicationContext()).isResumeOnBoot()) {
            resumeDone = true;
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        Thread.sleep(20000); // 开机后 DLNA 服务约需数十秒就绪
                        for (int attempt = 0; attempt < 3; attempt++) {
                            if (!running) return;
                            if (!Store.getInstance(getApplicationContext()).isResumeOnBoot()) {
                                // v16: 20 秒等待期间用户可能已在网页关掉开关，这里再查一次
                                Log.i("CastList", "auto resume skipped: disabled by setting");
                                return;
                            }
                            if (poller != null && poller.isExternalVideoPlaying()) {
                                Log.i("CastList", "auto resume skipped: external cast active");
                                return;
                            }
                            String fn = webServer.resumeLastPlayed();
                            if (fn != null) {
                                Log.i("CastList", "auto resume done: " + fn);
                                return;
                            }
                            try { Thread.sleep(10000); } catch (InterruptedException e) { return; }
                        }
                    } catch (InterruptedException e) {
                        Log.i("CastList", "auto resume interrupted");
                    }
                }
            }, "castlist-resume").start();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 被系统杀掉后自动重建，提高存活率
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (webServer != null) webServer.stop();
        super.onDestroy();
    }

    /** 建立前台服务通知（Android 8+ 必须带通知，否则 startForeground 会崩） */
    private void startForegroundNotify() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "投屏列表服务", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("局域网投屏列表 HTTP 服务");
            nm.createNotificationChannel(ch);
        }

        Intent open = new Intent(this, MainActivity.class);
        int flag = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flag |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, flag);

        Notification n;
        if (Build.VERSION.SDK_INT >= 26) {
            n = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("投屏列表运行中")
                    .setContentText("浏览器打开 http://" + getLocalIp() + ":18090")
                    .setSmallIcon(android.R.drawable.ic_menu_save)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        } else {
            n = new Notification.Builder(this)
                    .setContentTitle("投屏列表运行中")
                    .setSmallIcon(android.R.drawable.ic_menu_save)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
        }
        startForeground(1001, n);
    }

    /** 获取本机局域网 IP（DHCP 换 IP 后通知也会自动显示新地址） */
    private String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    // 过滤回环、IPv6、链路本地地址
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address
                            && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.1"; // 兜底（通用示例地址）
    }
}
