package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The one reusable ad component. Drop it anywhere with a slot id and nothing else:
 *
 * <pre>
 *   CigramAdSlotView.into(container, CigramAdSlots.SLOT_INLINE);
 *   CigramAdSlotView.splash(activity, whenDone);   // full-screen, with a skip
 *   CigramAdSlotView.popup(activity);              // once a day
 *   CigramAdSlotView.sticky(activity);             // dismissible bottom bar
 * </pre>
 *
 * Behaviour the spec requires, all handled in here:
 *   - it starts at zero height and **disappears completely when there is no ad**,
 *     so a slot never leaves a gap,
 *   - it never blocks the screen: the fetch is asynchronous and a cached copy
 *     paints immediately,
 *   - hero rotates on its own, **pauses while the finger is down**, and shows
 *     indicator dots,
 *   - every ad carries a visible "إعلان" label and a menu with
 *     "لا تظهر لي هذا" and "إبلاغ",
 *   - an impression is counted once, only after 50% of the view has been on
 *     screen for a continuous second, and the counter stops when the view
 *     scrolls away or the screen is left.
 */
public final class CigramAdSlotView extends FrameLayout {

    private static final long HERO_ROTATE_MS = 6000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<JSONObject> ads = new ArrayList<JSONObject>();
    private String slot = "";
    private int index = 0;
    private boolean paused;
    private boolean attached;
    private LinearLayout dots;
    private FrameLayout stage;

    // impression bookkeeping for the ad currently on screen
    private long visibleSince = 0L;
    private String countedFor = "";

    public CigramAdSlotView(Context context) {
        super(context);
        setVisibility(GONE);
        setLayoutParams(new LinearLayout.LayoutParams(-1, 0));
    }

    /** Creates the slot, adds it to {@code parent} and starts loading. */
    public static CigramAdSlotView into(ViewGroup parent, String slotId) {
        if (parent == null) return null;
        CigramAdSlotView view = new CigramAdSlotView(parent.getContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0);
        parent.addView(view, lp);
        view.setSlot(slotId);
        return view;
    }

    public void setSlot(String slotId) {
        slot = slotId == null ? "" : slotId;
        load();
    }

    // ------------------------------------------------------------- lifecycle

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
        main.post(visibilityTick);
    }

    @Override protected void onDetachedFromWindow() {
        attached = false;
        try {
            getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        } catch (Throwable ignored) { }
        main.removeCallbacksAndMessages(null);
        visibleSince = 0L;
        CigramAdSlots.flush(getContext());
        super.onDetachedFromWindow();
    }

    private final android.view.ViewTreeObserver.OnScrollChangedListener scrollListener =
            new android.view.ViewTreeObserver.OnScrollChangedListener() {
                @Override public void onScrollChanged() {
                    checkVisible();
                }
            };

    private final Runnable visibilityTick = new Runnable() {
        @Override public void run() {
            if (!attached) return;
            checkVisible();
            main.postDelayed(this, 250L);
        }
    };

    /** The 50%-for-one-second rule. */
    private void checkVisible() {
        if (!attached || ads.isEmpty() || getVisibility() != VISIBLE) {
            visibleSince = 0L;
            return;
        }
        if (!isShown() || getHeight() <= 0) {
            visibleSince = 0L;
            return;
        }
        android.graphics.Rect visible = new android.graphics.Rect();
        if (!getGlobalVisibleRect(visible)) {
            visibleSince = 0L;
            return;
        }
        float shown = (float) (visible.height() * visible.width())
                / (float) Math.max(1, getHeight() * getWidth());
        if (shown < CigramAdSlots.IMPRESSION_VISIBLE_FRACTION) {
            visibleSince = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (visibleSince == 0L) {
            visibleSince = now;
            return;
        }
        if (now - visibleSince < CigramAdSlots.IMPRESSION_DWELL_MS) return;

        JSONObject ad = current();
        if (ad == null) return;
        String id = ad.optString("id", "");
        if (id.equals(countedFor)) return;
        countedFor = id;
        CigramAdSlots.impression(getContext(), ad);
    }

    private JSONObject current() {
        if (ads.isEmpty()) return null;
        if (index < 0 || index >= ads.size()) index = 0;
        return ads.get(index);
    }

    // ------------------------------------------------------------------ load

    private void load() {
        if (slot.length() == 0) {
            collapse();
            return;
        }
        if (CigramAdSlots.SLOT_STICKY.equals(slot)
                && CigramAdSlots.stickyDismissedToday(getContext())) {
            collapse();
            return;
        }
        CigramAdSlots.load(getContext(), slot, new CigramAdSlots.Callback() {
            @Override public void ready(List<JSONObject> list) {
                if (!attached && getParent() == null) return;
                ads.clear();
                if (list != null) ads.addAll(list);
                if (ads.isEmpty()) {
                    collapse();
                    return;
                }
                index = 0;
                countedFor = "";
                render();
            }
        });
    }

    /** No ad: zero height, GONE, no padding, no gap. */
    private void collapse() {
        removeAllViews();
        setVisibility(GONE);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            setLayoutParams(lp);
        }
    }

    private void expand() {
        setVisibility(VISIBLE);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && lp.height == 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            setLayoutParams(lp);
        }
    }

    // ---------------------------------------------------------------- render

    private void render() {
        removeAllViews();
        expand();
        if (CigramAdSlots.SLOT_SPONSOR.equals(slot)) {
            addView(sponsorView(current()), new LayoutParams(-1, -2));
            return;
        }
        if (CigramAdSlots.SLOT_STICKY.equals(slot)) {
            addView(stickyView(current()), new LayoutParams(-1, -2));
            return;
        }
        if (CigramAdSlots.SLOT_HERO.equals(slot)) {
            renderHero();
            return;
        }
        addView(cardView(current()), new LayoutParams(-1, -2));
    }

    private void renderHero() {
        LinearLayout column = CigramAdsUi.column(getContext());
        stage = new FrameLayout(getContext());
        column.addView(stage, new LinearLayout.LayoutParams(-1,
                CigramAdsUi.dp(getContext(), 168)));

        dots = CigramAdsUi.row(getContext());
        dots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsLp = new LinearLayout.LayoutParams(-1, -2);
        dotsLp.topMargin = CigramAdsUi.dp(getContext(), 8);
        column.addView(dots, dotsLp);
        if (ads.size() < 2) dots.setVisibility(GONE);

        addView(column, new LayoutParams(-1, -2));
        showHeroPage(0, false);
        if (ads.size() > 1) main.postDelayed(rotate, HERO_ROTATE_MS);
    }

    private final Runnable rotate = new Runnable() {
        @Override public void run() {
            if (!attached || paused || ads.size() < 2) {
                main.postDelayed(this, HERO_ROTATE_MS);
                return;
            }
            showHeroPage((index + 1) % ads.size(), true);
            main.postDelayed(this, HERO_ROTATE_MS);
        }
    };

    private void showHeroPage(int page, boolean animate) {
        if (stage == null || ads.isEmpty()) return;
        index = Math.max(0, Math.min(ads.size() - 1, page));
        countedFor = "";
        visibleSince = 0L;
        View card = cardView(current());
        if (!animate) {
            stage.removeAllViews();
            stage.addView(card, new FrameLayout.LayoutParams(-1, -1));
        } else {
            card.setAlpha(0f);
            stage.addView(card, new FrameLayout.LayoutParams(-1, -1));
            card.animate().alpha(1f).setDuration(260L).start();
            if (stage.getChildCount() > 1) {
                final View old = stage.getChildAt(0);
                old.animate().alpha(0f).setDuration(260L).withEndAction(new Runnable() {
                    @Override public void run() {
                        if (stage != null) stage.removeView(old);
                    }
                }).start();
            }
        }
        paintDots();
    }

    private void paintDots() {
        if (dots == null) return;
        dots.removeAllViews();
        if (ads.size() < 2) {
            dots.setVisibility(GONE);
            return;
        }
        dots.setVisibility(VISIBLE);
        for (int i = 0; i < ads.size(); i++) {
            View dot = new View(getContext());
            boolean active = i == index;
            dot.setBackground(CigramAdsUi.round(getContext(),
                    active ? CigramAdsUi.ACCENT : 0x44FFFFFF, 3));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    CigramAdsUi.dp(getContext(), active ? 16 : 6),
                    CigramAdsUi.dp(getContext(), 6));
            lp.setMarginStart(CigramAdsUi.dp(getContext(), 3));
            lp.setMarginEnd(CigramAdsUi.dp(getContext(), 3));
            dots.addView(dot, lp);
        }
    }

    /** Touching the carousel stops the rotation; letting go resumes it. */
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) paused = true;
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) paused = false;
        return super.onInterceptTouchEvent(event);
    }

    // ------------------------------------------------------------- the shapes

    /** inline and hero: an image card with the headline, the CTA and the label. */
    private View cardView(final JSONObject ad) {
        Context context = getContext();
        if (ad == null) return new View(context);
        JSONObject creative = ad.optJSONObject("creative");
        if (creative == null) return new View(context);

        FrameLayout frame = new FrameLayout(context);
        frame.setBackground(CigramAdsUi.round(context, CigramAdsUi.CARD, CigramAdsUi.STROKE, 16));
        frame.setClipToOutline(true);

        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        CigramAdsMedia.loadInto(context, creative.optString("url", ""), image,
                context.getResources().getDisplayMetrics().widthPixels);

        // a scrim so white text stays readable on a bright creative
        View scrim = new View(context);
        scrim.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0x33000000, 0xCC000000}));
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout texts = CigramAdsUi.column(context);
        int p = CigramAdsUi.dp(context, 12);
        texts.setPadding(p, p, p, p);
        String headline = creative.optString("title", "");
        if (headline.length() > 0) {
            TextView title = CigramAdsUi.text(context, headline, 16f, 0xFFFFFFFF, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
        }
        String body = creative.optString("body", "");
        if (body.length() > 0) {
            TextView sub = CigramAdsUi.text(context, body, 13f, 0xDDFFFFFF, false);
            sub.setMaxLines(2);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub, CigramAdsUi.lp(context, -1, -2, 0, 3, 0, 0));
        }
        String cta = creative.optString("cta_label", "");
        if (cta.length() > 0) {
            TextView button = CigramAdsUi.text(context, cta, 13f, 0xFF04161D, true);
            button.setGravity(Gravity.CENTER);
            button.setBackground(CigramAdsUi.round(context, CigramAdsUi.ACCENT, 10));
            button.setPadding(CigramAdsUi.dp(context, 14), CigramAdsUi.dp(context, 7),
                    CigramAdsUi.dp(context, 14), CigramAdsUi.dp(context, 8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = CigramAdsUi.dp(context, 8);
            texts.addView(button, lp);
        }
        FrameLayout.LayoutParams textLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        frame.addView(texts, textLp);

        frame.addView(label(context), new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.START));
        frame.addView(menuButton(context, ad), new FrameLayout.LayoutParams(
                CigramAdsUi.dp(context, 34), CigramAdsUi.dp(context, 34),
                Gravity.TOP | Gravity.END));

        frame.setClickable(true);
        frame.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(ad);
            }
        });
        frame.setContentDescription("إعلان: " + headline);

        LinearLayout holder = CigramAdsUi.column(context);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,
                CigramAdSlots.SLOT_HERO.equals(slot) ? -1 : CigramAdsUi.dp(context, 160));
        holder.addView(frame, lp);
        return holder;
    }

    /** sponsor: a quiet "برعاية" row with the advertiser's mark. */
    private View sponsorView(final JSONObject ad) {
        Context context = getContext();
        JSONObject creative = ad == null ? null : ad.optJSONObject("creative");
        LinearLayout row = CigramAdsUi.row(context);
        row.setPadding(CigramAdsUi.dp(context, 12), CigramAdsUi.dp(context, 6),
                CigramAdsUi.dp(context, 12), CigramAdsUi.dp(context, 6));
        row.addView(CigramAdsUi.text(context, "برعاية", 11.5f, CigramAdsUi.MUTED, false),
                new LinearLayout.LayoutParams(-2, -2));

        ImageView logo = new ImageView(context);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoLp = new LinearLayout.LayoutParams(
                CigramAdsUi.dp(context, 76), CigramAdsUi.dp(context, 24));
        logoLp.setMarginStart(CigramAdsUi.dp(context, 8));
        row.addView(logo, logoLp);
        if (creative != null) {
            CigramAdsMedia.loadInto(context, creative.optString("url", ""), logo,
                    CigramAdsUi.dp(context, 200));
        }

        String name = creative == null ? "" : creative.optString("title", "");
        if (name.length() > 0) {
            TextView title = CigramAdsUi.text(context, name, 12.5f, 0xDDFFFFFF, true);
            title.setMaxLines(1);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMarginStart(CigramAdsUi.dp(context, 8));
            row.addView(title, lp);
        } else {
            row.addView(new View(context), new LinearLayout.LayoutParams(0, 1, 1f));
        }
        row.addView(menuButton(context, ad), new LinearLayout.LayoutParams(
                CigramAdsUi.dp(context, 30), CigramAdsUi.dp(context, 30)));
        row.setClickable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(ad);
            }
        });
        row.setContentDescription("برعاية " + name);
        return row;
    }

    /** sticky: a thin dismissible bar. */
    private View stickyView(final JSONObject ad) {
        Context context = getContext();
        JSONObject creative = ad == null ? null : ad.optJSONObject("creative");
        LinearLayout row = CigramAdsUi.row(context);
        row.setBackground(CigramAdsUi.round(context, 0xF2102F3B, CigramAdsUi.STROKE, 12));
        row.setPadding(CigramAdsUi.dp(context, 10), CigramAdsUi.dp(context, 8),
                CigramAdsUi.dp(context, 6), CigramAdsUi.dp(context, 8));

        ImageView thumb = new ImageView(context);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setBackground(CigramAdsUi.round(context, 0x22000000, 8));
        thumb.setClipToOutline(true);
        row.addView(thumb, new LinearLayout.LayoutParams(
                CigramAdsUi.dp(context, 40), CigramAdsUi.dp(context, 40)));
        if (creative != null) {
            CigramAdsMedia.loadInto(context, creative.optString("url", ""), thumb,
                    CigramAdsUi.dp(context, 120));
        }

        LinearLayout texts = CigramAdsUi.column(context);
        LinearLayout head = CigramAdsUi.row(context);
        TextView title = CigramAdsUi.text(context,
                creative == null ? "" : creative.optString("title", ""), 13.5f, CigramAdsUi.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(label(context), new LinearLayout.LayoutParams(-2, -2));
        texts.addView(head, new LinearLayout.LayoutParams(-1, -2));
        String body = creative == null ? "" : creative.optString("body", "");
        if (body.length() > 0) {
            TextView sub = CigramAdsUi.text(context, body, 11.5f, CigramAdsUi.MUTED, false);
            sub.setMaxLines(1);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1f);
        textLp.setMarginStart(CigramAdsUi.dp(context, 10));
        row.addView(texts, textLp);

        FrameLayout close = new FrameLayout(context);
        CigramAdsUi.Icon cross = new CigramAdsUi.Icon(context, CigramAdsUi.ICON_X, CigramAdsUi.MUTED);
        int inner = CigramAdsUi.dp(context, 13);
        close.addView(cross, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        CigramAdsUi.pressable(close, CigramAdsUi.round(context, Color.TRANSPARENT, 20),
                CigramAdsUi.ACCENT, 20);
        close.setContentDescription("إغلاق الإعلان");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramAdSlots.dismissStickyToday(getContext());
                collapse();
            }
        });
        row.addView(close, new LinearLayout.LayoutParams(
                CigramAdsUi.dp(context, 40), CigramAdsUi.dp(context, 40)));

        texts.setClickable(true);
        texts.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(ad);
            }
        });
        return row;
    }

    /** The mandatory "إعلان" marker. */
    private static View label(Context context) {
        TextView text = CigramAdsUi.text(context, "إعلان", 10f, 0xFFFFFFFF, true);
        text.setBackground(CigramAdsUi.round(context, 0x99000000, 6));
        text.setPadding(CigramAdsUi.dp(context, 6), CigramAdsUi.dp(context, 2),
                CigramAdsUi.dp(context, 6), CigramAdsUi.dp(context, 3));
        text.setIncludeFontPadding(false);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        lp.setMargins(CigramAdsUi.dp(context, 8), CigramAdsUi.dp(context, 8), 0, 0);
        text.setLayoutParams(lp);
        return text;
    }

    /** Hide / report. */
    private View menuButton(final Context context, final JSONObject ad) {
        FrameLayout button = new FrameLayout(context);
        TextView glyph = CigramAdsUi.text(context, "⋮", 16f, 0xFFFFFFFF, true);
        glyph.setGravity(Gravity.CENTER);
        button.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
        button.setBackground(CigramAdsUi.round(context, 0x66000000, 17));
        button.setContentDescription("خيارات الإعلان");
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openMenu(context, ad);
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                CigramAdsUi.dp(context, 34), CigramAdsUi.dp(context, 34));
        lp.setMargins(0, CigramAdsUi.dp(context, 6), CigramAdsUi.dp(context, 6), 0);
        button.setLayoutParams(lp);
        return button;
    }

    private void openMenu(final Context context, final JSONObject ad) {
        Activity activity = activityOf(context);
        if (activity == null || ad == null) return;
        CigramUI.sheet(activity)
                .icon(CigramUI.ICON_INFO, CigramUI.TEAL)
                .title("إعلان")
                .message("هذا المحتوى إعلان مدفوع من شركة. اختيارك هنا يُطبَّق على هذا الجهاز فقط.")
                .primary("لا تظهر لي هذا", CigramUI.TEAL, new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet sheet) {
                        CigramAdSlots.hide(context, ad.optString("campaign_id", ""));
                        reloadAfterHide();
                        return false;
                    }
                })
                .danger("إبلاغ", new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet sheet) {
                        reportSheet(ad);
                        return false;
                    }
                })
                .secondary("إغلاق", null)
                .show();
    }

    private void reportSheet(final JSONObject ad) {
        Activity activity = activityOf(getContext());
        if (activity == null) return;
        final String[] reasons = {"محتوى غير لائق", "مضلل أو كاذب", "لا يعمل الرابط", "يتكرر كثيراً"};
        CigramUI.Sheet sheet = CigramUI.sheet(activity)
                .icon(CigramUI.ICON_FLAG, CigramUI.DANGER)
                .title("الإبلاغ عن الإعلان")
                .message("اختر السبب. سنراجعه، وسيتوقف هذا الإعلان عن الظهور لك.");
        LinearLayout list = CigramAdsUi.column(activity);
        for (final String reason : reasons) {
            TextView row = CigramAdsUi.ghostButton(activity, reason);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    CigramAdSlots.report(getContext(), ad, reason);
                    reloadAfterHide();
                    toast("تم الإبلاغ. شكراً لك.");
                }
            });
            list.addView(row, CigramAdsUi.lp(activity, -1, CigramAdsUi.dp(activity, 46), 0, 8, 0, 0));
        }
        sheet.view(list).secondary("إلغاء", null).show();
    }

    private void reloadAfterHide() {
        ads.clear();
        collapse();
        load();
    }

    // ------------------------------------------------------------------ click

    private void open(JSONObject ad) {
        openAction(getContext(), ad);
    }

    /** Counts the click and performs the creative's action. Used by every shape. */
    static void openAction(Context context, JSONObject ad) {
        if (context == null || ad == null) return;
        CigramAdSlots.click(context, ad);
        JSONObject creative = ad.optJSONObject("creative");
        JSONObject action = creative == null ? null : creative.optJSONObject("action");
        String type = action == null ? "none" : action.optString("type", "none");
        String value = action == null ? "" : action.optString("value", "");

        if ("coupon".equals(type)) {
            try {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                        context.getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("coupon", value));
                note(context, "تم نسخ الكوبون: " + value);
            } catch (Throwable ignored) {
                note(context, "الكوبون: " + value);
            }
            return;
        }
        String url;
        if ("whatsapp".equals(type)) {
            url = "https://wa.me/" + value.replace("+", "");
        } else if ("call".equals(type)) {
            url = "tel:" + value;
        } else if ("url".equals(type) || "store".equals(type)) {
            url = value;
        } else {
            return; // no action: the ad is informational
        }
        if (url.length() == 0) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable ignored) {
            note(context, "تعذر فتح هذا الرابط.");
        }
    }

    private static void note(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    private void toast(String message) {
        note(getContext(), message);
    }

    private static Activity activityOf(Context context) {
        Context current = context;
        while (current instanceof android.content.ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            current = ((android.content.ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    // ================================================== full-screen presenters

    public interface Done {
        void finished();
    }

    /**
     * splash: full screen, a countdown, and a skip that is live from the start.
     * {@code done} always runs exactly once — immediately when there is no ad, so
     * app start is never delayed by this.
     */
    public static void splash(final Activity activity, final Done done) {
        if (activity == null) {
            if (done != null) done.finished();
            return;
        }
        final boolean[] finished = {false};
        final Runnable finish = new Runnable() {
            @Override public void run() {
                if (finished[0]) return;
                finished[0] = true;
                if (done != null) done.finished();
            }
        };
        CigramAdSlots.load(activity, CigramAdSlots.SLOT_SPLASH, new CigramAdSlots.Callback() {
            @Override public void ready(List<JSONObject> ads) {
                if (finished[0]) return;
                if (ads == null || ads.isEmpty() || activity.isFinishing()) {
                    finish.run();
                    return;
                }
                showSplash(activity, ads.get(0), finish);
            }
        });
        // A hard deadline: app start is never held for a network reply. A cached
        // response paints inside this window; a slow first-ever fetch simply
        // means no splash this launch, which is the right trade.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                finish.run();
            }
        }, 1200L);
    }

    private static void showSplash(final Activity activity, final JSONObject ad, final Runnable done) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout frame = new FrameLayout(activity);
        frame.setBackgroundColor(0xFF05141F);

        JSONObject creative = ad.optJSONObject("creative");
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (creative != null) {
            CigramAdsMedia.loadInto(activity, creative.optString("url", ""), image,
                    activity.getResources().getDisplayMetrics().widthPixels);
        }

        frame.addView(label(activity), new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.START));

        final TextView skip = CigramAdsUi.text(activity, "تخطي", 13f, 0xFFFFFFFF, true);
        skip.setGravity(Gravity.CENTER);
        skip.setBackground(CigramAdsUi.round(activity, 0x99000000, 16));
        skip.setPadding(CigramAdsUi.dp(activity, 14), CigramAdsUi.dp(activity, 8),
                CigramAdsUi.dp(activity, 14), CigramAdsUi.dp(activity, 9));
        FrameLayout.LayoutParams skipLp = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.END);
        skipLp.setMargins(0, CigramAdsUi.dp(activity, 14), CigramAdsUi.dp(activity, 14), 0);
        frame.addView(skip, skipLp);

        final Handler handler = new Handler(Looper.getMainLooper());
        final int[] seconds = {5};
        final Runnable tick = new Runnable() {
            @Override public void run() {
                seconds[0]--;
                if (seconds[0] <= 0) {
                    try {
                        dialog.dismiss();
                    } catch (Throwable ignored) { }
                    return;
                }
                skip.setText("تخطي · " + seconds[0]);
                handler.postDelayed(this, 1000L);
            }
        };
        skip.setText("تخطي · " + seconds[0]);
        handler.postDelayed(tick, 1000L);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) { }
            }
        });

        image.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                openAction(activity, ad);
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) { }
            }
        });

        dialog.setContentView(frame);
        dialog.setCancelable(false);
        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                handler.removeCallbacks(tick);
                done.run();
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF05141F));
        }
        try {
            dialog.show();
            if (window != null) window.setLayout(-1, -1);
            // A full-screen ad is "seen" the moment it is on screen.
            CigramAdSlots.impression(activity, ad);
        } catch (Throwable ignored) {
            done.run();
        }
    }

    /** popup: once a day, with a real close button. */
    public static void popup(final Activity activity) {
        if (activity == null || !CigramAdSlots.popupAllowedToday(activity)) return;
        CigramAdSlots.load(activity, CigramAdSlots.SLOT_POPUP, new CigramAdSlots.Callback() {
            @Override public void ready(List<JSONObject> ads) {
                if (ads == null || ads.isEmpty() || activity.isFinishing()) return;
                if (!CigramAdSlots.popupAllowedToday(activity)) return;
                CigramAdSlots.markPopupShown(activity);
                showPopup(activity, ads.get(0));
            }
        });
    }

    private static void showPopup(final Activity activity, final JSONObject ad) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final CigramAdSlotView host = new CigramAdSlotView(activity);
        host.slot = CigramAdSlots.SLOT_POPUP;
        host.ads.add(ad);

        LinearLayout column = CigramAdsUi.column(activity);
        column.setBackground(CigramAdsUi.round(activity, CigramAdsUi.CARD, CigramAdsUi.STROKE, 22));
        int p = CigramAdsUi.dp(activity, 12);
        column.setPadding(p, p, p, p);
        column.addView(host.cardView(ad), new LinearLayout.LayoutParams(-1, -2));

        TextView close = CigramAdsUi.ghostButton(activity, "إغلاق");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    dialog.dismiss();
                } catch (Throwable ignored) { }
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(activity, 48));
        lp.topMargin = CigramAdsUi.dp(activity, 12);
        column.addView(close, lp);

        dialog.setContentView(column);
        dialog.setCancelable(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.7f;
            window.setAttributes(attributes);
        }
        try {
            dialog.show();
            if (window != null) {
                window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.9f),
                        ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            CigramAdSlots.impression(activity, ad);
        } catch (Throwable ignored) { }
    }

    /** sticky: floats over the content at the bottom, dismissible for the day. */
    public static CigramAdSlotView sticky(Activity activity) {
        if (activity == null) return null;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return null;
        ViewGroup host = (ViewGroup) content;
        if (host.findViewWithTag("cigram_ad_sticky") != null) return null;

        CigramAdSlotView view = new CigramAdSlotView(activity);
        view.setTag("cigram_ad_sticky");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, 0, Gravity.BOTTOM);
        int m = CigramAdsUi.dp(activity, 10);
        lp.setMargins(m, 0, m, m);
        host.addView(view, lp);
        view.setSlot(CigramAdSlots.SLOT_STICKY);
        return view;
    }
}
