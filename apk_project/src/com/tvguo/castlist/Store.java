package com.tvguo.castlist;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.util.*;

/**
 * 投屏记录存储：用 JSON 文件存到 app 私有目录（无需 root、无需存储权限）。
 * 按 video_url 去重；同一 URL 后到的元数据（标题/时长）会补全到已有记录。
 * 该文件可通过 /api/export 导出，也可 adb pull 出来。
 */
public class Store {
    // 单例：WebServer 与 Poller 必须共享同一份内存状态，否则列表看不到新抓的记录
    private static volatile Store instance;

    private final File file;
    private final List<JSONObject> records = new ArrayList<>();
    private final Set<String> seenUrls = new HashSet<>();
    private int nextId = 1;

    // v16: 设置项持久化（SharedPreferences，应用私有，重启/重装覆盖安装都保留）
    private static final String PREFS_NAME = "castlist_settings";
    private static final String KEY_RESUME_ON_BOOT = "resume_on_boot";
    private final SharedPreferences prefs;

    public static synchronized Store getInstance(Context ctx) {
        if (instance == null) instance = new Store(ctx.getApplicationContext());
        return instance;
    }

    private Store(Context ctx) {
        file = new File(ctx.getFilesDir(), "cast_playlist.json");
        prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    /**
     * v16: 开机是否自动重播上次视频（默认 true = 保持原有行为）。
     * 用 SharedPreferences 而非塞进 cast_playlist.json，避免污染 /api/export 的记录数组结构。
     */
    public synchronized boolean isResumeOnBoot() {
        return prefs.getBoolean(KEY_RESUME_ON_BOOT, true);
    }

    /** v16: 网页开关写入设置；commit() 同步落盘，确保紧接着断电/重启也能读到 */
    public synchronized void setResumeOnBoot(boolean on) {
        prefs.edit().putBoolean(KEY_RESUME_ON_BOOT, on).commit();
        Log.i("CastList", "setting resume_on_boot = " + on);
    }

    /** 从磁盘加载已有记录 */
    private synchronized void load() {
        if (!file.exists()) return;
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            if (sb.length() == 0) return;
            JSONArray arr = new JSONArray(sb.toString());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                records.add(o);
                seenUrls.add(o.optString("video_url", ""));
                int id = o.optInt("id", 0);
                if (id >= nextId) nextId = id + 1;
            }
            Log.i("CastList", "Store loaded " + records.size() + " records");
        } catch (Exception e) {
            Log.e("CastList", "Store load error: " + e.getMessage());
        } finally {
            if (r != null) try { r.close(); } catch (Exception ignored) {}
        }
    }

    /** 原子写入磁盘 */
    private void save() {
        File tmp = new File(file.getPath() + ".tmp");
        PrintWriter w = null;
        try {
            w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"));
            w.print(new JSONArray(records).toString());
            w.flush();
            w.close();
            tmp.renameTo(file);
        } catch (Exception e) {
            Log.e("CastList", "Store save error: " + e.getMessage());
        } finally {
            if (w != null) w.close();
        }
    }

    /**
     * 新增一条记录（去重）。
     * @return true=新增成功；false=重复或无效
     */
    public synchronized boolean add(JSONObject info) {
        String url = info.optString("video_url", "");
        if (!url.startsWith("http") || seenUrls.contains(url)) {
            // 重复项：不重复下载，但更新时间戳并置顶，让每次投屏在网页上都有可见反馈
            if (seenUrls.contains(url)) {
                fillMissing(url, info);
                touch(url, info);
            }
            return false;
        }
        seenUrls.add(url);
        try { info.put("id", nextId); } catch (Exception e) { return false; }
        nextId++;
        records.add(info);
        save();
        Log.i("CastList", "NEW #" + info.optInt("id") + " " + info.optString("title"));
        return true;
    }

    /**
     * 重复投屏：更新该记录的投屏时间为当前时间，并移到列表末尾（WebServer 倒序显示 = 置顶）。
     * 不新增记录、不触发重复下载；仅给用户一个"刚刚投过"的可见反馈。
     */
    private void touch(String url, JSONObject info) {
        boolean changed = false;
        for (int i = 0; i < records.size(); i++) {
            JSONObject o = records.get(i);
            if (!url.equals(o.optString("video_url", ""))) continue;
            try {
                // 用 info 里 Poller 传来的当前时间更新时间戳
                String now = info.optString("record_date", "");
                if (!now.isEmpty()) { o.put("record_date", now); changed = true; }
                // 移到末尾：列表页是倒序遍历的，末尾 = 显示在最上面
                records.remove(i);
                records.add(o);
                changed = true;
                Log.i("CastList", "TOUCH #" + o.optInt("id") + " " + o.optString("title")
                        + " -> " + now);
            } catch (Exception e) { Log.w("CastList", "touch: " + e.getMessage()); }
            break;
        }
        if (changed) save();
    }

    /** 补全已有记录缺失的标题/时长/封面 */
    private void fillMissing(String url, JSONObject info) {
        boolean changed = false;
        for (JSONObject o : records) {
            if (url.equals(o.optString("video_url", ""))) {
                String[] keys = {"title", "duration", "image_url"};
                for (String k : keys) {
                    String old = o.optString(k, "");
                    String nu = info.optString(k, "");
                    if ((old.isEmpty() || "null".equals(old)) && !nu.isEmpty() && !"null".equals(nu)) {
                        try { o.put(k, nu); changed = true; } catch (Exception ignored) {}
                    }
                }
                break;
            }
        }
        if (changed) save();
    }

    /** 返回全部记录的快照（JSONArray） */
    public synchronized JSONArray all() {
        try { return new JSONArray(records.toString()); }
        catch (Exception e) { return new JSONArray(); }
    }

    /** 按 id 查记录 */
    public synchronized JSONObject find(String id) {
        for (JSONObject o : records) {
            if (String.valueOf(o.optInt("id")).equals(id)) return o;
        }
        return null;
    }

    /** 按 video_url 查记录 */
    public synchronized JSONObject findByUrl(String url) {
        for (JSONObject o : records) {
            if (url.equals(o.optString("video_url", ""))) return o;
        }
        return null;
    }

    public synchronized int size() { return records.size(); }
}
