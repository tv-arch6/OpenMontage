package com.Cigram.vid;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * المحادثة مع إدارة الإعلانات.
 *
 * Transport, ordering and the offline queue live in {@link CigramAdsChat}; this
 * class is the screen: the presence header, the list, the composer, the attach
 * sheet, the press-and-hold recorder, and the permission conversations.
 *
 * Resource discipline, because this screen owns hardware:
 *   - the recorder, the player and the socket are all released in onDestroy, and
 *     the recorder is also released in onStop (a backgrounded app must not keep
 *     the microphone),
 *   - uploads hold a cancel handle and are cancelled with the row,
 *   - every permission is explained in a sheet BEFORE the system prompt, and the
 *     screen keeps working without it.
 */
public class CigramAdsChatActivity extends Activity
        implements CigramAdsApi.Alive, CigramAdsChat.Listener, CigramAdsChatAdapter.Actions {

    private static final int REQ_IMAGE = 4101;
    private static final int REQ_CAMERA = 4102;
    private static final int REQ_VIDEO = 4103;
    private static final int REQ_FILE = 4104;
    private static final int PERM_AUDIO = 4201;
    private static final int PERM_IMAGES = 4202;
    private static final int PERM_CAMERA = 4203;

    private final Handler main = new Handler(Looper.getMainLooper());

    private CigramAdsChat chat;
    private CigramAdsChatAdapter adapter;
    private ListView list;
    private EditText input;
    private TextView sendButton;
    private View micButton;
    private LinearLayout recordingBar;
    private TextView recordingTime;
    private TextView recordingHint;
    private CigramAdsVoice.Waveform recordingWave;
    private LinearLayout replyBar;
    private TextView replyLabel;
    private LinearLayout statusBar;
    private TextView statusText;
    private TextView presenceText;
    private View presenceDot;
    private TextView autoReplyNote;

    private CigramAdsVoice.Recorder recorder;
    private CigramAdsVoice.Player player;
    private CigramAdsMedia.Upload activeUpload;
    private JSONObject uploadingRow;

    private long replyTo = 0L;
    private Uri cameraTarget;
    private boolean loadingOlder;
    private boolean atBottom = true;
    private String pendingOrder;

    @Override public boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    // ------------------------------------------------------------- lifecycle

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().setStatusBarColor(CigramAdsUi.PAGE);
            getWindow().setNavigationBarColor(Color.BLACK);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignored) { }

        if (!CigramUserData.isLoggedIn(this)) {
            askToSignIn();
            return;
        }

        pendingOrder = getIntent() == null ? null : getIntent().getStringExtra("order");

        LinearLayout column = CigramAdsUi.column(this);
        column.setBackgroundColor(CigramAdsUi.PAGE);
        column.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));
        column.addView(buildStatusBar(), new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        list.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        list.setPadding(0, CigramAdsUi.dp(this, 6), 0, CigramAdsUi.dp(this, 6));
        list.setClipToPadding(false);
        adapter = new CigramAdsChatAdapter(this, this);
        list.setAdapter(adapter);
        column.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        column.addView(buildReplyBar(), new LinearLayout.LayoutParams(-1, -2));
        column.addView(buildComposer(), new LinearLayout.LayoutParams(-1, -2));
        setContentView(column);
        try {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } catch (Throwable ignored) { }

        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int scrollState) { }

            @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                atBottom = first + visible >= total - 1;
                if (first == 0 && total > 0 && !loadingOlder && chat != null && chat.hasMore()) {
                    loadingOlder = true;
                    chat.loadOlder(new Runnable() {
                        @Override public void run() {
                            loadingOlder = false;
                        }
                    });
                }
            }
        });

        player = new CigramAdsVoice.Player(new CigramAdsVoice.PlayerListener() {
            @Override public void onProgress(int playingId, long positionMs, long durationMs) {
                if (adapter == null) return;
                float fraction = durationMs > 0L ? (float) positionMs / (float) durationMs : 0f;
                adapter.setPlaying(playingId, fraction);
            }

            @Override public void onFinished(int playingId) {
                if (adapter != null) adapter.setPlaying(-1, 0f);
            }
        });

        chat = new CigramAdsChat(this);
        chat.start(this, this);
    }

    @Override protected void onResume() {
        super.onResume();
        if (chat != null) chat.markRead();
    }

    @Override protected void onStop() {
        // A backgrounded screen must not hold the microphone or keep playing.
        if (recorder != null) {
            recorder.release();
            recorder = null;
            hideRecordingBar();
        }
        if (player != null) player.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (recorder != null) {
            recorder.release();
            recorder = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        if (activeUpload != null) {
            activeUpload.cancel();
            activeUpload = null;
        }
        if (chat != null) {
            chat.stop();
            chat = null;
        }
        super.onDestroy();
    }

    // ----------------------------------------------------------------- chrome

    private View buildHeader() {
        LinearLayout bar = CigramAdsUi.row(this);
        bar.setBackgroundColor(CigramAdsUi.PAGE);
        bar.setMinimumHeight(CigramAdsUi.dp(this, 60));
        bar.setPadding(CigramAdsUi.dp(this, 6), CigramAdsUi.dp(this, 6),
                CigramAdsUi.dp(this, 12), CigramAdsUi.dp(this, 6));

        FrameLayout back = new FrameLayout(this);
        CigramAdsUi.Icon icon = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_CHEVRON_START, CigramAdsUi.TEXT);
        icon.setRotation(180f);
        int inner = CigramAdsUi.dp(this, 22);
        back.addView(icon, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        CigramAdsUi.pressable(back, CigramAdsUi.round(this, Color.TRANSPARENT, 24), CigramAdsUi.ACCENT, 24);
        back.setContentDescription("رجوع");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        int size = CigramAdsUi.dp(this, 48);
        bar.addView(back, new LinearLayout.LayoutParams(size, size));

        int avatar = CigramAdsUi.dp(this, 40);
        bar.addView(CigramAdsUi.iconBox(this, CigramAdsUi.ICON_MEGAPHONE, CigramAdsUi.PRIMARY, 40f),
                new LinearLayout.LayoutParams(avatar, avatar));

        LinearLayout texts = CigramAdsUi.column(this);
        texts.addView(CigramAdsUi.text(this, "إدارة الإعلانات", 16f, CigramAdsUi.TEXT, true),
                new LinearLayout.LayoutParams(-1, -2));
        LinearLayout presenceRow = CigramAdsUi.row(this);
        presenceDot = new View(this);
        presenceDot.setBackground(CigramAdsUi.round(this, CigramAdsUi.DIM, 4));
        presenceRow.addView(presenceDot,
                new LinearLayout.LayoutParams(CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 8)));
        presenceText = CigramAdsUi.text(this, "جارٍ الاتصال…", 12f, CigramAdsUi.MUTED, false);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, -2, 1f);
        pp.setMarginStart(CigramAdsUi.dp(this, 6));
        presenceRow.addView(presenceText, pp);
        texts.addView(presenceRow, CigramAdsUi.lp(this, -1, -2, 0, 2, 0, 0));

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(this, 10));
        bar.addView(texts, tp);

        FrameLayout myAds = new FrameLayout(this);
        CigramAdsUi.Icon chart = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_CHART, CigramAdsUi.ACCENT);
        myAds.addView(chart, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        CigramAdsUi.pressable(myAds, CigramAdsUi.round(this, Color.TRANSPARENT, 24), CigramAdsUi.ACCENT, 24);
        myAds.setContentDescription("إعلاناتي");
        myAds.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openMyAds();
            }
        });
        bar.addView(myAds, new LinearLayout.LayoutParams(size, size));
        return bar;
    }

    private View buildStatusBar() {
        statusBar = CigramAdsUi.column(this);
        statusBar.setVisibility(View.GONE);
        statusText = CigramAdsUi.text(this, "", 12f, CigramAdsUi.WARN, false);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(CigramAdsUi.dp(this, 12), CigramAdsUi.dp(this, 7),
                CigramAdsUi.dp(this, 12), CigramAdsUi.dp(this, 7));
        statusText.setBackgroundColor(CigramAdsUi.alpha(CigramAdsUi.WARN, 0x1F));
        statusBar.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        autoReplyNote = CigramAdsUi.text(this, "", 12f, CigramAdsUi.MUTED, false);
        autoReplyNote.setGravity(Gravity.CENTER);
        autoReplyNote.setPadding(CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 8),
                CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 8));
        autoReplyNote.setVisibility(View.GONE);
        statusBar.addView(autoReplyNote, new LinearLayout.LayoutParams(-1, -2));
        return statusBar;
    }

    private View buildReplyBar() {
        replyBar = CigramAdsUi.row(this);
        replyBar.setVisibility(View.GONE);
        replyBar.setBackgroundColor(CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x1A));
        replyBar.setPadding(CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 8),
                CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 8));
        replyLabel = CigramAdsUi.text(this, "", 12.5f, CigramAdsUi.TEXT, false);
        replyLabel.setMaxLines(1);
        replyBar.addView(replyLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout clear = new FrameLayout(this);
        CigramAdsUi.Icon cross = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_X, CigramAdsUi.MUTED);
        int inner = CigramAdsUi.dp(this, 14);
        clear.addView(cross, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        CigramAdsUi.pressable(clear, CigramAdsUi.round(this, Color.TRANSPARENT, 20), CigramAdsUi.ACCENT, 20);
        clear.setContentDescription("إلغاء الرد");
        clear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                replyTo = 0L;
                replyBar.setVisibility(View.GONE);
            }
        });
        int size = CigramAdsUi.dp(this, 40);
        replyBar.addView(clear, new LinearLayout.LayoutParams(size, size));
        return replyBar;
    }

    private View buildComposer() {
        LinearLayout holder = CigramAdsUi.column(this);
        holder.setBackgroundColor(0xFF071C26);

        recordingBar = CigramAdsUi.row(this);
        recordingBar.setVisibility(View.GONE);
        recordingBar.setPadding(CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 10),
                CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 4));
        View dot = new View(this);
        dot.setBackground(CigramAdsUi.round(this, CigramAdsUi.BAD, 5));
        recordingBar.addView(dot,
                new LinearLayout.LayoutParams(CigramAdsUi.dp(this, 10), CigramAdsUi.dp(this, 10)));
        recordingTime = CigramAdsUi.text(this, "0:00", 13f, CigramAdsUi.TEXT, true);
        LinearLayout.LayoutParams rt = new LinearLayout.LayoutParams(-2, -2);
        rt.setMarginStart(CigramAdsUi.dp(this, 8));
        recordingBar.addView(recordingTime, rt);
        recordingWave = new CigramAdsVoice.Waveform(this);
        recordingWave.setColors(0x44FFFFFF, CigramAdsUi.BAD);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, CigramAdsUi.dp(this, 26), 1f);
        wp.setMarginStart(CigramAdsUi.dp(this, 10));
        wp.setMarginEnd(CigramAdsUi.dp(this, 10));
        recordingBar.addView(recordingWave, wp);
        recordingHint = CigramAdsUi.text(this, "اسحب للإلغاء", 12f, CigramAdsUi.MUTED, false);
        recordingBar.addView(recordingHint, new LinearLayout.LayoutParams(-2, -2));
        holder.addView(recordingBar, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout bar = CigramAdsUi.row(this);
        bar.setPadding(CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 6),
                CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 8));

        FrameLayout attach = new FrameLayout(this);
        CigramAdsUi.Icon plus = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_DOC, CigramAdsUi.MUTED);
        int inner = CigramAdsUi.dp(this, 22);
        attach.addView(plus, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        CigramAdsUi.pressable(attach, CigramAdsUi.round(this, Color.TRANSPARENT, 24), CigramAdsUi.ACCENT, 24);
        attach.setContentDescription("إرفاق ملف أو صورة");
        attach.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openAttachSheet();
            }
        });
        int size = CigramAdsUi.dp(this, 48);
        bar.addView(attach, new LinearLayout.LayoutParams(size, size));

        input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(4);
        CigramUI.styleInput(input, "اكتب رسالتك…");
        input.setMinHeight(CigramAdsUi.dp(this, 48));
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable e) {
                boolean typing = e != null && e.toString().trim().length() > 0;
                if (chat != null) chat.setTyping(typing);
                updateComposerButtons(typing);
            }
        });
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
        ip.setMarginStart(CigramAdsUi.dp(this, 4));
        ip.setMarginEnd(CigramAdsUi.dp(this, 4));
        bar.addView(input, ip);

        micButton = buildMic();
        bar.addView(micButton, new LinearLayout.LayoutParams(size, size));

        sendButton = new TextView(this);
        sendButton.setText("إرسال");
        sendButton.setTextSize(13.5f);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setTypeface(CigramAdsUi.font(this), android.graphics.Typeface.BOLD);
        sendButton.setTextColor(0xFF04161D);
        CigramAdsUi.pressable(sendButton, CigramAdsUi.round(this, CigramAdsUi.ACCENT, 22),
                0xFFFFFFFF, 22);
        sendButton.setVisibility(View.GONE);
        sendButton.setContentDescription("إرسال الرسالة");
        sendButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                sendTyped();
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, size);
        sendButton.setPadding(CigramAdsUi.dp(this, 14), 0, CigramAdsUi.dp(this, 14), 0);
        bar.addView(sendButton, sp);

        holder.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        return holder;
    }

    private void updateComposerButtons(boolean typing) {
        if (sendButton == null || micButton == null) return;
        sendButton.setVisibility(typing ? View.VISIBLE : View.GONE);
        micButton.setVisibility(typing ? View.GONE : View.VISIBLE);
    }

    /** Press and hold to record; drag away from the button to cancel. */
    private View buildMic() {
        FrameLayout mic = new FrameLayout(this);
        final CigramAdsUi.Icon icon = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_SPARK, CigramAdsUi.ACCENT);
        int inner = CigramAdsUi.dp(this, 22);
        mic.addView(icon, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        mic.setBackground(CigramAdsUi.round(this, CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x24), 24));
        mic.setContentDescription("اضغط مطولاً للتسجيل، اسحب للإلغاء");
        mic.setClickable(true);
        mic.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private boolean cancelled;

            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = event.getRawX();
                        cancelled = false;
                        v.setScaleX(1.15f);
                        v.setScaleY(1.15f);
                        beginRecording();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (recorder == null || cancelled) return true;
                        float travel = Math.abs(event.getRawX() - startX);
                        float limit = CigramAdsUi.dp(CigramAdsChatActivity.this, 90);
                        if (recordingHint != null) {
                            recordingHint.setText(travel > limit / 2f ? "اترك للإلغاء" : "اسحب للإلغاء");
                            recordingHint.setTextColor(travel > limit / 2f
                                    ? CigramAdsUi.BAD : CigramAdsUi.MUTED);
                        }
                        if (travel > limit) {
                            cancelled = true;
                            v.setScaleX(1f);
                            v.setScaleY(1f);
                            cancelRecording();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setScaleX(1f);
                        v.setScaleY(1f);
                        if (!cancelled) finishRecording();
                        return true;
                    default:
                        return false;
                }
            }
        });
        return mic;
    }

    // ------------------------------------------------------------ chat events

    @Override public void onChanged() {
        if (!alive() || chat == null || adapter == null) return;
        List<JSONObject> messages = chat.visible();
        adapter.submit(messages);
        if (atBottom) {
            list.post(new Runnable() {
                @Override public void run() {
                    if (list != null && adapter != null) list.setSelection(adapter.getCount() - 1);
                }
            });
        }
        if (pendingOrder != null) {
            // The calculator handed us a request: send it as the opening card.
            try {
                chat.sendOrder(new JSONObject(pendingOrder));
            } catch (Throwable ignored) { }
            pendingOrder = null;
        }
        chat.markRead();
    }

    @Override public void onPresence(JSONObject presence) {
        if (!alive() || presence == null) return;
        boolean typing = presence.optBoolean("typing_admin", false);
        String status = presence.optString("admin_status", "offline");
        boolean online = "online".equals(status);
        int color = online ? CigramAdsUi.GOOD
                : ("away".equals(status) ? CigramAdsUi.WARN : CigramAdsUi.DIM);
        presenceDot.setBackground(CigramAdsUi.round(this, color, 4));

        if (typing) {
            presenceText.setText("يكتب الآن…");
            presenceText.setTextColor(CigramAdsUi.ACCENT);
        } else if (online) {
            presenceText.setText("متصل الآن");
            presenceText.setTextColor(CigramAdsUi.GOOD);
        } else if ("away".equals(status)) {
            presenceText.setText("خارج ساعات العمل");
            presenceText.setTextColor(CigramAdsUi.WARN);
        } else {
            long lastSeen = presence.optLong("admin_last_seen", 0L);
            presenceText.setText(lastSeen > 0L
                    ? "غير متصل · آخر ظهور " + ago(lastSeen)
                    : "غير متصل");
            presenceText.setTextColor(CigramAdsUi.MUTED);
        }

        String auto = presence.optString("auto_reply", "");
        String note = presence.optString("offline_note", "");
        if (!online && auto.length() > 0) {
            autoReplyNote.setVisibility(View.VISIBLE);
            autoReplyNote.setText(auto + (note.length() > 0 ? "\n" + note : ""));
            statusBar.setVisibility(View.VISIBLE);
        } else {
            autoReplyNote.setVisibility(View.GONE);
            if (statusText.getVisibility() != View.VISIBLE) statusBar.setVisibility(View.GONE);
        }
    }

    @Override public void onMode(int mode) {
        if (!alive()) return;
        if (mode == CigramAdsChat.MODE_OFFLINE) {
            statusText.setVisibility(View.VISIBLE);
            statusBar.setVisibility(View.VISIBLE);
            statusText.setText(chat != null && chat.pendingCount() > 0
                    ? "لا يوجد اتصال — رسائلك محفوظة وسترسل تلقائياً"
                    : "لا يوجد اتصال");
        } else {
            statusText.setVisibility(View.GONE);
            if (autoReplyNote.getVisibility() != View.VISIBLE) statusBar.setVisibility(View.GONE);
        }
    }

    @Override public void onError(String message) {
        toast(message);
    }

    private static String ago(long at) {
        long minutes = Math.max(0L, (System.currentTimeMillis() - at) / 60000L);
        if (minutes < 1L) return "قبل لحظات";
        if (minutes < 60L) return "منذ " + minutes + " دقيقة";
        long hours = minutes / 60L;
        if (hours < 24L) return "منذ " + hours + " ساعة";
        return "منذ " + (hours / 24L) + " يوم";
    }

    // --------------------------------------------------------- list callbacks

    @Override public void onReply(JSONObject message) {
        if (message == null) return;
        replyTo = message.optLong("seq", 0L);
        if (replyTo <= 0L) return;
        replyBar.setVisibility(View.VISIBLE);
        String text = message.optString("text", "");
        replyLabel.setText("رد على: " + (text.length() > 48 ? text.substring(0, 48) + "…" : text));
        input.requestFocus();
    }

    @Override public void onLongPress(final JSONObject message, View anchor) {
        if (message == null) return;
        final boolean mine = !"admin".equals(message.optString("from", "user"));
        final long seq = message.optLong("seq", 0L);
        final String text = message.optString("text", "");

        LinearLayout sheet = CigramAdsUi.column(this);
        final Dialog dialog = sheetDialog(sheet);

        if (seq > 0L) {
            sheet.addView(sheetRow("رد على هذه الرسالة", CigramAdsUi.ICON_CHAT, new Runnable() {
                @Override public void run() {
                    dialog.dismiss();
                    onReply(message);
                }
            }), new LinearLayout.LayoutParams(-1, -2));
        }
        if (text.length() > 0) {
            sheet.addView(sheetRow("نسخ النص", CigramAdsUi.ICON_DOC, new Runnable() {
                @Override public void run() {
                    dialog.dismiss();
                    copy(text);
                }
            }), new LinearLayout.LayoutParams(-1, -2));
        }
        if (seq > 0L) {
            sheet.addView(sheetRow("حذف عندي", CigramAdsUi.ICON_CROSS, new Runnable() {
                @Override public void run() {
                    dialog.dismiss();
                    confirmDelete(seq);
                }
            }), new LinearLayout.LayoutParams(-1, -2));
        } else if (mine) {
            sheet.addView(sheetRow("إلغاء الإرسال", CigramAdsUi.ICON_CROSS, new Runnable() {
                @Override public void run() {
                    dialog.dismiss();
                    if (chat != null) chat.drop(message);
                }
            }), new LinearLayout.LayoutParams(-1, -2));
        }
        dialog.show();
        stretch(dialog);
    }

    private void confirmDelete(final long seq) {
        CigramUI.sheet(this)
                .icon(CigramUI.ICON_TRASH, CigramUI.DANGER)
                .title("حذف الرسالة عندك")
                .message("ستختفي من جهازك فقط. نسخة الإدارة تبقى كما هي، لأن المحادثة سجل للاتفاق بينكما.")
                .danger("حذف", new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        if (chat != null) chat.deleteForMe(seq);
                        return false;
                    }
                })
                .secondary("إلغاء", null)
                .show();
    }

    @Override public void onOpenMedia(JSONObject message) {
        JSONObject media = message == null ? null : message.optJSONObject("media");
        if (media == null) return;
        String url = media.optString("url", "");
        if (url.length() == 0) return;
        final String full = url.startsWith("http") ? url : CigramAdsApi.BASE + url;
        String kind = message.optString("kind", "");
        if ("video".equals(kind)) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.parse(full), "video/*");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Throwable error) {
                toast("لا يوجد تطبيق يستطيع تشغيل هذا الفيديو.");
            }
            return;
        }
        showImage(full);
    }

    private void showImage(String url) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xEE000000);
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        frame.addView(view, new FrameLayout.LayoutParams(-1, -1));
        CigramAdsMedia.loadInto(this, url, view,
                getResources().getDisplayMetrics().widthPixels);
        frame.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        dialog.setContentView(frame);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        try {
            dialog.show();
            if (window != null) window.setLayout(-1, -1);
        } catch (Throwable ignored) { }
    }

    @Override public void onPlayAudio(JSONObject message, int rowId) {
        JSONObject media = message == null ? null : message.optJSONObject("media");
        if (media == null || player == null) return;
        String url = media.optString("url", "");
        if (url.length() == 0) {
            // not uploaded yet: play the local file we just recorded
            String local = message.optString("local_path", "");
            if (local.length() > 0) player.toggle(this, rowId, local);
            return;
        }
        player.toggle(this, rowId, url.startsWith("http") ? url : CigramAdsApi.BASE + url);
    }

    @Override public void onQuoteAction(final JSONObject message, final String action) {
        if (chat == null || message == null) return;
        final long seq = message.optLong("seq", 0L);
        if (seq <= 0L) return;
        String title = "accepted".equals(action) ? "قبول العرض"
                : ("rejected".equals(action) ? "رفض العرض" : "طلب تفاوض");
        String body = "accepted".equals(action)
                ? "سنبدأ تنفيذ الحملة بعد تأكيد الدفع. هل توافق على هذا السعر؟"
                : ("rejected".equals(action)
                        ? "سيُسجَّل رفضك لهذا العرض، ويمكن للإدارة إرسال عرض آخر."
                        : "سنبلغ الإدارة أنك ترغب بالتفاوض على السعر.");
        CigramUI.sheet(this)
                .icon("accepted".equals(action) ? CigramUI.ICON_CHECK : CigramUI.ICON_INFO,
                        "accepted".equals(action) ? CigramUI.GREEN : CigramUI.TEAL)
                .title(title)
                .message(body)
                .primary("تأكيد", "accepted".equals(action) ? CigramUI.GREEN : CigramUI.TEAL,
                        new CigramUI.Click() {
                            @Override public boolean onClick(CigramUI.Sheet s) {
                                chat.respondToQuote(seq, action, null);
                                return false;
                            }
                        })
                .secondary("إلغاء", null)
                .show();
    }

    @Override public void onRetry(JSONObject pending) {
        if (chat == null) return;
        if (pending != null && pending.optBoolean("local_permanent", false)) {
            toast(pending.optString("local_error", "لا يمكن إرسال هذه الرسالة."));
            return;
        }
        chat.retryFailed();
    }

    @Override public void onOpenFile(JSONObject message) {
        final JSONObject media = message == null ? null : message.optJSONObject("media");
        if (media == null) return;
        String url = media.optString("url", "");
        if (url.length() == 0) return;
        final String name = media.optString("name", "ملف");
        toast("جارٍ تنزيل الملف…");
        CigramAdsMedia.fetchToCache(this, url.startsWith("http") ? url : CigramAdsApi.BASE + url, name,
                new CigramAdsMedia.Callback() {
                    @Override public void ready(File file) {
                        openLocalFile(file, media.optString("mime", ""), name);
                    }

                    @Override public void failed(String message) {
                        toast(message);
                    }
                });
    }

    /**
     * Documents open inside the app: a PDF through the framework's PdfRenderer, an
     * image through the image viewer. Handing the file to another app would need a
     * FileProvider, which needs an res/xml resource a Sketchware project cannot
     * add — and it is not necessary, because the Worker only accepts PDFs and
     * images as chat documents.
     */
    private void openLocalFile(File file, String mime, String name) {
        if (file == null || !file.exists()) {
            toast("تعذر تنزيل الملف.");
            return;
        }
        String type = mime == null ? "" : mime.toLowerCase(java.util.Locale.US);
        if (type.startsWith("image/")) {
            showImage(Uri.fromFile(file).toString());
            return;
        }
        if (type.indexOf("pdf") >= 0 || name.toLowerCase(java.util.Locale.US).endsWith(".pdf")) {
            CigramAdsDocViewer.show(this, file, name);
            return;
        }
        CigramAdsDocViewer.show(this, file, name);
    }

    // ---------------------------------------------------------------- sending

    private void sendTyped() {
        if (chat == null || input == null) return;
        String text = input.getText() == null ? "" : input.getText().toString().trim();
        if (text.length() == 0) return;
        chat.sendText(text, replyTo);
        input.setText("");
        replyTo = 0L;
        replyBar.setVisibility(View.GONE);
        chat.setTyping(false);
        atBottom = true;
    }

    // ----------------------------------------------------------- attachments

    private void openAttachSheet() {
        LinearLayout sheet = CigramAdsUi.column(this);
        final Dialog dialog = sheetDialog(sheet);
        sheet.addView(CigramAdsUi.title(this, "أرفق"), CigramAdsUi.lp(this, -1, -2, 4, 0, 4, 8));
        sheet.addView(sheetRow("صورة من المعرض", CigramAdsUi.ICON_DOC, new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                pickImage();
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(sheetRow("التقاط صورة بالكاميرا", CigramAdsUi.ICON_EYE, new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                takePhoto();
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(sheetRow("فيديو", CigramAdsUi.ICON_SPARK, new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                pickVideo();
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        sheet.addView(sheetRow("ملف (PDF أو مستند)", CigramAdsUi.ICON_DOC, new Runnable() {
            @Override public void run() {
                dialog.dismiss();
                pickFile();
            }
        }), new LinearLayout.LayoutParams(-1, -2));
        dialog.show();
        stretch(dialog);
    }

    private void pickImage() {
        if (!ensureImagePermission()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_IMAGE);
        } catch (Throwable error) {
            toast("لا يوجد تطبيق لاختيار الصور.");
        }
    }

    private void pickVideo() {
        if (!ensureImagePermission()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("video/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_VIDEO);
        } catch (Throwable error) {
            toast("لا يوجد تطبيق لاختيار الفيديو.");
        }
    }

    private void pickFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"application/pdf", "image/*"});
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQ_FILE);
        } catch (Throwable error) {
            toast("لا يوجد تطبيق لاختيار الملفات.");
        }
    }

    private void takePhoto() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            explainThenAsk("الكاميرا", "نطلب إذن الكاميرا لتصوير تصميمك أو منتجك وإرساله في المحادثة. "
                    + "يمكنك دائماً اختيار صورة من المعرض بدلاً من ذلك.",
                    Manifest.permission.CAMERA, PERM_CAMERA);
            return;
        }
        try {
            android.content.ContentValues values = new android.content.ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "cigram-" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cameraTarget = getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (cameraTarget == null) {
                toast("تعذر تجهيز الكاميرا.");
                return;
            }
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraTarget);
            startActivityForResult(intent, REQ_CAMERA);
        } catch (Throwable error) {
            toast("لا توجد كاميرا متاحة.");
        }
    }

    private boolean ensureImagePermission() {
        String permission = Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true;
        explainThenAsk("الصور", "نطلب الإذن بقراءة الصور لاختيار تصميم إعلانك وإرساله. "
                        + "لا نقرأ شيئاً آخر من جهازك، ولن نرفع أي صورة لم تخترها أنت.",
                permission, PERM_IMAGES);
        return false;
    }

    /** Explains WHY before the system dialog, as the policy requires. */
    private void explainThenAsk(String what, String why, final String permission, final int code) {
        CigramUI.sheet(this)
                .icon(CigramUI.ICON_LOCK, CigramUI.TEAL)
                .title("إذن " + what)
                .message(why)
                .primary("متابعة", CigramUI.TEAL, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet s) {
                        try {
                            requestPermissions(new String[]{permission}, code);
                        } catch (Throwable ignored) { }
                        return false;
                    }
                })
                .secondary("ليس الآن", null)
                .show();
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        boolean granted = results != null && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == PERM_AUDIO) {
            if (granted) toast("اضغط مطولاً على زر التسجيل للبدء.");
            else toast("بدون إذن الميكروفون لا يمكن التسجيل. يمكنك الكتابة أو إرسال صورة.");
        } else if (code == PERM_IMAGES) {
            if (granted) pickImage();
            else toast("بدون إذن الصور لا يمكن اختيار صورة. يمكنك التصوير بالكاميرا أو الكتابة.");
        } else if (code == PERM_CAMERA) {
            if (granted) takePhoto();
            else toast("بدون إذن الكاميرا لا يمكن التصوير. يمكنك اختيار صورة من المعرض.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK) {
            if (request == REQ_CAMERA && cameraTarget != null) {
                try {
                    getContentResolver().delete(cameraTarget, null, null);
                } catch (Throwable ignored) { }
                cameraTarget = null;
            }
            return;
        }
        if (request == REQ_CAMERA) {
            if (cameraTarget != null) uploadImage(cameraTarget);
            cameraTarget = null;
            return;
        }
        Uri uri = data == null ? null : data.getData();
        if (uri == null) return;
        if (request == REQ_IMAGE) uploadImage(uri);
        else if (request == REQ_VIDEO) uploadVideo(uri);
        else if (request == REQ_FILE) uploadFile(uri);
    }

    // ------------------------------------------------------------- uploading

    private void uploadImage(final Uri uri) {
        final JSONObject row = showUploadRow("image", "جارٍ تجهيز الصورة…");
        activeUpload = CigramAdsMedia.uploadImage(this, uri, new CigramAdsMedia.Progress() {
            @Override public void progress(int percent) {
                setRowProgress(row, percent);
            }

            @Override public void done(String mediaId, JSONObject info) {
                removeUploadRow(row);
                activeUpload = null;
                if (chat == null) return;
                chat.sendMedia("image", mediaId, "", "", info.optInt("width"), info.optInt("height"),
                        0, "", replyTo);
                clearReply();
            }

            @Override public void failed(String message) {
                removeUploadRow(row);
                activeUpload = null;
                toast(message);
            }
        });
    }

    private void uploadVideo(final Uri uri) {
        final JSONObject row = showUploadRow("video", "جارٍ رفع الفيديو…");
        activeUpload = CigramAdsMedia.uploadVideo(this, uri, new CigramAdsMedia.Progress() {
            @Override public void progress(int percent) {
                setRowProgress(row, percent);
            }

            @Override public void done(String mediaId, JSONObject info) {
                removeUploadRow(row);
                activeUpload = null;
                if (chat == null) return;
                chat.sendMedia("video", mediaId, info.optString("thumb_id", ""), "",
                        info.optInt("width"), info.optInt("height"),
                        (int) info.optLong("duration_ms", 0L), "", replyTo);
                clearReply();
            }

            @Override public void failed(String message) {
                removeUploadRow(row);
                activeUpload = null;
                toast(message);
            }
        });
    }

    private void uploadFile(final Uri uri) {
        final JSONObject row = showUploadRow("file", "جارٍ رفع الملف…");
        activeUpload = CigramAdsMedia.uploadFile(this, uri, new CigramAdsMedia.Progress() {
            @Override public void progress(int percent) {
                setRowProgress(row, percent);
            }

            @Override public void done(String mediaId, JSONObject info) {
                removeUploadRow(row);
                activeUpload = null;
                if (chat == null) return;
                chat.sendMedia("file", mediaId, "", "", 0, 0, 0, info.optString("name", ""), replyTo);
                clearReply();
            }

            @Override public void failed(String message) {
                removeUploadRow(row);
                activeUpload = null;
                toast(message);
            }
        });
    }

    /** A progress sheet with a working cancel, shown while an upload runs. */
    private JSONObject showUploadRow(String kind, String label) {
        uploadingRow = new JSONObject();
        try {
            uploadingRow.put("kind", kind);
            uploadingRow.put("label", label);
        } catch (Throwable ignored) { }
        showUploadSheet(label);
        return uploadingRow;
    }

    private Dialog uploadDialog;
    private TextView uploadPercent;
    private android.widget.ProgressBar uploadBar;

    private void showUploadSheet(String label) {
        LinearLayout sheet = CigramAdsUi.column(this);
        uploadDialog = sheetDialog(sheet);
        sheet.addView(CigramAdsUi.title(this, label), CigramAdsUi.lp(this, -1, -2, 4, 0, 4, 10));
        uploadBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        uploadBar.setMax(100);
        sheet.addView(uploadBar, new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 8)));
        uploadPercent = CigramAdsUi.muted(this, "0%");
        sheet.addView(uploadPercent, CigramAdsUi.lp(this, -1, -2, 4, 8, 4, 0));
        TextView cancel = CigramAdsUi.ghostButton(this, "إلغاء الرفع");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (activeUpload != null) activeUpload.cancel();
                activeUpload = null;
                removeUploadRow(uploadingRow);
                toast("أُلغي الرفع.");
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 48));
        cp.topMargin = CigramAdsUi.dp(this, 14);
        sheet.addView(cancel, cp);
        uploadDialog.setCancelable(false);
        uploadDialog.show();
        stretch(uploadDialog);
    }

    private void setRowProgress(JSONObject row, int percent) {
        if (uploadBar != null) uploadBar.setProgress(percent);
        if (uploadPercent != null) uploadPercent.setText(percent + "%");
    }

    private void removeUploadRow(JSONObject row) {
        uploadingRow = null;
        if (uploadDialog != null) {
            try {
                uploadDialog.dismiss();
            } catch (Throwable ignored) { }
            uploadDialog = null;
        }
        uploadBar = null;
        uploadPercent = null;
    }

    private void clearReply() {
        replyTo = 0L;
        if (replyBar != null) replyBar.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------- recording

    private void beginRecording() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            explainThenAsk("الميكروفون",
                    "نطلب إذن الميكروفون لتسجيل رسالة صوتية ترسلها لإدارة الإعلانات. "
                            + "لا نسجّل شيئاً إلا أثناء ضغطك على زر التسجيل، والتطبيق يعمل بدون هذا الإذن.",
                    Manifest.permission.RECORD_AUDIO, PERM_AUDIO);
            return;
        }
        if (recorder != null) return;
        recorder = new CigramAdsVoice.Recorder(this, new CigramAdsVoice.RecorderListener() {
            private final java.util.ArrayList<Float> live = new java.util.ArrayList<Float>();

            @Override public void onTick(long elapsedMs, float level) {
                if (recordingTime != null) recordingTime.setText(CigramAdsMedia.clockOf(elapsedMs));
                live.add(Float.valueOf(level));
                if (live.size() > 120) live.remove(0);
                if (recordingWave != null) {
                    float[] values = new float[live.size()];
                    for (int i = 0; i < values.length; i++) values[i] = live.get(i).floatValue();
                    recordingWave.setLevels(values);
                }
            }

            @Override public void onStopped(File file, long durationMs, float[] waveform) {
                hideRecordingBar();
                recorder = null;
                sendVoice(file, durationMs, waveform);
            }

            @Override public void onFailed(String message) {
                hideRecordingBar();
                recorder = null;
                toast(message);
            }
        });
        if (recorder.start()) {
            showRecordingBar();
        } else {
            recorder = null;
        }
    }

    private void finishRecording() {
        if (recorder == null) return;
        recorder.stop(true);
    }

    private void cancelRecording() {
        if (recorder == null) return;
        CigramAdsVoice.Recorder current = recorder;
        recorder = null;
        current.stop(false);
        hideRecordingBar();
        toast("أُلغي التسجيل.");
    }

    private void showRecordingBar() {
        if (recordingBar == null) return;
        recordingBar.setVisibility(View.VISIBLE);
        recordingHint.setText("اسحب للإلغاء");
        recordingHint.setTextColor(CigramAdsUi.MUTED);
        recordingTime.setText("0:00");
    }

    private void hideRecordingBar() {
        if (recordingBar != null) recordingBar.setVisibility(View.GONE);
        if (recordingWave != null) recordingWave.setLevels(new float[0]);
    }

    private void sendVoice(final File file, final long durationMs, final float[] waveform) {
        if (file == null || chat == null) return;
        final JSONObject row = showUploadRow("audio", "جارٍ إرسال التسجيل…");
        activeUpload = CigramAdsMedia.uploadAudio(this, file, (int) durationMs,
                new CigramAdsMedia.Progress() {
                    @Override public void progress(int percent) {
                        setRowProgress(row, percent);
                    }

                    @Override public void done(String mediaId, JSONObject info) {
                        removeUploadRow(row);
                        activeUpload = null;
                        if (chat == null) return;
                        chat.sendMedia("audio", mediaId, "", "", 0, 0, (int) durationMs, "",
                                CigramAdsVoice.encode(waveform), replyTo);
                        clearReply();
                        file.delete();
                    }

                    @Override public void failed(String message) {
                        removeUploadRow(row);
                        activeUpload = null;
                        toast(message);
                    }
                });
    }

    // -------------------------------------------------------------- helpers

    private Dialog sheetDialog(LinearLayout sheet) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        sheet.setBackground(CigramAdsUi.round(this, CigramAdsUi.CARD, CigramAdsUi.STROKE, 24));
        int p = CigramAdsUi.dp(this, 16);
        sheet.setPadding(p, p, p, CigramAdsUi.dp(this, 20));
        dialog.setContentView(sheet);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.68f;
            attributes.gravity = Gravity.BOTTOM;
            window.setAttributes(attributes);
        }
        return dialog;
    }

    private void stretch(Dialog dialog) {
        Window window = dialog == null ? null : dialog.getWindow();
        if (window != null) window.setLayout(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View sheetRow(String label, int icon, final Runnable action) {
        LinearLayout row = CigramAdsUi.row(this);
        row.setMinimumHeight(CigramAdsUi.dp(this, 56));
        int p = CigramAdsUi.dp(this, 10);
        row.setPadding(p, p, p, p);
        CigramAdsUi.pressable(row, CigramAdsUi.round(this, CigramAdsUi.CARD_SOFT, CigramAdsUi.STROKE, 14),
                CigramAdsUi.ACCENT, 14);
        int size = CigramAdsUi.dp(this, 34);
        row.addView(CigramAdsUi.iconBox(this, icon, CigramAdsUi.ACCENT, 34f),
                new LinearLayout.LayoutParams(size, size));
        TextView text = CigramAdsUi.text(this, label, 14.5f, CigramAdsUi.TEXT, false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(this, 10));
        row.addView(text, tp);
        row.setContentDescription(label);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
            }
        });
        LinearLayout wrap = CigramAdsUi.column(this);
        wrap.addView(row, CigramAdsUi.lp(this, -1, -2, 0, 4, 0, 4));
        return wrap;
    }

    private void copy(String text) {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cigram", text));
            toast("تم النسخ.");
        } catch (Throwable ignored) {
            toast("تعذر النسخ.");
        }
    }

    private void openMyAds() {
        try {
            startActivity(new Intent(this, CigramMyAdsActivity.class));
        } catch (Throwable error) {
            toast("تعذر فتح «إعلاناتي».");
        }
    }

    private void askToSignIn() {
        CigramUI.sheet(this)
                .icon(CigramUI.ICON_USER, CigramUI.TEAL)
                .cancelable(false)
                .title("تحتاج حساباً للمحادثة")
                .message("المحادثة مرتبطة بحسابك حتى تصلك الردود وتتابع حملاتك. سجّل الدخول أولاً.")
                .primary("حسناً", CigramUI.TEAL, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet sheet) {
                        finish();
                        return false;
                    }
                })
                .show();
    }

    private void toast(String message) {
        if (message == null || message.length() == 0 || !alive()) return;
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }
}
