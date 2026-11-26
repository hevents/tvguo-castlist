# 在 Android TV / 电视果上开启 USB 调试（ADB）

本 App 本身不依赖 adb，但安装 APK、查看日志需要 adb。本文给出两条路径。

## 路径一：常规设置（优先）

1. 遥控器打开 **设置 → 关于 / 关于本机**。
2. 找到 **版本号**，用确定键连按 **7 次**，直到提示进入开发者模式。
3. 返回设置，进入新出现的 **开发者选项**。
4. 打开 **USB 调试**（有的 ROM 叫 **ADB 调试 / 网络调试 / 网络 ADB**）。
5. 电脑验证：

```
adb connect <电视IP>:5555
adb devices
```

## 路径二：DevEnabler（厂商隐藏入口时）

开源项目 [BuddhaYi/android-tv-developer-enabler](https://github.com/BuddhaYi/android-tv-developer-enabler)（MIT）提供一个约 12 KB 的小 APK，包名 `com.gelab.devenab`，界面有 9 个按钮，逐个尝试跳转到隐藏的开发者选项。

```
adb install android-tv-developer-enabler.apk
# 或者把 APK 放 U 盘，用电视上的文件管理器安装
```

在电视应用列表打开 **DevEnabler**，按顺序尝试：

| 按钮 | 说明 |
|---|---|
| 1 | 标准 Intent `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` |
| 2 | 旧 AOSP 类 `com.android.settings.DevelopmentSettings` |
| 3 | 新类 `com.android.settings.development.DevelopmentSettingsActivity` |
| 4 | `SubSettings` + Fragment 通用路径 |
| 5 | 跳到「关于设备」，手动连点版本号 7 次（**推荐兜底**） |
| 6 | `Settings.Global.putInt(ADB_ENABLED, 1)`，需 `WRITE_SECURE_SETTINGS`，未 root 通常失败 |
| 7 | Shell 命令 `setprop service.adb.tcp.port 5555` + 重启 adbd，需 root/shell 权限 |
| 8 | 打开系统设置根页面，自行查找 |
| 9 | 打开无线和网络设置（部分 ROM 在这里放 ADB 开关） |

按钮 6、7 在未 root 的设备上一般无效；点到开发者选项后，手动打开 USB 调试即可。

## 常见问题

- **`adb connect` 报 ConnectionRefused**：adb 没开或 IP 不对；确认同一局域网后重新开启。
- **显示 unauthorized**：到电视上确认「允许 USB 调试？」弹窗。
- **重启后 adb 又失效**：部分固件只在运行时设置 `service.adb.tcp.port`，重启需要重新开一次；本 App 的开机自启服务不受影响。
- **装不上 APK**：在电视设置里允许「安装未知来源应用」。

> DevEnabler 只是用法参考，本项目未修改或内置该 APK；请从上游仓库自行获取。
