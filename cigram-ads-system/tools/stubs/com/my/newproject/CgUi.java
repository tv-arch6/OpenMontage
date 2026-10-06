package com.my.newproject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** UI building blocks that follow the existing Cigram admin identity (dark teal, airo font, RTL). */
final class CgUi {

    private CgUi() {}

    static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    interface Callback<T> {
        void run(T value);
    }

    private static Typeface font;
    private static boolean fontTried;

    static Typeface font(Context c) {
        if (!fontTried) {
            fontTried = true;
            try {
                font = Typeface.createFromAsset(c.getAssets(), "fonts/airo.ttf");
            } catch (Exception e) {
                font = null;
            }
        }
        return font;
    }

    static void applyFont(TextView tv, boolean bold) {
        Typeface f = font(tv.getContext());
        int style = bold ? Typeface.BOLD : Typeface.NORMAL;
        if (f != null) tv.setTypeface(f, style);
        else tv.setTypeface(Typeface.DEFAULT, style);
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    static GradientDrawable bg(Context c, int fill, float radiusDp, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, dp(c, 1)), stroke);
        return g;
    }

    static int levelColor(int level) {
        if (level == 0) return CgCfg.GOOD;
        if (level == 1) return CgCfg.WARN;
        if (level == 2) return CgCfg.BAD;
        return CgCfg.GRAY;
    }

    static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }

    // ------------------------------------------------------------- layout

    static LinearLayout.LayoutParams lp(Context c, int w, int h, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout.LayoutParams lpWeight(Context c, float weight, float l, float t, float r, float b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, WRAP, weight);
        p.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        return p;
    }

    static LinearLayout vbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout hbox(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = vbox(c);
        l.setBackground(bg(c, CgCfg.CARD, 14, CgCfg.STROKE));
        int p = dp(c, 14);
        l.setPadding(p, p, p, p);
        return l;
    }

    static View space(Context c, float heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(MATCH, dp(c, heightDp)));
        return v;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(CgCfg.STROKE);
        v.setLayoutParams(lp(c, MATCH, 1, 0, 8, 0, 8));
        return v;
    }

    // --------------------------------------------------------------- text

    static TextView text(Context c, CharSequence t, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        applyFont(tv, bold);
        tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        // Latin titles (e.g. "Little Lorraine") must align to the right edge like Arabic ones in this RTL UI.
        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        return tv;
    }

    static TextView title(Context c, CharSequence t) {
        return text(c, t, 17, CgCfg.TEXT, true);
    }

    static TextView muted(Context c, CharSequence t) {
        return text(c, t, 12.5f, CgCfg.MUTED, false);
    }

    static TextView sectionTitle(Context c, CharSequence t) {
        TextView tv = text(c, t, 14, CgCfg.ACCENT, true);
        tv.setLayoutParams(lp(c, MATCH, WRAP, 4, 14, 4, 6));
        return tv;
    }

    static void ltr(TextView tv) {
        tv.setTextDirection(View.TEXT_DIRECTION_LTR);
    }

    // ------------------------------------------------------------ buttons

    /** style: 0 primary, 1 secondary, 2 danger, 3 ghost */
    static TextView button(Context c, CharSequence label, int style) {
        TextView b = new TextView(c);
        b.setText(label);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setClickable(true);
        applyFont(b, true);
        int fill;
        int stroke;
        int pressed;
        int textColor = CgCfg.TEXT;
        if (style == 0) {
            fill = CgCfg.ACCENT;
            stroke = 0;
            pressed = CgCfg.ACCENT_D;
        } else if (style == 2) {
            fill = alpha(CgCfg.BAD, 0x22);
            stroke = CgCfg.BAD;
            pressed = alpha(CgCfg.BAD, 0x55);
            textColor = CgCfg.BAD;
        } else if (style == 3) {
            fill = 0;
            stroke = 0;
            pressed = alpha(CgCfg.ACCENT, 0x33);
            textColor = CgCfg.ACCENT;
        } else {
            fill = CgCfg.CARD2;
            stroke = CgCfg.STROKE;
            pressed = CgCfg.STROKE;
        }
        b.setTextColor(textColor);
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[]{android.R.attr.state_pressed}, bg(c, pressed, 12, stroke));
        sl.addState(new int[]{}, bg(c, fill, 12, stroke));
        b.setBackground(sl);
        b.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        return b;
    }

    static TextView smallButton(Context c, CharSequence label, int style) {
        TextView b = button(c, label, style);
        b.setTextSize(12.5f);
        b.setPadding(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
        return b;
    }

    static TextView chip(Context c, CharSequence label, boolean selected) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(12.5f);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        applyFont(t, selected);
        setChipState(t, selected);
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        return t;
    }

    static void setChipState(TextView t, boolean selected) {
        Context c = t.getContext();
        if (selected) {
            t.setBackground(bg(c, CgCfg.ACCENT, 20, 0));
            t.setTextColor(CgCfg.TEXT);
        } else {
            t.setBackground(bg(c, CgCfg.CARD2, 20, CgCfg.STROKE));
            t.setTextColor(CgCfg.MUTED);
        }
    }

    static TextView badge(Context c, CharSequence label, int color) {
        TextView t = new TextView(c);
        t.setText(label);
        t.setTextSize(11.5f);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        applyFont(t, true);
        t.setBackground(bg(c, alpha(color, 0x26), 8, alpha(color, 0x66)));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    // ------------------------------------------------------------- fields

    static EditText field(Context c, String hint, boolean multiline) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(alpha(CgCfg.MUTED, 0xAA));
        e.setTextColor(CgCfg.TEXT);
        e.setTextSize(14);
        applyFont(e, false);
        e.setBackground(bg(c, CgCfg.CARD2, 12, CgCfg.STROKE));
        e.setPadding(dp(c, 12), dp(c, 11), dp(c, 12), dp(c, 11));
        e.setGravity(Gravity.START | (multiline ? Gravity.TOP : Gravity.CENTER_VERTICAL));
        if (multiline) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            e.setMinLines(3);
            e.setMaxLines(8);
        } else {
            e.setInputType(InputType.TYPE_CLASS_TEXT);
            e.setSingleLine(true);
        }
        return e;
    }

    static EditText urlField(Context c, String hint) {
        EditText e = field(c, hint, false);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        return e;
    }

    static EditText numberField(Context c, String hint) {
        EditText e = field(c, hint, false);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextDirection(View.TEXT_DIRECTION_LTR);
        return e;
    }

    static LinearLayout labeled(Context c, String label, View input) {
        LinearLayout l = vbox(c);
        TextView t = text(c, label, 12, CgCfg.MUTED, false);
        t.setPadding(dp(c, 4), 0, dp(c, 4), dp(c, 4));
        l.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        l.addView(input, new LinearLayout.LayoutParams(MATCH, WRAP));
        l.setLayoutParams(lp(c, MATCH, WRAP, 0, 0, 0, 10));
        return l;
    }

    /** Pill style on/off toggle with a label. */
    static final class Toggle extends LinearLayout {
        boolean on;
        private final TextView pill;
        private Runnable change;

        Toggle(Context c, String label, boolean initial) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            on = initial;
            TextView l = text(c, label, 14, CgCfg.TEXT, false);
            addView(l, new LinearLayout.LayoutParams(0, WRAP, 1f));
            pill = new TextView(c);
            pill.setGravity(Gravity.CENTER);
            pill.setTextSize(12.5f);
            applyFont(pill, true);
            pill.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
            addView(pill, new LinearLayout.LayoutParams(WRAP, WRAP));
            refresh();
            setPadding(0, dp(c, 4), 0, dp(c, 4));
            setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    set(!on);
                }
            });
        }

        void set(boolean v) {
            on = v;
            refresh();
            if (change != null) change.run();
        }

        void silent(boolean v) {
            on = v;
            refresh();
        }

        void onChange(Runnable r) {
            change = r;
        }

        private void refresh() {
            Context c = getContext();
            pill.setText(on ? "مفعّل" : "متوقف");
            pill.setTextColor(on ? CgCfg.TEXT : CgCfg.MUTED);
            pill.setBackground(bg(c, on ? CgCfg.ACCENT : CgCfg.CARD2, 20, on ? 0 : CgCfg.STROKE));
        }
    }

    static Toggle toggle(Context c, String label, boolean initial) {
        return new Toggle(c, label, initial);
    }

    // -------------------------------------------------------- composite UI

    static View empty(Context c, String title, String sub) {
        LinearLayout l = vbox(c);
        l.setGravity(Gravity.CENTER_HORIZONTAL);
        l.setPadding(dp(c, 20), dp(c, 40), dp(c, 20), dp(c, 40));
        TextView icon = text(c, "∅", 38, CgCfg.STROKE, true);
        icon.setGravity(Gravity.CENTER);
        l.addView(icon, new LinearLayout.LayoutParams(MATCH, WRAP));
        TextView t = text(c, title, 16, CgCfg.TEXT, true);
        t.setGravity(Gravity.CENTER);
        l.addView(t, new LinearLayout.LayoutParams(MATCH, WRAP));
        if (sub != null && sub.length() > 0) {
            TextView s = muted(c, sub);
            s.setGravity(Gravity.CENTER);
            s.setPadding(0, dp(c, 6), 0, 0);
            l.addView(s, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        return l;
    }

    static View errorCard(Context c, String message, final Runnable retry) {
        LinearLayout card = card(c);
        card.setBackground(bg(c, alpha(CgCfg.BAD, 0x1A), 14, CgCfg.BAD));
        card.addView(text(c, "تعذر تحميل البيانات", 15, CgCfg.BAD, true));
        TextView m = muted(c, message);
        m.setPadding(0, dp(c, 6), 0, dp(c, 10));
        card.addView(m);
        if (retry != null) {
            TextView b = button(c, "إعادة المحاولة", 1);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    retry.run();
                }
            });
            card.addView(b, new LinearLayout.LayoutParams(WRAP, WRAP));
        }
        return card;
    }

    static ProgressBar spinner(Context c) {
        ProgressBar p = new ProgressBar(c);
        try {
            p.getIndeterminateDrawable().setColorFilter(CgCfg.ACCENT, PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) { }
        return p;
    }

    /** Horizontal scrolling chip row. inner is where chips are added. */
    static final class ChipBar {
        final HorizontalScrollView scroll;
        final LinearLayout inner;

        ChipBar(Context c) {
            scroll = new HorizontalScrollView(c);
            scroll.setHorizontalScrollBarEnabled(false);
            inner = new LinearLayout(c);
            inner.setOrientation(LinearLayout.HORIZONTAL);
            inner.setPadding(dp(c, 12), dp(c, 4), dp(c, 12), dp(c, 4));
            scroll.addView(inner, new ViewGroup.LayoutParams(WRAP, WRAP));
        }

        TextView add(String label, boolean selected) {
            Context c = inner.getContext();
            TextView t = chip(c, label, selected);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
            p.setMargins(dp(c, 3), 0, dp(c, 3), 0);
            inner.addView(t, p);
            return t;
        }
    }

    // ---------------------------------------------------------- toolbar

    static final class Toolbar {
        final LinearLayout view;
        final TextView titleView;
        final TextView subtitleView;
        final LinearLayout actions;

        Toolbar(final Activity a, String title, boolean back) {
            view = hbox(a);
            view.setBackgroundColor(CgCfg.CARD);
            view.setPadding(dp(a, 6), dp(a, 8), dp(a, 6), dp(a, 8));
            if (back) {
                TextView b = text(a, "→", 24, CgCfg.TEXT, true);
                b.setGravity(Gravity.CENTER);
                b.setClickable(true);
                b.setPadding(dp(a, 12), 0, dp(a, 12), 0);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        a.finish();
                    }
                });
                view.addView(b, new LinearLayout.LayoutParams(WRAP, dp(a, 40)));
            }
            LinearLayout mid = vbox(a);
            titleView = text(a, title, 18, CgCfg.TEXT, true);
            mid.addView(titleView, new LinearLayout.LayoutParams(MATCH, WRAP));
            subtitleView = muted(a, "");
            subtitleView.setVisibility(View.GONE);
            mid.addView(subtitleView, new LinearLayout.LayoutParams(MATCH, WRAP));
            view.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));
            actions = hbox(a);
            view.addView(actions, new LinearLayout.LayoutParams(WRAP, WRAP));
        }

        TextView action(String label, final Runnable r) {
            Context c = view.getContext();
            TextView t = smallButton(c, label, 3);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    r.run();
                }
            });
            actions.addView(t, new LinearLayout.LayoutParams(WRAP, WRAP));
            return t;
        }

        void subtitle(String s) {
            if (s == null || s.length() == 0) {
                subtitleView.setVisibility(View.GONE);
            } else {
                subtitleView.setText(s);
                subtitleView.setVisibility(View.VISIBLE);
            }
        }
    }

    // ----------------------------------------------------------- dialogs

    static AlertDialog.Builder dialog(Activity a) {
        return new AlertDialog.Builder(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_LONG).show();
    }

    static void info(Activity a, String title, String msg) {
        dialog(a).setTitle(title).setMessage(msg).setPositiveButton("حسناً", null).show();
    }

    static void confirm(Activity a, String title, String msg, String yes, boolean danger, final Runnable onYes) {
        AlertDialog d = dialog(a).setTitle(title).setMessage(msg)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton(yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onYes.run();
                    }
                }).create();
        d.show();
        if (danger) {
            try {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(CgCfg.BAD);
            } catch (Exception ignored) { }
        }
    }

    static void input(Activity a, String title, String hint, String initial, boolean multiline, final Callback<String> cb) {
        final EditText e = field(a, hint, multiline);
        if (initial != null) e.setText(initial);
        LinearLayout box = vbox(a);
        box.setPadding(dp(a, 18), dp(a, 8), dp(a, 18), 0);
        box.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        dialog(a).setTitle(title).setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        cb.run(e.getText().toString().trim());
                    }
                }).show();
    }

    interface IntCb {
        void run(int index);
    }

    static void choose(Activity a, String title, CharSequence[] labels, final IntCb cb) {
        dialog(a).setTitle(title).setItems(labels, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                cb.run(which);
            }
        }).setNegativeButton("إلغاء", null).show();
    }

    // -------------------------------------------------------- date / time

    private static SimpleDateFormat isoFormat(boolean millis) {
        SimpleDateFormat f = new SimpleDateFormat(millis ? "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'" : "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f;
    }

    /** @return epoch millis or -1 */
    static long parseIso(String iso) {
        if (iso == null || iso.trim().length() == 0) return -1;
        String s = iso.trim();
        try {
            return isoFormat(false).parse(s).getTime();
        } catch (Exception ignored) { }
        try {
            return isoFormat(true).parse(s).getTime();
        } catch (Exception ignored) { }
        return -1;
    }

    static String toIso(long ms) {
        return isoFormat(false).format(new Date(ms));
    }

    static String localText(String iso) {
        long ms = parseIso(iso);
        if (ms < 0) return iso == null ? "" : iso;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        return f.format(new Date(ms));
    }

    static String ago(String iso) {
        long ms = parseIso(iso);
        if (ms < 0) return "";
        long diff = (System.currentTimeMillis() - ms) / 1000;
        if (diff < 60) return "الآن";
        if (diff < 3600) return "منذ " + (diff / 60) + " دقيقة";
        if (diff < 86400) return "منذ " + (diff / 3600) + " ساعة";
        return "منذ " + (diff / 86400) + " يوم";
    }

    /** Date then time pickers. Returns ISO UTC string. */
    static void pickDateTime(final Activity a, String initialIso, final Callback<String> cb) {
        final Calendar cal = Calendar.getInstance();
        long init = parseIso(initialIso);
        if (init > 0) cal.setTimeInMillis(init);
        new DatePickerDialog(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK, new DatePickerDialog.OnDateSetListener() {
            @Override
            public void onDateSet(DatePicker view, int year, int month, int day) {
                cal.set(Calendar.YEAR, year);
                cal.set(Calendar.MONTH, month);
                cal.set(Calendar.DAY_OF_MONTH, day);
                new TimePickerDialog(a, AlertDialog.THEME_DEVICE_DEFAULT_DARK, new TimePickerDialog.OnTimeSetListener() {
                    @Override
                    public void onTimeSet(TimePicker v, int hour, int minute) {
                        cal.set(Calendar.HOUR_OF_DAY, hour);
                        cal.set(Calendar.MINUTE, minute);
                        cal.set(Calendar.SECOND, 0);
                        cb.run(toIso(cal.getTimeInMillis()));
                    }
                }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
            }
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ------------------------------------------------------------- misc

    static ColorDrawable color(int c) {
        return new ColorDrawable(c);
    }

    static String sizeText(long bytes) {
        if (bytes <= 0) return "—";
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1024) return String.format(Locale.US, "%.2f GB", mb / 1024.0);
        if (mb >= 1) return String.format(Locale.US, "%.1f MB", mb);
        return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
    }

    static String durationText(long ms) {
        if (ms <= 0) return "—";
        long s = ms / 1000;
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%d:%02d", m, sec);
    }
}
