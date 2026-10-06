package com.my.newproject;

import android.app.Activity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Every ads route the admin app calls, in one place, on top of the existing
 * {@link CgHttp} (same timeouts, same Arabic error mapping, same background pool).
 * No new networking: this is a thin, typed surface over CgHttp.api().
 */
final class CgAdsApi {

    private CgAdsApi() { }

    // ------------------------------------------------------------- settings

    static CgHttp.Res settings() {
        return CgHttp.apiGet("/admin/ads/settings");
    }

    static CgHttp.Res saveSettings(JSONObject settings, boolean bumpPolicyVersion) {
        JSONObject body = new JSONObject();
        try {
            body.put("settings", settings);
            body.put("bump_policy_version", bumpPolicyVersion);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/settings", body);
    }

    static CgHttp.Res setPresence(String status, String note) {
        JSONObject body = new JSONObject();
        try {
            body.put("status", status);
            body.put("note", note == null ? "" : note);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/presence", body);
    }

    static CgHttp.Res quote(JSONObject request) {
        return CgHttp.apiPost("/admin/ads/quote", request);
    }

    // ----------------------------------------------------------- advertisers

    static CgHttp.Res advertisers() {
        return CgHttp.apiGet("/admin/ads/advertisers");
    }

    static CgHttp.Res saveAdvertiser(JSONObject advertiser) {
        return CgHttp.apiPost("/admin/ads/advertiser/save", advertiser);
    }

    static CgHttp.Res deleteAdvertiser(String id, boolean force) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
            body.put("force", force);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/advertiser/delete", body);
    }

    // ------------------------------------------------------------- campaigns

    static CgHttp.Res campaigns(String status, String slot) {
        StringBuilder path = new StringBuilder("/admin/ads/campaigns");
        boolean first = true;
        if (status != null && status.length() > 0) {
            path.append(first ? "?" : "&").append("status=").append(enc(status));
            first = false;
        }
        if (slot != null && slot.length() > 0) {
            path.append(first ? "?" : "&").append("slot=").append(enc(slot));
        }
        return CgHttp.apiGet(path.toString());
    }

    static CgHttp.Res saveCampaign(JSONObject campaign) {
        return CgHttp.apiPost("/admin/ads/campaign/save", campaign);
    }

    static CgHttp.Res approve(String id) {
        return CgHttp.apiPost("/admin/ads/campaign/approve", idBody(id));
    }

    static CgHttp.Res reject(String id, String reason) {
        JSONObject body = idBody(id);
        try {
            body.put("reason", reason);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/campaign/reject", body);
    }

    static CgHttp.Res status(String id, String status) {
        JSONObject body = idBody(id);
        try {
            body.put("status", status);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/campaign/status", body);
    }

    static CgHttp.Res extend(String id, int days) {
        JSONObject body = idBody(id);
        try {
            body.put("days", days);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/campaign/extend", body);
    }

    static CgHttp.Res payment(String id, String status, double amount, String method, String note) {
        JSONObject body = idBody(id);
        try {
            body.put("status", status);
            body.put("amount_paid", amount);
            body.put("method", method == null ? "" : method);
            body.put("note", note == null ? "" : note);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/campaign/payment", body);
    }

    static CgHttp.Res deleteCampaign(String id) {
        return CgHttp.apiPost("/admin/ads/campaign/delete", idBody(id));
    }

    static CgHttp.Res uploadCreative(byte[] bytes, String contentType) {
        return CgHttp.postBytes(CgCfg.API + "/admin/ads/creative/upload", bytes, contentType, true, 90000);
    }

    // --------------------------------------------------------------- reports

    static CgHttp.Res report(String campaignId) {
        if (campaignId == null || campaignId.length() == 0) {
            return CgHttp.apiGet("/admin/ads/report");
        }
        return CgHttp.apiGet("/admin/ads/report?campaign=" + enc(campaignId));
    }

    static CgHttp.Res security() {
        return CgHttp.apiGet("/admin/ads/security");
    }

    // ------------------------------------------------------------------ chat

    static CgHttp.Res threads(String filter, String query, boolean includeArchived) {
        StringBuilder path = new StringBuilder("/admin/ads/chat/threads?limit=200");
        if (filter != null && filter.length() > 0) path.append("&filter=").append(enc(filter));
        if (query != null && query.length() > 0) path.append("&q=").append(enc(query));
        if (includeArchived) path.append("&archived=1");
        return CgHttp.apiGet(path.toString());
    }

    static CgHttp.Res history(String user, long since, long before, int limit) {
        StringBuilder path = new StringBuilder("/admin/ads/chat/history?user=").append(enc(user));
        path.append("&limit=").append(limit <= 0 ? 40 : limit);
        if (since > 0L) path.append("&since=").append(since);
        if (before > 0L) path.append("&before=").append(before);
        return CgHttp.apiGet(path.toString());
    }

    static CgHttp.Res poll(String user, long since) {
        return CgHttp.api("GET", "/admin/ads/chat/poll?user=" + enc(user) + "&since=" + since, null);
    }

    static CgHttp.Res send(String user, JSONObject message) {
        JSONObject body = message == null ? new JSONObject() : message;
        try {
            body.put("user", user);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/chat/send", body);
    }

    static CgHttp.Res markRead(String user) {
        return CgHttp.apiPost("/admin/ads/chat/read", userBody(user));
    }

    static CgHttp.Res typing(String user, boolean on) {
        JSONObject body = userBody(user);
        try {
            body.put("on", on);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/chat/typing", body);
    }

    static CgHttp.Res heartbeat(String user) {
        return CgHttp.apiPost("/admin/ads/chat/heartbeat", userBody(user));
    }

    static CgHttp.Res flags(String user, JSONObject changes) {
        JSONObject body = changes == null ? new JSONObject() : changes;
        try {
            body.put("user", user);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/chat/flags", body);
    }

    static CgHttp.Res exportThread(String user, boolean includeInternal) {
        JSONObject body = userBody(user);
        try {
            body.put("internal", includeInternal);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/chat/export", body);
    }

    static CgHttp.Res purgeThread(String user) {
        return CgHttp.apiPost("/admin/ads/chat/purge", userBody(user));
    }

    static CgHttp.Res toCampaign(String user, long seq, String title) {
        JSONObject body = userBody(user);
        try {
            body.put("seq", seq);
            if (title != null && title.length() > 0) body.put("title", title);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/chat/to-campaign", body);
    }

    static CgHttp.Res respondToQuote(String user, long seq, String action) {
        JSONObject body = userBody(user);
        try {
            body.put("seq", seq);
            body.put("action", action);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/chat/quote-respond", body);
    }

    static CgHttp.Res deleteMessage(String user, long seq) {
        JSONObject body = userBody(user);
        try {
            body.put("seq", seq);
        } catch (Exception ignored) { }
        return CgHttp.apiPost("/admin/ads/chat/delete", body);
    }

    static CgHttp.Res uploadChatMedia(String user, String kind, String name, byte[] bytes,
                                      String contentType) {
        String path = CgCfg.API + "/admin/ads/chat/media/upload?user=" + enc(user)
                + "&kind=" + enc(kind) + "&name=" + enc(name == null ? "" : name);
        return CgHttp.postBytes(path, bytes, contentType, true, 120000);
    }

    /** The WebSocket URL for one thread; the admin token travels as a header. */
    static String socketUrl(String user) {
        return CgCfg.API.replaceFirst("^https://", "wss://")
                + "/admin/ads/chat/ws?user=" + enc(user);
    }

    // --------------------------------------------------------------- helpers

    private static JSONObject idBody(String id) {
        JSONObject body = new JSONObject();
        try {
            body.put("id", id);
        } catch (Exception ignored) { }
        return body;
    }

    private static JSONObject userBody(String user) {
        JSONObject body = new JSONObject();
        try {
            body.put("user", user);
        } catch (Exception ignored) { }
        return body;
    }

    static String enc(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception ignored) {
            return "";
        }
    }

    /** The "data" object of the unified envelope, never null. */
    static JSONObject data(CgHttp.Res res) {
        if (res == null || res.json == null) return new JSONObject();
        JSONObject data = res.json.optJSONObject("data");
        return data == null ? res.json : data;
    }

    static JSONArray array(JSONObject object, String key) {
        JSONArray array = object == null ? null : object.optJSONArray(key);
        return array == null ? new JSONArray() : array;
    }

    /** Runs a blocking call off the UI thread and delivers the result on it. */
    static void async(Activity activity, final Call call, final CgHttp.Done<CgHttp.Res> done) {
        CgHttp.async(activity, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(call.run());
            }
        }, done);
    }

    interface Call {
        CgHttp.Res run();
    }
}
