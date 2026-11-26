# 电视果投屏列表（CastList）

一个装在电视果（Android TV 盒子）本机上的**无界面 APK**：抓取本机 DLNA 正在播放的视频直链，自动保存到电视果本地，并提供局域网 Web 管理页面。投过的片自动下载、开机自启、可选开机自动重播；电脑和手机在同 WiFi 下打开网页即可查看、播放、下载、删除。

> 纯 Java + 原生 HttpServer（`ServerSocket`），无 Gradle、无第三方依赖、无需 root。
> 实测设备：电视果6（Android 11 / user 固件 / 1920×1080）。其他 Android TV 设备理论可用，但厂商固件行为可能不同。

## 功能一览

| 功能 | 说明 |
|---|---|
| 投屏自动入库 | 每 10 秒轮询本机 DLNA（`127.0.0.1:39620`）的 `GetMediaInfo` / `GetPositionInfo`，抓到新 `video_url` 即写入 JSON 记录。 |
| 自动下载 | 新片入库后台立即下载；支持普通 HTTP(S) 断点续传与 HLS(m3u8) 分片合并。 |
| 局域网 Web 面板 | `http://<电视果IP>:18090/`，列表标题/站点/投屏时间/时长/下载状态；SSE 推送变化，页面不刷新。 |
| 播放方式 | 「电脑播放」走浏览器流式播放（`/file/?inline=1`，支持 Range）；「电视播放」走本机 DLNA 把文件投回电视果播放器。 |
| 已下载管理 | `/downloads` 页列出本地文件，支持播放/下载/删除；主页列表也有删除按钮。 |
| 空间保护 | 每次下载前检查可用空间，低于 2GB 预留阈值时，按最后修改时间删除最旧文件（LRU 循环）。 |
| 开机自启 | `BOOT_COMPLETED` 广播拉起前台服务，无可见 Activity，可扛住投屏时的固件清理。 |
| 开机自动重播（可关） | 重启后把最近一个已完整下载的视频投回电视从头播放；网页顶部复选框可随时开关，设置持久化。 |
| 去重 | 按完整 `video_url` 去重；同一 URL 重复投屏只更新时间并置顶，不会重复下载。 |

## 快速开始

### 1. 准备 ADB

电视果需要先打开 USB/网络调试。常规方法：设置 → 关于 → **版本号连点 7 次** → 开发者选项 → 打开 **USB 调试 / 网络调试**，然后电脑执行：

```
adb connect <电视果IP>:5555
adb devices
```

如果固件隐藏了开发者选项入口，可以用开源小工具 [android-tv-developer-enabler](https://github.com/BuddhaYi/android-tv-developer-enabler)（MIT）尝试 9 种入口；详见 [docs/enable-adb.zh-CN.md](docs/enable-adb.zh-CN.md)。该工具只是备用钥匙，本 App 本身不依赖它。

### 2. 安装

**推荐：从 Releases 下载已签名的 APK**（不需要编译）：

👉 **[下载最新版 APK](https://github.com/hevents/tvguo-castlist/releases/latest/download/tvguo-castlist.apk)** ｜ [全部版本](https://github.com/hevents/tvguo-castlist/releases)

下载后执行：

```
adb install -r tvguo-castlist.apk
```

也可以安装仓库 `apk/` 目录下的预编译版本，或从源码自行编译（见下）。安装后服务会自动启动；若没有，可打开应用图标，或发送广播：

```
adb shell am broadcast -a com.tvguo.castlist.START -n com.tvguo.castlist/.BootReceiver
```

### 3. 打开面板

浏览器访问：

```
http://<电视果IP>:18090/
```

IP 可在路由器 DHCP 列表、电视设置里查看；App 前台通知里也会显示。用手机向电视果投屏，10 秒内列表出现新条目并开始下载。

## Web 接口

| 路径 | 作用 |
|---|---|
| `GET /` | 播放列表主页（含「重启后自动播放上次视频」开关） |
| `GET /api/list` | 记录、下载任务状态、磁盘统计、`resumeOnBoot` 等 JSON |
| `GET /api/download?id=N` | 手动触发下载 |
| `GET /api/delete?name=FILE` | 删除已下载文件 |
| `GET /api/playontv?name=FILE` | 让电视果播放器播放本地文件 |
| `GET /api/setresume?on=0\|1` | 开关开机自动重播 |
| `GET /api/events` | SSE 事件流，数据变化才推送 |
| `GET /api/export` | 导出原始投屏记录 JSON |
| `GET /downloads` | 已下载文件列表 |
| `GET /play/FILE` | 浏览器边下边放页面 |
| `GET /file/FILE` | 文件下载/播放，支持 `Range` 断点请求 |

## 从源码编译

需要：Android SDK（build-tools 34.0.0、platform android-34）、JDK 11+（`javac` 需能编译 source 8）、Python 3（用于清理构建目录与写入 dex）。不需要 Gradle。

```bash
export ANDROID_HOME=/path/to/Android/Sdk   # Windows 可用 C:/Android
python3 apk_project/build.sh
```

产物：`apk/tvguo-castlist.apk`。首次编译会自动生成调试密钥库 `apk_project/castlist.jks`（已被 `.gitignore` 忽略）；**不要提交自己的签名密钥**。

## 已知限制

- 只抓 DLNA 直链；镜像投屏/屏幕镜像没有可下载 URL，无法保存。
- 重启后播放会话丢失，自动重播是**从头播放**，不是卡点续播。
- 加密 HLS（`EXT-X-KEY`）不支持。
- Web 服务绑定 `0.0.0.0:18090` 且**无鉴权**：请只在可信局域网使用，不要暴露到公网。
- 本机固件（`ro.secure=1` / `ro.debuggable=0`）的 adb shell 非 root；`persist.adb.tcp.port` 为空，重启后 adb 可能需要重新开启，但本 App 的服务不依赖 adb。

## 目录结构

```
.
├── apk/tvguo-castlist.apk          # 预编译签名 APK
├── apk_project/
│   ├── AndroidManifest.xml
│   ├── build.sh                    # 纯命令行构建脚本
│   └── src/com/tvguo/castlist/     # Java 源码
└── docs/enable-adb.zh-CN.md        # 开启 USB 调试说明
```

## 许可

MIT — 见 [LICENSE](LICENSE)。仅供自有设备学习与个人使用；请遵守当地法律法规及视频版权。
