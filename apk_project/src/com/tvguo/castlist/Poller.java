package com.tvguo.castlist;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

/**
 * DLNA 轮询线程：每 10 秒查一次本机 UPnP AVTransport 服务（127.0.0.1:39620）。
 * - GetMediaInfo  → CurrentURI（视频直链）
 * - GetPositionInfo → 标题 / 时长 / 封面（元数据是 &lt; 转义的，需反转义）
 * 抓到新 URI 就交给 Store 去重入库。这是 DLNA 订阅在 TV 端落地的简化替代方案。
 */
public class Poller implements Runnable {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 39620;
    private static final String CTRL = "/_urn:schemas-upnp-org:service:AVTransport_control";
    private static final String SOAP_ACTION_PREFIX =
            "\"urn:schemas-upnp-org:service:AVTransport:1#%s\"";

    private final Context ctx;
    private final Store store;
    private final WebServer webServer; // v4: 抓到新片时触发自动下载
    private final SimpleDateFormat dateFmt =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);

    public Poller(Context ctx, WebServer webServer) {
        this.ctx = ctx;
        this.store = Store.getInstance(ctx);
        this.webServer = webServer;
    }

    @Override
    public void run() {
        Log.i("CastList", "Poller started");
        // 首次延迟 3 秒，等服务与网络完全起来
        try { Thread.sleep(3000); } catch (InterruptedException e) { return; }

        while (!Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (Exception e) {
                Log.w("CastList", "poll error: " + e.getMessage());
            }
            try { Thread.sleep(10000); } catch (InterruptedException e) { break; }
        }
        Log.i("CastList", "Poller stopped");
    }

    /** 轮询一次：取直链 + 元数据 + 入库 */
    private void pollOnce() {
        String media = soap("GetMediaInfo");
        if (media == null || media.isEmpty()) return;

        String uri = match(media, "CurrentURI");
        if (uri.isEmpty()) return;
        uri = htmlUnescape(uri).trim();
        if (!uri.startsWith("http")) return;
        // v10: 若是"电视播放"按钮切过去的本机文件流(18090)，跳过——否则会被当新片重复下载
        if (uri.contains(":18090/")) return;

        // 补充元数据：标题、时长、封面
        String title = "", duration = "", image = "";
        String pos = soap("GetPositionInfo");
        if (pos != null && !pos.isEmpty()) {
            // SOAP 里 <dc:title> 被转义成 &lt;dc:title&gt;，整体反转义后再匹配
            String decoded = htmlUnescape(pos);
            title = htmlUnescape(match(decoded, "dc:title")).trim();
            duration = match(decoded, "TrackDuration").trim();
            image = htmlUnescape(match(decoded, "upnp:albumArtURI")).trim();
        }

        JSONObject info = new JSONObject();
        try {
            info.put("video_url", uri);
            info.put("title", title.isEmpty() ? "(无标题)" : title);
            info.put("duration", duration);
            info.put("site_id", detectSite(uri));
            info.put("image_url", image);
            info.put("record_date", dateFmt.format(new Date()));
            info.put("state", "POLL");
        } catch (Exception e) {
            return;
        }
        // v4: 新片入库后立即触发自动下载（边投边下，抢直链时效）
        // store.add 返回 true 表示新 URL（去重后首次出现），此时才下载
        boolean isNew = store.add(info);
        if (isNew && webServer != null) {
            String newId = String.valueOf(info.optInt("id"));
            Log.i("CastList", "auto download triggered for #" + newId);
            webServer.startDownload(newId, info);
        }
    }

    /**
     * v11：判断当前是否正在播放"外部投屏"（非本机 18090 文件）。
     * 开机自动重播前用它做守卫：若用户已经在投新片，就不再自动切回旧文件。
     */
    public boolean isExternalVideoPlaying() {
        String media = soap("GetMediaInfo");
        if (media == null || media.isEmpty()) return false;
        String uri = htmlUnescape(match(media, "CurrentURI")).trim();
        return uri.startsWith("http") && !uri.contains(":18090/");
    }

    /** 调用一个无参 AVTransport SOAP 动作，返回原始响应文本 */
    private String soap(String action) {
        String body = "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"
                + "<InstanceID>0</InstanceID></u:" + action + "></s:Body></s:Envelope>";
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + HOST + ":" + PORT + CTRL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            conn.setRequestProperty("SOAPACTION", String.format(SOAP_ACTION_PREFIX, action));
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();

            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            is.close();
            return new String(baos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 正则取 XML 标签内容（支持带属性的标签，如 &lt;res ...&gt;） */
    private static String match(String xml, String tag) {
        if (xml == null) return "";
        Pattern p = Pattern.compile("<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>(.*?)</"
                + Pattern.quote(tag) + ">", Pattern.DOTALL);
        Matcher m = p.matcher(xml);
        return m.find() ? m.group(1) : "";
    }

    /** HTML/XML 实体反转义（顺序：先 &amp; 之外的，最后 &amp;） */
    private static String htmlUnescape(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }

    /** 根据直链域名推断站点 */
    private static String detectSite(String url) {
        String u = url.toLowerCase();
        if (u.contains("bdxiguavod") || u.contains("ixigua") || u.contains("xigua")) return "xigua";
        if (u.contains("qiyi") || u.contains("iqiyi")) return "iqiyi";
        if (u.contains("douyin") || u.contains("toutiao") || u.contains("bytedance")) return "douyin";
        if (u.contains("m3u8")) return "hls";
        return "other";
    }
}
