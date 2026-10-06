package com.Cigram.vid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The advertiser's side of the conversation: transport, ordering and the offline
 * send queue. It holds no views, so the Activity can be destroyed and rebuilt
 * without losing the thread.
 *
 * Transport, in order of preference:
 *   1. WebSocket (wss) — instant, kept alive by {@link CigramWs}'s own ping.
 *   2. Long-poll — a 25 s hanging GET, used the moment the socket fails and
 *      retried as the socket is re-attempted with a growing back-off.
 * Both land in {@link #merge}, so the history can never differ between them.
 *
 * Offline: outgoing messages are written to SharedPreferences BEFORE the network
 * is touched, each with a client_id. They render immediately as "جاري الإرسال",
 * survive the app being killed, and are flushed in order when the connection is
 * back. The server de-duplicates by that same client_id, so a retry can never
 * post the same message twice.
 */
public final class CigramAdsChat {

    public static final int MODE_OFFLINE = 0;
    public static final int MODE_POLLING = 1;
    public static final int MODE_LIVE = 2;

    /** Local-only states; anything the server has returned is at least SENT. */
    public static final int STATE_SENDING = 0;
    public static final int STATE_FAILED = 1;
    public static final int STATE_SENT = 2;
    public static final int STATE_DELIVERED = 3;
    public static final int STATE_READ = 4;

    public interface Listener {
        /** The visible list changed (new messages, a state change, a removal). */
        void onChanged();

        void onPresence(JSONObject presence);

        void onMode(int mode);

        /** A failure worth telling the user about once. */
        void onError(String message);
    }

    private static final String PREFS = "cigram_ads_chat_v1";
    private static final long POLL_GAP_MS = 1200L;
    private static final long HEARTBEAT_MS = 25000L;
    private static final long[] BACKOFF_MS = {1000L, 2000L, 5000L, 10000L, 20000L, 30000L};
    private static final int CACHE_LIMIT = 80;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Confirmed messages from the server, ascending by seq. */
    private final List<JSONObject> messages = new ArrayList<JSONObject>();
    /** Not yet acknowledged, rendered after the confirmed ones. */
    private final List<JSONObject> queue = new ArrayList<JSONObject>();

    private Listener listener;
    private CigramAdsApi.Alive alive;
    private CigramWs socket;
    private int mode = MODE_OFFLINE;
    private long latestSeq = 0L;
    private long oldestSeq = 0L;
    private boolean hasMore = false;
    private boolean running;
    private boolean polling;
    private boolean flushing;
    private int failures;
    private JSONObject presence = new JSONObject();

    public CigramAdsChat(Context context) {
        this.app = context.getApplicationContext();
    }

    // ------------------------------------------------------------------ state

    public List<JSONObject> visible() {
        List<JSONObject> out = new ArrayList<JSONObject>(messages.size() + queue.size());
        out.addAll(messages);
        out.addAll(queue);
        return out;
    }

    public JSONObject presence() {
        return presence;
    }

    public int mode() {
        return mode;
    }

    public boolean hasMore() {
        return hasMore;
    }

    public int pendingCount() {
        return queue.size();
    }

    public boolean hasFailed() {
        for (JSONObject item : queue) {
            if (item.optInt("local_state", STATE_SENDING) == STATE_FAILED) return true;
        }
        return false;
    }

    // ----------------------------------------------------------------- start

    public void start(CigramAdsApi.Alive alive, Listener listener) {
        this.alive = alive;
        this.listener = listener;
        if (running) {
            notifyChanged();
            return;
        }
        running = true;
        loadCache();
        loadQueue();
        notifyChanged();
        fetchHistory(true);
        openSocket();
        main.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    /** Must be called from the Activity's onDestroy: closes the socket and timers. */
    public void stop() {
        running = false;
        listener = null;
        alive = null;
        main.removeCallbacksAndMessages(null);
        CigramWs ws = socket;
        socket = null;
        if (ws != null) ws.close();
        setMode(MODE_OFFLINE);
    }

    // ---------------------------------------------------------------- socket

    private void openSocket() {
        if (!running) return;
        String token = CigramUserData.token(app);
        if (token.length() == 0) {
            startPolling();
            return;
        }
        String url = CigramAdsApi.BASE.replaceFirst("^https://", "wss://")
                + "/ads/chat/ws?token=" + CigramAdsApi.encode(token);
        final CigramWs ws = new CigramWs(url, new CigramWs.Listener() {
            @Override public void onOpen() {
                failures = 0;
                setMode(MODE_LIVE);
                // Catch up on anything missed while the socket was down.
                fetchHistory(false);
            }

            @Override public void onText(String text) {
                handleSocketEvent(text);
            }

            @Override public void onClosed(boolean clean, String reason) {
                if (socket != null) socket = null;
                if (!running) return;
                startPolling();
                scheduleReconnect();
            }
        });
        ws.header("Authorization", "Bearer " + token);
        socket = ws;
        ws.connect();
    }

    private void scheduleReconnect() {
        long delay = BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
        failures++;
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running || socket != null) return;
                openSocket();
            }
        }, delay);
    }

    private void handleSocketEvent(String raw) {
        JSONObject event;
        try {
            event = new JSONObject(raw);
        } catch (Throwable ignored) {
            return;
        }
        String type = event.optString("type", "");
        if ("message".equals(type)) {
            JSONObject message = event.optJSONObject("message");
            if (message != null) {
                merge(one(message));
                notifyChanged();
            }
            return;
        }
        if ("read".equals(type)) {
            // The admin read up to this seq: promote our own messages' ticks.
            long upTo = event.optLong("up_to", 0L);
            boolean changed = false;
            for (JSONObject message : messages) {
                if ("user".equals(message.optString("from")) && message.optLong("seq") <= upTo
                        && message.optLong("read_at", 0L) == 0L) {
                    try {
                        message.put("read_at", event.optLong("at", System.currentTimeMillis()));
                        changed = true;
                    } catch (Throwable ignored) { }
                }
            }
            if (changed) notifyChanged();
            return;
        }
        if ("typing".equals(type)) {
            try {
                presence.put("typing_admin", event.optBoolean("on", false));
            } catch (Throwable ignored) { }
            notifyPresence();
            return;
        }
        if ("quote".equals(type) || "flags".equals(type)) {
            fetchHistory(false);
        }
    }

    // ----------------------------------------------------------------- polling

    private void startPolling() {
        if (!running || polling) return;
        polling = true;
        setMode(MODE_POLLING);
        pollOnce();
    }

    private void pollOnce() {
        if (!running) {
            polling = false;
            return;
        }
        if (mode == MODE_LIVE) {
            polling = false;
            return;
        }
        CigramAdsApi.get(app, "/ads/chat/poll?since=" + latestSeq, true, alive,
                new CigramAdsApi.Callback() {
                    @Override public void done(CigramAdsApi.Result result) {
                        if (!running) {
                            polling = false;
                            return;
                        }
                        if (result.ok()) {
                            setMode(MODE_POLLING);
                            apply(result.json, false);
                        } else {
                            setMode(MODE_OFFLINE);
                        }
                        main.postDelayed(new Runnable() {
                            @Override public void run() {
                                pollOnce();
                            }
                        }, result.ok() ? POLL_GAP_MS : 4000L);
                    }
                });
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!running) return;
            CigramWs ws = socket;
            if (ws != null && ws.isOpen()) {
                ws.send("{\"type\":\"ping\"}");
            } else {
                CigramAdsApi.post(app, "/ads/chat/heartbeat", new JSONObject(), true, alive,
                        new CigramAdsApi.Callback() {
                            @Override public void done(CigramAdsApi.Result result) {
                                if (result.ok()) {
                                    JSONObject p = result.json.optJSONObject("presence");
                                    if (p != null) {
                                        presence = p;
                                        notifyPresence();
                                    }
                                }
                            }
                        });
            }
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    // ---------------------------------------------------------------- history

    private void fetchHistory(final boolean initial) {
        String path = "/ads/chat/history?limit=40";
        if (latestSeq > 0L) path += "&since=" + latestSeq;
        CigramAdsApi.get(app, path, true, alive, new CigramAdsApi.Callback() {
            @Override public void done(CigramAdsApi.Result result) {
                if (!result.ok()) {
                    if (initial && messages.isEmpty()) {
                        setMode(MODE_OFFLINE);
                        report(result.error);
                    }
                    return;
                }
                apply(result.json, initial);
                flushQueue();
            }
        });
    }

    /** Loads one older page. {@code done} runs whether or not anything was added. */
    public void loadOlder(final Runnable done) {
        if (!hasMore || oldestSeq <= 1L) {
            if (done != null) done.run();
            return;
        }
        CigramAdsApi.get(app, "/ads/chat/history?limit=40&before=" + oldestSeq, true, alive,
                new CigramAdsApi.Callback() {
                    @Override public void done(CigramAdsApi.Result result) {
                        if (result.ok()) {
                            JSONArray list = result.json.optJSONArray("messages");
                            merge(list);
                            hasMore = result.json.optBoolean("has_more", false);
                            long reported = result.json.optLong("oldest_seq", oldestSeq);
                            if (reported > 0L) oldestSeq = Math.min(oldestSeq, reported);
                            notifyChanged();
                        }
                        if (done != null) done.run();
                    }
                });
    }

    private void apply(JSONObject body, boolean initial) {
        if (body == null) return;
        JSONObject p = body.optJSONObject("presence");
        if (p != null) {
            presence = p;
            notifyPresence();
        }
        JSONArray list = body.optJSONArray("messages");
        boolean added = merge(list);
        long latest = body.optLong("latest_seq", 0L);
        if (latest > latestSeq) latestSeq = latest;
        if (initial || oldestSeq == 0L) {
            long reported = body.optLong("oldest_seq", 0L);
            if (reported > 0L) oldestSeq = reported;
            hasMore = body.optBoolean("has_more", false);
        }
        if (added || initial) {
            saveCache();
            notifyChanged();
        }
    }

    private static JSONArray one(JSONObject message) {
        JSONArray array = new JSONArray();
        array.put(message);
        return array;
    }

    /** Inserts by seq, replacing an existing entry, and clears a matching queue item. */
    private boolean merge(JSONArray incoming) {
        if (incoming == null || incoming.length() == 0) return false;
        boolean changed = false;
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject message = incoming.optJSONObject(i);
            if (message == null) continue;
            long seq = message.optLong("seq", 0L);
            if (seq <= 0L) continue;

            String clientId = message.optString("client_id", "");
            if (clientId.length() > 0) {
                for (int q = queue.size() - 1; q >= 0; q--) {
                    if (clientId.equals(queue.get(q).optString("client_id", ""))) {
                        queue.remove(q);
                        saveQueue();
                        changed = true;
                    }
                }
            }

            int at = -1;
            for (int k = messages.size() - 1; k >= 0; k--) {
                long existing = messages.get(k).optLong("seq", 0L);
                if (existing == seq) {
                    messages.set(k, message);
                    at = -2;
                    changed = true;
                    break;
                }
                if (existing < seq) {
                    at = k + 1;
                    break;
                }
            }
            if (at == -2) continue;
            if (at < 0) at = 0;
            messages.add(at, message);
            changed = true;
            if (seq > latestSeq) latestSeq = seq;
            if (oldestSeq == 0L || seq < oldestSeq) oldestSeq = seq;
        }
        return changed;
    }

    /** The tick state to draw next to one row. */
    public static int stateOf(JSONObject message) {
        if (message == null) return STATE_SENDING;
        if (message.has("local_state")) return message.optInt("local_state", STATE_SENDING);
        if (message.optLong("read_at", 0L) > 0L) return STATE_READ;
        if (message.optLong("delivered_at", 0L) > 0L) return STATE_DELIVERED;
        return STATE_SENT;
    }

    // ------------------------------------------------------------- sending

    public void sendText(String text, long replyTo) {
        if (text == null || text.trim().length() == 0) return;
        JSONObject draft = draft("text", replyTo);
        try {
            draft.put("text", text.trim());
        } catch (Throwable ignored) { }
        enqueue(draft);
    }

    /** kind is image | video | audio | file. The media must already be uploaded. */
    public void sendMedia(String kind, String mediaId, String thumbId, String caption,
                          int width, int height, int durationMs, String name, long replyTo) {
        sendMedia(kind, mediaId, thumbId, caption, width, height, durationMs, name, "", replyTo);
    }

    /** {@code wave} is the packed waveform of a voice note; empty for anything else. */
    public void sendMedia(String kind, String mediaId, String thumbId, String caption,
                          int width, int height, int durationMs, String name, String wave,
                          long replyTo) {
        JSONObject draft = draft(kind, replyTo);
        try {
            draft.put("media_id", mediaId);
            if (wave != null && wave.length() > 0) draft.put("wave", wave);
            if (thumbId != null && thumbId.length() > 0) draft.put("thumb_id", thumbId);
            if (caption != null && caption.length() > 0) draft.put("text", caption);
            if (width > 0) draft.put("width", width);
            if (height > 0) draft.put("height", height);
            if (durationMs > 0) draft.put("duration_ms", durationMs);
            if (name != null && name.length() > 0) draft.put("name", name);
            // shown while the upload is still local
            JSONObject media = new JSONObject();
            media.put("id", mediaId);
            media.put("mime", "");
            media.put("width", width);
            media.put("height", height);
            media.put("duration_ms", durationMs);
            media.put("name", name == null ? "" : name);
            draft.put("media", media);
        } catch (Throwable ignored) { }
        enqueue(draft);
    }

    public void sendOrder(JSONObject order) {
        if (order == null) return;
        JSONObject draft = draft("ad_request", 0L);
        try {
            draft.put("order", order);
            // the card renders from the same shape the server will return
            JSONObject card = new JSONObject();
            card.put("slot", order.optString("slot"));
            card.put("slot_name", order.optString("slot_name"));
            card.put("days", order.optInt("days"));
            JSONObject quote = order.optJSONObject("quote");
            card.put("total", quote == null ? 0d : quote.optDouble("total", 0d));
            card.put("currency_label", quote == null ? "" : quote.optString("currency_label", ""));
            draft.put("order_preview", card);
        } catch (Throwable ignored) { }
        enqueue(draft);
    }

    private JSONObject draft(String kind, long replyTo) {
        JSONObject draft = new JSONObject();
        try {
            draft.put("kind", kind);
            draft.put("from", "user");
            draft.put("at", System.currentTimeMillis());
            draft.put("client_id", "c" + System.currentTimeMillis() + "-"
                    + Integer.toHexString((int) (Math.random() * 0xFFFFFF)));
            draft.put("local_state", STATE_SENDING);
            if (replyTo > 0L) draft.put("reply_to", replyTo);
        } catch (Throwable ignored) { }
        return draft;
    }

    private void enqueue(JSONObject draft) {
        queue.add(draft);
        saveQueue();
        notifyChanged();
        flushQueue();
    }

    /** Retries everything marked failed, oldest first. */
    public void retryFailed() {
        for (JSONObject item : queue) {
            try {
                item.put("local_state", STATE_SENDING);
            } catch (Throwable ignored) { }
        }
        saveQueue();
        notifyChanged();
        flushQueue();
    }

    public void drop(JSONObject pending) {
        if (pending == null) return;
        String clientId = pending.optString("client_id", "");
        for (int i = queue.size() - 1; i >= 0; i--) {
            if (clientId.equals(queue.get(i).optString("client_id", ""))) queue.remove(i);
        }
        saveQueue();
        notifyChanged();
    }

    /** Sends the head of the queue, then calls itself: strict order, one in flight. */
    private void flushQueue() {
        if (flushing || !running || queue.isEmpty()) return;
        JSONObject head = null;
        for (JSONObject item : queue) {
            if (item.optInt("local_state", STATE_SENDING) == STATE_SENDING) {
                head = item;
                break;
            }
        }
        if (head == null) return;
        flushing = true;
        final JSONObject sending = head;

        JSONObject body = new JSONObject();
        try {
            body.put("kind", sending.optString("kind", "text"));
            body.put("client_id", sending.optString("client_id", ""));
            if (sending.has("text")) body.put("text", sending.optString("text"));
            if (sending.has("media_id")) body.put("media_id", sending.optString("media_id"));
            if (sending.has("thumb_id")) body.put("thumb_id", sending.optString("thumb_id"));
            if (sending.has("width")) body.put("width", sending.optInt("width"));
            if (sending.has("height")) body.put("height", sending.optInt("height"));
            if (sending.has("duration_ms")) body.put("duration_ms", sending.optInt("duration_ms"));
            if (sending.has("wave")) body.put("wave", sending.optString("wave"));
            if (sending.has("reply_to")) body.put("reply_to", sending.optLong("reply_to"));
            if (sending.has("order")) body.put("order", sending.optJSONObject("order"));
        } catch (Throwable ignored) { }

        CigramAdsApi.post(app, "/ads/chat/send", body, true, alive, new CigramAdsApi.Callback() {
            @Override public void done(CigramAdsApi.Result result) {
                flushing = false;
                if (!running) return;
                if (result.ok()) {
                    JSONObject message = result.json.optJSONObject("message");
                    if (message != null) merge(one(message));
                    drop(sending);
                    saveCache();
                    notifyChanged();
                    flushQueue();
                    return;
                }
                // 4xx means the server refused it: retrying would never help.
                boolean permanent = result.code >= 400 && result.code < 500 && result.code != 408
                        && result.code != 429;
                try {
                    sending.put("local_state", STATE_FAILED);
                    sending.put("local_error", result.error == null ? "" : result.error);
                    sending.put("local_permanent", permanent);
                } catch (Throwable ignored) { }
                saveQueue();
                notifyChanged();
                if (permanent) report(result.error);
            }
        });
    }

    // --------------------------------------------------------------- actions

    public void markRead() {
        if (latestSeq <= 0L) return;
        CigramWs ws = socket;
        if (ws != null && ws.isOpen()) {
            ws.send("{\"type\":\"read\",\"up_to\":" + latestSeq + "}");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("up_to", latestSeq);
        } catch (Throwable ignored) { }
        CigramAdsApi.post(app, "/ads/chat/read", body, true, alive, null);
    }

    private long typingSentAt = 0L;

    public void setTyping(boolean on) {
        long now = System.currentTimeMillis();
        if (on && now - typingSentAt < 3000L) return;
        typingSentAt = on ? now : 0L;
        CigramWs ws = socket;
        if (ws != null && ws.isOpen()) {
            ws.send("{\"type\":\"typing\",\"on\":" + on + "}");
            return;
        }
        JSONObject body = new JSONObject();
        try {
            body.put("on", on);
        } catch (Throwable ignored) { }
        CigramAdsApi.post(app, "/ads/chat/typing", body, true, alive, null);
    }

    public void respondToQuote(long seq, String action, final Runnable done) {
        JSONObject body = new JSONObject();
        try {
            body.put("seq", seq);
            body.put("action", action);
        } catch (Throwable ignored) { }
        CigramAdsApi.post(app, "/ads/chat/quote-respond", body, true, alive,
                new CigramAdsApi.Callback() {
                    @Override public void done(CigramAdsApi.Result result) {
                        if (!result.ok()) report(result.error);
                        fetchHistory(false);
                        if (done != null) done.run();
                    }
                });
    }

    public void deleteForMe(final long seq) {
        JSONObject body = new JSONObject();
        try {
            body.put("seq", seq);
        } catch (Throwable ignored) { }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).optLong("seq", 0L) == seq) messages.remove(i);
        }
        saveCache();
        notifyChanged();
        CigramAdsApi.post(app, "/ads/chat/delete", body, true, alive, new CigramAdsApi.Callback() {
            @Override public void done(CigramAdsApi.Result result) {
                if (!result.ok()) {
                    report(result.error);
                    fetchHistory(false);
                }
            }
        });
    }

    public JSONObject messageBySeq(long seq) {
        for (JSONObject message : messages) {
            if (message.optLong("seq", 0L) == seq) return message;
        }
        return null;
    }

    // ------------------------------------------------------------ persistence

    private SharedPreferences prefs() {
        return app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Namespaced per account, so switching accounts never shows the wrong thread. */
    private String scope() {
        String id = CigramUserData.authUserId(app);
        if (id.length() == 0) id = CigramUserData.owner(app);
        return id;
    }

    private void saveQueue() {
        try {
            JSONArray array = new JSONArray();
            for (JSONObject item : queue) array.put(item);
            prefs().edit().putString("queue::" + scope(), array.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private void loadQueue() {
        queue.clear();
        try {
            String raw = prefs().getString("queue::" + scope(), "");
            if (raw == null || raw.length() == 0) return;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                // Anything that was in flight when we died is retried, not lost.
                if (item.optInt("local_state", STATE_SENDING) == STATE_SENT) continue;
                queue.add(item);
            }
        } catch (Throwable ignored) { }
    }

    private void saveCache() {
        try {
            JSONArray array = new JSONArray();
            int from = Math.max(0, messages.size() - CACHE_LIMIT);
            for (int i = from; i < messages.size(); i++) array.put(messages.get(i));
            prefs().edit()
                    .putString("cache::" + scope(), array.toString())
                    .putLong("latest::" + scope(), latestSeq)
                    .apply();
        } catch (Throwable ignored) { }
    }

    /** Lets the chat open instantly, and with no connection at all. */
    private void loadCache() {
        messages.clear();
        try {
            String raw = prefs().getString("cache::" + scope(), "");
            if (raw == null || raw.length() == 0) return;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject message = array.optJSONObject(i);
                if (message == null) continue;
                messages.add(message);
                long seq = message.optLong("seq", 0L);
                if (seq > latestSeq) latestSeq = seq;
                if (oldestSeq == 0L || seq < oldestSeq) oldestSeq = seq;
            }
            hasMore = oldestSeq > 1L;
        } catch (Throwable ignored) { }
    }

    /** Called when the account is deleted or the user signs out. */
    public static void forget(Context context) {
        try {
            context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------- callbacks

    private void setMode(int value) {
        if (mode == value) return;
        mode = value;
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (listener == l) l.onMode(mode);
            }
        });
    }

    private void notifyChanged() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (listener == l) l.onChanged();
            }
        });
    }

    private void notifyPresence() {
        final Listener l = listener;
        if (l == null) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (listener == l) l.onPresence(presence);
            }
        });
    }

    private void report(final String message) {
        final Listener l = listener;
        if (l == null || message == null || message.length() == 0) return;
        main.post(new Runnable() {
            @Override public void run() {
                if (listener == l) l.onError(message);
            }
        });
    }
}
