package com.my.newproject;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small blocking HTTP helper + background runner. Never call request() on the UI thread. */
final class CgHttp {

    private CgHttp() {}

    private static final ExecutorService POOL = Executors.newFixedThreadPool(5);

    static final class Res {
        int code;
        String body = "";
        JSONObject json;
        JSONArray array;
        Exception error;
        long ms;
        String contentType = "";
        long contentLength = -1;

        boolean ok() {
            if (error != null) return false;
            if (code < 200 || code >= 300) return false;
            if (json != null && json.has("ok") && !json.optBoolean("ok", true)) return false;
            return true;
        }

        String errorCode() {
            if (json == null) return "";
            return json.isNull("error") ? "" : json.optString("error", "");
        }

        String message() {
            if (error != null) return describe(error);
            if (json != null) {
                String m = json.isNull("message") ? "" : json.optString("message", "");
                if (m.length() > 0) return m;
                String e = errorCode();
                if (e.length() > 0) return mapCode(e);
            }
            if (code == 401) return "رمز الإدارة غير صحيح (401).";
            if (code == 403) return "ممنوع (403).";
            if (code == 404) return "غير موجود (404).";
            if (code == 408) return "انتهت المهلة (408).";
            if (code >= 500) return "خطأ في الخادم (HTTP " + code + ").";
            if (code >= 400) return "فشل الطلب (HTTP " + code + ").";
            return "رد غير متوقع من الخادم.";
        }
    }

    static String mapCode(String code) {
        if ("unauthorized".equals(code)) return "رمز الإدارة غير صحيح.";
        if ("not_found".equals(code)) return "العنصر غير موجود.";
        if ("invalid_json".equals(code)) return "البيانات المرسلة غير صالحة.";
        if ("internal_error".equals(code)) return "خطأ داخلي في الخادم.";
        if ("write_conflict".equals(code)) return "تعذر الحفظ بسبب تعديل متزامن. أعد المحاولة.";
        return code;
    }

    static String describe(Exception e) {
        if (e instanceof UnknownHostException) return "لا يوجد اتصال بالإنترنت أو تعذر الوصول للخادم.";
        if (e instanceof SocketTimeoutException) return "انتهت مهلة الاتصال.";
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.toLowerCase().contains("cleartext")) {
            return "الرابط يبدأ بـ http والتطبيق يمنع الاتصال غير المشفّر (cleartext). استخدم https أو فعّل cleartext في Manifest.";
        }
        return friendly(m);
    }

    /** Never show Java / HTTP exception text to the admin: unknown technical messages become a clear Arabic one. */
    static String friendly(String m) {
        String s = m == null ? "" : m.trim();
        String low = s.toLowerCase();
        if (s.length() == 0 || low.contains("java.") || low.contains("exception") || low.contains("null object")
                || low.contains("android.") || low.contains("org.json") || low.contains("httpurlconnection")
                || low.contains("ssl") || low.contains("socket") || low.contains("unexpected end of stream")
                || low.contains("connection reset") || low.contains("failed to connect")) {
            return "تعذر إكمال الطلب. تحقق من الاتصال بالإنترنت ثم أعد المحاولة.";
        }
        return s;
    }

    /** Raw bytes POST (used for carousel image upload). Returns parsed JSON like call(). */
    static Res postBytes(String url, byte[] data, String contentType, boolean auth, int timeoutMs) {
        Res res = new Res();
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (auth) {
                c.setRequestProperty("Authorization", "Bearer " + CgCfg.TOKEN);
                c.setRequestProperty("X-Admin-Token", CgCfg.TOKEN);
            }
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            c.setFixedLengthStreamingMode(data.length);
            OutputStream os = c.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            res.code = c.getResponseCode();
            InputStream in = res.code >= 400 ? c.getErrorStream() : c.getInputStream();
            res.body = readAll(in, 4 * 1024 * 1024);
            String trimmed = res.body.trim();
            if (trimmed.startsWith("{")) {
                try { res.json = new JSONObject(trimmed); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            res.error = e;
        } finally {
            if (c != null) c.disconnect();
            res.ms = System.currentTimeMillis() - t0;
        }
        return res;
    }

    /** Generic blocking request. */
    static Res call(String method, String url, JSONObject body, boolean auth, int timeoutMs, String[][] headers) {
        Res res = new Res();
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (auth) {
                c.setRequestProperty("Authorization", "Bearer " + CgCfg.TOKEN);
                c.setRequestProperty("X-Admin-Token", CgCfg.TOKEN);
            }
            if (headers != null) {
                for (int i = 0; i < headers.length; i++) {
                    c.setRequestProperty(headers[i][0], headers[i][1]);
                }
            }
            if (body != null) {
                byte[] payload = body.toString().getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                c.setFixedLengthStreamingMode(payload.length);
                OutputStream os = c.getOutputStream();
                os.write(payload);
                os.flush();
                os.close();
            }
            res.code = c.getResponseCode();
            res.contentType = c.getContentType() == null ? "" : c.getContentType();
            res.contentLength = c.getContentLength();
            InputStream in = res.code >= 400 ? c.getErrorStream() : c.getInputStream();
            res.body = readAll(in, 24 * 1024 * 1024);
            String trimmed = res.body.trim();
            if (trimmed.startsWith("{")) {
                try { res.json = new JSONObject(trimmed); } catch (Exception ignored) { }
            } else if (trimmed.startsWith("[")) {
                try { res.array = new JSONArray(trimmed); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            res.error = e;
        } finally {
            if (c != null) c.disconnect();
            res.ms = System.currentTimeMillis() - t0;
        }
        return res;
    }

    static String readAll(InputStream in, int max) throws Exception {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > max) throw new Exception("الاستجابة كبيرة جداً");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    /** Admin Worker request with token. */
    static Res api(String method, String path, JSONObject body) {
        return call(method, CgCfg.API + path, body, true, 30000, null);
    }

    static Res apiGet(String path) {
        return api("GET", path, null);
    }

    static Res apiPost(String path, JSONObject body) {
        return api("POST", path, body == null ? new JSONObject() : body);
    }

    // ------------------------------------------------------------------ async

    interface Work<T> {
        T run() throws Exception;
    }

    interface Done<T> {
        void ok(T result);

        void fail(String message);
    }

    static <T> void async(final Activity a, final Work<T> work, final Done<T> done) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                String err = null;
                try {
                    result = work.run();
                } catch (Exception e) {
                    err = friendly(e.getMessage() == null ? "" : e.getMessage());
                }
                final T fr = result;
                final String fe = err;
                if (a == null || a.isFinishing()) return;
                a.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (a.isFinishing()) return;
                        if (fe != null) done.fail(fe);
                        else done.ok(fr);
                    }
                });
            }
        });
    }

    /** Fire-and-forget background task. */
    static void bg(Runnable r) {
        POOL.execute(r);
    }

    /** Throws an Exception with the server's Arabic message unless the response is OK. */
    static Res require(Res r) throws Exception {
        if (!r.ok()) throw new Exception(r.message());
        return r;
    }
}
