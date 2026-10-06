package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * "أعلن هنا" — one vertical page, seven sections:
 *   1 hero with live numbers       2 the placements             3 cost calculator
 *   4 accepted vs. rejected        5 policies (accordion)       6 FAQ (accordion)
 *   7 contact, unlocked only after the policies are accepted
 *
 * Every word, price and policy on this page is served by GET /ads/config, so the
 * admin changes them without an app update. The last good copy is cached, so the
 * page also opens with no connection at all.
 */
public class CigramAdvertiseActivity extends Activity implements CigramAdsApi.Alive {

    private final Handler main = new Handler(Looper.getMainLooper());

    private FrameLayout rootFrame;
    private ScrollView scroll;
    private LinearLayout content;
    private LinearLayout footer;
    private TextView contactButton;
    private LinearLayout consentRow;
    private CigramAdsUi.Icon consentTick;

    private JSONObject config;
    private CigramAdsCalculator calculator;
    private View calculatorAnchor;

    private boolean accepted = false;
    private boolean reachedEnd = false;
    private boolean offlineCopy = false;
    private final List<View> revealables = new ArrayList<View>();

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

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(CigramAdsUi.PAGE);

        LinearLayout column = CigramAdsUi.column(this);
        column.addView(buildToolbar(), new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(false);
        content = CigramAdsUi.column(this);
        content.setPadding(0, CigramAdsUi.dp(this, 8), 0, CigramAdsUi.dp(this, 120));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        rootFrame.addView(column, new FrameLayout.LayoutParams(-1, -1));
        rootFrame.addView(buildFooter(), new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        setContentView(rootFrame);
        try {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } catch (Throwable ignored) { }

        scroll.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        showSkeleton();
        load();
    }

    @Override protected void onDestroy() {
        try {
            if (scroll != null) scroll.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        } catch (Throwable ignored) { }
        if (calculator != null) calculator.release();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final android.view.ViewTreeObserver.OnScrollChangedListener scrollListener =
            new android.view.ViewTreeObserver.OnScrollChangedListener() {
                @Override public void onScrollChanged() {
                    if (!alive() || scroll == null) return;
                    revealVisible();
                    checkReachedEnd();
                }
            };

    // --------------------------------------------------------------- chrome

    private View buildToolbar() {
        LinearLayout bar = CigramAdsUi.row(this);
        bar.setBackgroundColor(CigramAdsUi.PAGE);
        bar.setMinimumHeight(CigramAdsUi.dp(this, 56));
        bar.setPadding(CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 6),
                CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 6));

        FrameLayout back = new FrameLayout(this);
        CigramAdsUi.Icon icon = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_CHEVRON_START, CigramAdsUi.TEXT);
        icon.setRotation(180f); // the chevron points back, the other way from a list row
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

        TextView title = CigramAdsUi.text(this, "أعلن هنا", 18f, CigramAdsUi.TEXT, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(this, 6));
        bar.addView(title, tp);

        return bar;
    }

    /** Sticky footer: the consent row and the main contact button. */
    private View buildFooter() {
        footer = CigramAdsUi.column(this);
        footer.setBackground(CigramAdsUi.round(this, 0xF205141F, CigramAdsUi.STROKE, 0));
        footer.setPadding(CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 10),
                CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 14));
        footer.setElevation(CigramAdsUi.dp(this, 10));

        consentRow = CigramAdsUi.row(this);
        consentRow.setMinimumHeight(CigramAdsUi.dp(this, 48));
        consentRow.setPadding(CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 6),
                CigramAdsUi.dp(this, 8), CigramAdsUi.dp(this, 6));
        consentTick = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_CHECK, CigramAdsUi.DIM);
        consentRow.addView(consentTick,
                new LinearLayout.LayoutParams(CigramAdsUi.dp(this, 24), CigramAdsUi.dp(this, 24)));
        TextView consentText = CigramAdsUi.text(this,
                "قرأت وأوافق على السياسات والشروط", 13.5f, 0xDDFFFFFF, false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        cp.setMarginStart(CigramAdsUi.dp(this, 10));
        consentRow.addView(consentText, cp);
        CigramAdsUi.pressable(consentRow, CigramAdsUi.round(this, Color.TRANSPARENT, 12),
                CigramAdsUi.ACCENT, 12);
        consentRow.setContentDescription("خانة الموافقة على السياسات والشروط");
        consentRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                toggleConsent();
            }
        });
        footer.addView(consentRow, new LinearLayout.LayoutParams(-1, -2));

        contactButton = CigramAdsUi.primaryButton(this, "تواصل معنا", CigramAdsUi.PRIMARY);
        contactButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!accepted) {
                    nudgeConsent();
                    return;
                }
                openContactSheet(null);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 52));
        bp.topMargin = CigramAdsUi.dp(this, 8);
        footer.addView(contactButton, bp);

        updateFooterState();
        return footer;
    }

    private void updateFooterState() {
        if (contactButton == null) return;
        boolean enabled = accepted;
        CigramAdsUi.setEnabled(contactButton, enabled, "اقرأ السياسات أولاً ثم وافق عليها");
        contactButton.setText(enabled ? "تواصل معنا" : "اقرأ السياسات أولاً");
        contactButton.setBackground(CigramAdsUi.ripple(this,
                enabled ? CigramAdsUi.round(this, CigramAdsUi.PRIMARY, 15)
                        : CigramAdsUi.round(this, 0xFF2B3B44, CigramAdsUi.STROKE, 15),
                0xFFFFFFFF, 15));
        contactButton.setTextColor(enabled ? CigramAdsUi.TEXT : 0xFF9FB0B8);
        if (consentTick != null) {
            consentTick.set(CigramAdsUi.ICON_CHECK, accepted ? CigramAdsUi.GOOD : CigramAdsUi.DIM);
        }
        if (consentRow != null) {
            // The box stays tappable before the end of the page, but says why it is early.
            consentRow.setAlpha(reachedEnd || accepted ? 1f : 0.65f);
        }
    }

    private void toggleConsent() {
        if (!reachedEnd && !accepted) {
            toast("اقرأ الصفحة حتى النهاية أولاً.");
            smoothScrollToBottom();
            return;
        }
        accepted = !accepted;
        int version = config == null ? 0 : config.optInt("policy_version", 0);
        if (accepted) {
            CigramAdsApi.rememberAcceptance(this, version);
            // Mirrored on the server when signed in; a guest's acceptance is local
            // until they sign in to open the chat, where it is sent again.
            if (CigramUserData.isLoggedIn(this)) {
                CigramAdsApi.sendConsent(this, version, this, null);
            }
        }
        updateFooterState();
    }

    private void nudgeConsent() {
        toast(reachedEnd ? "وافق على السياسات والشروط للمتابعة."
                : "اقرأ الصفحة حتى النهاية ثم وافق على السياسات.");
        if (!reachedEnd) {
            smoothScrollToBottom();
            return;
        }
        if (consentRow == null) return;
        consentRow.animate().translationX(CigramAdsUi.dp(this, 6)).setDuration(70L)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        if (!alive() || consentRow == null) return;
                        consentRow.animate().translationX(-CigramAdsUi.dp(CigramAdvertiseActivity.this, 6))
                                .setDuration(70L).withEndAction(new Runnable() {
                                    @Override public void run() {
                                        if (!alive() || consentRow == null) return;
                                        consentRow.animate().translationX(0f).setDuration(70L).start();
                                    }
                                }).start();
                    }
                }).start();
    }

    private void smoothScrollToBottom() {
        if (scroll == null) return;
        scroll.post(new Runnable() {
            @Override public void run() {
                if (!alive() || scroll == null) return;
                scroll.smoothScrollTo(0, content.getHeight());
            }
        });
    }

    // ----------------------------------------------------------------- load

    private void showSkeleton() {
        content.removeAllViews();
        content.addView(CigramAdsUi.skeletonCard(this, 3), CigramAdsUi.lp(this, -1, -2, 16, 8, 16, 0));
        content.addView(CigramAdsUi.skeletonCard(this, 2), CigramAdsUi.lp(this, -1, -2, 16, 12, 16, 0));
        content.addView(CigramAdsUi.skeletonCard(this, 4), CigramAdsUi.lp(this, -1, -2, 16, 12, 16, 0));
    }

    private void load() {
        JSONObject cached = CigramAdsApi.cachedConfig(this);
        if (cached != null) {
            config = cached;
            render();
        }
        CigramAdsApi.loadConfig(this, this, new CigramAdsApi.Callback() {
            @Override public void done(CigramAdsApi.Result result) {
                if (!result.ok()) {
                    if (config == null) showLoadError(result.error);
                    return;
                }
                offlineCopy = result.fromCache;
                config = result.data();
                render();
            }
        });
    }

    private void showLoadError(final String message) {
        content.removeAllViews();
        content.addView(CigramAdsUi.errorState(this, message == null
                ? "تعذر تحميل صفحة الإعلانات." : message, new Runnable() {
            @Override public void run() {
                showSkeleton();
                load();
            }
        }), CigramAdsUi.lp(this, -1, -2, 16, 24, 16, 0));
    }

    // --------------------------------------------------------------- render

    private void render() {
        if (!alive() || config == null) return;
        int scrollY = scroll == null ? 0 : scroll.getScrollY();
        content.removeAllViews();
        revealables.clear();

        if (offlineCopy) content.addView(offlineBanner(), CigramAdsUi.lp(this, -1, -2, 16, 4, 16, 8));

        add(buildHero());
        add(buildPlacements());
        calculatorAnchor = buildCalculator();
        add(calculatorAnchor);
        add(buildRules());
        add(buildPolicies());
        add(buildFaq());
        add(buildContactSection());

        int version = config.optInt("policy_version", 0);
        accepted = CigramAdsApi.acceptedLocally(this, version);
        updateFooterState();

        final int restore = scrollY;
        scroll.post(new Runnable() {
            @Override public void run() {
                if (!alive()) return;
                if (restore > 0) scroll.scrollTo(0, restore);
                revealVisible();
                checkReachedEnd();
            }
        });
    }

    private void add(View section) {
        if (section == null) return;
        LinearLayout.LayoutParams lp = CigramAdsUi.lp(this, -1, -2, 16, 0, 16, 26);
        content.addView(section, lp);
        revealables.add(section);
        section.setAlpha(0f);
    }

    private View offlineBanner() {
        LinearLayout box = CigramAdsUi.row(this);
        box.setBackground(CigramAdsUi.round(this, CigramAdsUi.alpha(CigramAdsUi.WARN, 0x1A),
                CigramAdsUi.alpha(CigramAdsUi.WARN, 0x55), 12));
        int p = CigramAdsUi.dp(this, 10);
        box.setPadding(p, p, p, p);
        box.addView(CigramAdsUi.text(this, "أنت تشاهد نسخة محفوظة. الأسعار قد تكون تغيّرت.",
                12.5f, CigramAdsUi.WARN, false), new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    // ------------------------------------------------------------- 1. hero

    private View buildHero() {
        JSONObject hero = config.optJSONObject("hero");
        if (hero == null) hero = new JSONObject();

        LinearLayout box = CigramAdsUi.column(this);
        box.setBackground(CigramAdsUi.round(this, CigramAdsUi.CARD, CigramAdsUi.STROKE, 24));
        int p = CigramAdsUi.dp(this, 18);
        box.setPadding(p, CigramAdsUi.dp(this, 22), p, CigramAdsUi.dp(this, 18));

        int size = CigramAdsUi.dp(this, 52);
        box.addView(CigramAdsUi.iconBox(this, CigramAdsUi.ICON_MEGAPHONE, CigramAdsUi.PRIMARY, 52f),
                new LinearLayout.LayoutParams(size, size));

        TextView headline = CigramAdsUi.text(this,
                hero.optString("headline", "أعلن عن شركتك هنا"), 24f, CigramAdsUi.TEXT, true);
        headline.setGravity(Gravity.RIGHT | Gravity.TOP);
        box.addView(headline, CigramAdsUi.lp(this, -1, -2, 0, 14, 0, 0));

        box.addView(CigramAdsUi.body(this, hero.optString("sub", "")),
                CigramAdsUi.lp(this, -1, -2, 0, 8, 0, 0));

        LinearLayout stats = CigramAdsUi.row(this);
        stats.addView(statTile(CigramAdsUi.ICON_USERS, "مستخدم نشط",
                        hero.optLong("active_users", 0L)),
                new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1f);
        second.setMarginStart(CigramAdsUi.dp(this, 10));
        stats.addView(statTile(CigramAdsUi.ICON_EYE, "مشاهدة يومياً",
                hero.optLong("daily_views", 0L)), second);
        box.addView(stats, CigramAdsUi.lp(this, -1, -2, 0, 16, 0, 0));

        String note = hero.optString("stats_note", "");
        if (note.length() > 0) {
            box.addView(CigramAdsUi.muted(this, note), CigramAdsUi.lp(this, -1, -2, 0, 8, 0, 0));
        }

        TextView cta = CigramAdsUi.primaryButton(this, "احسب تكلفة إعلانك", CigramAdsUi.ACCENT);
        cta.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                scrollTo(calculatorAnchor);
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 52));
        cp.topMargin = CigramAdsUi.dp(this, 16);
        box.addView(cta, cp);
        return box;
    }

    private View statTile(int icon, String label, final long value) {
        LinearLayout tile = CigramAdsUi.column(this);
        tile.setBackground(CigramAdsUi.round(this, CigramAdsUi.CARD_SOFT, CigramAdsUi.STROKE, 16));
        int p = CigramAdsUi.dp(this, 12);
        tile.setPadding(p, p, p, p);

        int size = CigramAdsUi.dp(this, 30);
        tile.addView(CigramAdsUi.iconBox(this, icon, CigramAdsUi.ACCENT, 30f),
                new LinearLayout.LayoutParams(size, size));

        final TextView number = CigramAdsUi.text(this, "—", 21f, CigramAdsUi.TEXT, true);
        tile.addView(number, CigramAdsUi.lp(this, -1, -2, 0, 8, 0, 0));
        tile.addView(CigramAdsUi.muted(this, label), CigramAdsUi.lp(this, -1, -2, 0, 2, 0, 0));
        tile.setContentDescription(label + " " + value);

        if (value <= 0L) {
            number.setText("—");
            tile.setContentDescription(label + " غير متاح");
        } else {
            // count up once, when the tile first appears on screen
            number.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    CigramAdsUi.countUp(number, value, "");
                }

                @Override public void onViewDetachedFromWindow(View v) { }
            });
        }
        return tile;
    }

    // -------------------------------------------------------- 2. placements

    private View buildPlacements() {
        LinearLayout box = CigramAdsUi.column(this);
        box.addView(CigramAdsUi.sectionHeader(this, "أماكن الإعلان",
                        "ست مساحات داخل التطبيق، لكل منها شكل وجمهور."),
                CigramAdsUi.lp(this, -1, -2, 4, 0, 4, 14));

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        strip.setOverScrollMode(View.OVER_SCROLL_NEVER);
        strip.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        strip.setClipToPadding(false);
        strip.setPadding(CigramAdsUi.dp(this, 4), 0, CigramAdsUi.dp(this, 4), 0);

        LinearLayout row = CigramAdsUi.row(this);
        row.setGravity(Gravity.TOP);
        JSONArray slots = config.optJSONArray("slots");
        if (slots == null) slots = new JSONArray();
        String currency = config.optString("currency_label", "");
        for (int i = 0; i < slots.length(); i++) {
            JSONObject s = slots.optJSONObject(i);
            if (s == null) continue;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CigramAdsUi.dp(this, 230), -2);
            if (i > 0) lp.setMarginStart(CigramAdsUi.dp(this, 12));
            row.addView(placementCard(s, currency), lp);
        }
        strip.addView(row, new HorizontalScrollView.LayoutParams(-2, -2));
        box.addView(strip, new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private View placementCard(final JSONObject slot, String currency) {
        LinearLayout card = CigramAdsUi.column(this);
        card.setBackground(CigramAdsUi.round(this, CigramAdsUi.CARD, CigramAdsUi.STROKE, 18));
        int p = CigramAdsUi.dp(this, 12);
        card.setPadding(p, p, p, p);

        SlotPreview preview = new SlotPreview(this, slot.optString("id", ""));
        card.addView(preview, new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 150)));

        card.addView(CigramAdsUi.text(this, slot.optString("name", ""), 16f, CigramAdsUi.TEXT, true),
                CigramAdsUi.lp(this, -1, -2, 0, 12, 0, 0));
        TextView desc = CigramAdsUi.text(this, slot.optString("desc", ""), 13f, 0xBBFFFFFF, false);
        desc.setMinLines(2);
        card.addView(desc, CigramAdsUi.lp(this, -1, -2, 0, 6, 0, 0));

        String suits = slot.optString("suits", "");
        if (suits.length() > 0) {
            card.addView(CigramAdsUi.text(this, "تناسب: " + suits, 12.5f, CigramAdsUi.ACCENT, false),
                    CigramAdsUi.lp(this, -1, -2, 0, 8, 0, 0));
        }

        double perDay = slot.optDouble("base_price_day", 0d);
        if (perDay > 0d) {
            card.addView(CigramAdsUi.text(this, "من " + CigramAdsUi.money(perDay, currency) + " / يوم",
                    13.5f, CigramAdsUi.TEXT, true), CigramAdsUi.lp(this, -1, -2, 0, 10, 0, 0));
        }

        TextView pick = CigramAdsUi.ghostButton(this, "احسب لهذه المساحة");
        pick.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (calculator != null) calculator.selectSlot(slot.optString("id", ""));
                scrollTo(calculatorAnchor);
            }
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 46));
        bp.topMargin = CigramAdsUi.dp(this, 12);
        card.addView(pick, bp);

        card.setContentDescription(slot.optString("name", "") + ". " + slot.optString("desc", ""));
        return card;
    }

    /**
     * A drawn mock-up of the app with this slot highlighted. Drawn rather than
     * shipped as an image so it stays sharp at any density and adds no assets.
     */
    private static final class SlotPreview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String slot;
        private final float density;

        SlotPreview(Activity a, String slot) {
            super(a);
            this.slot = slot == null ? "" : slot;
            this.density = a.getResources().getDisplayMetrics().density;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        private float dp(float v) {
            return v * density;
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;

            // phone body
            float phoneW = Math.min(w * 0.62f, h * 0.52f);
            float phoneH = h * 0.92f;
            float left = (w - phoneW) / 2f;
            float top = (h - phoneH) / 2f;
            RectF body = new RectF(left, top, left + phoneW, top + phoneH);

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF0A1C25);
            canvas.drawRoundRect(body, dp(10), dp(10), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(CigramAdsUi.STROKE);
            canvas.drawRoundRect(body, dp(10), dp(10), paint);

            float pad = dp(5);
            float innerLeft = body.left + pad;
            float innerRight = body.right - pad;
            float cursor = body.top + dp(9);
            float rowH = phoneH * 0.085f;
            float gap = dp(4);

            // generic app furniture
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFF17323D);
            canvas.drawRoundRect(new RectF(innerLeft, cursor, innerRight, cursor + rowH * 0.6f),
                    dp(3), dp(3), paint);
            cursor += rowH * 0.6f + gap;

            RectF highlight = null;
            if ("hero".equals(slot)) {
                highlight = new RectF(innerLeft, cursor, innerRight, cursor + rowH * 1.9f);
                cursor += rowH * 1.9f + gap;
            } else {
                paint.setColor(0xFF143039);
                canvas.drawRoundRect(new RectF(innerLeft, cursor, innerRight, cursor + rowH * 1.9f),
                        dp(4), dp(4), paint);
                cursor += rowH * 1.9f + gap;
            }

            for (int i = 0; i < 3; i++) {
                boolean inlineHere = "inline".equals(slot) && i == 1;
                boolean sponsorHere = "sponsor".equals(slot) && i == 0;
                RectF r = new RectF(innerLeft, cursor, innerRight,
                        cursor + (sponsorHere ? rowH * 0.5f : rowH));
                if (inlineHere || sponsorHere) {
                    highlight = r;
                } else {
                    paint.setColor(0xFF122C35);
                    canvas.drawRoundRect(r, dp(3), dp(3), paint);
                }
                cursor += (sponsorHere ? rowH * 0.5f : rowH) + gap;
            }

            if ("sticky".equals(slot)) {
                highlight = new RectF(innerLeft, body.bottom - dp(9) - rowH * 0.7f,
                        innerRight, body.bottom - dp(9));
            }
            if ("splash".equals(slot)) {
                highlight = new RectF(innerLeft, body.top + dp(9), innerRight, body.bottom - dp(9));
            }
            if ("popup".equals(slot)) {
                float ph = phoneH * 0.34f;
                float pw = phoneW * 0.78f;
                float cx = body.centerX();
                float cy = body.centerY();
                highlight = new RectF(cx - pw / 2f, cy - ph / 2f, cx + pw / 2f, cy + ph / 2f);
            }

            if (highlight != null) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(CigramAdsUi.alpha(CigramAdsUi.PRIMARY, 0x4D));
                canvas.drawRoundRect(highlight, dp(4), dp(4), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.6f));
                paint.setColor(CigramAdsUi.PRIMARY);
                canvas.drawRoundRect(highlight, dp(4), dp(4), paint);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                paint.setTextSize(Math.max(dp(8), Math.min(dp(11), highlight.height() * 0.45f)));
                paint.setTextAlign(Paint.Align.CENTER);
                if (highlight.height() > dp(14)) {
                    canvas.drawText("إعلان", highlight.centerX(),
                            highlight.centerY() + paint.getTextSize() * 0.35f, paint);
                }
                paint.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    // -------------------------------------------------------- 3. calculator

    private View buildCalculator() {
        calculator = new CigramAdsCalculator(this, config, this, new CigramAdsCalculator.Listener() {
            @Override public void onQuote(JSONObject quote) {
                // nothing to do: the send button reads calculator.order() on demand
            }

            @Override public void onSlotChanged(String slotId) { }
        });
        LinearLayout box = CigramAdsUi.column(this);
        box.addView(calculator.build(), new LinearLayout.LayoutParams(-1, -2));

        TextView send = CigramAdsUi.primaryButton(this, "أرسل هذا الطلب", CigramAdsUi.PRIMARY);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                JSONObject quote = calculator == null ? null : calculator.quote();
                if (quote == null) {
                    toast("انتظر حتى يظهر السعر ثم أرسل الطلب.");
                    return;
                }
                if (!accepted) {
                    nudgeConsent();
                    return;
                }
                openContactSheet(calculator.order());
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 52));
        sp.topMargin = CigramAdsUi.dp(this, 14);
        sp.setMarginStart(CigramAdsUi.dp(this, 4));
        sp.setMarginEnd(CigramAdsUi.dp(this, 4));
        box.addView(send, sp);
        return box;
    }

    // ------------------------------------------------------------- 4. rules

    private View buildRules() {
        LinearLayout box = CigramAdsUi.column(this);
        box.addView(CigramAdsUi.sectionHeader(this, "ما نقبله وما نرفضه",
                        "كل إعلان يمر بمراجعة يدوية قبل النشر."),
                CigramAdsUi.lp(this, -1, -2, 4, 0, 4, 14));
        box.addView(ruleCard(config.optJSONObject("accepted"), true),
                new LinearLayout.LayoutParams(-1, -2));
        box.addView(ruleCard(config.optJSONObject("rejected"), false),
                CigramAdsUi.lp(this, -1, -2, 0, 12, 0, 0));
        return box;
    }

    private View ruleCard(JSONObject group, boolean positive) {
        if (group == null) group = new JSONObject();
        int color = positive ? CigramAdsUi.GOOD : CigramAdsUi.BAD;
        LinearLayout card = CigramAdsUi.column(this);
        card.setBackground(CigramAdsUi.round(this, CigramAdsUi.alpha(color, 0x12),
                CigramAdsUi.alpha(color, 0x4D), 18));
        int p = CigramAdsUi.dp(this, 14);
        card.setPadding(p, p, p, p);

        LinearLayout head = CigramAdsUi.row(this);
        int size = CigramAdsUi.dp(this, 32);
        head.addView(CigramAdsUi.iconBox(this, positive ? CigramAdsUi.ICON_CHECK : CigramAdsUi.ICON_CROSS,
                color, 32f), new LinearLayout.LayoutParams(size, size));
        TextView heading = CigramAdsUi.text(this,
                group.optString("title", positive ? "مقبول" : "مرفوض"), 16f, color, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1f);
        hp.setMarginStart(CigramAdsUi.dp(this, 10));
        head.addView(heading, hp);
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        JSONArray items = group.optJSONArray("items");
        if (items == null) items = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            LinearLayout row = CigramAdsUi.row(this);
            row.setGravity(Gravity.TOP);

            View dot = new View(this);
            dot.setBackground(CigramAdsUi.round(this, color, 4));
            LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                    CigramAdsUi.dp(this, 7), CigramAdsUi.dp(this, 7));
            dp.topMargin = CigramAdsUi.dp(this, 7);
            row.addView(dot, dp);

            LinearLayout texts = CigramAdsUi.column(this);
            texts.addView(CigramAdsUi.text(this, item.optString("title", ""), 14f, CigramAdsUi.TEXT, true),
                    new LinearLayout.LayoutParams(-1, -2));
            String desc = item.optString("desc", "");
            if (desc.length() > 0) {
                texts.addView(CigramAdsUi.text(this, desc, 12.8f, 0xAAFFFFFF, false),
                        CigramAdsUi.lp(this, -1, -2, 0, 2, 0, 0));
            }
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.setMarginStart(CigramAdsUi.dp(this, 10));
            row.addView(texts, tp);

            card.addView(row, CigramAdsUi.lp(this, -1, -2, 0, 12, 0, 0));
        }
        return card;
    }

    // ---------------------------------------------------------- 5. policies

    private View buildPolicies() {
        LinearLayout box = CigramAdsUi.column(this);
        box.addView(CigramAdsUi.sectionHeader(this, "السياسات",
                        "نسخة " + config.optInt("policy_version", 1)),
                CigramAdsUi.lp(this, -1, -2, 4, 0, 4, 14));
        JSONArray all = config.optJSONArray("policies");
        if (all == null) all = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject policy = all.optJSONObject(i);
            if (policy == null) continue;
            TextView body = CigramAdsUi.body(this, policy.optString("body", ""));
            box.addView(CigramAdsUi.accordion(this, policy.optString("title", ""), body),
                    CigramAdsUi.lp(this, -1, -2, 0, i == 0 ? 0 : 10, 0, 0));
        }
        return box;
    }

    // --------------------------------------------------------------- 6. FAQ

    private View buildFaq() {
        JSONArray all = config.optJSONArray("faq");
        if (all == null || all.length() == 0) return null;
        LinearLayout box = CigramAdsUi.column(this);
        box.addView(CigramAdsUi.sectionHeader(this, "أسئلة شائعة", null),
                CigramAdsUi.lp(this, -1, -2, 4, 0, 4, 14));
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i);
            if (item == null) continue;
            TextView answer = CigramAdsUi.body(this, item.optString("a", ""));
            box.addView(CigramAdsUi.accordion(this, item.optString("q", ""), answer),
                    CigramAdsUi.lp(this, -1, -2, 0, i == 0 ? 0 : 10, 0, 0));
        }
        return box;
    }

    // ----------------------------------------------------------- 7. contact

    private View buildContactSection() {
        LinearLayout box = CigramAdsUi.column(this);
        box.addView(CigramAdsUi.sectionHeader(this, "جاهز؟ تواصل معنا",
                        "اختر الطريقة التي تناسبك، والمحادثة داخل التطبيق هي الأسرع."),
                CigramAdsUi.lp(this, -1, -2, 4, 0, 4, 14));
        box.addView(presenceCard(), new LinearLayout.LayoutParams(-1, -2));
        return box;
    }

    private View presenceCard() {
        JSONObject presence = config.optJSONObject("presence");
        if (presence == null) presence = new JSONObject();
        String status = presence.optString("status", "offline");
        boolean online = "online".equals(status);
        int color = online ? CigramAdsUi.GOOD : ("away".equals(status) ? CigramAdsUi.WARN : CigramAdsUi.DIM);
        String label = online ? "إدارة الإعلانات متصلة الآن"
                : ("away".equals(status) ? "الإدارة خارج ساعات العمل" : "الإدارة غير متصلة");

        LinearLayout card = CigramAdsUi.card(this);
        LinearLayout row = CigramAdsUi.row(this);
        View dot = new View(this);
        dot.setBackground(CigramAdsUi.round(this, color, 5));
        row.addView(dot, new LinearLayout.LayoutParams(CigramAdsUi.dp(this, 10), CigramAdsUi.dp(this, 10)));
        TextView text = CigramAdsUi.text(this, label, 14.5f, CigramAdsUi.TEXT, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(this, 8));
        row.addView(text, tp);
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));

        String hours = presence.optString("offline_note", "");
        if (hours.length() > 0) {
            card.addView(CigramAdsUi.muted(this, hours + " " + presence.optString("hours_label", "")),
                    CigramAdsUi.lp(this, -1, -2, 0, 6, 0, 0));
        }
        String auto = presence.optString("auto_reply", "");
        if (!online && auto.length() > 0) {
            card.addView(CigramAdsUi.muted(this, auto), CigramAdsUi.lp(this, -1, -2, 0, 6, 0, 0));
        }
        return card;
    }

    /** The three ways to reach us. {@code order} is the calculator request, or null. */
    private void openContactSheet(final JSONObject order) {
        if (!alive() || config == null) return;
        JSONObject contact = config.optJSONObject("contact");
        if (contact == null) contact = new JSONObject();

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = CigramAdsUi.column(this);
        sheet.setBackground(CigramAdsUi.round(this, CigramAdsUi.CARD, CigramAdsUi.STROKE, 26));
        int p = CigramAdsUi.dp(this, 18);
        sheet.setPadding(p, CigramAdsUi.dp(this, 14), p, CigramAdsUi.dp(this, 20));

        View grabber = new View(this);
        grabber.setBackground(CigramAdsUi.round(this, 0x33FFFFFF, 3));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                CigramAdsUi.dp(this, 42), CigramAdsUi.dp(this, 4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.bottomMargin = CigramAdsUi.dp(this, 14);
        sheet.addView(grabber, gp);

        sheet.addView(CigramAdsUi.title(this, "كيف تحب أن نتواصل؟"),
                new LinearLayout.LayoutParams(-1, -2));
        if (order != null) {
            sheet.addView(CigramAdsUi.muted(this, "سيُرفق ملخص طلبك تلقائياً."),
                    CigramAdsUi.lp(this, -1, -2, 0, 6, 0, 0));
        }

        if (contact.optBoolean("inapp_enabled", true)) {
            sheet.addView(contactOption(CigramAdsUi.ICON_CHAT, CigramAdsUi.ACCENT,
                    "المحادثة داخل التطبيق", "الأسرع — رد مباشر من إدارة الإعلانات", true,
                    new Runnable() {
                        @Override public void run() {
                            dialog.dismiss();
                            openInAppChat(order);
                        }
                    }), CigramAdsUi.lp(this, -1, -2, 0, 16, 0, 0));
        }

        final String whatsapp = contact.optString("whatsapp", "");
        if (whatsapp.length() > 0) {
            final String template = contact.optString("whatsapp_template", "");
            sheet.addView(contactOption(CigramAdsUi.ICON_WHATSAPP, CigramAdsUi.GOOD,
                    "واتساب", "محادثة على رقم الإعلانات", false, new Runnable() {
                        @Override public void run() {
                            dialog.dismiss();
                            openWhatsApp(whatsapp, template, order);
                        }
                    }), CigramAdsUi.lp(this, -1, -2, 0, 10, 0, 0));
        }

        final String xUrl = contact.optString("x_url", "");
        if (xUrl.length() > 0) {
            sheet.addView(contactOption(CigramAdsUi.ICON_X, CigramAdsUi.TEXT,
                    "X (تويتر)", "حساب الإعلانات الرسمي", false, new Runnable() {
                        @Override public void run() {
                            dialog.dismiss();
                            openUrl(xUrl);
                        }
                    }), CigramAdsUi.lp(this, -1, -2, 0, 10, 0, 0));
        }

        TextView close = CigramAdsUi.ghostButton(this, "إغلاق");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 48));
        cp.topMargin = CigramAdsUi.dp(this, 16);
        sheet.addView(close, cp);

        dialog.setContentView(sheet);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.7f;
            attributes.gravity = Gravity.BOTTOM;
            window.setAttributes(attributes);
        }
        try {
            dialog.show();
            if (window != null) window.setLayout(-1, ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Throwable error) {
            android.util.Log.w("CigramAds", "contact sheet failed", error);
        }
    }

    private View contactOption(int icon, int color, String title, String subtitle,
                               boolean recommended, final Runnable action) {
        LinearLayout row = CigramAdsUi.row(this);
        row.setMinimumHeight(CigramAdsUi.dp(this, 64));
        int p = CigramAdsUi.dp(this, 12);
        row.setPadding(p, p, p, p);
        CigramAdsUi.pressable(row, recommended
                        ? CigramAdsUi.round(this, CigramAdsUi.alpha(color, 0x1A), CigramAdsUi.alpha(color, 0x66), 16)
                        : CigramAdsUi.round(this, CigramAdsUi.CARD_SOFT, CigramAdsUi.STROKE, 16),
                color, 16);

        int size = CigramAdsUi.dp(this, 40);
        row.addView(CigramAdsUi.iconBox(this, icon, color, 40f),
                new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = CigramAdsUi.column(this);
        LinearLayout head = CigramAdsUi.row(this);
        head.addView(CigramAdsUi.text(this, title, 15f, CigramAdsUi.TEXT, true),
                new LinearLayout.LayoutParams(-2, -2));
        if (recommended) {
            TextView badge = CigramAdsUi.badge(this, "موصى به", color);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
            bp.setMarginStart(CigramAdsUi.dp(this, 8));
            head.addView(badge, bp);
        }
        texts.addView(head, new LinearLayout.LayoutParams(-1, -2));
        texts.addView(CigramAdsUi.muted(this, subtitle), CigramAdsUi.lp(this, -1, -2, 0, 2, 0, 0));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(this, 12));
        row.addView(texts, tp);

        row.setContentDescription(title + ". " + subtitle);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                action.run();
            }
        });
        return row;
    }

    // ------------------------------------------------------------- channels

    /**
     * Opens the in-app chat. The chat screen ships in phase (ج); this build looks it
     * up by name so the page works either way, and falls back to WhatsApp when the
     * chat module is not part of the installed build.
     */
    private void openInAppChat(JSONObject order) {
        if (!CigramUserData.isLoggedIn(this)) {
            askToSignIn();
            return;
        }
        int version = config == null ? 0 : config.optInt("policy_version", 0);
        CigramAdsApi.sendConsent(this, version, this, null);
        try {
            Class<?> chat = Class.forName("com.Cigram.vid.CigramAdsChatActivity");
            Intent intent = new Intent(this, chat);
            if (order != null) intent.putExtra("order", order.toString());
            startActivity(intent);
        } catch (ClassNotFoundException missing) {
            JSONObject contact = config == null ? null : config.optJSONObject("contact");
            String whatsapp = contact == null ? "" : contact.optString("whatsapp", "");
            if (whatsapp.length() > 0) {
                openWhatsApp(whatsapp, contact.optString("whatsapp_template", ""), order);
            } else {
                toast("المحادثة داخل التطبيق غير متاحة في هذه النسخة.");
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramAds", "chat open failed", error);
            toast("تعذر فتح المحادثة. أعد المحاولة.");
        }
    }

    private void askToSignIn() {
        CigramUI.sheet(this)
                .icon(CigramUI.ICON_USER, CigramUI.TEAL)
                .title("تحتاج حساباً للمتابعة")
                .message("المحادثة مرتبطة بحسابك حتى تصلك الردود وتتابع حملاتك من «إعلاناتي». "
                        + "سجّل الدخول أو أنشئ حساباً — الأمر لا يستغرق دقيقة.")
                .primary("تسجيل الدخول", CigramUI.TEAL, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet sheet) {
                        openAccountPage();
                        return false;
                    }
                })
                .secondary("لاحقاً", null)
                .show();
    }

    private void openAccountPage() {
        try {
            Class<?> account = Class.forName("com.Cigram.vid.AccountActivity");
            Intent intent = new Intent(this, account);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        } catch (Throwable error) {
            finish(); // the account page is where this screen was opened from
        }
    }

    private void openWhatsApp(String number, String template, JSONObject order) {
        StringBuilder message = new StringBuilder(template == null ? "" : template);
        String summary = orderSummary(order);
        if (summary.length() > 0) {
            if (message.length() > 0) message.append("\n\n");
            message.append(summary);
        }
        String url = "https://wa.me/" + number.replace("+", "")
                + "?text=" + CigramAdsApi.encode(message.toString());
        openUrl(url);
    }

    /** The calculator selection, written out as plain text for WhatsApp. */
    private String orderSummary(JSONObject order) {
        if (order == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("طلب إعلان:");
        sb.append("\n• المساحة: ").append(order.optString("slot_name", ""));
        sb.append("\n• المدة: ").append(order.optInt("days", 0)).append(" يوم");
        JSONArray cities = order.optJSONArray("cities");
        if (cities != null && cities.length() > 0) {
            sb.append("\n• المدن: ").append(join(cities));
        }
        JSONArray hours = order.optJSONArray("hours");
        if (hours != null && hours.length() > 0 && hours.length() < 24) {
            sb.append("\n• ساعات الظهور: ").append(hours.length()).append(" ساعة محددة");
        }
        JSONObject quote = order.optJSONObject("quote");
        if (quote != null) {
            String currency = quote.optString("currency_label", "");
            JSONArray addons = quote.optJSONArray("addons");
            if (addons != null && addons.length() > 0) {
                StringBuilder names = new StringBuilder();
                for (int i = 0; i < addons.length(); i++) {
                    JSONObject a = addons.optJSONObject(i);
                    if (a == null) continue;
                    if (names.length() > 0) names.append("، ");
                    names.append(a.optString("label", ""));
                }
                if (names.length() > 0) sb.append("\n• إضافات: ").append(names);
            }
            sb.append("\n• الإجمالي: ")
                    .append(CigramAdsUi.money(quote.optDouble("total", 0d), currency));
        }
        return sb.toString();
    }

    private static String join(JSONArray array) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length(); i++) {
            if (sb.length() > 0) sb.append("، ");
            sb.append(array.optString(i, ""));
        }
        return sb.toString();
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable error) {
            toast("لا يوجد تطبيق يستطيع فتح هذا الرابط.");
        }
    }

    // ------------------------------------------------------------- scrolling

    private void scrollTo(final View target) {
        if (target == null || scroll == null) return;
        scroll.post(new Runnable() {
            @Override public void run() {
                if (!alive() || scroll == null) return;
                int y = target.getTop();
                View parent = target.getParent() instanceof View ? (View) target.getParent() : null;
                while (parent != null && parent != content) {
                    y += parent.getTop();
                    parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
                }
                scroll.smoothScrollTo(0, Math.max(0, y - CigramAdsUi.dp(CigramAdvertiseActivity.this, 12)));
            }
        });
    }

    /** Fades each section in the first time it enters the viewport. */
    private void revealVisible() {
        if (scroll == null) return;
        int top = scroll.getScrollY();
        int bottom = top + scroll.getHeight();
        for (View section : revealables) {
            if (section == null) continue;
            int y = section.getTop();
            if (y < bottom + CigramAdsUi.dp(this, 40) && y + section.getHeight() > top - 1) {
                CigramAdsUi.revealOnce(section, 0L);
            }
        }
    }

    /** Unlocks the consent box once the advertiser has actually reached the bottom. */
    private void checkReachedEnd() {
        if (reachedEnd || scroll == null || content == null) return;
        int bottom = scroll.getScrollY() + scroll.getHeight();
        int limit = content.getHeight() - CigramAdsUi.dp(this, 160);
        if (bottom >= limit) {
            reachedEnd = true;
            updateFooterState();
        }
    }

    private void toast(String message) {
        try {
            android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }
}
