package com.my.newproject;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * "زر حالتي": online / offline / out-of-hours, as the advertiser sees it.
 *
 * Two rules the spec asks for, both handled here so no screen has to remember them:
 *   - the chosen status is pushed to the Worker at once, so the advertiser's chat
 *     header changes within a second,
 *   - closing the app flips it to offline. "Closed" is detected by counting the
 *     ads screens that are resumed: when the count reaches zero we wait a short
 *     grace period (so moving between the inbox and a conversation does not look
 *     like leaving) and only then publish offline.
 *
 * The chosen status is also remembered locally, so reopening the app restores it
 * instead of silently leaving the admin invisible.
 */
final class CgAdsPresence {

    static final String ONLINE = "online";
    static final String OFFLINE = "offline";
    static final String AWAY = "away";

    private static final String PREFS = "cigram_ads_admin_v1";
    private static final String KEY_STATUS = "my_status";
    /** Long enough to cover a screen transition, short enough to be honest. */
    private static final long LEAVE_GRACE_MS = 2500L;
    private static final long HEARTBEAT_MS = 25000L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static int resumedScreens = 0;
    private static boolean heartbeatRunning = false;
    private static Context appContext;

    private CgAdsPresence() { }

    static String current(Context context) {
        try {
            String value = prefs(context).getString(KEY_STATUS, OFFLINE);
            return value == null ? OFFLINE : value;
        } catch (Exception ignored) {
            return OFFLINE;
        }
    }

    static String label(String status) {
        if (ONLINE.equals(status)) return "متصل الآن";
        if (AWAY.equals(status)) return "خارج ساعات العمل";
        return "غير متصل";
    }

    static int color(String status) {
        if (ONLINE.equals(status)) return CgCfg.GOOD;
        if (AWAY.equals(status)) return CgCfg.WARN;
        return CgCfg.GRAY;
    }

    /** The next status in the cycle the button offers. */
    static String next(String status) {
        if (ONLINE.equals(status)) return AWAY;
        if (AWAY.equals(status)) return OFFLINE;
        return ONLINE;
    }

    static void set(final Activity activity, final String status, final Runnable done) {
        remember(activity, status);
        CgAdsApi.async(activity, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                return CgAdsApi.setPresence(status, "");
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                CgUi.toast(activity, "حالتك الآن: " + label(status));
                if (done != null) done.run();
            }

            @Override public void fail(String message) {
                CgUi.toast(activity, "تعذر تحديث حالتك: " + message);
                if (done != null) done.run();
            }
        });
    }

    private static void remember(Context context, String status) {
        try {
            prefs(context).edit().putString(KEY_STATUS, status).apply();
        } catch (Exception ignored) { }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------- screen counting

    /** Called from onResume of every ads screen. */
    static void onScreenResumed(Activity activity) {
        appContext = activity.getApplicationContext();
        resumedScreens++;
        MAIN.removeCallbacks(goOffline);
        if (!heartbeatRunning) {
            heartbeatRunning = true;
            MAIN.postDelayed(heartbeat, HEARTBEAT_MS);
        }
        // Restore the remembered status when coming back after being away.
        String status = current(activity);
        if (!OFFLINE.equals(status)) {
            final String keep = status;
            CgHttp.bg(new Runnable() {
                @Override public void run() {
                    CgAdsApi.setPresence(keep, "");
                }
            });
        }
    }

    /** Called from onStop of every ads screen. */
    static void onScreenStopped() {
        resumedScreens = Math.max(0, resumedScreens - 1);
        if (resumedScreens == 0) {
            MAIN.removeCallbacks(goOffline);
            MAIN.postDelayed(goOffline, LEAVE_GRACE_MS);
        }
    }

    private static final Runnable goOffline = new Runnable() {
        @Override public void run() {
            heartbeatRunning = false;
            MAIN.removeCallbacks(heartbeat);
            if (resumedScreens > 0) return;
            CgHttp.bg(new Runnable() {
                @Override public void run() {
                    // The stored choice is kept, so reopening restores it.
                    CgAdsApi.setPresence(OFFLINE, "");
                }
            });
        }
    };

    /** Keeps the admin's socket-level presence fresh while a screen is open. */
    private static final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (resumedScreens <= 0) {
                heartbeatRunning = false;
                return;
            }
            final String thread = CgAdsThread.active();
            if (thread != null && thread.length() > 0) {
                CgHttp.bg(new Runnable() {
                    @Override public void run() {
                        CgAdsApi.heartbeat(thread);
                    }
                });
            }
            MAIN.postDelayed(this, HEARTBEAT_MS);
        }
    };
}
