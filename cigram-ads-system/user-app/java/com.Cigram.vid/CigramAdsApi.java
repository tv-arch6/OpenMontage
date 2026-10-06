package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Every network call of the company-ads feature goes through here.
 *
 * Rules this class enforces for the whole feature:
 *   - nothing ever runs on the UI thread (one shared pool, same shape as CigramSync),
 *   - a callback never fires after its Activity is gone,
 *   - the public config is cached in SharedPreferences, so "أعلن هنا" opens instantly
 *     and still opens with no connection at all,
 *   - a failure is an Arabic sentence, never a Java exception string.
 *
 * It is completely separate from CigramAdConfig/CigramAdManager (AdMob): different
 * endpoints, different storage, no shared state.
 */
public final class CigramAdsApi {

    /** Same Worker as the catalogue: it owns MOVIES_BUCKET and ADMIN_TOKEN. */
    public static final String BASE = "https://cigram-admin-api.wwq-mixtv.workers.dev";

    private static final String PREFS = "cigram_ads_company_v1";
    private static final String KEY_CONFIG = "config_json";
    private static final String KEY_CONFIG_AT = "config_at";
    private static final String KEY_CONSENT_VERSION = "consent_version";
    private static final String KEY_CONSENT_AT = "consent_at";
    private static final String KEY_DEVICE = "device_id";
    /** A cached config older than this is refreshed, but it is still shown while refreshing. */
    private static final long CONFIG_FRESH_MS = 30L * 60L * 1000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newFixedThreadPool(3);

    private CigramAdsApi() { }

    // ------------------------------------------------------------------ types

    /** Result of one call: either {@link #json} is set, or {@link #error} is a human sentence. */
    public static final class Result {
        public final JSONObject json;
        public final String error;
        public final int code;
        public final boolean fromCache;

        Result(JSONObject json, String error, int code, boolean fromCache) {
            this.json = json;
            this.error = error;
            this.code = code;
            this.fromCache = fromCache;
        }

        public boolean ok() {
            return json != null && error == null;
        }

        /** The "data" object of the unified Worker envelope, never null. */
        public JSONObject data() {
            JSONObject d = json == null ? null : json.optJSONObject("data");
            return d == null ? new JSONObject() : d;
        }
    }

    public interface Callback {
        void done(Result result);
    }

    /** Set by an Activity in onDestroy() so late callbacks are dropped. */
    public interface Alive {
        boolean alive();
    }

    // ------------------------------------------------------------------ device

    /** Random per-install id. Used only to rotate ads fairly and to de-duplicate counts. */
    public static String deviceId(Context context) {
        if (context == null) return "anon";
        try {
            SharedPreferences p = prefs(context);
            String id = p.getString(KEY_DEVICE, "");
            if (id == null || id.length() == 0) {
                id = Long.toHexString(System.currentTimeMillis())
                        + Integer.toHexString((int) (Math.random() * 0x7FFFFFFF));
                p.edit().putString(KEY_DEVICE, id).apply();
            }
            return id;
        } catch (Throwable ignored) {
            return "anon";
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ config

    /** The last config this device saw, or null. Safe to call on the UI thread. */
    public static JSONObject cachedConfig(Context context) {
        try {
            String raw = prefs(context).getString(KEY_CONFIG, "");
            if (raw == null || raw.length() == 0) return null;
            return new JSONObject(raw);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean configIsStale(Context context) {
        try {
            long at = prefs(context).getLong(KEY_CONFIG_AT, 0L);
            long age = System.currentTimeMillis() - at;
            return age < 0L || age > CONFIG_FRESH_MS;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Loads /ads/config. The callback fires once with the fresh copy, or — when the
     * network fails and a cached copy exists — with the cached copy and fromCache=true.
     */
    public static void loadConfig(final Context context, final Alive alive, final Callback callback) {
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                Result result = request(app, "GET", "/ads/config", null, false);
                if (result.ok()) {
                    JSONObject data = result.data();
                    if (data.length() > 0) {
                        try {
                            prefs(app).edit()
                                    .putString(KEY_CONFIG, data.toString())
                                    .putLong(KEY_CONFIG_AT, System.currentTimeMillis())
                                    .apply();
                        } catch (Throwable ignored) { }
                    }
                    deliver(alive, callback, result);
                    return;
                }
                JSONObject cached = cachedConfig(app);
                if (cached != null) {
                    JSONObject wrapper = new JSONObject();
                    try {
                        wrapper.put("ok", true);
                        wrapper.put("data", cached);
                    } catch (Throwable ignored) { }
                    deliver(alive, callback, new Result(wrapper, null, 0, true));
                    return;
                }
                deliver(alive, callback, result);
            }
        });
    }

    // ------------------------------------------------------------------ quote

    /** Server-side price calculation. The app never does the arithmetic itself. */
    public static void quote(final Context context, final JSONObject body,
                             final Alive alive, final Callback callback) {
        post(context, "/ads/quote", body, false, alive, callback);
    }

    // ------------------------------------------------------------------ consent

    /** The locally remembered acceptance, so the page opens correct while offline. */
    public static boolean acceptedLocally(Context context, int policyVersion) {
        try {
            return prefs(context).getInt(KEY_CONSENT_VERSION, 0) >= policyVersion && policyVersion > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void rememberAcceptance(Context context, int policyVersion) {
        try {
            prefs(context).edit()
                    .putInt(KEY_CONSENT_VERSION, policyVersion)
                    .putLong(KEY_CONSENT_AT, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) { }
    }

    /**
     * Mirrors the acceptance on the server (date + policy version). Local acceptance is
     * written first by the caller, so a failure here never blocks the advertiser.
     */
    public static void sendConsent(final Context context, final int policyVersion,
                                   final Alive alive, final Callback callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("policy_version", policyVersion);
            body.put("app_version", appVersion(context));
        } catch (Throwable ignored) { }
        post(context, "/ads/consent", body, true, alive, callback);
    }

    // ------------------------------------------------------------------ campaigns

    public static void myCampaigns(final Context context, final Alive alive, final Callback callback) {
        get(context, "/ads/my-campaigns", true, alive, callback);
    }

    // ------------------------------------------------------------------ serving

    /** GET /ads for one slot. Used by CigramAdSlotView (phase هـ). */
    public static void slot(final Context context, final String slotId, final String city,
                            final String lang, final Alive alive, final Callback callback) {
        StringBuilder path = new StringBuilder("/ads?slot=").append(encode(slotId));
        if (city != null && city.length() > 0) path.append("&city=").append(encode(city));
        if (lang != null && lang.length() > 0) path.append("&lang=").append(encode(lang));
        path.append("&app_version=").append(encode(appVersion(context)));
        path.append("&did=").append(encode(deviceId(context)));
        get(context, path.toString(), false, alive, callback);
    }

    /** Fire-and-forget impression/click batch. Never reports back and never throws. */
    public static void track(final Context context, final JSONArray events) {
        if (context == null || events == null || events.length() == 0) return;
        final Context app = context.getApplicationContext();
        final JSONObject body = new JSONObject();
        try {
            body.put("did", deviceId(app));
            body.put("events", events);
        } catch (Throwable ignored) {
            return;
        }
        IO.execute(new Runnable() {
            @Override public void run() {
                request(app, "POST", "/ads/track", body, false);
            }
        });
    }

    // ------------------------------------------------------------------ plumbing

    public static void get(final Context context, final String path, final boolean auth,
                           final Alive alive, final Callback callback) {
        final Context app = context.getApplicationContext();
        IO.execute(new Runnable() {
            @Override public void run() {
                deliver(alive, callback, request(app, "GET", path, null, auth));
            }
        });
    }

    public static void post(final Context context, final String path, final JSONObject body,
                            final boolean auth, final Alive alive, final Callback callback) {
        final Context app = context.getApplicationContext();
        final JSONObject payload = body == null ? new JSONObject() : body;
        IO.execute(new Runnable() {
            @Override public void run() {
                deliver(alive, callback, request(app, "POST", path, payload, auth));
            }
        });
    }

    private static void deliver(final Alive alive, final Callback callback, final Result result) {
        if (callback == null) return;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (alive != null && !alive.alive()) return;
                try {
                    callback.done(result);
                } catch (Throwable error) {
                    android.util.Log.w("CigramAds", "callback failed", error);
                }
            }
        });
    }

    /** Blocking. Always returns a Result, never throws. */
    static Result request(Context app, String method, String path, JSONObject body, boolean auth) {
        HttpURLConnection c = null;
        try {
            if (auth) {
                String token = CigramUserData.token(app);
                if (token.length() == 0) {
                    return new Result(null, "يلزم تسجيل الدخول للمتابعة.", 401, false);
                }
            }
            c = (HttpURLConnection) new URL(BASE + path).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (auth) {
                c.setRequestProperty("Authorization", "Bearer " + CigramUserData.token(app));
            }
            if (body != null) {
                byte[] data = body.toString().getBytes("UTF-8");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(data.length);
                OutputStream out = c.getOutputStream();
                try {
                    out.write(data);
                    out.flush();
                } finally {
                    out.close();
                }
            }

            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = readAll(in);

            JSONObject json = null;
            if (text.length() > 0 && text.charAt(0) == '{') {
                try {
                    json = new JSONObject(text);
                } catch (Throwable ignored) {
                    json = null;
                }
            }

            if (code >= 200 && code < 300 && json != null && json.optBoolean("ok", false)) {
                return new Result(json, null, code, false);
            }
            return new Result(null, messageFor(code, json), code, false);
        } catch (java.net.UnknownHostException e) {
            return new Result(null, "لا يوجد اتصال بالإنترنت.", 0, false);
        } catch (java.net.SocketTimeoutException e) {
            return new Result(null, "انتهت مهلة الاتصال. أعد المحاولة.", 0, false);
        } catch (Throwable error) {
            return new Result(null, "تعذر إكمال الطلب. تحقق من الاتصال ثم أعد المحاولة.", 0, false);
        } finally {
            if (c != null) {
                try {
                    c.disconnect();
                } catch (Throwable ignored) { }
            }
        }
    }

    /** Server messages are already Arabic; this only covers the cases that have none. */
    private static String messageFor(int code, JSONObject json) {
        String message = json == null ? "" : json.optString("message", "").trim();
        if (message.length() > 0) return message;
        if (code == 401) return "انتهت جلستك. سجّل الدخول من جديد.";
        if (code == 403) return "ليس لديك صلاحية لهذا الإجراء.";
        if (code == 404) return "العنصر غير موجود.";
        if (code == 413) return "حجم الملف أكبر من المسموح.";
        if (code == 429) return "طلبات كثيرة في وقت قصير. انتظر قليلاً.";
        if (code >= 500) return "الخادم لا يستجيب الآن. أعد المحاولة بعد قليل.";
        if (code > 0) return "تعذر إكمال الطلب (" + code + ").";
        return "تعذر إكمال الطلب. تحقق من الاتصال ثم أعد المحاولة.";
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > 4 * 1024 * 1024) break;
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Throwable ignored) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) { }
        }
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Shared background pool, so other ads classes do not spawn their own threads. */
    public static void runIO(Runnable job) {
        try {
            IO.execute(job);
        } catch (Throwable ignored) { }
    }

    public static void runMain(Runnable job) {
        try {
            MAIN.post(job);
        } catch (Throwable ignored) { }
    }
}
