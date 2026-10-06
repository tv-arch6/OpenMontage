package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One look for every dialog of the app (v2.5): dark card, sima font, rounded controls, accent
 * colours taken from the app (red = main action, teal = information, danger = destructive).
 * Replaces the white system AlertDialog that did not match the application.
 */
public final class CigramUI {

    public static final int BG = 0xFF0B2733;
    public static final int BG_DEEP = 0xFF05141F;
    public static final int BG_SOFT = 0xFF102F3B;
    public static final int STROKE = 0xFF1F4553;
    public static final int RED = 0xFFE21B14;
    public static final int TEAL = 0xFF26A5B5;
    public static final int GREEN = 0xFF3DDC97;
    public static final int AMBER = 0xFFE9A23B;
    public static final int DANGER = 0xFFFF5252;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int MUTED = 0xFF8FA1AA;

    public static final int ICON_BELL = 1;
    public static final int ICON_WARN = 2;
    public static final int ICON_FLAG = 3;
    public static final int ICON_CHECK = 4;
    public static final int ICON_UPDATE = 5;
    public static final int ICON_TRASH = 6;
    public static final int ICON_MAIL = 7;
    public static final int ICON_LOCK = 8;
    public static final int ICON_LOGOUT = 9;
    public static final int ICON_PROGRESS = 10;
    public static final int ICON_CHEVRON = 11;
    public static final int ICON_USER = 12;
    public static final int ICON_INFO = 13;

    private CigramUI() { }

    // ---------------------------------------------------------------- helpers

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static Typeface font(Context c) {
        return CigramStage1InitProvider.cigramFont(c);
    }

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable round(int color, int strokeColor, float radiusDp, Context c) {
        GradientDrawable g = round(color, radiusDp, c);
        g.setStroke(dp(c, 1), strokeColor);
        return g;
    }

    public static TextView label(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(font(c), bold ? Typeface.BOLD : Typeface.NORMAL);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        return t;
    }

    public static void styleInput(EditText e, String hint) {
        Context c = e.getContext();
        e.setHint(hint);
        e.setHintTextColor(0xFF6F8591);
        e.setTextColor(TEXT);
        e.setTextSize(15f);
        e.setTypeface(font(c));
        e.setTextDirection(View.TEXT_DIRECTION_LOCALE);
        e.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        e.setBackground(round(BG_SOFT, STROKE, 14, c));
        e.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        e.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
    }

    // ------------------------------------------------------------------ icon

    /** Small drawn glyphs so no image resources are needed. */
    public static final class Glyph extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private int kind;
        private int color;

        public Glyph(Context c, int kind, int color) {
            super(c);
            this.kind = kind;
            this.color = color;
        }

        public void set(int kind, int color) {
            this.kind = kind;
            this.color = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float s = Math.min(w, h);
            float u = s / 24f;
            cv.save();
            cv.translate((w - s) / 2f, (h - s) / 2f);
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(2f * u);
            path.reset();
            switch (kind) {
                case ICON_BELL: {
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(5 * u, 17 * u);
                    path.lineTo(5 * u, 16 * u);
                    path.cubicTo(7 * u, 15 * u, 7 * u, 13 * u, 7 * u, 10 * u);
                    path.cubicTo(7 * u, 6.5f * u, 9.2f * u, 5 * u, 12 * u, 5 * u);
                    path.cubicTo(14.8f * u, 5 * u, 17 * u, 6.5f * u, 17 * u, 10 * u);
                    path.cubicTo(17 * u, 13 * u, 17 * u, 15 * u, 19 * u, 16 * u);
                    path.lineTo(19 * u, 17 * u);
                    path.close();
                    cv.drawPath(path, p);
                    cv.drawCircle(12 * u, 19.5f * u, 1.8f * u, p);
                    cv.drawCircle(12 * u, 4 * u, 1.3f * u, p);
                    break;
                }
                case ICON_WARN: {
                    p.setStyle(Paint.Style.STROKE);
                    path.moveTo(12 * u, 4 * u);
                    path.lineTo(21 * u, 19.5f * u);
                    path.lineTo(3 * u, 19.5f * u);
                    path.close();
                    cv.drawPath(path, p);
                    cv.drawLine(12 * u, 10 * u, 12 * u, 14.5f * u, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12 * u, 17 * u, 1.1f * u, p);
                    break;
                }
                case ICON_FLAG: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawLine(6 * u, 4 * u, 6 * u, 21 * u, p);
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(7 * u, 5 * u);
                    path.lineTo(18 * u, 5 * u);
                    path.lineTo(15 * u, 9.5f * u);
                    path.lineTo(18 * u, 14 * u);
                    path.lineTo(7 * u, 14 * u);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_CHECK: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.6f * u);
                    cv.drawCircle(12 * u, 12 * u, 9 * u, p);
                    path.moveTo(7.5f * u, 12.5f * u);
                    path.lineTo(10.8f * u, 15.8f * u);
                    path.lineTo(16.8f * u, 9 * u);
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_UPDATE: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.2f * u);
                    RectF r = new RectF(4 * u, 4 * u, 20 * u, 20 * u);
                    cv.drawArc(r, -40, 270, false, p);
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(15.2f * u, 2.5f * u);
                    path.lineTo(20.8f * u, 6.2f * u);
                    path.lineTo(14.2f * u, 8.6f * u);
                    path.close();
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_TRASH: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawLine(4 * u, 7 * u, 20 * u, 7 * u, p);
                    cv.drawLine(9 * u, 4 * u, 15 * u, 4 * u, p);
                    path.moveTo(6 * u, 7 * u);
                    path.lineTo(7 * u, 20 * u);
                    path.lineTo(17 * u, 20 * u);
                    path.lineTo(18 * u, 7 * u);
                    cv.drawPath(path, p);
                    cv.drawLine(10 * u, 11 * u, 10 * u, 16 * u, p);
                    cv.drawLine(14 * u, 11 * u, 14 * u, 16 * u, p);
                    break;
                }
                case ICON_MAIL: {
                    p.setStyle(Paint.Style.STROKE);
                    RectF r = new RectF(3 * u, 5.5f * u, 21 * u, 18.5f * u);
                    cv.drawRoundRect(r, 2.5f * u, 2.5f * u, p);
                    path.moveTo(3.8f * u, 7 * u);
                    path.lineTo(12 * u, 13.2f * u);
                    path.lineTo(20.2f * u, 7 * u);
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_LOCK: {
                    p.setStyle(Paint.Style.STROKE);
                    RectF r = new RectF(5 * u, 10.5f * u, 19 * u, 20.5f * u);
                    cv.drawRoundRect(r, 2.5f * u, 2.5f * u, p);
                    RectF a = new RectF(8 * u, 3.5f * u, 16 * u, 14 * u);
                    cv.drawArc(a, 180, 180, false, p);
                    cv.drawLine(8 * u, 8.7f * u, 8 * u, 10.5f * u, p);
                    cv.drawLine(16 * u, 8.7f * u, 16 * u, 10.5f * u, p);
                    break;
                }
                case ICON_LOGOUT: {
                    p.setStyle(Paint.Style.STROKE);
                    path.moveTo(10 * u, 4 * u);
                    path.lineTo(5 * u, 4 * u);
                    path.lineTo(5 * u, 20 * u);
                    path.lineTo(10 * u, 20 * u);
                    cv.drawPath(path, p);
                    cv.drawLine(10 * u, 12 * u, 20 * u, 12 * u, p);
                    path.reset();
                    path.moveTo(16.5f * u, 8.5f * u);
                    path.lineTo(20 * u, 12 * u);
                    path.lineTo(16.5f * u, 15.5f * u);
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_CHEVRON: {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(2.4f * u);
                    path.moveTo(14.5f * u, 5.5f * u);
                    path.lineTo(8 * u, 12 * u);
                    path.lineTo(14.5f * u, 18.5f * u);
                    cv.drawPath(path, p);
                    break;
                }
                case ICON_USER: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawCircle(12 * u, 8 * u, 3.8f * u, p);
                    RectF a = new RectF(4.5f * u, 13.5f * u, 19.5f * u, 28 * u);
                    cv.drawArc(a, 195, 150, false, p);
                    break;
                }
                case ICON_INFO: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawCircle(12 * u, 12 * u, 9 * u, p);
                    cv.drawLine(12 * u, 11 * u, 12 * u, 16.5f * u, p);
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(12 * u, 7.8f * u, 1.2f * u, p);
                    break;
                }
                default: {
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawCircle(12 * u, 12 * u, 8 * u, p);
                    break;
                }
            }
            cv.restore();
        }
    }

    // ----------------------------------------------------------------- sheet

    public interface Click {
        /** Return true to keep the dialog open (e.g. a validation error). */
        boolean onClick(Sheet sheet);
    }

    /** Builder + handle of one dialog. */
    public static final class Sheet {
        public final Activity activity;
        public final Dialog dialog;
        public final LinearLayout card;
        public final LinearLayout body;
        private final LinearLayout buttons;
        private final Glyph glyph;
        private final FrameBadge badge;
        private final TextView titleView;
        private final TextView messageView;
        private final LinearLayout.LayoutParams badgeLp;
        private boolean cancelable = true;

        private static final class FrameBadge extends LinearLayout {
            FrameBadge(Context c) { super(c); }
        }

        public Sheet(Activity a) {
            activity = a;
            dialog = new Dialog(a);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            card = new LinearLayout(a);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            card.setPadding(dp(a, 22), dp(a, 22), dp(a, 22), dp(a, 18));
            card.setBackground(round(BG, STROKE, 26, a));

            badge = new FrameBadge(a);
            badge.setGravity(Gravity.CENTER);
            glyph = new Glyph(a, ICON_BELL, TEAL);
            badge.addView(glyph, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
            badgeLp = new LinearLayout.LayoutParams(dp(a, 56), dp(a, 56));
            badgeLp.gravity = Gravity.RIGHT;
            badge.setVisibility(View.GONE);
            card.addView(badge, badgeLp);

            titleView = label(a, "", 20f, TEXT, true);
            titleView.setVisibility(View.GONE);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-1, -2);
            tl.topMargin = dp(a, 12);
            card.addView(titleView, tl);

            messageView = label(a, "", 14.5f, 0xCCFFFFFF, false);
            messageView.setLineSpacing(0f, 1.2f);
            messageView.setGravity(Gravity.RIGHT | Gravity.TOP);
            messageView.setVisibility(View.GONE);
            LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, -2);
            ml.topMargin = dp(a, 8);
            card.addView(messageView, ml);

            body = new LinearLayout(a);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            card.addView(body, new LinearLayout.LayoutParams(-1, -2));

            buttons = new LinearLayout(a);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            buttons.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-1, -2);
            bl.topMargin = dp(a, 18);
            buttons.setVisibility(View.GONE);
            card.addView(buttons, bl);
        }

        public Sheet icon(int kind, int color) {
            glyph.set(kind, color);
            int tint = (color & 0x00FFFFFF) | 0x26000000;
            badge.setBackground(round(tint, 18, activity));
            badge.setVisibility(View.VISIBLE);
            return this;
        }

        public Sheet title(String text) {
            titleView.setText(text);
            titleView.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
            return this;
        }

        public Sheet message(String text) {
            messageView.setText(text);
            messageView.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
            return this;
        }

        public Sheet view(View v) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(activity, 14);
            body.addView(v, lp);
            return this;
        }

        public Sheet cancelable(boolean value) {
            cancelable = value;
            return this;
        }

        public Sheet clearButtons() {
            buttons.removeAllViews();
            buttons.setVisibility(View.GONE);
            return this;
        }

        /** style: 0 = filled accent, 1 = outlined, 2 = filled danger. First button added sits on the right. */
        public TextView button(String text, int style, int accent, final Click click) {
            TextView b = new TextView(activity);
            b.setText(text);
            b.setTextSize(15f);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(font(activity), Typeface.BOLD);
            b.setClickable(true);
            b.setFocusable(true);
            if (style == 1) {
                b.setTextColor(0xFFD7E2E7);
                b.setBackground(round(BG_SOFT, STROKE, 15, activity));
            } else {
                b.setTextColor(style == 2 ? TEXT : (accent == GREEN || accent == AMBER || accent == TEAL ? 0xFF04161D : TEXT));
                b.setBackground(round(accent, 15, activity));
            }
            b.setPadding(dp(activity, 12), dp(activity, 13), dp(activity, 12), dp(activity, 13));
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean keep = false;
                    if (click != null) keep = click.onClick(Sheet.this);
                    if (!keep) dismiss();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            if (buttons.getChildCount() > 0) lp.setMarginEnd(dp(activity, 10));
            buttons.addView(b, lp);
            buttons.setVisibility(View.VISIBLE);
            return b;
        }

        public Sheet primary(String text, int accent, Click click) {
            button(text, 0, accent, click);
            return this;
        }

        public Sheet danger(String text, Click click) {
            button(text, 2, DANGER, click);
            return this;
        }

        public Sheet secondary(String text, Click click) {
            button(text, 1, 0, click);
            return this;
        }

        public Sheet spinner(String text) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ProgressBar pb = new ProgressBar(activity);
            pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(TEAL));
            row.addView(pb, new LinearLayout.LayoutParams(dp(activity, 26), dp(activity, 26)));
            TextView t = label(activity, text, 14.5f, 0xCCFFFFFF, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMarginStart(dp(activity, 12));
            row.addView(t, lp);
            view(row);
            return this;
        }

        public void dismiss() {
            try {
                if (dialog.isShowing()) dialog.dismiss();
            } catch (Throwable ignored) { }
        }

        public Sheet show() {
            if (activity == null || activity.isFinishing()) return this;
            try {
                final int maxH = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.86f);
                ScrollView scroll = new ScrollView(activity) {
                    @Override protected void onMeasure(int w, int h) {
                        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
                    }
                };
                scroll.setVerticalScrollBarEnabled(false);
                scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
                scroll.setFillViewport(false);
                scroll.addView(card, new ViewGroup.LayoutParams(-1, -2));
                dialog.setContentView(scroll);
                dialog.setCancelable(cancelable);
                dialog.setCanceledOnTouchOutside(cancelable);
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    WindowManager.LayoutParams at = w.getAttributes();
                    at.dimAmount = 0.66f;
                    w.setAttributes(at);
                    w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                }
                dialog.show();
                if (w != null) {
                    w.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f),
                            WindowManager.LayoutParams.WRAP_CONTENT);
                }
            } catch (Throwable error) {
                android.util.Log.w("CigramUI", "dialog failed", error);
            }
            return this;
        }
    }

    // --------------------------------------------------------------- shortcuts

    public static Sheet sheet(Activity a) {
        return new Sheet(a);
    }

    public static Sheet info(Activity a, int icon, int accent, String title, String message, String ok) {
        return sheet(a).icon(icon, accent).title(title).message(message).primary(ok, accent, null);
    }
}
