package com.tvguo.castlist;

import android.content.Context;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 局域网 HTTP 服务（纯 ServerSocket 实现，零第三方依赖）。
 * 监听 0.0.0.0:18090，手机和 PC 同 WiFi 浏览器即可访问。
 *
 * 路由：
 *   GET /                 → 播放列表页（HTML）
 *   GET /api/list         → 记录 + 下载任务状态（JSON）
 *   GET /api/download?id=N → 触发后台下载
 *   GET /api/delete?name=X → 删除已下载文件
 *   GET /api/export       → 导出 JSON 原始数据
 *   GET /downloads        → 已下载文件列表（播放/下载/删除）
 *   GET /play/xxx.mp4     → 边下边放播放页（HTML5 video）
 *   GET /file/xxx.mp4     → 提供已下载文件（支持 Range 断点续传）
 *
 * v4 新增：
 *   - startDownload() 提升为公开方法，供 Poller 抓到新片时自动触发（边投边下）
 *   - 下载前 StatFs 空间预检，不足则按最旧优先删除已下载文件（LRU 淘汰）
 *   - /play/ 播放页：Content-Disposition inline，浏览器边下边放
 */
public class WebServer implements Runnable {
    private static final int PORT = 18090;
    /** 下载后给系统预留的最小可用空间（2GB），低于此值就淘汰旧文件 */
    private static final long RESERVE_BYTES = 2L * 1024 * 1024 * 1024;
    private final Context ctx;
    private final Store store;
    private final File dlDir;
    private final Map<String, JSONObject> jobs = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private ServerSocket server;
    private volatile boolean running = false;

    public WebServer(Context ctx) {
        this.ctx = ctx;
        this.store = Store.getInstance(ctx);
        // 下载目录：app 外部私有目录，Android 11 无需存储权限即可读写
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        this.dlDir = new File(base, "downloads");
        if (!dlDir.exists()) dlDir.mkdirs();
    }

    @Override
    public void run() {
        running = true;
        try {
            server = new ServerSocket(PORT);
            Log.i("CastList", "HTTP server listening on 0.0.0.0:" + PORT);
            Log.i("CastList", "download dir: " + dlDir.getAbsolutePath());
            while (running) {
                try {
                    Socket s = server.accept();
                    pool.execute(new Handler(s));
                } catch (SocketException e) {
                    if (running) Log.w("CastList", "accept: " + e.getMessage());
                    break;
                }
            }
        } catch (Exception e) {
            Log.e("CastList", "HTTP server fatal: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        pool.shutdownNow();
    }

    /**
     * 让电视果系统播放器播放本机已下载的文件（v10）。
     * 原理：向本机 DLNA AVTransport (127.0.0.1:39620) 发 SetAVTransportURI，
     * URI 指向本 HTTP 服务的 /file/xxx（电视果自己拉自己的 18090 端口），再发 Play。
     * 实测 2026-11-24：TVGuoPlayer 的 ijkplayer 能正常打开该 URL 并播放。
     * @return null 表示成功，否则返回错误信息
     */
    public String playOnTv(String name) {
        if (name == null || name.isEmpty()) return "文件名不能为空";
        File f = new File(dlDir, new File(name).getName()); // getName 防路径穿越
        if (!f.isFile()) return "文件不存在: " + name;
        String uri = "http://" + localIp() + ":" + PORT + "/file/" + urlEncode(f.getName());
        try {
            soapDlna("SetAVTransportURI",
                    "<CurrentURI>" + esc(uri) + "</CurrentURI>"
                            + "<CurrentURIMetaData></CurrentURIMetaData>");
            soapDlna("Play", "<Speed>1</Speed>");
            Log.i("CastList", "play on TV: " + f.getName());
            return null; // 成功
        } catch (Exception e) {
            Log.w("CastList", "play on TV fail: " + e.getMessage());
            return "播放失败: " + e.getMessage();
        }
    }

    /**
     * v11 开机自动重播：从投屏记录倒序找最近一条"本地已有完整文件"的视频，投回电视从头播。
     * 整机重启会清空 DLNA 播放会话，无法知道"刚才播到哪一帧"，故用最近下载的本地文件近似。
     * @return 成功播放的文件名；无可播文件或 DLNA 失败返回 null
     */
    public String resumeLastPlayed() {
        JSONArray arr = store.all();
        for (int i = arr.length() - 1; i >= 0; i--) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            String title = r.optString("title", "");
            if (title.isEmpty() || "(无标题)".equals(title)) continue;
            String fn = safeFilename(title) + ".mp4";
            File f = new File(dlDir, fn);
            // 小于 100KB 的多半是下载中断的残片，跳过
            if (f.isFile() && f.length() > 100L * 1024) {
                String err = playOnTv(fn);
                if (err == null) {
                    Log.i("CastList", "auto resume played: " + fn);
                    return fn;
                }
                Log.w("CastList", "auto resume play fail: " + err);
                return null; // DLNA 调用失败就别再试别的，避免乱切
            }
        }
        Log.i("CastList", "auto resume: no local file found");
        return null;
    }

    /** 调用本机 DLNA AVTransport SOAP 动作（SetAVTransportURI / Play 等） */
    private String soapDlna(String action, String extraParams) throws IOException {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<InstanceID>0</InstanceID>" + extraParams
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:39620/"
                    + "_urn:schemas-upnp-org:service:AVTransport_control");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION",
                    "\"urn:schemas-upnp-org:service:AVTransport:1#" + action + "\"");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            return new String(baos.toByteArray(), "UTF-8");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 后台下载指定 id（支持断点续传：已存在文件则续写）。
     * 公开方法：Web 手动下载 与 Poller 自动下载 共用同一套逻辑。
     * v12：HEAD 探测真实长度避免 HTTP 416；416 自动删残片重下；HLS(m3u8) 走分片合并。
     */
    public void startDownload(final String id, final JSONObject rec) {
        // 防重复：同一 id 正在下载就直接忽略（自动+手动并发时的双保险）
        JSONObject cur = jobs.get(id);
        if (cur != null && "downloading".equals(cur.optString("status"))) return;

        JSONObject j = new JSONObject();
        try { j.put("status", "downloading"); j.put("msg", ""); j.put("file", ""); }
        catch (Exception ignored) {}
        jobs.put(id, j);

        pool.execute(new Runnable() {
            @Override
            public void run() {
                String fname = rec.optString("title", "video");
                try {
                    String url = rec.optString("video_url", "");
                    String title = rec.optString("title", "video");
                    fname = safeFilename(title) + ".mp4";
                    File out = new File(dlDir, fname);

                    // v12: HLS 分片流 → 单独走 m3u8 合并逻辑（传 id 做临时文件名，防同标题并发冲突）
                    if (url.toLowerCase(Locale.US).contains("m3u8")) {
                        long size = downloadHls(url, out, id);
                        JSONObject done = new JSONObject();
                        done.put("status", "done");
                        done.put("file", fname);
                        done.put("size", size);
                        done.put("msg", "完成(HLS)");
                        jobs.put(id, done);
                        Log.i("CastList", "HLS download done: " + fname + " " + size + "B");
                        return;
                    }

                    long total = downloadProgressive(url, out);

                    JSONObject done = new JSONObject();
                    done.put("status", "done");
                    done.put("file", fname);
                    done.put("size", total);
                    done.put("msg", "完成");
                    jobs.put(id, done);
                    Log.i("CastList", "download done: " + fname + " " + total + "B");
                } catch (final Exception e) {
                    JSONObject err = new JSONObject();
                    try {
                        err.put("status", "error");
                        err.put("msg", "失败: " + e.getMessage());
                    } catch (Exception ignored) {}
                    jobs.put(id, err);
                    Log.w("CastList", "download fail: " + e.getMessage());
                }
            }
        });
    }

    /**
     * v12: 普通 HTTP(S) 文件下载，带断点续传 + 416 自愈。
     * 流程：HEAD 探测远端真实长度 → 本地文件比远端小才带 Range 续传；
     * 若服务端不支持 HEAD/长度未知，则退回"有残片即续传"，遇 416 删残片整文件重下一次。
     * @return 最终文件总字节数
     */
    private long downloadProgressive(String url, File out) throws IOException {
        long remoteLen = probeContentLength(url);
        long exist = out.exists() ? out.length() : 0;
        // 关键修复：本地文件 >= 远端长度（同长度或残片更大）就删掉重下，避免发 Range 被回 416
        boolean resume = remoteLen > 0 && exist > 0 && exist < remoteLen;
        if (exist > 0 && !resume) {
            Log.w("CastList", "discard stale/oversized partial: " + out.getName()
                    + " local=" + exist + " remote=" + remoteLen);
            if (!out.delete()) Log.w("CastList", "delete stale failed");
            exist = 0;
        }

        long total = doHttpDownload(url, out, exist);
        // 兜底：万一探测失准仍收到 416，删掉重来一次整文件
        if (total < 0) {
            Log.w("CastList", "416 fallback: delete and redownload whole file");
            if (out.exists() && !out.delete()) throw new IOException("无法删除残片");
            total = doHttpDownload(url, out, 0);
            if (total < 0) throw new IOException("HTTP 416（整文件重试仍失败）");
        }
        return total;
    }

    /** 读取远端 Content-Length；HEAD 失败或未知返回 -1（不阻断下载） */
    private long probeContentLength(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = c.getResponseCode();
            if (code >= 200 && code < 400) {
                String len = c.getHeaderField("Content-Length");
                if (len != null) return Long.parseLong(len);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.disconnect();
        }
        return -1;
    }

    /**
     * 执行一次 HTTP(S) 下载。exist>0 时带 Range 续写。
     * @return 最终文件总字节数；-1 表示收到 416（调用方删除残片重试）
     */
    private long doHttpDownload(String url, File out, long exist) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (exist > 0) conn.setRequestProperty("Range", "bytes=" + exist + "-");

            int code = conn.getResponseCode();
            if (code == 416) return -1; // 交给调用方删残片重下
            if (code >= 400) throw new IOException("HTTP " + code);

            long need = -1;
            try { need = Long.parseLong(conn.getHeaderField("Content-Length")); }
            catch (Exception ignored) {}
            ensureSpace(need, out);

            InputStream is = conn.getInputStream();
            FileOutputStream fos = new FileOutputStream(out, exist > 0);
            byte[] buf = new byte[65536];
            int n;
            long total = exist;
            while ((n = is.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
            fos.close();
            is.close();
            return total;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * v12: HLS(m3u8) 下载。支持 master playlist（选第一个变体）+ media playlist；
     * 逐个下载 .ts 分片并按序拼接成 .mp4（MPEG-TS 容器可直接二进制拼接，ijkplayer/VLC 可播）。
     * 加密(EXT-X-KEY)流不支持，明确报错。
     * v13: 临时文件名带 id 防同标题并发写坏；整体 15 分钟超时防卡死。
     * @return 写入的总字节数
     */
    private long downloadHls(String masterUrl, File out, String taskId) throws IOException {
        String text = httpGetText(masterUrl);
        if (text == null || text.trim().isEmpty()) throw new IOException("m3u8 为空");

        // master playlist：含 EXT-X-STREAM-INF，取第一个变体 URL
        String mediaUrl = masterUrl;
        if (text.contains("#EXT-X-STREAM-INF")) {
            String first = null;
            for (String ln : text.split("\\r?\\n")) {
                String s = ln.trim();
                if (s.isEmpty() || s.startsWith("#")) continue;
                first = s;
                break;
            }
            if (first == null) throw new IOException("master playlist 无变体");
            mediaUrl = resolveUrl(masterUrl, first);
            text = httpGetText(mediaUrl);
            if (text == null) throw new IOException("变体 playlist 拉取失败");
        }
        if (text.contains("#EXT-X-KEY")) throw new IOException("加密 HLS(EXT-X-KEY) 暂不支持");

        // 收集分片 URL
        List<String> segs = new ArrayList<String>();
        for (String ln : text.split("\\r?\\n")) {
            String s = ln.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            segs.add(resolveUrl(mediaUrl, s));
        }
        if (segs.isEmpty()) throw new IOException("m3u8 无分片");
        Log.i("CastList", "HLS segments: " + segs.size());

        ensureSpace(segs.size() * 2L * 1024 * 1024, out); // 粗估：每分片 2MB 预检
        // v13: 临时文件名带任务 id——两个同标题 HLS 并发时各写各的，不互相破坏
        File tmp = new File(dlDir, safeFilename(out.getName()) + ".part." + taskId);
        FileOutputStream fos = new FileOutputStream(tmp, false);
        long total = 0;
        long deadline = System.currentTimeMillis() + 15L * 60 * 1000; // 整体 15 分钟超时
        byte[] buf = new byte[65536];
        try {
            for (int i = 0; i < segs.size(); i++) {
                if (System.currentTimeMillis() > deadline) throw new IOException("HLS 下载超时(15分钟)");
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL(segs.get(i)).openConnection();
                    c.setConnectTimeout(12000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", "Mozilla/5.0");
                    if (c.getResponseCode() >= 400) throw new IOException("分片 " + i + " HTTP " + c.getResponseCode());
                    InputStream is = c.getInputStream();
                    int n;
                    while ((n = is.read(buf)) > 0) { fos.write(buf, 0, n); total += n; }
                    is.close();
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        } finally {
            fos.close();
        }
        if (!tmp.renameTo(out)) {
            if (out.exists()) out.delete();
            tmp.renameTo(out);
        }
        return total;
    }

    /** HTTP GET 文本（m3u8），失败返回 null */
    private String httpGetText(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (c.getResponseCode() >= 400) return null;
            InputStream is = c.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) baos.write(b, 0, n);
            is.close();
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 相对 URL 转绝对 URL */
    private static String resolveUrl(String base, String ref) throws MalformedURLException {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref;
        URL b = new URL(base);
        if (ref.startsWith("/")) return b.getProtocol() + "://" + b.getHost()
                + (b.getPort() > 0 ? ":" + b.getPort() : "") + ref;
        String path = b.getPath();
        int slash = path.lastIndexOf('/');
        String dir = (slash >= 0 ? path.substring(0, slash + 1) : "/");
        return b.getProtocol() + "://" + b.getHost()
                + (b.getPort() > 0 ? ":" + b.getPort() : "") + dir + ref;
    }

    /**
     * 空间预检：若可用空间 - 本次需要 < 预留阈值，按最后修改时间升序（最旧先删）
     * 淘汰已下载文件，直到空间足够或无可删文件。exclude 是当前正在写的文件，跳过。
     */
    private synchronized void ensureSpace(long required, File exclude) {
        try {
            StatFs sf = new StatFs(dlDir.getPath());
            long avail = sf.getAvailableBytes();
            if (required < 0) required = 0;
            if (avail - required >= RESERVE_BYTES) return; // 空间充足，直接返回
            Log.w("CastList", "low space: avail=" + avail + " need=" + required
                    + " reserve=" + RESERVE_BYTES + " -> cleanup oldest");

            File[] fs = dlDir.listFiles();
            if (fs == null) return;
            List<File> cand = new ArrayList<File>();
            String excludePath = exclude == null ? "" : exclude.getAbsolutePath();
            for (File f : fs) {
                if (f.isFile() && !f.getAbsolutePath().equals(excludePath)) cand.add(f);
            }
            Collections.sort(cand, new Comparator<File>() {
                public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for (File f : cand) {
                if (avail - required >= RESERVE_BYTES) break;
                long sz = f.length();
                if (f.delete()) {
                    avail += sz;
                    Log.i("CastList", "space cleanup deleted: " + f.getName() + " " + sz + "B");
                }
            }
        } catch (Exception e) {
            Log.w("CastList", "ensureSpace: " + e.getMessage());
        }
    }

    /** 单个 HTTP 连接的处理器 */
    private class Handler implements Runnable {
        private final Socket socket;
        Handler(Socket s) { this.socket = s; }

        @Override
        public void run() {
            try {
                socket.setSoTimeout(10000);
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), "UTF-8"));

                // 解析请求行：GET /path?a=b HTTP/1.1
                String line = in.readLine();
                if (line == null || line.isEmpty()) { socket.close(); return; }
                String[] parts = line.split("\\s+");
                if (parts.length < 2) { socket.close(); return; }
                String method = parts[0];
                String target = parts[1];

                // 读取请求头，保存 Range 等字段（断点续传需要）
                headers.clear();
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    fillHeaders(line);
                }

                String path = target;
                String query = "";
                int q = target.indexOf('?');
                if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }

                if ("GET".equals(method)) {
                    routeGet(path, query);
                } else {
                    send("HTTP/1.1 405 Method Not Allowed\r\nContent-Length: 0\r\n\r\n");
                }
            } catch (Exception e) {
                Log.w("CastList", "handler: " + e.getMessage());
            } finally {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }

        private void routeGet(String path, String query) throws IOException {
            if (path.equals("/api/list")) {
                JSONObject js = new JSONObject();
                try {
                    js.put("records", store.all());
                    JSONObject jjobs = new JSONObject();
                    for (Map.Entry<String, JSONObject> e : jobs.entrySet()) {
                        jjobs.put(e.getKey(), e.getValue());
                    }
                    js.put("jobs", jjobs);
                    js.put("dlDir", dlDir.getAbsolutePath());
                    js.put("freeBytes", freeBytes());
                    js.put("totalBytes", totalBytes());
                    js.put("usedBytes", usedBytes());
                    js.put("downloadBytes", downloadBytes());
                    js.put("fileCount", fileCount());
                    js.put("aliasHost", ALIAS_HOST);
                    js.put("realUrl", "http://" + localIp() + ":" + PORT);
                    js.put("resumeOnBoot", store.isResumeOnBoot()); // v16: 开机自动重播开关状态
                } catch (Exception ignored) {}
                sendJson(js.toString());

            } else if (path.equals("/api/download")) {
                String id = param(query, "id");
                JSONObject rec = store.find(id);
                if (rec == null) {
                    sendJson("{\"ok\":false,\"msg\":\"记录不存在\"}");
                    return;
                }
                JSONObject cur = jobs.get(id);
                if (cur != null && "downloading".equals(cur.optString("status"))) {
                    sendJson("{\"ok\":false,\"msg\":\"已在下载中\"}");
                    return;
                }
                startDownload(id, rec);
                sendJson("{\"ok\":true,\"msg\":\"已开始下载\"}");

            } else if (path.equals("/api/delete")) {
                String name = param(query, "name");
                File f = new File(dlDir, new File(name).getName()); // getName 防路径穿越
                boolean ok = f.exists() && f.isFile() && f.delete();
                sendJson("{\"ok\":" + ok + "}");

            } else if (path.equals("/api/setresume")) {
                // v16: 开机自动重播开关。on=1 开 / on=0 关；空参数返回当前状态
                String on = param(query, "on");
                if (!on.isEmpty()) store.setResumeOnBoot("1".equals(on) || "true".equalsIgnoreCase(on));
                sendJson("{\"ok\":true,\"resumeOnBoot\":" + store.isResumeOnBoot() + "}");

            } else if (path.equals("/api/playontv")) {
                // v10: 让电视果系统播放器播放本机已下载文件
                String name = param(query, "name");
                String err = playOnTv(name);
                if (err == null) {
                    sendJson("{\"ok\":true,\"msg\":\"已在电视果播放\"}");
                } else {
                    sendJson("{\"ok\":false,\"msg\":\"" + esc(err) + "\"}");
                }

            } else if (path.equals("/api/events")) {
                // SSE 订阅：长连接，数据变化才推送；浏览器局部更新，不整页刷新
                serveEvents();

            } else if (path.equals("/api/export")) {
                sendJson(store.all().toString());

            } else if (path.equals("/downloads")) {
                send(page("已下载文件", downloadsHtml()), "text/html; charset=utf-8");

            } else if (path.startsWith("/play/")) {
                servePlayPage(urlDecode(path.substring("/play/".length())));

            } else if (path.startsWith("/file/")) {
                serveFile(urlDecode(path.substring("/file/".length())), query);

            } else if (path.equals("/favicon.ico")) {
                send("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n");

            } else {
                send(page("电视果投屏列表", listHtml()), "text/html; charset=utf-8");
            }
        }

        /**
         * SSE（Server-Sent Events）订阅：建立长连接，只有数据指纹变化时才推送新 HTML 片段。
         * 浏览器用 EventSource 接收，仅替换列表容器，不再每 5 秒整页 reload。
         * 每 15 秒发一次心跳注释，防止中间设备断开空闲连接。
         */
        private void serveEvents() throws IOException {
            OutputStream os = socket.getOutputStream();
            os.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream; charset=utf-8\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: keep-alive\r\n"
                    + "X-Accel-Buffering: no\r\n\r\n").getBytes("UTF-8"));
            os.flush();

            String lastFp = "";
            int beat = 0;
            while (running && !socket.isClosed()) {
                String fp = dataFingerprint();
                if (!fp.equals(lastFp)) {
                    lastFp = fp;
                    String html = listBodyHtml(); // 只发可变内容（hero+space+表格），不含 <!DOCTYPE>
                    html = html.replace("\n", ""); // SSE data 必须是单行
                    os.write(("event: update\ndata: " + html + "\n\n").getBytes("UTF-8"));
                    os.flush();
                }
                beat++;
                if (beat % 15 == 0) { // 约 15 秒（循环 1 秒一次）发心跳
                    os.write(": keepalive\n\n".getBytes("UTF-8"));
                    os.flush();
                }
                try { Thread.sleep(1000); }
                catch (InterruptedException e) { break; }
            }
        }

        /** 数据指纹：记录数/最后记录时间/下载任务状态/文件数/剩余空间任一变化都会触发推送 */
        private String dataFingerprint() {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = store.all();
            sb.append("n=").append(arr.length());
            if (arr.length() > 0) {
                JSONObject last = arr.optJSONObject(arr.length() - 1);
                sb.append(",id=").append(last.optInt("id"))
                  .append(",d=").append(last.optString("record_date", ""));
            }
            for (Map.Entry<String, JSONObject> e : jobs.entrySet()) {
                sb.append(",j").append(e.getKey()).append("=").append(e.getValue().optString("status", ""));
            }
            sb.append(",f=").append(fileCount()).append(",free=").append(freeBytes());
            sb.append(",resume=").append(store.isResumeOnBoot()); // v16: 开关变化也触发 SSE 同步多标签页
            return sb.toString();
        }

        /** 边下边放播放页：HTML5 video 走 /file/xxx?inline=1，支持拖动进度条（Range） */
        private void servePlayPage(String name) throws IOException {
            String enc = urlEncode(name);
            String body = "<h2>▶ " + esc(name) + "</h2>"
                    + "<video src=\"/file/" + enc + "?inline=1\" controls autoplay "
                    + "style=\"width:100%;max-width:860px;background:#000\"></video>"
                    + "<p style='margin-top:14px'><a href=\"/file/" + enc + "\">⬇ 下载到本地</a> · "
                    + "<a href=\"/downloads\">← 已下载文件</a></p>"
                    + "<p><small>浏览器边下边放；若无法播放（编码不兼容），请用 VLC/PotPlayer 打开 "
                    + "http://" + localIp() + ":" + PORT + "/file/" + enc + "</small></p>";
            send(page("播放 - " + name, body), "text/html; charset=utf-8");
        }

        /** 提供已下载文件，支持 Range 断点续传（手机浏览器可播放/续传） */
        private void serveFile(String name, String query) throws IOException {
            File f = new File(dlDir, new File(name).getName()); // getName 防路径穿越
            if (!f.exists() || !f.isFile()) {
                send("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n");
                return;
            }
            long size = f.length();
            long start = 0, end = size - 1;
            String range = headers.get("range"); // fillHeaders 里 key 已转小写
            int code = 200;
            if (range != null && range.startsWith("bytes=")) {
                try {
                    String[] seg = range.substring(6).split("-");
                    start = Long.parseLong(seg[0]);
                    if (seg.length > 1 && !seg[1].isEmpty()) end = Long.parseLong(seg[1]);
                    if (end >= size) end = size - 1;
                    code = 206;
                } catch (Exception e) { start = 0; end = size - 1; code = 200; }
            }
            long length = end - start + 1;
            // inline=1 时浏览器内联播放（/play/ 页用）；否则 attachment 触发下载
            String disp = "1".equals(param(query, "inline")) ? "inline" : "attachment";
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 ").append(code).append(code == 206 ? " Partial Content" : " OK").append("\r\n");
            head.append("Content-Type: video/mp4\r\n");
            head.append("Accept-Ranges: bytes\r\n");
            // v12: RFC5987 编码——ASCII 回退名 + UTF-8 percent 编码名，解决中文文件名乱码
            String asciiName = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "_");
            String utf8Name = urlEncode(name).replace("+", "%20");
            head.append("Content-Disposition: ").append(disp)
                    .append("; filename=\"").append(asciiName).append("\"")
                    .append("; filename*=UTF-8''").append(utf8Name).append("\r\n");
            head.append("Content-Length: ").append(length).append("\r\n");
            if (code == 206) head.append("Content-Range: bytes ").append(start).append("-")
                    .append(end).append("/").append(size).append("\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream os = socket.getOutputStream();
            os.write(head.toString().getBytes("UTF-8"));
            RandomAccessFile raf = new RandomAccessFile(f, "r");
            raf.seek(start);
            byte[] buf = new byte[262144];
            long left = length;
            while (left > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, left));
                if (n <= 0) break;
                os.write(buf, 0, n);
                left -= n;
            }
            raf.close();
        }

        // 请求头缓存（仅 Range 需要）
        private final Map<String, String> headers = new HashMap<>();
        // 覆写 run 里填充 headers —— 见下方 fillHeaders 钩子
        private void fillHeaders(String rawLine) {
            int c = rawLine.indexOf(':');
            if (c > 0) headers.put(rawLine.substring(0, c).trim().toLowerCase(),
                    rawLine.substring(c + 1).trim());
        }

        // ===== 响应发送辅助 =====
        private void sendJson(String json) throws IOException {
            send(json, "application/json; charset=utf-8");
        }

        private void send(String body, String ctype) throws IOException {
            byte[] data = body.getBytes("UTF-8");
            StringBuilder head = new StringBuilder();
            head.append("HTTP/1.1 200 OK\r\n");
            head.append("Content-Type: ").append(ctype).append("\r\n");
            head.append("Content-Length: ").append(data.length).append("\r\n");
            head.append("Cache-Control: no-cache\r\n");
            head.append("Connection: close\r\n\r\n");
            OutputStream os = socket.getOutputStream();
            os.write(head.toString().getBytes("UTF-8"));
            os.write(data);
            os.flush();
        }

        private void send(String raw) throws IOException {
            socket.getOutputStream().write(raw.getBytes("UTF-8"));
            socket.getOutputStream().flush();
        }

        // ===== 页面生成 =====
        private String page(String title, String body) {
            return "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                    + "<title>" + title + "</title><style>"
                    + "body{font-family:-apple-system,'Microsoft YaHei',sans-serif;max-width:900px;"
                    + "margin:24px auto;padding:0 16px;color:#222}"
                    + "h1{font-size:22px}table{width:100%;border-collapse:collapse;font-size:14px}"
                    + "th,td{padding:9px 8px;border-bottom:1px solid #e5e5e5;text-align:left;vertical-align:top}"
                    + "th{background:#fafafa}.btn{background:#1677ff;color:#fff;border:0;padding:5px 12px;"
                    + "border-radius:5px;cursor:pointer;font-size:13px;text-decoration:none;display:inline-block}"
                    + ".btn.tv{background:#722ed1}.btn.del{background:#ff4d4f}.st{font-size:12px;color:#666}.ok{color:#0a0}a{color:#1677ff}"
                    + ".bar{background:#f0f7ff;padding:10px 14px;border-radius:8px;margin-bottom:16px;font-size:13px}"
                    + ".hero{background:linear-gradient(135deg,#1677ff,#4096ff);color:#fff;padding:16px 18px;"
                    + "border-radius:10px;margin-bottom:16px}"
                    + ".hero .host{font-size:24px;font-weight:700;letter-spacing:.5px;word-break:break-all}"
                    + ".hero .host a{color:#fff;text-decoration:none;border-bottom:1px dashed rgba(255,255,255,.7)}"
                    + ".hero .real{margin-top:6px;font-size:12px;opacity:.92;word-break:break-all}"
                    + ".bar2{background:#fff;border:1px solid #e5e5e5;border-radius:8px;padding:12px 14px;"
                    + "margin-bottom:16px;font-size:13px}"
                    + ".track{height:8px;background:#eef2f7;border-radius:4px;margin:8px 0 4px;overflow:hidden}"
                    + ".fill{height:100%;background:#1677ff;border-radius:4px}"
                    + "</style></head><body>" + body
                    // v10: 电视播放按钮通用脚本（列表页 + 下载页共用）
                    + "<script>"
                    + "function tvPlay(a){"
                    + "var old=a.innerHTML;a.innerHTML='⏳ 发送中…';"
                    + "fetch(a.href).then(function(r){return r.json()}).then(function(j){"
                    + "a.innerHTML=j.ok?'✓ 已发送到电视':'✗ '+j.msg;"
                    + "setTimeout(function(){a.innerHTML=old},3000);"
                    + "}).catch(function(){a.innerHTML='✗ 请求失败';"
                    + "setTimeout(function(){a.innerHTML=old},3000)});"
                    + "return false;};"
                    // v15: 删除已下载文件（异步 fetch，弹确认，不跳 JSON 页）
                    // 主页 '/'：靠 SSE 指纹变化自动刷新按钮为“下载”，无需整页刷新
                    // /downloads 页：无 SSE，删完 setTimeout 整页 reload 更新列表与统计
                    + "function delFile(a){"
                    + "if(!confirm('删除此文件?'))return false;"
                    + "fetch(a.href).then(function(r){return r.json()}).then(function(j){"
                    + "a.innerHTML=j.ok?'✓ 已删除':'✗ 失败';"
                    + "if(j.ok&&location.pathname==='/downloads'){setTimeout(function(){location.reload()},600);}"
                    + "else{setTimeout(function(){a.innerHTML='✕ 删除'},3000);}"
                    + "}).catch(function(){a.innerHTML='✗ 请求失败';"
                    + "setTimeout(function(){a.innerHTML='✕ 删除'},3000)});"
                    + "return false;};"
                    // v16: 开机自动重播开关——异步写设置，失败回滚复选框；SSE 会用服务端值覆盖以同步多标签页
                    + "function setResume(cb){"
                    + "var was=cb.checked;"
                    + "fetch('/api/setresume?on='+(was?1:0)).then(function(r){return r.json()}).then(function(j){"
                    + "if(!j.ok){cb.checked=!was;}"
                    + "}).catch(function(){cb.checked=!was;});"
                    + "};"
                    + "</script>"
                    + "</body></html>";
        }

        /** 首屏 HTML：可变内容 + 页脚 + EventSource 客户端脚本（订阅后不再整页刷新） */
        private String listHtml() {
            return listBodyHtml()
                    + "<p id=\"foot\"><small>服务运行在电视果本机 · 下载文件保存在 " + esc(dlDir.getAbsolutePath())
                    + " · " + ALIAS_HOST + " 为公网域名，仅在本页作显示别名，浏览器直接输入不会指向本机"
                    + " · 订阅模式：数据变化自动更新，页面不刷新</small></p>"
                    + "<script>"
                    + "var es=new EventSource('/api/events');"
                    + "es.addEventListener('update',function(e){"
                    + "var box=document.getElementById('dyn');if(box)box.innerHTML=e.data;"
                    + "});"
                    + "</script>";
        }

        /** 可变内容（hero + 空间条 + 状态栏 + 表格）——首屏与 SSE 推送共用，ID 用 dyn 包住 */
        private String listBodyHtml() {
            JSONArray arr = store.all();
            StringBuilder rows = new StringBuilder();
            for (int i = arr.length() - 1; i >= 0; i--) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                String id = String.valueOf(r.optInt("id"));
                // 保留原始标题：按钮状态要靠它反查磁盘文件（jobs 是内存态，服务重启后清空）
                String rawTitle = r.optString("title", "video");
                String title = esc(rawTitle);
                if (title.isEmpty()) title = "(无标题)";
                String site = esc(r.optString("site_id", ""));
                String date = esc(r.optString("record_date", ""));
                String dur = esc(r.optString("duration", ""));
                JSONObject job = jobs.get(id);
                String btn = "下载按钮占位";
                String st = job == null ? "" : job.optString("status", "");
                // 服务重启后 jobs 清空，但文件仍在磁盘：按下载时的同名规则反查
                File saved = new File(dlDir, safeFilename(rawTitle) + ".mp4");
                boolean hasFile = saved.isFile() && saved.length() > 0;
                if ("downloading".equals(st)) {
                    btn = "<span class=\"st\">⏬ 自动下载中…</span>";
                } else if ("done".equals(st) || hasFile) {
                    // 优先用 job 里的文件名，重启后用磁盘反查的文件名
                    String fn = "done".equals(st) ? job.optString("file", "") : saved.getName();
                    long size = "done".equals(st) ? job.optLong("size") : saved.length();
                    btn = "<a class=\"btn\" href=\"/play/" + urlEncode(fn) + "\">▶ 电脑播放</a> "
                            + "<a class=\"btn tv\" href=\"/api/playontv?name=" + urlEncode(fn)
                            + "\" onclick=\"return tvPlay(this)\">📺 电视播放</a> "
                            + "<a class=\"btn\" href=\"/file/" + urlEncode(fn) + "\">⬇ 下载</a> "
                            // v14: 主页直接删除已下载文件（异步 fetch，删除成功后靠 SSE 指纹变化自动把按钮刷回“下载”）
                            + "<a class=\"btn del\" href=\"/api/delete?name=" + urlEncode(fn)
                            + "\" onclick=\"return delFile(this)\">✕ 删除</a> "
                            + "<span class=\"st ok\">✓ " + fmtSize(size) + "</span>";
                } else if ("error".equals(st)) {
                    btn = "<a class=\"btn\" href=\"/api/download?id=" + id + "\">重试</a>"
                            + "<div class=\"st\">" + esc(job.optString("msg", "")) + "</div>";
                } else {
                    btn = "<a class=\"btn\" href=\"/api/download?id=" + id + "\">下载</a>";
                }
                rows.append("<tr><td>").append(id).append("</td><td>").append(title)
                        .append("</td><td>").append(site).append("</td><td>").append(date)
                        .append("</td><td>").append(dur).append("</td><td>").append(btn)
                        .append("</td></tr>");
            }
            String table = rows.length() > 0
                    ? "<table><tr><th>#</th><th>标题</th><th>站点</th><th>投屏时间</th><th>时长</th><th>操作</th></tr>"
                    + rows + "</table>"
                    : "<p>(列表为空 — 用手机向电视果投屏后，10 秒内自动出现并自动下载)</p>";
            // 顶部信息卡片：别名 tvguo.com（点击回落到本机真实地址），下面小字给出真实地址
            String real = "http://" + localIp() + ":" + PORT;
            String hero = "<div class=\"hero\">"
                    + "<div style=\"font-size:13px;opacity:.9\">访问地址（别名）</div>"
                    + "<div class=\"host\"><a href=\"" + real + "/\">http://" + ALIAS_HOST + ":" + PORT + "</a></div>"
                    + "<div class=\"real\">实际地址：<a href=\"" + real + "/\" style=\"color:#fff\">" + real
                    + "</a> · 别名仅作显示，点上面链接即可打开本机面板</div>"
                    + "</div>";

            // 存储空间条：总容量 / 已用 / 剩余 / 已下载占用 + 进度条
            long total = totalBytes(), used = usedBytes(), free = freeBytes(), dl = downloadBytes();
            int pct = (total > 0 && used >= 0) ? (int) (used * 100 / total) : 0;
            if (pct < 0) pct = 0;
            if (pct > 100) pct = 100;
            String space = "<div class=\"bar2\"><b>存储空间</b>　总容量 " + fmtSize(total)
                    + " · 已用 " + fmtSize(used) + " · 剩余 " + fmtSize(free)
                    + " · 已下载占用 " + fmtSize(dl) + "（" + fileCount() + " 个文件）"
                    + "<div class=\"track\"><div class=\"fill\" style=\"width:" + pct + "%\"></div></div>"
                    + "<div class=\"st\">已用 " + pct + "% · 总容量为电视果整机存储，下载与系统共用"
                    + " · 空间不足时自动删除最旧文件</div></div>";

            // v16: 开机重播开关（默认开）。关闭后电视果重启只启动服务，不会自动把上次视频投回电视
            boolean resumeOn = store.isResumeOnBoot();
            String switchHtml = "<label title=\"关闭后：重启电视果只启动服务，不自动播放上次视频\" "
                    + "style=\"cursor:pointer;user-select:none;white-space:nowrap\">"
                    + "<input type=\"checkbox\" id=\"resumeOnBoot\" onchange=\"setResume(this)\""
                    + (resumeOn ? " checked" : "") + "> 重启后自动播放上次视频</label>";
            String bar = "<div class=\"bar\">共 " + store.size() + " 条 · 订阅模式（数据变化自动更新，页面不刷新）· 投屏即自动下载 · "
                    + "<a href=\"/downloads\">已下载文件</a> · "
                    + "<a href=\"/api/export\">导出JSON</a> · "
                    + "直链有时效，太旧需重新投屏<br>" + switchHtml + "</div>";
            return "<div id=\"dyn\">" + hero + space + bar + table + "</div>";
        }

        private String downloadsHtml() {
            File[] fs = dlDir.listFiles();
            List<File> list = new ArrayList<>();
            if (fs != null) for (File f : fs) if (f.isFile()) list.add(f);
            Collections.sort(list, new Comparator<File>() {
                public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
            });
            StringBuilder sb = new StringBuilder("<h2>已下载文件</h2>"
                    + "<div class=\"bar2\">存储：总容量 " + fmtSize(totalBytes())
                    + " · 已用 " + fmtSize(usedBytes()) + " · 剩余 " + fmtSize(freeBytes())
                    + " · 本页文件占用 " + fmtSize(downloadBytes()) + "（" + fileCount() + " 个）"
                    + " · 空间不足时自动删除最旧文件</div><ul>");
            if (list.isEmpty()) sb.append("<li>(空)</li>");
            long sum = 0;
            for (File f : list) {
                sum += f.length();
                String enc = urlEncode(f.getName());
                sb.append("<li style='margin-bottom:10px'>")
                        .append("<a href=\"/play/").append(enc).append("\">▶ 电脑播放</a> · ")
                        .append("<a href=\"/api/playontv?name=").append(enc)
                        .append("\" onclick=\"return tvPlay(this)\">📺 电视播放</a> · ")
                        .append("<a href=\"/file/").append(enc).append("\">⬇ 下载</a> · ")
                        .append("<a href=\"/api/delete?name=").append(enc)
                        .append("\" class=\"btn del\" onclick=\"return delFile(this)\">✕ 删除</a><br>")
                        .append("<span class='st'>").append(esc(f.getName())).append(" — ")
                        .append(fmtSize(f.length())).append("</span></li>");
            }
            sb.append("</ul><p>共 ").append(list.size()).append(" 个文件，占用 ").append(fmtSize(sum))
                    .append("</p><p><a href=\"/\">← 返回播放列表</a></p>");
            return sb.toString();
        }
    }

    // ===== 工具方法 =====
    /** 页面友好别名：tvguo.com 是公网域名，仅作显示用，真实可点地址仍是本机 IP:PORT */
    private static final String ALIAS_HOST = "tvguo.com";

    private long freeBytes() {
        try { return new StatFs(dlDir.getPath()).getAvailableBytes(); }
        catch (Exception e) { return -1; }
    }

    /** 存储总容量（整个 /data 分区，下载与系统数据共用） */
    private long totalBytes() {
        try { return new StatFs(dlDir.getPath()).getTotalBytes(); }
        catch (Exception e) { return -1; }
    }

    /** 已用容量 = 总容量 - 可用 */
    private long usedBytes() {
        long t = totalBytes(), f = freeBytes();
        return (t > 0 && f >= 0) ? (t - f) : -1;
    }

    /** 本 APP 下载目录实际占用（只统计下载的文件） */
    private long downloadBytes() {
        long sum = 0;
        File[] fs = dlDir.listFiles();
        if (fs != null) for (File f : fs) if (f.isFile()) sum += f.length();
        return sum;
    }

    /** 已下载文件个数 */
    private int fileCount() {
        File[] fs = dlDir.listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.isFile()) n++;
        return n;
    }

    /** 本机局域网 IP（页面与播放页显示真实地址用，DHCP 换 IP 后自动更新） */
    private String localIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> ifs =
                    java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address
                            && !a.isLinkLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.1"; // 兜底（通用示例地址）
    }

    private static String param(String query, String key) {
        if (query == null) return "";
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return urlDecode(kv[1]);
        }
        return "";
    }

    private static String urlDecode(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String urlEncode(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String fmtSize(long b) {
        if (b < 0) return "未知";
        if (b < 1024) return b + "B";
        if (b < 1048576) return String.format("%.1fKB", b / 1024.0);
        if (b < 1073741824) return String.format("%.1fMB", b / 1048576.0);
        return String.format("%.2fGB", b / 1073741824.0);
    }

    private static String safeFilename(String s) {
        if (s == null || s.isEmpty()) s = "video";
        String cleaned = s.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80);
        return cleaned;
    }
}
