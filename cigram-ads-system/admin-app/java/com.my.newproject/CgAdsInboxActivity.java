package com.my.newproject;

import android.content.Intent;
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

/**
 * صندوق وارد الإعلانات.
 *
 * Threads newest first, with the advertiser's name and logo, the last message, an
 * unread badge and their connection dot. Search and the filters the spec asks for
 * (unread, new, important, awaiting payment), plus the admin's own status button.
 *
 * It refreshes while open on a slow timer rather than holding a socket: the inbox
 * is a list of summaries, and a conversation that needs instant delivery is the
 * chat screen's job.
 */
public class CgAdsInboxActivity extends CgBase {

    private static final long REFRESH_MS = 12000L;

    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout page;
    private LinearLayout threadList;
    private EditText search;
    private TextView statusButton;
    private TextView counter;
    private CgAdsUi.Filters filters;

    private String filter = "";
    private String query = "";
    private boolean includeArchived = false;
    private boolean loading = false;

    @Override protected String screenTitle() {
        return "صندوق وارد الإعلانات";
    }

    @Override protected void onBuild() {
        page = scrollBody();
        page.addView(buildStatusCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(buildSearch(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        page.addView(buildFilters(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        counter = CgUi.muted(this, "");
        page.addView(counter, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 0, 2, 8));

        threadList = CgUi.vbox(this);
        page.addView(threadList, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        showSkeleton();
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        CgAdsPresence.onScreenResumed(this);
        paintStatus();
        main.removeCallbacks(refresh);
        main.postDelayed(refresh, REFRESH_MS);
        if (!loading) load();
    }

    @Override protected void onStop() {
        main.removeCallbacks(refresh);
        CgAdsPresence.onScreenStopped();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!alive()) return;
            load();
            main.postDelayed(this, REFRESH_MS);
        }
    };

    // -------------------------------------------------------------- my status

    private View buildStatusCard() {
        LinearLayout card = CgUi.card(this);
        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = CgUi.vbox(this);
        texts.addView(CgUi.text(this, "حالتي للمعلنين", 13.5f, CgCfg.MUTED, false),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        statusButton = CgUi.text(this, "", 16f, CgCfg.TEXT, true);
        texts.addView(statusButton, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        row.addView(texts, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));

        TextView change = CgUi.button(this, "تغيير", 1);
        change.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                chooseStatus();
            }
        });
        row.addView(change, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        card.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(CgUi.muted(this, "يتحول تلقائياً إلى «غير متصل» عند إغلاق التطبيق."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        paintStatus();
        return card;
    }

    private void paintStatus() {
        if (statusButton == null) return;
        String status = CgAdsPresence.current(this);
        statusButton.setText(CgAdsPresence.label(status));
        statusButton.setTextColor(CgAdsPresence.color(status));
    }

    private void chooseStatus() {
        final String[] values = new String[]{CgAdsPresence.ONLINE, CgAdsPresence.AWAY, CgAdsPresence.OFFLINE};
        CharSequence[] labels = new CharSequence[]{
                "متصل الآن", "خارج ساعات العمل", "غير متصل"};
        CgUi.choose(this, "حالتي للمعلنين", labels, new CgUi.IntCb() {
            @Override public void run(int index) {
                if (index < 0 || index >= values.length) return;
                CgAdsPresence.set(CgAdsInboxActivity.this, values[index], new Runnable() {
                    @Override public void run() {
                        paintStatus();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------ search + filters

    private View buildSearch() {
        search = CgUi.field(this, "ابحث باسم الشركة أو نص الرسالة", false);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable e) {
                query = e == null ? "" : e.toString().trim();
                main.removeCallbacks(searchSoon);
                main.postDelayed(searchSoon, 320L);
            }
        });
        return search;
    }

    private final Runnable searchSoon = new Runnable() {
        @Override public void run() {
            load();
        }
    };

    private View buildFilters() {
        filters = new CgAdsUi.Filters(this);
        filters.add("الكل", "")
                .add("غير مقروء", "unread")
                .add("جديد", "new")
                .add("مهم", "important")
                .add("بانتظار الدفع", "awaiting_payment")
                .add("محظور", "blocked")
                .add("المؤرشفة", "archived")
                .onPicked(new CgAdsUi.Filters.Picked() {
                    @Override public void onPicked(String value) {
                        includeArchived = "archived".equals(value);
                        filter = "archived".equals(value) ? "" : value;
                        load();
                    }
                });
        return filters.view;
    }

    // ----------------------------------------------------------------- data

    private void showSkeleton() {
        threadList.removeAllViews();
        threadList.addView(CgAdsUi.skeleton(this, 5), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
    }

    private void load() {
        if (loading) return;
        loading = true;
        final String wantedFilter = filter;
        final String wantedQuery = query;
        final boolean wantedArchived = includeArchived;
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgAdsApi.threads(wantedFilter, wantedQuery, wantedArchived));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                loading = false;
                JSONObject data = CgAdsApi.data(res);
                render(CgAdsApi.array(data, "threads"), data.optInt("unread_total", 0));
            }

            @Override public void fail(String message) {
                loading = false;
                threadList.removeAllViews();
                threadList.addView(CgUi.errorCard(CgAdsInboxActivity.this, message, new Runnable() {
                    @Override public void run() {
                        showSkeleton();
                        load();
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    private void render(JSONArray threads, int unreadTotal) {
        threadList.removeAllViews();
        counter.setText(threads.length() + " محادثة"
                + (unreadTotal > 0 ? " · " + unreadTotal + " غير مقروءة" : ""));
        if (threads.length() == 0) {
            threadList.addView(CgUi.empty(this, "لا توجد محادثات",
                            query.length() > 0 ? "لا نتائج لهذا البحث." : "ستظهر هنا أول رسالة من معلن."),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            return;
        }
        for (int i = 0; i < threads.length(); i++) {
            JSONObject thread = threads.optJSONObject(i);
            if (thread == null) continue;
            threadList.addView(threadRow(thread),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
    }

    private View threadRow(final JSONObject thread) {
        LinearLayout card = CgUi.card(this);
        card.setPadding(CgUi.dp(this, 12), CgUi.dp(this, 10), CgUi.dp(this, 12), CgUi.dp(this, 10));
        final int unread = thread.optInt("unread_admin", 0);
        if (unread > 0) {
            card.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.ACCENT, 0x14), 14, CgCfg.ACCENT));
        }

        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);

        // Avatar: the first letter of the company, since logos are optional.
        String name = thread.optString("advertiser_name", "");
        if (name.length() == 0) name = "معلن";
        TextView avatar = CgUi.text(this, name.substring(0, 1), 18f, CgCfg.TEXT, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.ACCENT, 0x33), 22, CgCfg.ACCENT));
        int size = CgUi.dp(this, 44);
        row.addView(avatar, new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = CgUi.vbox(this);
        LinearLayout head = CgUi.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = CgUi.text(this, name, 15f, CgCfg.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));

        View dot = new View(this);
        boolean online = thread.optBoolean("user_online", false);
        dot.setBackground(CgUi.bg(this, online ? CgCfg.GOOD : CgCfg.GRAY, 5, 0));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(CgUi.dp(this, 9), CgUi.dp(this, 9));
        dotLp.setMarginStart(CgUi.dp(this, 6));
        head.addView(dot, dotLp);
        texts.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        TextView preview = CgUi.text(this, thread.optString("preview", "—"), 13f, CgCfg.MUTED, false);
        preview.setMaxLines(1);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(preview, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 3, 0, 0));

        LinearLayout tags = CgUi.hbox(this);
        JSONArray tagList = thread.optJSONArray("tags");
        if (tagList != null) {
            for (int i = 0; i < tagList.length() && i < 3; i++) {
                TextView tag = CgUi.badge(this, tagList.optString(i, ""), CgCfg.WARN);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
                if (i > 0) tp.setMarginStart(CgUi.dp(this, 4));
                tags.addView(tag, tp);
            }
        }
        if (thread.optBoolean("awaiting_payment", false)) {
            TextView tag = CgUi.badge(this, "بانتظار الدفع", CgCfg.WARN);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            if (tags.getChildCount() > 0) tp.setMarginStart(CgUi.dp(this, 4));
            tags.addView(tag, tp);
        }
        if (thread.optBoolean("blocked", false)) {
            TextView tag = CgUi.badge(this, "محظور", CgCfg.BAD);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            if (tags.getChildCount() > 0) tp.setMarginStart(CgUi.dp(this, 4));
            tags.addView(tag, tp);
        }
        if (thread.optBoolean("archived", false)) {
            TextView tag = CgUi.badge(this, "مؤرشفة", CgCfg.GRAY);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            if (tags.getChildCount() > 0) tp.setMarginStart(CgUi.dp(this, 4));
            tags.addView(tag, tp);
        }
        if (tags.getChildCount() > 0) {
            texts.addView(tags, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
        }

        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        tl.setMarginStart(CgUi.dp(this, 10));
        tl.setMarginEnd(CgUi.dp(this, 10));
        row.addView(texts, tl);

        LinearLayout right = CgUi.vbox(this);
        right.setGravity(Gravity.CENTER_HORIZONTAL);
        long lastAt = thread.optLong("last_at", 0L);
        right.addView(CgUi.text(this, lastAt > 0L ? CgAdsUi.agoMs(lastAt) : "", 11f, CgCfg.GRAY, false),
                new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        if (unread > 0) {
            TextView badge = CgUi.text(this, String.valueOf(unread), 12f, 0xFF04161D, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(CgUi.bg(this, CgCfg.ACCENT, 10, 0));
            badge.setMinWidth(CgUi.dp(this, 22));
            badge.setPadding(CgUi.dp(this, 6), CgUi.dp(this, 2), CgUi.dp(this, 6), CgUi.dp(this, 3));
            right.addView(badge, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 6, 0, 0));
        }
        row.addView(right, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));

        card.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        final String user = thread.optString("thread_id", "");
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(user, thread.optString("advertiser_name", ""));
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                threadMenu(thread);
                return true;
            }
        });
        card.setContentDescription(name + ". " + thread.optString("preview", "")
                + (unread > 0 ? ". " + unread + " رسالة غير مقروءة" : ""));
        return card;
    }

    private void open(String user, String name) {
        if (user == null || user.length() == 0) return;
        Intent intent = new Intent(this, CgAdsChatActivity.class);
        intent.putExtra("user", user);
        intent.putExtra("name", name);
        startActivity(intent);
    }

    private void threadMenu(final JSONObject thread) {
        final String user = thread.optString("thread_id", "");
        final boolean archived = thread.optBoolean("archived", false);
        final boolean blocked = thread.optBoolean("blocked", false);
        CharSequence[] labels = new CharSequence[]{
                archived ? "إلغاء الأرشفة" : "أرشفة",
                blocked ? "إلغاء الحظر" : "حظر من المحادثة",
                "تصدير سجل المحادثة",
                "حذف المحادثة ووسائطها",
        };
        CgUi.choose(this, thread.optString("advertiser_name", "المحادثة"), labels, new CgUi.IntCb() {
            @Override public void run(int index) {
                if (index == 0) setFlag(user, "archived", !archived);
                else if (index == 1) confirmBlock(user, !blocked);
                else if (index == 2) exportThread(user);
                else if (index == 3) confirmPurge(user);
            }
        });
    }

    private void setFlag(final String user, final String key, final boolean value) {
        showLoading("جارٍ الحفظ...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                JSONObject changes = new JSONObject();
                try {
                    changes.put(key, value);
                } catch (Exception ignored) { }
                return CgAdsApi.flags(user, changes);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                CgUi.toast(CgAdsInboxActivity.this, "تم التحديث.");
                load();
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsInboxActivity.this, "تعذر التحديث", message);
            }
        });
    }

    private void confirmBlock(final String user, final boolean blocked) {
        CgUi.confirm(this, blocked ? "حظر المعلن" : "إلغاء الحظر",
                blocked
                        ? "لن يستطيع إرسال رسائل جديدة، وستبقى محادثته وحملاته كما هي. يمكنك أنت الكتابة إليه."
                        : "سيستطيع إرسال الرسائل من جديد.",
                blocked ? "حظر" : "إلغاء الحظر", blocked, new Runnable() {
                    @Override public void run() {
                        setFlag(user, "blocked", blocked);
                    }
                });
    }

    private void exportThread(final String user) {
        showLoading("جارٍ التصدير...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                return CgAdsApi.exportThread(user, true);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                String transcript = CgAdsApi.data(res).optString("transcript", "");
                if (transcript.length() == 0) {
                    CgUi.info(CgAdsInboxActivity.this, "التصدير", "المحادثة فارغة.");
                    return;
                }
                showTranscript(transcript);
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsInboxActivity.this, "تعذر التصدير", message);
            }
        });
    }

    private void showTranscript(final String transcript) {
        ScrollView scroll = new ScrollView(this);
        TextView text = CgUi.text(this, transcript, 12f, CgCfg.TEXT, false);
        text.setTextIsSelectable(true);
        int p = CgUi.dp(this, 10);
        text.setPadding(p, p, p, p);
        scroll.addView(text);
        CgUi.dialog(this)
                .setTitle("سجل المحادثة")
                .setView(scroll)
                .setPositiveButton("نسخ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            android.content.ClipboardManager clipboard =
                                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText("cigram", transcript));
                            CgUi.toast(CgAdsInboxActivity.this, "تم النسخ.");
                        } catch (Exception ignored) {
                            CgUi.toast(CgAdsInboxActivity.this, "تعذر النسخ.");
                        }
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void confirmPurge(final String user) {
        CgUi.confirm(this, "حذف المحادثة",
                "سيُحذف كل سجل المحادثة ووسائطها من التخزين نهائياً، ولا يمكن التراجع. "
                        + "حملات المعلن وملفه لا تُحذف.",
                "حذف نهائي", true, new Runnable() {
                    @Override public void run() {
                        showLoading("جارٍ الحذف...");
                        CgAdsApi.async(CgAdsInboxActivity.this, new CgAdsApi.Call() {
                            @Override public CgHttp.Res run() {
                                return CgAdsApi.purgeThread(user);
                            }
                        }, new CgHttp.Done<CgHttp.Res>() {
                            @Override public void ok(CgHttp.Res res) {
                                hideLoading();
                                JSONObject data = CgAdsApi.data(res);
                                CgUi.toast(CgAdsInboxActivity.this,
                                        "تم الحذف (" + data.optInt("removed_media", 0) + " ملف وسائط).");
                                load();
                            }

                            @Override public void fail(String message) {
                                hideLoading();
                                CgUi.info(CgAdsInboxActivity.this, "تعذر الحذف", message);
                            }
                        });
                    }
                });
    }

    // ------------------------------------------------------------ entry card

    /**
     * The pinned inbox card for the admin home screen. Call it from the home
     * screen's build and add the returned view; it refreshes its own unread count
     * when the home screen resumes.
     */
    static View entryCard(final android.app.Activity activity) {
        final LinearLayout card = CgUi.card(activity);
        card.setBackground(CgUi.bg(activity, CgUi.alpha(CgCfg.ACCENT, 0x1A), 16, CgCfg.ACCENT));

        LinearLayout row = CgUi.hbox(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView glyph = CgUi.text(activity, "✉", 22f, CgCfg.ACCENT, true);
        glyph.setGravity(Gravity.CENTER);
        int size = CgUi.dp(activity, 44);
        glyph.setBackground(CgUi.bg(activity, CgUi.alpha(CgCfg.ACCENT, 0x33), 14, 0));
        row.addView(glyph, new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = CgUi.vbox(activity);
        texts.addView(CgUi.text(activity, "صندوق وارد الإعلانات", 16f, CgCfg.TEXT, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final TextView subtitle = CgUi.text(activity, "جارٍ التحديث…", 12.5f, CgCfg.MUTED, false);
        texts.addView(subtitle, CgUi.lp(activity, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        tp.setMarginStart(CgUi.dp(activity, 10));
        row.addView(texts, tp);

        final TextView badge = CgUi.text(activity, "", 13f, 0xFF04161D, true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(CgUi.bg(activity, CgCfg.BAD, 11, 0));
        badge.setMinWidth(CgUi.dp(activity, 24));
        badge.setPadding(CgUi.dp(activity, 7), CgUi.dp(activity, 2), CgUi.dp(activity, 7), CgUi.dp(activity, 3));
        badge.setVisibility(View.GONE);
        row.addView(badge, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));

        card.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                activity.startActivity(new Intent(activity, CgAdsInboxActivity.class));
            }
        });
        card.setContentDescription("صندوق وارد الإعلانات");

        refreshEntryCard(activity, subtitle, badge);
        return card;
    }

    /** Fetches just the counts for the home-screen card. */
    private static void refreshEntryCard(final android.app.Activity activity,
                                         final TextView subtitle, final TextView badge) {
        CgHttp.async(activity, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgAdsApi.threads("", "", false);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                if (res == null || !res.ok()) {
                    subtitle.setText("اضغط لعرض المحادثات");
                    return;
                }
                JSONObject data = CgAdsApi.data(res);
                int unread = data.optInt("unread_total", 0);
                int total = CgAdsApi.array(data, "threads").length();
                subtitle.setText(total + " محادثة"
                        + (unread > 0 ? " · " + unread + " غير مقروءة" : " · لا جديد"));
                badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
                badge.setText(String.valueOf(unread));
            }

            @Override public void fail(String message) {
                subtitle.setText("اضغط لعرض المحادثات");
            }
        });
    }
}
