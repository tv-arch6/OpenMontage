package com.Cigram.vid;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * The visual kit shared by every company-ads screen.
 *
 * It deliberately adds no resources: colours, radii and the font all come from
 * {@link CigramUI}, the same source the rest of the app already uses, so the ads
 * screens cannot drift away from the app's look. Everything is built in code,
 * matching how the rest of this project builds its UI.
 *
 * Layout rules kept throughout: an 8dp grid, a 48dp minimum touch target, RTL by
 * default, and a text colour pair that keeps contrast on the dark background.
 */
public final class CigramAdsUi {

    // palette — CigramUI is the source of truth; these are only names for intent
    public static final int PAGE = CigramUI.BG_DEEP;     // 05141F
    public static final int CARD = CigramUI.BG;          // 0B2733
    public static final int CARD_SOFT = CigramUI.BG_SOFT; // 102F3B
    public static final int STROKE = CigramUI.STROKE;    // 1F4553
    public static final int ACCENT = CigramUI.TEAL;      // 26A5B5
    public static final int PRIMARY = CigramUI.RED;      // E21B14
    public static final int GOOD = CigramUI.GREEN;
    public static final int WARN = CigramUI.AMBER;
    public static final int BAD = CigramUI.DANGER;
    public static final int TEXT = CigramUI.TEXT;
    public static final int MUTED = CigramUI.MUTED;
    public static final int DIM = 0xFF6F8591;

    /** Grid unit. Every margin/padding in the ads screens is a multiple of this. */
    public static final int GRID = 8;

    private CigramAdsUi() { }

    // ------------------------------------------------------------------ basics

    public static int dp(Context c, float v) {
        return CigramUI.dp(c, v);
    }

    public static Typeface font(Context c) {
        return CigramUI.font(c);
    }

    public static GradientDrawable round(Context c, int fill, float radiusDp) {
        return CigramUI.round(fill, radiusDp, c);
    }

    public static GradientDrawable round(Context c, int fill, int stroke, float radiusDp) {
        return CigramUI.round(fill, stroke, radiusDp, c);
    }

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /** Ripple over a rounded background, with the mask clipped to the same radius. */
    public static Drawable ripple(Context c, Drawable background, int rippleColor, float radiusDp) {
        GradientDrawable mask = round(c, 0xFFFFFFFF, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(alpha(rippleColor, 0x40)), background, mask);
    }

    /** Press feedback used on every tappable surface: ripple + a 2% scale dip. */
    public static void pressable(final View view, Drawable background, int rippleColor, float radiusDp) {
        view.setBackground(ripple(view.getContext(), background, rippleColor, radiusDp));
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90L).start();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150L).start();
                }
                return false; // never swallow the click
            }
        });
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h, float m) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        int v = dp(c, m);
        p.setMargins(v, v, v, v);
        return p;
    }

    public static LinearLayout.LayoutParams lp(Context c, int w, int h,
                                               float start, float top, float end, float bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMarginStart(dp(c, start));
        p.topMargin = dp(c, top);
        p.setMarginEnd(dp(c, end));
        p.bottomMargin = dp(c, bottom);
        return p;
    }

    public static View space(Context c, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, heightDp)));
        return v;
    }

    public static View divider(Context c, float insetDp) {
        View v = new View(c);
        v.setBackgroundColor(alpha(STROKE, 0x8C));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 0.7f)));
        p.setMarginStart(dp(c, insetDp));
        p.setMarginEnd(dp(c, insetDp));
        v.setLayoutParams(p);
        return v;
    }

    /** The standard content card: soft fill, hairline stroke, 20dp corners. */
    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(round(c, CARD, STROKE, 20));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    // ------------------------------------------------------------------- text

    public static TextView text(Context c, CharSequence value, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(c), bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0f, 1.25f);
        return t;
    }

    public static TextView title(Context c, CharSequence value) {
        TextView t = text(c, value, 19f, TEXT, true);
        t.setGravity(Gravity.RIGHT | Gravity.TOP);
        return t;
    }

    public static TextView body(Context c, CharSequence value) {
        TextView t = text(c, value, 14.5f, 0xCCFFFFFF, false);
        t.setGravity(Gravity.RIGHT | Gravity.TOP);
        return t;
    }

    public static TextView muted(Context c, CharSequence value) {
        TextView t = text(c, value, 13f, MUTED, false);
        t.setGravity(Gravity.RIGHT | Gravity.TOP);
        return t;
    }

    /** Section heading with a short accent rule under it. */
    public static LinearLayout sectionHeader(Context c, String heading, String sub) {
        LinearLayout box = column(c);
        box.addView(title(c, heading), new LinearLayout.LayoutParams(-1, -2));
        View rule = new View(c);
        rule.setBackground(round(c, ACCENT, 2));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(dp(c, 34), dp(c, 3));
        rp.topMargin = dp(c, 6);
        box.addView(rule, rp);
        if (sub != null && sub.length() > 0) {
            TextView s = muted(c, sub);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(c, 8);
            box.addView(s, sp);
        }
        return box;
    }

    /** Small pill, e.g. "جديد" or a campaign status. */
    public static TextView badge(Context c, String label, int color) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(11f);
        t.setTextColor(color);
        t.setTypeface(font(c), Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(c, alpha(color, 0x26), alpha(color, 0x66), 9));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 4));
        t.setIncludeFontPadding(false);
        return t;
    }

    // ------------------------------------------------------------------ icons

    public static final int ICON_MEGAPHONE = 1;
    public static final int ICON_CALC = 2;
    public static final int ICON_SHIELD = 3;
    public static final int ICON_QUESTION = 4;
    public static final int ICON_CHAT = 5;
    public static final int ICON_WHATSAPP = 6;
    public static final int ICON_X = 7;
    public static final int ICON_CHECK = 8;
    public static final int ICON_CROSS = 9;
    public static final int ICON_TARGET = 10;
    public static final int ICON_CHART = 11;
    public static final int ICON_CHEVRON_START = 12; // points the way the page reads (RTL: left)
    public static final int ICON_CHEVRON_DOWN = 13;
    public static final int ICON_CLOCK = 14;
    public static final int ICON_SPARK = 15;
    public static final int ICON_DOC = 16;
    public static final int ICON_USERS = 17;
    public static final int ICON_EYE = 18;
    public static final int ICON_GEAR = 19;
    public static final int ICON_FONT = 20;
    public static final int ICON_BOOKMARK = 21;
    public static final int ICON_UPDATE = 22;
    public static final int ICON_MAIL = 23;

    /** Vector glyphs drawn on a 24x24 grid: no drawable resources, scales to any size. */
    public static final class Icon extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int kind;
        private int color;

        public Icon(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        public void set(int kind, int color) {
            this.kind = kind;
            this.color = color;
            invalidate();
        }

        public void tint(int color) {
            this.color = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            if (s <= 0f) return;
            float u = s / 24f;
            cv.save();
            cv.translate((w - s) / 2f, (h - s) / 2f);
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(2f * u);
            p.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (kind) {
                case ICON_MEGAPHONE: {
                    path.moveTo(4 * u, 10 * u);
                    path.lineTo(10 * u, 10 * u);
                    path.lineTo(19 * u, 5 * u);
                    path.lineTo(19 * u, 19 * u);
                    path.lineTo(10 * u, 14 * u);
                    path.lineTo(4 * u, 14 * u);
                    path.close();
                    cv.drawPath(path, p);
                    cv.drawLine(7 * u, 14 * u, 8.5f * u, 20 * u, p);
                    break;
                }
                case ICON_CALC: {
                    cv.drawRoundRect(new RectF(5 * u, 3 * u, 19 * u, 21 * u), 2.5f * u, 2.5f * u, p);
                    cv.drawLine(8 * u, 7.5f * u, 16 * u, 7.5f * u, p);
                    p.setStyle(Paint.Style.FILL);
                    for (int r = 0; r < 3; r++) {
                        for (int cI = 0; cI < 3; cI++) {
                            cv.drawCircle((8.5f + cI * 3.5f) * u, (12f + r * 3.2f) * u, 0.95f * u, p);
                        }
                    }
                    break;
                }
                case ICON_SHIELD: {
                    path.moveTo(12 * u, 3 * u);
                    path.lineTo(20 * u, 6 * u);
                    path.lineTo(20 * u, 12 * u);
                    path.cubicTo(20 * u, 17 * u, 16 * u, 20 * u, 12 * u, 21.5f * u);
                    path.cubicTo(8 * u, 20 * u, 4 * u, 17 * u, 4 * u, 12 * u);
                    path.lineTo(4 * u, 6 * u);
                    path.close();
                    cv.drawPath(path, p);
                    path.reset();
                    path.moveTo(9 * u, 12 * u);
                    path.lineTo(11.3f * u, 14.3f * u);
                    path.lineTo(15.3f * u, 9.6f * u);
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_QUESTION: {
                    cv.drawCircle(12 * u, 12 * u, 9 * u, p);
                    RectF arc = new RectF(9 * u, 6 * u, 15 * u, 12 * u);
                    cv.drawArc(arc, 160, 230, false, p);
                    cv.drawLine(12 * u, 12 * u, 12 * u, 14.6f * u, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12 * u, 17.4f * u, 1.1f * u, p);
                    break;
                }
                case ICON_CHAT: {
                    path.moveTo(4 * u, 7 * u);
                    path.cubicTo(4 * u, 5 * u, 5.5f * u, 4 * u, 7.5f * u, 4 * u);
                    path.lineTo(16.5f * u, 4 * u);
                    path.cubicTo(18.5f * u, 4 * u, 20 * u, 5 * u, 20 * u, 7 * u);
                    path.lineTo(20 * u, 14 * u);
                    path.cubicTo(20 * u, 16 * u, 18.5f * u, 17 * u, 16.5f * u, 17 * u);
                    path.lineTo(10 * u, 17 * u);
                    path.lineTo(6 * u, 20.5f * u);
                    path.lineTo(6 * u, 17 * u);
                    path.cubicTo(4.8f * u, 16.6f * u, 4 * u, 15.6f * u, 4 * u, 14 * u);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_WHATSAPP: {
                    cv.drawCircle(12 * u, 12 * u, 8.6f * u, p);
                    p.setStrokeWidth(1.9f * u);
                    path.moveTo(9 * u, 8.6f * u);
                    path.cubicTo(8.2f * u, 10.6f * u, 9.4f * u, 13.2f * u, 11 * u, 14.6f * u);
                    path.cubicTo(12.4f * u, 15.9f * u, 14.4f * u, 16.4f * u, 15.6f * u, 15.3f * u);
                    path.lineTo(14.2f * u, 13.5f * u);
                    path.lineTo(12.6f * u, 14.1f * u);
                    path.lineTo(10.6f * u, 12 * u);
                    path.lineTo(11.1f * u, 10.4f * u);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_X: {
                    p.setStrokeWidth(2.3f * u);
                    cv.drawLine(5 * u, 5 * u, 19 * u, 19 * u, p);
                    cv.drawLine(19 * u, 5 * u, 5 * u, 19 * u, p);
                    break;
                }
                case ICON_CHECK: {
                    p.setStrokeWidth(2.4f * u);
                    cv.drawCircle(12 * u, 12 * u, 8.6f * u, p);
                    path.moveTo(7.6f * u, 12.4f * u);
                    path.lineTo(10.8f * u, 15.6f * u);
                    path.lineTo(16.6f * u, 9 * u);
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_CROSS: {
                    p.setStrokeWidth(2.4f * u);
                    cv.drawCircle(12 * u, 12 * u, 8.6f * u, p);
                    cv.drawLine(8.6f * u, 8.6f * u, 15.4f * u, 15.4f * u, p);
                    cv.drawLine(15.4f * u, 8.6f * u, 8.6f * u, 15.4f * u, p);
                    break;
                }
                case ICON_TARGET: {
                    cv.drawCircle(12 * u, 12 * u, 8.6f * u, p);
                    cv.drawCircle(12 * u, 12 * u, 4.8f * u, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12 * u, 12 * u, 1.7f * u, p);
                    break;
                }
                case ICON_CHART: {
                    cv.drawLine(4 * u, 20 * u, 20 * u, 20 * u, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawRoundRect(new RectF(6 * u, 12 * u, 9.5f * u, 19 * u), u, u, p);
                    cv.drawRoundRect(new RectF(10.5f * u, 7 * u, 14 * u, 19 * u), u, u, p);
                    cv.drawRoundRect(new RectF(15 * u, 14.5f * u, 18.5f * u, 19 * u), u, u, p);
                    break;
                }
                case ICON_CHEVRON_START: {
                    p.setStrokeWidth(2.3f * u);
                    boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                    if (rtl) {
                        path.moveTo(14.5f * u, 5.5f * u);
                        path.lineTo(8 * u, 12 * u);
                        path.lineTo(14.5f * u, 18.5f * u);
                    } else {
                        path.moveTo(9.5f * u, 5.5f * u);
                        path.lineTo(16 * u, 12 * u);
                        path.lineTo(9.5f * u, 18.5f * u);
                    }
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_CHEVRON_DOWN: {
                    p.setStrokeWidth(2.3f * u);
                    path.moveTo(6 * u, 9.5f * u);
                    path.lineTo(12 * u, 15.5f * u);
                    path.lineTo(18 * u, 9.5f * u);
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_CLOCK: {
                    cv.drawCircle(12 * u, 12 * u, 8.6f * u, p);
                    cv.drawLine(12 * u, 7 * u, 12 * u, 12 * u, p);
                    cv.drawLine(12 * u, 12 * u, 15.5f * u, 14 * u, p);
                    break;
                }
                case ICON_SPARK: {
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(12 * u, 2.5f * u);
                    path.lineTo(14 * u, 9.6f * u);
                    path.lineTo(21 * u, 11.6f * u);
                    path.lineTo(14 * u, 13.6f * u);
                    path.lineTo(12 * u, 20.8f * u);
                    path.lineTo(10 * u, 13.6f * u);
                    path.lineTo(3 * u, 11.6f * u);
                    path.lineTo(10 * u, 9.6f * u);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_DOC: {
                    path.moveTo(6 * u, 3.5f * u);
                    path.lineTo(14 * u, 3.5f * u);
                    path.lineTo(18.5f * u, 8 * u);
                    path.lineTo(18.5f * u, 20.5f * u);
                    path.lineTo(6 * u, 20.5f * u);
                    path.close();
                    cv.drawPath(path, p);
                    cv.drawLine(9 * u, 12 * u, 15.5f * u, 12 * u, p);
                    cv.drawLine(9 * u, 15.5f * u, 15.5f * u, 15.5f * u, p);
                    break;
                }
                case ICON_USERS: {
                    cv.drawCircle(9.5f * u, 8.5f * u, 3.6f * u, p);
                    cv.drawArc(new RectF(3 * u, 13.5f * u, 16 * u, 26 * u), 195, 150, false, p);
                    p.setStrokeWidth(1.7f * u);
                    cv.drawArc(new RectF(13.5f * u, 5.4f * u, 20.5f * u, 12.4f * u), 300, 180, false, p);
                    cv.drawArc(new RectF(13 * u, 13.8f * u, 22 * u, 24 * u), 250, 95, false, p);
                    break;
                }
                case ICON_EYE: {
                    path.moveTo(2.5f * u, 12 * u);
                    path.cubicTo(6 * u, 6.5f * u, 18 * u, 6.5f * u, 21.5f * u, 12 * u);
                    path.cubicTo(18 * u, 17.5f * u, 6 * u, 17.5f * u, 2.5f * u, 12 * u);
                    path.close();
                    cv.drawPath(path, p);
                    cv.drawCircle(12 * u, 12 * u, 3.2f * u, p);
                    break;
                }
                case ICON_GEAR: {
                    cv.drawCircle(12 * u, 12 * u, 3.4f * u, p);
                    p.setStrokeWidth(1.8f * u);
                    for (int i = 0; i < 8; i++) {
                        double angle = Math.PI * i / 4.0;
                        float cos = (float) Math.cos(angle);
                        float sin = (float) Math.sin(angle);
                        cv.drawLine(12 * u + cos * 6f * u, 12 * u + sin * 6f * u,
                                12 * u + cos * 9.2f * u, 12 * u + sin * 9.2f * u, p);
                    }
                    break;
                }
                case ICON_FONT: {
                    p.setStrokeWidth(2.2f * u);
                    path.moveTo(5.5f * u, 19.5f * u);
                    path.lineTo(11 * u, 5 * u);
                    path.lineTo(13 * u, 5 * u);
                    path.lineTo(18.5f * u, 19.5f * u);
                    cv.drawPath(path, p);
                    cv.drawLine(8.4f * u, 14.4f * u, 15.6f * u, 14.4f * u, p);
                    break;
                }
                case ICON_BOOKMARK: {
                    path.moveTo(6.5f * u, 3.5f * u);
                    path.lineTo(17.5f * u, 3.5f * u);
                    path.lineTo(17.5f * u, 20.5f * u);
                    path.lineTo(12 * u, 16 * u);
                    path.lineTo(6.5f * u, 20.5f * u);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_UPDATE: {
                    p.setStrokeWidth(2.1f * u);
                    cv.drawArc(new RectF(4 * u, 4 * u, 20 * u, 20 * u), -40, 270, false, p);
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(15.2f * u, 2.5f * u);
                    path.lineTo(20.8f * u, 6.2f * u);
                    path.lineTo(14.2f * u, 8.6f * u);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_MAIL: {
                    cv.drawRoundRect(new RectF(3 * u, 5.5f * u, 21 * u, 18.5f * u), 2.5f * u, 2.5f * u, p);
                    path.moveTo(3.8f * u, 7 * u);
                    path.lineTo(12 * u, 13.2f * u);
                    path.lineTo(20.2f * u, 7 * u);
                    cv.drawPath(path, p);
                    break;
                }
                default: {
                    cv.drawCircle(12 * u, 12 * u, 8 * u, p);
                    break;
                }
            }
            cv.restore();
        }
    }

    /** Icon inside a tinted rounded square — the one icon treatment used everywhere. */
    public static FrameLayout iconBox(Context c, int kind, int color, float sizeDp) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(round(c, alpha(color, 0x24), 14));
        Icon icon = new Icon(c, kind, color);
        int inner = dp(c, sizeDp * 0.54f);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER);
        box.addView(icon, lp);
        return box;
    }

    // -------------------------------------------------------------- list rows

    /**
     * The one row shape used by the account list and by every list inside the ads
     * screens: 56dp tall, tinted icon box, title, optional subtitle, RTL chevron.
     */
    public static LinearLayout listRow(Context c, int iconKind, int iconColor, String titleText,
                                       String subtitleText, View trailing, View.OnClickListener click) {
        LinearLayout r = row(c);
        r.setMinimumHeight(dp(c, 56));
        r.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        pressable(r, round(c, Color.TRANSPARENT, 16), ACCENT, 16);
        if (click != null) r.setOnClickListener(click);

        int boxSize = dp(c, 38);
        r.addView(iconBox(c, iconKind, iconColor, 38f), new LinearLayout.LayoutParams(boxSize, boxSize));

        LinearLayout texts = column(c);
        TextView t = text(c, titleText, 15.5f, TEXT, false);
        t.setMaxLines(1);
        t.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(t, new LinearLayout.LayoutParams(-1, -2));
        if (subtitleText != null && subtitleText.length() > 0) {
            TextView s = text(c, subtitleText, 12.5f, MUTED, false);
            s.setMaxLines(1);
            s.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(c, 2);
            texts.addView(s, sp);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(c, 12));
        tp.setMarginEnd(dp(c, 12));
        r.addView(texts, tp);

        if (trailing != null) {
            LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(-2, -2);
            xp.setMarginEnd(dp(c, 8));
            r.addView(trailing, xp);
        }

        Icon chevron = new Icon(c, ICON_CHEVRON_START, DIM);
        r.addView(chevron, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));

        r.setContentDescription(titleText);
        return r;
    }

    // ------------------------------------------------------------- accordion

    /**
     * Collapsible section. The body is measured once and animated by height, so a
     * long policy text expands smoothly without a jump.
     */
    public static LinearLayout accordion(final Context c, String heading, final View content) {
        final LinearLayout wrapper = column(c);
        wrapper.setBackground(round(c, CARD, STROKE, 16));
        wrapper.setClipChildren(true);

        final LinearLayout header = row(c);
        header.setMinimumHeight(dp(c, 52));
        header.setPadding(dp(c, 14), dp(c, 10), dp(c, 12), dp(c, 10));
        pressable(header, round(c, Color.TRANSPARENT, 16), ACCENT, 16);

        TextView h = text(c, heading, 15f, TEXT, true);
        h.setMaxLines(2);
        header.addView(h, new LinearLayout.LayoutParams(0, -2, 1f));

        final Icon arrow = new Icon(c, ICON_CHEVRON_DOWN, ACCENT);
        header.addView(arrow, new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
        wrapper.addView(header, new LinearLayout.LayoutParams(-1, -2));

        final LinearLayout bodyBox = column(c);
        bodyBox.setPadding(dp(c, 14), 0, dp(c, 14), dp(c, 14));
        bodyBox.addView(content, new LinearLayout.LayoutParams(-1, -2));
        bodyBox.setVisibility(View.GONE);
        wrapper.addView(bodyBox, new LinearLayout.LayoutParams(-1, -2));

        header.setContentDescription(heading);
        header.setOnClickListener(new View.OnClickListener() {
            private boolean open = false;

            @Override public void onClick(View v) {
                open = !open;
                arrow.animate().rotation(open ? 180f : 0f).setDuration(200L).start();
                if (open) {
                    bodyBox.setVisibility(View.VISIBLE);
                    bodyBox.measure(
                            View.MeasureSpec.makeMeasureSpec(wrapper.getWidth(), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    animateHeight(bodyBox, 0, bodyBox.getMeasuredHeight(), false);
                } else {
                    animateHeight(bodyBox, bodyBox.getHeight(), 0, true);
                }
            }
        });
        return wrapper;
    }

    private static void animateHeight(final View view, int from, int to, final boolean hideAtEnd) {
        ValueAnimator a = ValueAnimator.ofInt(from, to);
        a.setDuration(220L);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp == null) return;
                lp.height = (Integer) animation.getAnimatedValue();
                view.setLayoutParams(lp);
            }
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp != null) {
                    lp.height = hideAtEnd ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
                    view.setLayoutParams(lp);
                }
                if (hideAtEnd) view.setVisibility(View.GONE);
            }
        });
        a.start();
    }

    // -------------------------------------------------------------- skeleton

    /** Shimmering placeholder block — used instead of a spinner while content loads. */
    public static final class Shimmer extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private ValueAnimator animator;

        public Shimmer(Context c, float radiusDp) {
            super(c);
            this.radius = dp(c, radiusDp);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            animator = ValueAnimator.ofFloat(0.35f, 1f);
            animator.setDuration(900L);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) {
                    setAlpha((Float) a.getAnimatedValue());
                }
            });
            animator.start();
        }

        /** Stops the loop as soon as the view leaves the window: no animator leak. */
        @Override protected void onDetachedFromWindow() {
            if (animator != null) {
                animator.cancel();
                animator.removeAllUpdateListeners();
                animator = null;
            }
            super.onDetachedFromWindow();
        }

        @Override protected void onDraw(Canvas canvas) {
            paint.setColor(alpha(0xFFFFFFFF, 0x14));
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), radius, radius, paint);
        }
    }

    public static View shimmer(Context c, int widthDp, int heightDp, float radiusDp, float topDp) {
        Shimmer s = new Shimmer(c, radiusDp);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                widthDp < 0 ? -1 : dp(c, widthDp), dp(c, heightDp));
        p.topMargin = dp(c, topDp);
        s.setLayoutParams(p);
        return s;
    }

    /** A card-shaped skeleton that stands in for one content block. */
    public static LinearLayout skeletonCard(Context c, int lines) {
        LinearLayout box = card(c);
        box.addView(shimmer(c, 140, 18, 6, 0));
        for (int i = 0; i < Math.max(1, lines); i++) {
            box.addView(shimmer(c, -1, 13, 5, 10));
        }
        box.addView(shimmer(c, 180, 13, 5, 10));
        return box;
    }

    // ------------------------------------------------------- empty / error

    public static LinearLayout emptyState(Context c, int iconKind, String heading, String sub) {
        LinearLayout box = column(c);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(c, 24), dp(c, 32), dp(c, 24), dp(c, 32));
        int size = dp(c, 64);
        box.addView(iconBox(c, iconKind, MUTED, 64f), new LinearLayout.LayoutParams(size, size));
        TextView t = text(c, heading, 16f, TEXT, true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(c, 14);
        box.addView(t, tp);
        if (sub != null && sub.length() > 0) {
            TextView s = text(c, sub, 13.5f, MUTED, false);
            s.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = dp(c, 6);
            box.addView(s, sp);
        }
        return box;
    }

    public static LinearLayout errorState(Context c, String message, final Runnable retry) {
        LinearLayout box = card(c);
        box.setBackground(round(c, alpha(BAD, 0x16), alpha(BAD, 0x55), 18));
        LinearLayout head = row(c);
        int size = dp(c, 34);
        head.addView(iconBox(c, ICON_CROSS, BAD, 34f), new LinearLayout.LayoutParams(size, size));
        TextView t = text(c, message, 14f, 0xEEFFFFFF, false);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(dp(c, 10));
        head.addView(t, tp);
        box.addView(head, new LinearLayout.LayoutParams(-1, -2));
        if (retry != null) {
            TextView button = primaryButton(c, "أعد المحاولة", ACCENT);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    retry.run();
                }
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, dp(c, 46));
            bp.topMargin = dp(c, 12);
            box.addView(button, bp);
        }
        return box;
    }

    // ---------------------------------------------------------------- buttons

    public static TextView primaryButton(Context c, String label, int accent) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(15.5f);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(font(c), Typeface.BOLD);
        b.setTextColor(accent == ACCENT || accent == GOOD || accent == WARN ? 0xFF04161D : TEXT);
        b.setMinHeight(dp(c, 48));
        pressable(b, round(c, accent, 15), 0xFFFFFFFF, 15);
        b.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return b;
    }

    public static TextView ghostButton(Context c, String label) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(15f);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(font(c), Typeface.BOLD);
        b.setTextColor(0xFFD7E2E7);
        b.setMinHeight(dp(c, 48));
        pressable(b, round(c, CARD_SOFT, STROKE, 15), ACCENT, 15);
        b.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return b;
    }

    /** Disabled styling for the "read the policies first" state. */
    public static void setEnabled(TextView button, boolean enabled, String disabledHint) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.55f);
        if (!enabled && disabledHint != null) button.setContentDescription(disabledHint);
    }

    /** Selectable chip used by the calculator (duration, city, hour, add-on). */
    public static TextView chip(Context c, String label, boolean selected) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(13.5f);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(font(c), selected ? Typeface.BOLD : Typeface.NORMAL);
        t.setMinHeight(dp(c, 40));
        t.setMinWidth(dp(c, 56));
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        chipState(t, selected);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    public static void chipState(TextView t, boolean selected) {
        Context c = t.getContext();
        t.setTextColor(selected ? 0xFF04161D : 0xFFD7E2E7);
        t.setTypeface(font(c), selected ? Typeface.BOLD : Typeface.NORMAL);
        t.setBackground(ripple(c,
                selected ? round(c, ACCENT, 12) : round(c, CARD_SOFT, STROKE, 12),
                selected ? 0xFFFFFFFF : ACCENT, 12));
        t.setSelected(selected);
    }

    // ------------------------------------------------------------ animations

    /** Counts a number up when its section first appears. Cancelled with the view. */
    public static void countUp(final TextView view, final long target, final String suffix) {
        if (view == null) return;
        if (target <= 0L) {
            view.setText(formatNumber(0) + (suffix == null ? "" : suffix));
            return;
        }
        final ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(Math.min(1400L, 600L + target / 50L));
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float f = (Float) animation.getAnimatedValue();
                view.setText(formatNumber((long) (target * f)) + (suffix == null ? "" : suffix));
            }
        });
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { }

            @Override public void onViewDetachedFromWindow(View v) {
                a.cancel();
            }
        });
        a.start();
    }

    /** Fade + rise used when a section scrolls into view. Runs at most once per view. */
    public static void revealOnce(final View view, long delayMs) {
        if (view == null || Boolean.TRUE.equals(view.getTag(TAG_REVEALED))) return;
        view.setTag(TAG_REVEALED, Boolean.TRUE);
        view.setAlpha(0f);
        view.setTranslationY(dp(view.getContext(), 14));
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(delayMs)
                .setDuration(380L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** A private tag key so reveal state cannot collide with another feature's tags. */
    private static final int TAG_REVEALED = 0x7F0A0A01;

    public static String formatNumber(long value) {
        if (value >= 1000000L) {
            return trimZero(value / 1000000.0) + " مليون";
        }
        if (value >= 1000L) {
            return trimZero(value / 1000.0) + " ألف";
        }
        return String.valueOf(value);
    }

    private static String trimZero(double value) {
        String s = String.format(Locale.US, "%.1f", value);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }

    /** Money shown the way the server sent it: value + the configured currency label. */
    public static String money(double value, String currencyLabel) {
        String number;
        if (Math.abs(value - Math.rint(value)) < 0.005) {
            number = String.format(Locale.US, "%,d", Math.round(value));
        } else {
            number = String.format(Locale.US, "%,.2f", value);
        }
        return number + " " + (currencyLabel == null ? "" : currencyLabel);
    }
}
