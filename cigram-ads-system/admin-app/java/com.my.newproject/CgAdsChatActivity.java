package com.my.newproject;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * محادثة المعلن من جهة الإدارة.
 *
 * Same conversation and the same transports as the advertiser's screen (a
 * WebSocket with a long-poll fallback over one Durable Object), plus what only
 * the admin has: canned replies, internal notes the advertiser never sees, tags
 * and assignment, "إنشاء عرض سعر", and "تحويل الطلب إلى حملة".
 */
public class CgAdsChatActivity extends CgBase {

    private static final long POLL_GAP_MS = 1500L;
    private static final long HEARTBEAT_MS = 25000L;
    private static final long[] BACKOFF_MS = {1000L, 2000L, 5000L, 10000L, 20000L, 30000L};
    private static final int REQ_IMAGE = 5101;
    private static final int REQ_FILE = 5102;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> messages = new ArrayList<JSONObject>();

    private String user = "";
    private String advertiserName = "";
    private long latestSeq = 0L;
    private long oldestSeq = 0L;
    private boolean hasMore = false;
    private boolean internalMode = false;
    private boolean polling;
    private int failures;
    private CgWs socket;
    private JSONObject threadMeta = new JSONObject();

    private ScrollView scroll;
    private LinearLayout messageColumn;
    private EditText input;
    private TextView internalToggle;
    private TextView presenceLine;
    private TextView loadOlder;

    @Override protected String screenTitle() {
        return "محادثة المعلن";
    }

    @Override protected void onBuild() {
        user = getIntent() == null ? "" : getIntent().getStringExtra("user");
        advertiserName = getIntent() == null ? "" : getIntent().getStringExtra("name");
        if (user == null) user = "";
        if (advertiserName == null || advertiserName.length() == 0) advertiserName = "معلن";
        if (user.length() == 0) {
            CgUi.info(this, "خطأ", "لم تُحدَّد المحادثة.");
            finish();
            return;
        }
        bar.titleView.setText(advertiserName);

        body.removeAllViews();
        body.addView(buildHeader(), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        messageColumn = CgUi.vbox(this);
        int p = CgUi.dp(this, 10);
        messageColumn.setPadding(p, p, p, p);
        scroll.addView(messageColumn, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(scroll, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));

        body.addView(buildComposer(), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        addToolbarActions();
        messageColumn.addView(CgAdsUi.skeleton(this, 4),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        loadHistory(true);
        openSocket();
        main.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    @Override protected void onResume() {
        super.onResume();
        CgAdsThread.setActive(user);
        CgAdsPresence.onScreenResumed(this);
        markRead();
    }

    @Override protected void onStop() {
        CgAdsThread.clear(user);
        CgAdsPresence.onScreenStopped();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        CgWs ws = socket;
        socket = null;
        if (ws != null) ws.close();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- chrome

    private void addToolbarActions() {
        TextView more = CgUi.smallButton(this, "⋮", 1);
        more.setContentDescription("خيارات المحادثة");
        more.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openMenu();
            }
        });
        bar.actions.addView(more, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
    }

    private View buildHeader() {
        LinearLayout box = CgUi.vbox(this);
        box.setBackgroundColor(CgCfg.CARD);
        box.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 6), CgUi.dp(this, 12), CgUi.dp(this, 8));
        presenceLine = CgUi.text(this, "جارٍ الاتصال…", 12f, CgCfg.MUTED, false);
        box.addView(presenceLine, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        return box;
    }

    private View buildComposer() {
        LinearLayout holder = CgUi.vbox(this);
        holder.setBackgroundColor(CgCfg.CARD);

        LinearLayout quick = CgUi.hbox(this);
        android.widget.HorizontalScrollView quickScroll = new android.widget.HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickScroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        for (final String reply : CANNED) {
            TextView chip = CgUi.chip(this, shorten(reply), false);
            chip.setMinHeight(CgUi.dp(this, 36));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    input.setText(reply);
                    input.setSelection(reply.length());
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMarginStart(quick.getChildCount() == 0 ? 0 : CgUi.dp(this, 6));
            quick.addView(chip, lp);
        }
        quickScroll.addView(quick, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        holder.addView(quickScroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 10, 6, 10, 2));

        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(CgUi.dp(this, 8), CgUi.dp(this, 4), CgUi.dp(this, 8), CgUi.dp(this, 8));

        TextView attach = CgUi.smallButton(this, "＋", 1);
        attach.setContentDescription("إرفاق");
        attach.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                attachMenu();
            }
        });
        row.addView(attach, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));

        input = CgUi.field(this, "اكتب رداً…", true);
        input.setMaxLines(4);
        input.addTextChangedListener(new TextWatcher() {
            private long lastSent = 0L;

            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable e) {
                long now = System.currentTimeMillis();
                if (now - lastSent < 3000L) return;
                lastSent = now;
                final boolean on = e != null && e.toString().trim().length() > 0;
                CgWs ws = socket;
                if (ws != null && ws.isOpen()) {
                    ws.send("{\"type\":\"typing\",\"on\":" + on + "}");
                } else {
                    CgHttp.bg(new Runnable() {
                        @Override public void run() {
                            CgAdsApi.typing(user, on);
                        }
                    });
                }
            }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        ip.setMarginStart(CgUi.dp(this, 6));
        ip.setMarginEnd(CgUi.dp(this, 6));
        row.addView(input, ip);

        TextView send = CgUi.button(this, "إرسال", 0);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendTyped();
            }
        });
        row.addView(send, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        holder.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        internalToggle = CgUi.text(this, "", 12f, CgCfg.WARN, true);
        internalToggle.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 2), CgUi.dp(this, 12), CgUi.dp(this, 8));
        internalToggle.setClickable(true);
        internalToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                internalMode = !internalMode;
                paintInternalToggle();
            }
        });
        holder.addView(internalToggle, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        paintInternalToggle();
        return holder;
    }

    private void paintInternalToggle() {
        internalToggle.setText(internalMode
                ? "◉ ملاحظة داخلية — لن يراها المعلن (اضغط للعودة إلى الرد العادي)"
                : "○ رد عادي — اضغط للكتابة كملاحظة داخلية");
        internalToggle.setTextColor(internalMode ? CgCfg.WARN : CgCfg.GRAY);
        if (input != null) {
            input.setBackground(CgUi.bg(this, CgCfg.CARD2, 12,
                    internalMode ? CgCfg.WARN : CgCfg.STROKE));
        }
    }

    private static final String[] CANNED = {
            "أهلاً بك. أرسل لنا تفاصيل حملتك وسنرد عليك بعرض سعر.",
            "تم استلام طلبك، سنراجعه ونرد عليك خلال ساعات العمل.",
            "نحتاج المادة الإعلانية بجودة أعلى (١٠٨٠ بكسل على الأقل).",
            "الإعلان لا يتوافق مع سياستنا، وسأوضح لك السبب بالتفصيل.",
            "تم اعتماد حملتك وستبدأ في الموعد المحدد.",
            "بانتظار تأكيد الدفع لبدء التشغيل.",
    };

    private static String shorten(String value) {
        return value.length() > 22 ? value.substring(0, 22) + "…" : value;
    }

    // ---------------------------------------------------------------- loading

    private void loadHistory(final boolean initial) {
        final long since = latestSeq;
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgAdsApi.history(user, since, 0L, 40));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                apply(CgAdsApi.data(res), initial);
            }

            @Override public void fail(String message) {
                if (!initial) return;
                messageColumn.removeAllViews();
                messageColumn.addView(CgUi.errorCard(CgAdsChatActivity.this, message, new Runnable() {
                    @Override public void run() {
                        messageColumn.removeAllViews();
                        messageColumn.addView(CgAdsUi.skeleton(CgAdsChatActivity.this, 4),
                                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                        loadHistory(true);
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    private void loadOlderPage() {
        if (!hasMore || oldestSeq <= 1L) return;
        final long before = oldestSeq;
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgAdsApi.history(user, 0L, before, 40));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                JSONObject data = CgAdsApi.data(res);
                merge(CgAdsApi.array(data, "messages"));
                hasMore = data.optBoolean("has_more", false);
                long reported = data.optLong("oldest_seq", oldestSeq);
                if (reported > 0L) oldestSeq = Math.min(oldestSeq, reported);
                render(false);
            }

            @Override public void fail(String message) {
                CgUi.toast(CgAdsChatActivity.this, message);
            }
        });
    }

    private void apply(JSONObject data, boolean initial) {
        if (data == null) return;
        JSONObject meta = data.optJSONObject("meta");
        if (meta != null) threadMeta = meta;
        JSONObject presence = data.optJSONObject("presence");
        if (presence != null) paintPresence(presence);
        merge(CgAdsApi.array(data, "messages"));
        long latest = data.optLong("latest_seq", 0L);
        if (latest > latestSeq) latestSeq = latest;
        if (initial || oldestSeq == 0L) {
            long reported = data.optLong("oldest_seq", 0L);
            if (reported > 0L) oldestSeq = reported;
            hasMore = data.optBoolean("has_more", false);
        }
        render(initial);
        markRead();
    }

    private void merge(JSONArray incoming) {
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject message = incoming.optJSONObject(i);
            if (message == null) continue;
            long seq = message.optLong("seq", 0L);
            if (seq <= 0L) continue;
            boolean replaced = false;
            for (int k = 0; k < messages.size(); k++) {
                if (messages.get(k).optLong("seq", 0L) == seq) {
                    messages.set(k, message);
                    replaced = true;
                    break;
                }
            }
            if (replaced) continue;
            int at = messages.size();
            for (int k = messages.size() - 1; k >= 0; k--) {
                if (messages.get(k).optLong("seq", 0L) < seq) {
                    at = k + 1;
                    break;
                }
                at = k;
            }
            messages.add(at, message);
            if (seq > latestSeq) latestSeq = seq;
            if (oldestSeq == 0L || seq < oldestSeq) oldestSeq = seq;
        }
    }

    private void paintPresence(JSONObject presence) {
        boolean typing = presence.optBoolean("typing_user", false);
        boolean online = presence.optBoolean("user_online", false);
        long lastSeen = presence.optLong("user_last_seen", 0L);
        if (typing) {
            presenceLine.setText("المعلن يكتب الآن…");
            presenceLine.setTextColor(CgCfg.ACCENT);
        } else if (online) {
            presenceLine.setText("المعلن متصل الآن");
            presenceLine.setTextColor(CgCfg.GOOD);
        } else {
            presenceLine.setText(lastSeen > 0L
                    ? "المعلن غير متصل · آخر ظهور " + CgAdsUi.agoMs(lastSeen)
                    : "المعلن غير متصل");
            presenceLine.setTextColor(CgCfg.MUTED);
        }
    }

    // ----------------------------------------------------------------- render

    private void render(boolean scrollToEnd) {
        messageColumn.removeAllViews();

        if (hasMore) {
            loadOlder = CgUi.button(this, "تحميل رسائل أقدم", 1);
            loadOlder.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    loadOlderPage();
                }
            });
            messageColumn.addView(loadOlder, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        }

        String lastDay = "";
        for (JSONObject message : messages) {
            String day = dayOf(message.optLong("at", 0L));
            if (!day.equals(lastDay)) {
                lastDay = day;
                TextView separator = CgUi.text(this, day, 11.5f, CgCfg.GRAY, true);
                separator.setGravity(Gravity.CENTER);
                messageColumn.addView(separator, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 8));
            }
            messageColumn.addView(bubble(message), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }

        if (messages.isEmpty()) {
            messageColumn.addView(CgUi.empty(this, "لا رسائل بعد",
                            "اكتب أول رسالة للمعلن، أو انتظر رسالته."),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }

        if (scrollToEnd) {
            scroll.post(new Runnable() {
                @Override public void run() {
                    if (scroll != null) scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
    }

    private View bubble(final JSONObject message) {
        boolean fromAdmin = "admin".equals(message.optString("from", "user"));
        boolean internal = message.optBoolean("internal", false);
        String kind = message.optString("kind", "text");

        LinearLayout wrap = CgUi.hbox(this);
        wrap.setLayoutDirection(fromAdmin ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);

        LinearLayout card = CgUi.vbox(this);
        card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        int fill = internal ? CgUi.alpha(CgCfg.WARN, 0x24) : (fromAdmin ? 0xFF11384A : CgCfg.CARD2);
        card.setBackground(CgUi.bg(this, fill, 14, internal ? CgCfg.WARN : CgCfg.STROKE));
        int p = CgUi.dp(this, 10);
        card.setPadding(p, CgUi.dp(this, 8), p, CgUi.dp(this, 8));

        if (internal) {
            card.addView(CgUi.text(this, "ملاحظة داخلية — لا يراها المعلن", 10.5f, CgCfg.WARN, true),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 5));
        }

        long replyTo = message.optLong("reply_to", 0L);
        if (replyTo > 0L) {
            TextView quoted = CgUi.text(this, "رداً على رسالة #" + replyTo, 11f, CgCfg.MUTED, false);
            quoted.setBackground(CgUi.bg(this, CgUi.alpha(0xFFFFFFFF, 0x12), 8, 0));
            quoted.setPadding(CgUi.dp(this, 7), CgUi.dp(this, 4), CgUi.dp(this, 7), CgUi.dp(this, 4));
            card.addView(quoted, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 0, 0, 6));
        }

        JSONObject media = message.optJSONObject("media");
        if (media != null) {
            String label = "image".equals(kind) ? "صورة"
                    : "video".equals(kind) ? ("فيديو · " + CgAdsUi.clock(media.optLong("duration_ms", 0L)))
                    : "audio".equals(kind) ? ("تسجيل صوتي · " + CgAdsUi.clock(media.optLong("duration_ms", 0L)))
                    : ("ملف · " + media.optString("name", ""));
            TextView mediaLine = CgUi.text(this, "📎 " + label, 13f, CgCfg.ACCENT, true);
            mediaLine.setClickable(true);
            final String url = media.optString("url", "");
            mediaLine.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openMedia(url);
                }
            });
            card.addView(mediaLine, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            TextView size = CgUi.text(this, CgUi.sizeText(media.optLong("size", 0L)), 11f, CgCfg.GRAY, false);
            card.addView(size, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        }

        if ("ad_request".equals(kind)) {
            card.addView(orderCard(message), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        }
        if ("quote".equals(kind)) {
            card.addView(quoteCard(message.optJSONObject("quote")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        }

        String text = message.optString("text", "");
        if (text.length() > 0) {
            TextView content = CgUi.text(this, text, 14.5f, CgCfg.TEXT, false);
            content.setTextIsSelectable(true);
            card.addView(content, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, media != null ? 6 : 0, 0, 0));
        }

        LinearLayout footer = CgUi.hbox(this);
        footer.addView(CgUi.text(this, timeOf(message.optLong("at", 0L)) + " · #" + message.optLong("seq", 0L),
                10.5f, CgCfg.GRAY, false), new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        if (fromAdmin && !internal) {
            boolean read = message.optLong("read_at", 0L) > 0L;
            TextView tick = CgUi.text(this, read ? "✓✓ قُرئت" : "✓ أُرسلت", 10.5f,
                    read ? CgCfg.ACCENT : CgCfg.GRAY, false);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            tp.setMarginStart(CgUi.dp(this, 8));
            footer.addView(tick, tp);
        }
        card.addView(footer, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 5, 0, 0));

        card.setClickable(true);
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                messageMenu(message);
                return true;
            }
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 0.86f);
        wrap.addView(card, lp);
        wrap.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 0.14f));
        return wrap;
    }

    private View orderCard(JSONObject message) {
        JSONObject order = message.optJSONObject("order");
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.ACCENT, 0x1A), 12, CgCfg.ACCENT));
        int p = CgUi.dp(this, 10);
        box.setPadding(p, p, p, p);
        box.addView(CgUi.text(this, "طلب إعلان من المعلن", 13.5f, CgCfg.ACCENT, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        if (order == null) return box;

        String currency = order.optString("currency_label", "ر.س");
        box.addView(CgAdsUi.line(this, "المساحة", order.optString("slot_name", "")),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        box.addView(CgAdsUi.line(this, "المدة", order.optInt("days", 0) + " يوم"),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        JSONArray cities = order.optJSONArray("cities");
        if (cities != null && cities.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cities.length(); i++) {
                if (sb.length() > 0) sb.append("، ");
                sb.append(cities.optString(i, ""));
            }
            box.addView(CgAdsUi.line(this, "المدن", sb.toString()),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }
        box.addView(CgAdsUi.line(this, "الإجمالي المحسوب",
                        CgAdsUi.money(order.optDouble("total", 0d), currency)),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));

        final long seq = message.optLong("seq", 0L);
        LinearLayout buttons = CgUi.hbox(this);
        TextView toCampaign = CgUi.smallButton(this, "تحويل إلى حملة", 0);
        toCampaign.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                convertToCampaign(seq);
            }
        });
        buttons.addView(toCampaign, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView offer = CgUi.smallButton(this, "عرض سعر", 1);
        offer.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                composeOffer(order);
            }
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        op.setMarginStart(CgUi.dp(this, 6));
        buttons.addView(offer, op);
        box.addView(buttons, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        return box;
    }

    private View quoteCard(JSONObject quote) {
        LinearLayout box = CgUi.vbox(this);
        box.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.GOOD, 0x16), 12, CgCfg.GOOD));
        int p = CgUi.dp(this, 10);
        box.setPadding(p, p, p, p);
        box.addView(CgUi.text(this, "عرض سعر", 13f, CgCfg.GOOD, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        if (quote == null) return box;
        String currency = quote.optString("currency_label", quote.optString("currency", "ر.س"));
        box.addView(CgUi.text(this, CgAdsUi.money(quote.optDouble("amount", 0d), currency),
                        19f, CgCfg.TEXT, true),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        if (quote.optString("note", "").length() > 0) {
            box.addView(CgUi.text(this, quote.optString("note"), 12.5f, CgCfg.MUTED, false),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        }
        String status = quote.optString("status", "open");
        int color = "accepted".equals(status) ? CgCfg.GOOD
                : "rejected".equals(status) ? CgCfg.BAD
                : "negotiating".equals(status) ? CgCfg.WARN : CgCfg.GRAY;
        String label = "accepted".equals(status) ? "قبله المعلن"
                : "rejected".equals(status) ? "رفضه المعلن"
                : "negotiating".equals(status) ? "طلب التفاوض" : "بانتظار رد المعلن";
        box.addView(CgUi.badge(this, label, color), CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        return box;
    }

    // ---------------------------------------------------------------- sending

    private void sendTyped() {
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.length() == 0) return;
        final JSONObject message = new JSONObject();
        try {
            message.put("kind", "text");
            message.put("text", text);
            message.put("internal", internalMode);
        } catch (Exception ignored) { }
        input.setText("");
        post(message, "تم الإرسال.");
    }

    private void post(final JSONObject message, final String success) {
        showLoading("جارٍ الإرسال...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                return CgAdsApi.send(user, message);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                JSONObject data = CgAdsApi.data(res);
                JSONObject stored = data.optJSONObject("message");
                if (stored != null) {
                    JSONArray one = new JSONArray();
                    one.put(stored);
                    merge(one);
                    render(true);
                }
                if (success != null) CgUi.toast(CgAdsChatActivity.this, success);
            }

            @Override public void fail(String message2) {
                hideLoading();
                CgUi.info(CgAdsChatActivity.this, "تعذر الإرسال", message2);
            }
        });
    }

    /** "إنشاء عرض سعر": amount and note, prefilled from the request when there is one. */
    private void composeOffer(final JSONObject order) {
        LinearLayout form = CgUi.vbox(this);
        int p = CgUi.dp(this, 4);
        form.setPadding(p, p, p, p);
        final EditText amount = CgUi.numberField(this, "المبلغ");
        if (order != null && order.optDouble("total", 0d) > 0d) {
            amount.setText(String.valueOf(Math.round(order.optDouble("total", 0d))));
        }
        form.addView(CgUi.labeled(this, "المبلغ المطلوب", amount),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final EditText days = CgUi.numberField(this, "عدد الأيام");
        if (order != null && order.optInt("days", 0) > 0) {
            days.setText(String.valueOf(order.optInt("days")));
        }
        form.addView(CgUi.labeled(this, "المدة", days),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        final EditText note = CgUi.field(this, "ملاحظة للمعلن (اختياري)", true);
        form.addView(CgUi.labeled(this, "ملاحظة", note),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        ScrollView scrollForm = new ScrollView(this);
        scrollForm.addView(form);
        CgUi.dialog(this)
                .setTitle("إنشاء عرض سعر")
                .setView(scrollForm)
                .setPositiveButton("إرسال العرض", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        double value = parseDouble(amount);
                        if (value <= 0d) {
                            CgUi.info(CgAdsChatActivity.this, "مبلغ غير صالح", "أدخل مبلغاً أكبر من صفر.");
                            return;
                        }
                        JSONObject quote = new JSONObject();
                        JSONObject message = new JSONObject();
                        try {
                            quote.put("amount", value);
                            quote.put("currency", "SAR");
                            quote.put("currency_label", "ر.س");
                            quote.put("days", (int) parseDouble(days));
                            quote.put("note", note.getText() == null ? "" : note.getText().toString().trim());
                            if (order != null) {
                                quote.put("slot", order.optString("slot", ""));
                                quote.put("slot_name", order.optString("slot_name", ""));
                            }
                            message.put("kind", "quote");
                            message.put("quote", quote);
                        } catch (Exception ignored) { }
                        post(message, "تم إرسال عرض السعر.");
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static double parseDouble(EditText field) {
        try {
            return Double.parseDouble(field.getText().toString().trim());
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private void convertToCampaign(final long seq) {
        CgUi.input(this, "تحويل الطلب إلى حملة", "عنوان الحملة", advertiserName, false,
                new CgUi.Callback<String>() {
                    @Override public void run(final String title) {
                        showLoading("جارٍ إنشاء الحملة...");
                        CgAdsApi.async(CgAdsChatActivity.this, new CgAdsApi.Call() {
                            @Override public CgHttp.Res run() {
                                return CgAdsApi.toCampaign(user, seq, title);
                            }
                        }, new CgHttp.Done<CgHttp.Res>() {
                            @Override public void ok(CgHttp.Res res) {
                                hideLoading();
                                JSONObject campaign = CgAdsApi.data(res).optJSONObject("campaign");
                                String id = campaign == null ? "" : campaign.optString("id", "");
                                CgUi.toast(CgAdsChatActivity.this,
                                        "تم إنشاء الحملة كمسودة. أكمل المواد ثم اعتمدها.");
                                if (id.length() > 0) {
                                    Intent intent = new Intent(CgAdsChatActivity.this,
                                            CgAdsCampaignActivity.class);
                                    intent.putExtra("campaign_id", id);
                                    startActivity(intent);
                                }
                            }

                            @Override public void fail(String message) {
                                hideLoading();
                                CgUi.info(CgAdsChatActivity.this, "تعذر التحويل", message);
                            }
                        });
                    }
                });
    }

    // ------------------------------------------------------------ attachments

    private void attachMenu() {
        CgUi.choose(this, "إرفاق", new CharSequence[]{"صورة", "ملف PDF"}, new CgUi.IntCb() {
            @Override public void run(int index) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                if (index == 0) {
                    intent.setType("image/*");
                    startActivityForResult(intent, REQ_IMAGE);
                } else {
                    intent.setType("application/pdf");
                    startActivityForResult(intent, REQ_FILE);
                }
            }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final boolean image = request == REQ_IMAGE;
        showLoading("جارٍ الرفع...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                byte[] bytes = readUri(uri, image ? 8 * 1024 * 1024 : 20 * 1024 * 1024);
                if (bytes == null) throw new Exception("تعذر قراءة الملف أو أنه أكبر من المسموح.");
                String name = nameOf(uri);
                return CgHttp.require(CgAdsApi.uploadChatMedia(user, image ? "image" : "file",
                        name, bytes, "application/octet-stream"));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                String mediaId = CgAdsApi.data(res).optString("media_id", "");
                if (mediaId.length() == 0) {
                    CgUi.info(CgAdsChatActivity.this, "تعذر الرفع", "رد غير متوقع من الخادم.");
                    return;
                }
                JSONObject message = new JSONObject();
                try {
                    message.put("kind", image ? "image" : "file");
                    message.put("media_id", mediaId);
                    message.put("internal", internalMode);
                } catch (Exception ignored) { }
                post(message, "تم الإرسال.");
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsChatActivity.this, "تعذر الرفع", message);
            }
        });
    }

    private byte[] readUri(Uri uri, long limit) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int n;
                long total = 0L;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > limit) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private String nameOf(Uri uri) {
        try {
            android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                try {
                    int column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (column >= 0 && cursor.moveToFirst()) {
                        String name = cursor.getString(column);
                        if (name != null) return name;
                    }
                } finally {
                    cursor.close();
                }
            }
        } catch (Exception ignored) { }
        return "file";
    }

    private void openMedia(String path) {
        if (path == null || path.length() == 0) return;
        String url = path.startsWith("http") ? path : CgCfg.API + path;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception ignored) {
            CgUi.toast(this, "تعذر فتح الملف.");
        }
    }

    // ------------------------------------------------------------------ menus

    private void openMenu() {
        final boolean archived = threadMeta.optBoolean("archived", false);
        final boolean blocked = threadMeta.optBoolean("blocked", false);
        CharSequence[] labels = new CharSequence[]{
                "وسوم وملاحظة داخلية",
                "إسناد المحادثة",
                "إنشاء عرض سعر",
                archived ? "إلغاء الأرشفة" : "أرشفة",
                blocked ? "إلغاء الحظر" : "حظر المعلن",
                "تصدير السجل",
                "حملات هذا المعلن",
        };
        CgUi.choose(this, advertiserName, labels, new CgUi.IntCb() {
            @Override public void run(int index) {
                if (index == 0) editTagsAndNote();
                else if (index == 1) assignThread();
                else if (index == 2) composeOffer(null);
                else if (index == 3) setFlag("archived", !archived);
                else if (index == 4) setFlag("blocked", !blocked);
                else if (index == 5) exportThread();
                else if (index == 6) openCampaigns();
            }
        });
    }

    private void editTagsAndNote() {
        LinearLayout form = CgUi.vbox(this);
        final EditText tags = CgUi.field(this, "مثال: مهم، عميل متكرر", false);
        JSONArray current = threadMeta.optJSONArray("tags");
        if (current != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < current.length(); i++) {
                if (sb.length() > 0) sb.append("، ");
                sb.append(current.optString(i, ""));
            }
            tags.setText(sb.toString());
        }
        form.addView(CgUi.labeled(this, "الوسوم (مفصولة بفاصلة)", tags),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final EditText note = CgUi.field(this, "ملاحظة لا يراها المعلن", true);
        note.setText(threadMeta.optString("note", ""));
        form.addView(CgUi.labeled(this, "ملاحظة داخلية", note),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        ScrollView scrollForm = new ScrollView(this);
        scrollForm.addView(form);
        CgUi.dialog(this)
                .setTitle("وسوم وملاحظة")
                .setView(scrollForm)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        JSONObject changes = new JSONObject();
                        try {
                            JSONArray list = new JSONArray();
                            String raw = tags.getText() == null ? "" : tags.getText().toString();
                            for (String part : raw.split("[،,]")) {
                                String trimmed = part.trim();
                                if (trimmed.length() > 0) list.put(trimmed);
                            }
                            changes.put("tags", list);
                            changes.put("note", note.getText() == null ? "" : note.getText().toString());
                        } catch (Exception ignored) { }
                        applyFlags(changes, "تم حفظ الوسوم والملاحظة.");
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    /** Assignment is stored as a tag, so it shows in the inbox and needs no new field. */
    private void assignThread() {
        CgUi.input(this, "إسناد المحادثة", "اسم المسؤول", "", false, new CgUi.Callback<String>() {
            @Override public void run(String value) {
                JSONArray list = new JSONArray();
                JSONArray current = threadMeta.optJSONArray("tags");
                if (current != null) {
                    for (int i = 0; i < current.length(); i++) {
                        String tag = current.optString(i, "");
                        if (tag.length() > 0 && !tag.startsWith("مسند:")) list.put(tag);
                    }
                }
                if (value != null && value.trim().length() > 0) list.put("مسند:" + value.trim());
                JSONObject changes = new JSONObject();
                try {
                    changes.put("tags", list);
                } catch (Exception ignored) { }
                applyFlags(changes, "تم الإسناد.");
            }
        });
    }

    private void setFlag(final String key, final boolean value) {
        JSONObject changes = new JSONObject();
        try {
            changes.put(key, value);
        } catch (Exception ignored) { }
        applyFlags(changes, "تم التحديث.");
    }

    private void applyFlags(final JSONObject changes, final String success) {
        showLoading("جارٍ الحفظ...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                return CgAdsApi.flags(user, changes);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                JSONObject meta = CgAdsApi.data(res).optJSONObject("meta");
                if (meta != null) threadMeta = meta;
                CgUi.toast(CgAdsChatActivity.this, success);
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsChatActivity.this, "تعذر الحفظ", message);
            }
        });
    }

    private void exportThread() {
        showLoading("جارٍ التصدير...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                return CgAdsApi.exportThread(user, true);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                final String transcript = CgAdsApi.data(res).optString("transcript", "");
                ScrollView view = new ScrollView(CgAdsChatActivity.this);
                TextView text = CgUi.text(CgAdsChatActivity.this, transcript, 12f, CgCfg.TEXT, false);
                text.setTextIsSelectable(true);
                int p = CgUi.dp(CgAdsChatActivity.this, 10);
                text.setPadding(p, p, p, p);
                view.addView(text);
                CgUi.dialog(CgAdsChatActivity.this)
                        .setTitle("سجل المحادثة")
                        .setView(view)
                        .setPositiveButton("نسخ", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int which) {
                                try {
                                    android.content.ClipboardManager clipboard =
                                            (android.content.ClipboardManager)
                                                    getSystemService(CLIPBOARD_SERVICE);
                                    clipboard.setPrimaryClip(android.content.ClipData
                                            .newPlainText("cigram", transcript));
                                    CgUi.toast(CgAdsChatActivity.this, "تم النسخ.");
                                } catch (Exception ignored) { }
                            }
                        })
                        .setNegativeButton("إغلاق", null)
                        .show();
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsChatActivity.this, "تعذر التصدير", message);
            }
        });
    }

    private void openCampaigns() {
        Intent intent = new Intent(this, CgAdsCampaignsActivity.class);
        intent.putExtra("user", user);
        startActivity(intent);
    }

    private void messageMenu(final JSONObject message) {
        final long seq = message.optLong("seq", 0L);
        String text = message.optString("text", "");
        List<CharSequence> labels = new ArrayList<CharSequence>();
        labels.add("نسخ النص");
        labels.add("حذف عندي");
        if ("ad_request".equals(message.optString("kind"))) labels.add("تحويل إلى حملة");
        final String copyText = text;
        CgUi.choose(this, "رسالة #" + seq, labels.toArray(new CharSequence[0]), new CgUi.IntCb() {
            @Override public void run(int index) {
                if (index == 0) {
                    try {
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboard.setPrimaryClip(android.content.ClipData
                                .newPlainText("cigram", copyText));
                        CgUi.toast(CgAdsChatActivity.this, "تم النسخ.");
                    } catch (Exception ignored) { }
                } else if (index == 1) {
                    deleteForMe(seq);
                } else {
                    convertToCampaign(seq);
                }
            }
        });
    }

    private void deleteForMe(final long seq) {
        CgUi.confirm(this, "حذف عندي",
                "ستختفي من شاشتك فقط، ونسخة المعلن تبقى كما هي.", "حذف", true, new Runnable() {
                    @Override public void run() {
                        CgAdsApi.async(CgAdsChatActivity.this, new CgAdsApi.Call() {
                            @Override public CgHttp.Res run() {
                                return CgAdsApi.deleteMessage(user, seq);
                            }
                        }, new CgHttp.Done<CgHttp.Res>() {
                            @Override public void ok(CgHttp.Res res) {
                                for (int i = messages.size() - 1; i >= 0; i--) {
                                    if (messages.get(i).optLong("seq", 0L) == seq) messages.remove(i);
                                }
                                render(false);
                            }

                            @Override public void fail(String message) {
                                CgUi.toast(CgAdsChatActivity.this, message);
                            }
                        });
                    }
                });
    }

    // ------------------------------------------------------------- transport

    private void openSocket() {
        if (!alive()) return;
        final CgWs ws = new CgWs(CgAdsApi.socketUrl(user), new CgWs.Listener() {
            @Override public void onOpen() {
                failures = 0;
                loadHistory(false);
            }

            @Override public void onText(String text) {
                handleEvent(text);
            }

            @Override public void onClosed(boolean clean, String reason) {
                socket = null;
                if (!alive()) return;
                startPolling();
                long delay = BACKOFF_MS[Math.min(failures, BACKOFF_MS.length - 1)];
                failures++;
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (!alive() || socket != null) return;
                        openSocket();
                    }
                }, delay);
            }
        });
        ws.header("Authorization", "Bearer " + CgCfg.TOKEN);
        ws.header("X-Admin-Token", CgCfg.TOKEN);
        socket = ws;
        ws.connect();
    }

    private void handleEvent(String raw) {
        JSONObject event;
        try {
            event = new JSONObject(raw);
        } catch (Exception ignored) {
            return;
        }
        String type = event.optString("type", "");
        if ("message".equals(type)) {
            JSONObject message = event.optJSONObject("message");
            if (message != null) {
                JSONArray one = new JSONArray();
                one.put(message);
                merge(one);
                render(true);
                markRead();
            }
        } else if ("typing".equals(type)) {
            if (!"admin".equals(event.optString("viewer"))) {
                presenceLine.setText(event.optBoolean("on", false)
                        ? "المعلن يكتب الآن…" : "المعلن متصل");
                presenceLine.setTextColor(CgCfg.ACCENT);
            }
        } else if ("read".equals(type) || "quote".equals(type) || "flags".equals(type)) {
            loadHistory(false);
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        pollOnce();
    }

    private void pollOnce() {
        if (!alive()) {
            polling = false;
            return;
        }
        CgWs ws = socket;
        if (ws != null && ws.isOpen()) {
            polling = false;
            return;
        }
        final long since = latestSeq;
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgAdsApi.poll(user, since);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                if (res != null && res.ok()) {
                    JSONObject data = CgAdsApi.data(res);
                    JSONObject presence = data.optJSONObject("presence");
                    if (presence != null) paintPresence(presence);
                    JSONArray incoming = CgAdsApi.array(data, "messages");
                    if (incoming.length() > 0) {
                        merge(incoming);
                        render(true);
                        markRead();
                    }
                }
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        pollOnce();
                    }
                }, POLL_GAP_MS);
            }

            @Override public void fail(String message) {
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        pollOnce();
                    }
                }, 4000L);
            }
        });
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!alive()) return;
            CgWs ws = socket;
            if (ws != null && ws.isOpen()) {
                ws.send("{\"type\":\"ping\"}");
            } else {
                CgHttp.bg(new Runnable() {
                    @Override public void run() {
                        CgAdsApi.heartbeat(user);
                    }
                });
            }
            main.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private void markRead() {
        if (latestSeq <= 0L) return;
        CgWs ws = socket;
        if (ws != null && ws.isOpen()) {
            ws.send("{\"type\":\"read\",\"up_to\":" + latestSeq + "}");
            return;
        }
        CgHttp.bg(new Runnable() {
            @Override public void run() {
                CgAdsApi.markRead(user);
            }
        });
    }

    // --------------------------------------------------------------- helpers

    private static String timeOf(long at) {
        if (at <= 0L) return "";
        return new java.text.SimpleDateFormat("HH:mm", new java.util.Locale("ar"))
                .format(new java.util.Date(at));
    }

    private static String dayOf(long at) {
        if (at <= 0L) return "";
        java.util.Calendar today = java.util.Calendar.getInstance();
        java.util.Calendar when = java.util.Calendar.getInstance();
        when.setTimeInMillis(at);
        if (today.get(java.util.Calendar.YEAR) == when.get(java.util.Calendar.YEAR)
                && today.get(java.util.Calendar.DAY_OF_YEAR) == when.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "اليوم";
        }
        today.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (today.get(java.util.Calendar.YEAR) == when.get(java.util.Calendar.YEAR)
                && today.get(java.util.Calendar.DAY_OF_YEAR) == when.get(java.util.Calendar.DAY_OF_YEAR)) {
            return "أمس";
        }
        return new java.text.SimpleDateFormat("d MMMM yyyy", new java.util.Locale("ar"))
                .format(new java.util.Date(at));
    }
}
