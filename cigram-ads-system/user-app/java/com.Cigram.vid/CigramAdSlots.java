package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine behind {@link CigramAdSlotView}: fetching, caching, time, capping
 * and counting for the direct-sold ads.
 *
 * This is NOT AdMob. {@link CigramAdConfig}/{@link CigramAdManager} keep running
 * untouched; nothing here reads or writes their state, their ad units or their caps.
 *
 * Four things it guarantees:
 *   1. **No screen ever waits for an ad.** Every load is asynchronous and the
 *      slot starts at zero height; a cached copy renders immediately.
 *   2. **An expired campaign never shows, even offline.** The Worker sends
 *      server_time with every response; we keep the offset and judge `ends_at`
 *      against corrected time, so a device with a wrong clock cannot resurrect a
 *      finished campaign and a cached response cannot outlive its campaign.
 *   3. **An impression is counted once, and only when actually seen** — at least
 *      50% of the view on screen for a continuous second — and never twice for
 *      the same campaign+creative+slot on the same day on this device.
 *   4. **The viewer stays in control**: hiding an ad suppresses that campaign on
 *      this device for 30 days, and reporting it does the same and tells us.
 */
public final class CigramAdSlots {

    public static final String SLOT_SPLASH = "splash";
    public static final String SLOT_HERO = "hero";
    public static final String SLOT_INLINE = "inline";
    public static final String SLOT_POPUP = "popup";
    public static final String SLOT_STICKY = "sticky";
    public static final String SLOT_SPONSOR = "sponsor";

    /** 50% of the view visible for this long counts as "seen". */
    static final long IMPRESSION_DWELL_MS = 1000L;
    static final float IMPRESSION_VISIBLE_FRACTION = 0.5f;

    private static final String PREFS = "cigram_ads_slots_v1";
    private static final long FRESH_MS = 5L * 60L * 1000L;
    private static final long CACHE_MAX_MS = 24L * 60L * 60L * 1000L;
    private static final long HIDE_DAYS_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final int TRACK_BATCH = 12;
    private static final long TRACK_FLUSH_MS = 4000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();
    private static final Map<String, Snapshot> MEMORY = new HashMap<String, Snapshot>();
    private static final JSONArray PENDING = new JSONArray();

    /** serverTime - deviceTime, in ms. Added to System.currentTimeMillis(). */
    private static volatile long clockOffsetMs = 0L;
    private static boolean flushScheduled = false;

    private CigramAdSlots() { }

    // ------------------------------------------------------------------ types

    public interface Callback {
        /** {@code ads} is never null; an empty list means "show nothing". */
        void ready(List<JSONObject> ads);
    }

    private static final class Snapshot {
        List<JSONObject> ads = new ArrayList<JSONObject>();
        long at;
        boolean loading;
    }

    // ------------------------------------------------------------------ clock

    /** Device time corrected by the Worker's server_time. */
    public static long serverNow() {
        return System.currentTimeMillis() + clockOffsetMs;
    }

    private static void noteServerTime(Context context, long serverTimeMs) {
        if (serverTimeMs <= 0L) return;
        long offset = serverTimeMs - System.currentTimeMillis();
        // Ignore an absurd offset (a broken reply) rather than trusting it.
        if (Math.abs(offset) > 365L * 24L * 60L * 60L * 1000L) return;
        clockOffsetMs = offset;
        try {
            prefs(context).edit().putLong("clock_offset", offset).apply();
        } catch (Throwable ignored) { }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Called once at app start so the offset survives a restart. */
    public static void init(Context context) {
        if (context == null) return;
        try {
            clockOffsetMs = prefs(context).getLong("clock_offset", 0L);
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ load

    /**
     * Hands over whatever is usable now (cache) and, when it is stale, refreshes
     * and calls back a second time. The callback always runs on the main thread.
     */
    public static void load(final Context context, final String slot, final Callback callback) {
        if (context == null || slot == null || slot.length() == 0) {
            if (callback != null) callback.ready(new ArrayList<JSONObject>());
            return;
        }
        final Context app = context.getApplicationContext();
        init(app);

        Snapshot snapshot;
        synchronized (LOCK) {
            snapshot = MEMORY.get(slot);
            if (snapshot == null) {
                snapshot = new Snapshot();
                snapshot.ads = readCache(app, slot);
                snapshot.at = cacheAt(app, slot);
                MEMORY.put(slot, snapshot);
            }
        }

        final List<JSONObject> immediate = usable(app, snapshot.ads);
        if (callback != null && !immediate.isEmpty()) {
            MAIN.post(new Runnable() {
                @Override public void run() {
                    callback.ready(immediate);
                }
            });
        }

        boolean stale = System.currentTimeMillis() - snapshot.at > FRESH_MS;
        if (!stale) {
            if (callback != null && immediate.isEmpty()) {
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        callback.ready(new ArrayList<JSONObject>());
                    }
                });
            }
            return;
        }
        synchronized (LOCK) {
            if (snapshot.loading) return;
            snapshot.loading = true;
        }

        CigramAdsApi.slot(app, slot, city(app), language(app), null, new CigramAdsApi.Callback() {
            @Override public void done(CigramAdsApi.Result result) {
                Snapshot current;
                synchronized (LOCK) {
                    current = MEMORY.get(slot);
                    if (current != null) current.loading = false;
                }
                if (!result.ok()) {
                    // Offline: keep showing the cached copy (already delivered).
                    if (callback != null && immediate.isEmpty()) callback.ready(new ArrayList<JSONObject>());
                    return;
                }
                noteServerTime(app, result.json.optLong("server_time_ms", 0L));
                JSONArray incoming = result.json.optJSONArray("ads");
                List<JSONObject> list = new ArrayList<JSONObject>();
                if (incoming != null) {
                    for (int i = 0; i < incoming.length(); i++) {
                        JSONObject ad = incoming.optJSONObject(i);
                        if (ad != null) list.add(ad);
                    }
                }
                if (current != null) {
                    current.ads = list;
                    current.at = System.currentTimeMillis();
                }
                writeCache(app, slot, list);
                if (callback != null) callback.ready(usable(app, list));
            }
        });
    }

    /** Drops expired, capped, hidden and malformed entries. */
    private static List<JSONObject> usable(Context app, List<JSONObject> ads) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        if (ads == null) return out;
        long now = serverNow();
        for (JSONObject ad : ads) {
            if (ad == null) continue;
            JSONObject creative = ad.optJSONObject("creative");
            if (creative == null) continue;
            String url = creative.optString("url", "");
            if (url.length() == 0) continue;
            long endsAt = parseIso(ad.optString("ends_at", ""));
            if (endsAt > 0L && now >= endsAt) continue;
            String campaignId = ad.optString("campaign_id", "");
            if (isHidden(app, campaignId)) continue;
            if (overDailyCap(app, ad)) continue;
            out.add(ad);
        }
        return out;
    }

    static long parseIso(String iso) {
        if (iso == null || iso.length() < 10) return 0L;
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String trimmed = iso.length() > 19 ? iso.substring(0, 19) : iso;
            java.util.Date date = format.parse(trimmed);
            return date == null ? 0L : date.getTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ cache

    private static List<JSONObject> readCache(Context app, String slot) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        try {
            long at = cacheAt(app, slot);
            if (at <= 0L || System.currentTimeMillis() - at > CACHE_MAX_MS) return out;
            String raw = prefs(app).getString("ads::" + slot, "");
            if (raw == null || raw.length() == 0) return out;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject ad = array.optJSONObject(i);
                if (ad != null) out.add(ad);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private static long cacheAt(Context app, String slot) {
        try {
            return prefs(app).getLong("at::" + slot, 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static void writeCache(Context app, String slot, List<JSONObject> ads) {
        try {
            JSONArray array = new JSONArray();
            for (JSONObject ad : ads) array.put(ad);
            prefs(app).edit()
                    .putString("ads::" + slot, array.toString())
                    .putLong("at::" + slot, System.currentTimeMillis())
                    .apply();
        } catch (Throwable ignored) { }
    }

    // ----------------------------------------------------------- targeting in

    /** The city the viewer picked, if the app knows one. Empty is fine. */
    private static String city(Context app) {
        try {
            return app.getSharedPreferences("cigram_prefs", Context.MODE_PRIVATE)
                    .getString("city", "");
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String language(Context app) {
        try {
            String language = java.util.Locale.getDefault().getLanguage();
            return language == null ? "ar" : language;
        } catch (Throwable ignored) {
            return "ar";
        }
    }

    // ------------------------------------------------------------- frequency

    private static String today() {
        return new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
                .format(new java.util.Date(serverNow()));
    }

    private static boolean overDailyCap(Context app, JSONObject ad) {
        int cap = ad.optInt("daily_cap_per_user", 0);
        if (cap <= 0) return false;
        try {
            String key = "shown::" + ad.optString("campaign_id", "") + "::" + ad.optString("slot", "")
                    + "::" + today();
            return prefs(app).getInt(key, 0) >= cap;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void countShown(Context app, JSONObject ad) {
        try {
            String key = "shown::" + ad.optString("campaign_id", "") + "::" + ad.optString("slot", "")
                    + "::" + today();
            SharedPreferences p = prefs(app);
            p.edit().putInt(key, p.getInt(key, 0) + 1).apply();
        } catch (Throwable ignored) { }
    }

    /** The popup slot may appear once a day, as the spec requires. */
    public static boolean popupAllowedToday(Context context) {
        if (context == null) return false;
        try {
            return !today().equals(prefs(context).getString("popup_day", ""));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void markPopupShown(Context context) {
        if (context == null) return;
        try {
            prefs(context).edit().putString("popup_day", today()).apply();
        } catch (Throwable ignored) { }
    }

    /** The sticky bar stays dismissed for the rest of the day. */
    public static boolean stickyDismissedToday(Context context) {
        if (context == null) return false;
        try {
            return today().equals(prefs(context).getString("sticky_day", ""));
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void dismissStickyToday(Context context) {
        if (context == null) return;
        try {
            prefs(context).edit().putString("sticky_day", today()).apply();
        } catch (Throwable ignored) { }
    }

    // --------------------------------------------------------------- hiding

    static boolean isHidden(Context app, String campaignId) {
        if (campaignId == null || campaignId.length() == 0) return false;
        try {
            long until = prefs(app).getLong("hide::" + campaignId, 0L);
            return until > System.currentTimeMillis();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** "لا تظهر لي هذا الإعلان": suppressed on this device for 30 days. */
    public static void hide(Context context, String campaignId) {
        if (context == null || campaignId == null || campaignId.length() == 0) return;
        try {
            prefs(context).edit()
                    .putLong("hide::" + campaignId, System.currentTimeMillis() + HIDE_DAYS_MS)
                    .apply();
        } catch (Throwable ignored) { }
        synchronized (LOCK) {
            for (Snapshot snapshot : MEMORY.values()) {
                List<JSONObject> kept = new ArrayList<JSONObject>();
                for (JSONObject ad : snapshot.ads) {
                    if (!campaignId.equals(ad.optString("campaign_id", ""))) kept.add(ad);
                }
                snapshot.ads = kept;
            }
        }
    }

    /** Hides it and records a report, so the admin can act on it. */
    public static void report(Context context, JSONObject ad, String reason) {
        if (context == null || ad == null) return;
        hide(context, ad.optString("campaign_id", ""));
        JSONArray events = new JSONArray();
        JSONObject event = new JSONObject();
        try {
            event.put("type", "report");
            event.put("campaign_id", ad.optString("campaign_id", ""));
            event.put("creative_id", ad.optString("creative_id", ""));
            event.put("slot", ad.optString("slot", ""));
            event.put("nonce", nonce(ad, "report"));
            event.put("reason", reason == null ? "" : reason);
            event.put("ts", serverNow());
            events.put(event);
        } catch (Throwable ignored) {
            return;
        }
        CigramAdsApi.track(context, events);
    }

    // -------------------------------------------------------------- tracking

    /**
     * Records that the ad was genuinely seen. Caller guarantees the dwell rule;
     * this adds the once-per-day-per-device guard and the batching.
     */
    static void impression(Context context, JSONObject ad) {
        if (context == null || ad == null) return;
        Context app = context.getApplicationContext();
        String key = "imp::" + ad.optString("campaign_id", "") + "::"
                + ad.optString("creative_id", "") + "::" + ad.optString("slot", "") + "::" + today();
        try {
            if (prefs(app).getBoolean(key, false)) {
                // Already counted today, but still obey the display cap.
                countShown(app, ad);
                return;
            }
            prefs(app).edit().putBoolean(key, true).apply();
        } catch (Throwable ignored) { }
        countShown(app, ad);
        enqueue(app, ad, "impression");
    }

    static void click(Context context, JSONObject ad) {
        if (context == null || ad == null) return;
        enqueue(context.getApplicationContext(), ad, "click");
    }

    private static void enqueue(final Context app, JSONObject ad, String type) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", type);
            event.put("campaign_id", ad.optString("campaign_id", ""));
            event.put("creative_id", ad.optString("creative_id", ""));
            event.put("slot", ad.optString("slot", ""));
            event.put("nonce", nonce(ad, type));
            event.put("ts", serverNow());
        } catch (Throwable ignored) {
            return;
        }
        boolean flushNow;
        synchronized (PENDING) {
            PENDING.put(event);
            flushNow = PENDING.length() >= TRACK_BATCH;
            if (!flushNow && !flushScheduled) {
                flushScheduled = true;
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        synchronized (PENDING) {
                            flushScheduled = false;
                        }
                        flush(app);
                    }
                }, TRACK_FLUSH_MS);
            }
        }
        if (flushNow) flush(app);
    }

    /** Sends and clears the batch. Safe to call at any time. */
    public static void flush(Context context) {
        if (context == null) return;
        JSONArray batch;
        synchronized (PENDING) {
            if (PENDING.length() == 0) return;
            batch = new JSONArray();
            for (int i = 0; i < PENDING.length(); i++) batch.put(PENDING.opt(i));
            while (PENDING.length() > 0) PENDING.remove(0);
        }
        CigramAdsApi.track(context, batch);
    }

    /** Unique per event, so a retried batch is de-duplicated server side. */
    private static String nonce(JSONObject ad, String type) {
        return type.charAt(0) + ad.optString("campaign_id", "") + "-"
                + Long.toString(serverNow(), 36) + "-"
                + Integer.toHexString((int) (Math.random() * 0xFFFFFF));
    }

    /** Clears every local ad state. Used when the account is deleted. */
    public static void forget(Context context) {
        try {
            prefs(context).edit().clear().apply();
        } catch (Throwable ignored) { }
        synchronized (LOCK) {
            MEMORY.clear();
        }
    }
}
