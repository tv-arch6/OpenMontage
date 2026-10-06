package com.my.newproject;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The few widgets the ads screens need that {@link CgUi} does not already have.
 *
 * It deliberately adds no new colours, radii or fonts: everything comes from CgUi
 * and {@link CgCfg}, so the ads screens look like the rest of the admin app.
 * (The newer `CxUi` has a skeleton and a chip picker too, but pulling it in would
 * drag the whole content-CMS stack along; these are a few lines instead.)
 */
final class CgAdsUi {

    private CgAdsUi() { }

    /** Grey placeholder rows shown while a screen loads, instead of a spinner. */
    static View skeleton(Context c, int rows) {
        LinearLayout box = CgUi.vbox(c);
        for (int i = 0; i < Math.max(1, rows); i++) {
            LinearLayout card = CgUi.card(c);
            card.addView(bar(c, 0.42f, 16), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            card.addView(bar(c, 1f, 12), CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            card.addView(bar(c, 0.65f, 12), CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 0));
            box.addView(card, CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        return box;
    }

    private static View bar(Context c, float widthFraction, int heightDp) {
        View view = new View(c);
        view.setBackground(CgUi.bg(c, CgUi.alpha(0xFFFFFFFF, 0x14), 5, 0));
        int width = widthFraction >= 1f
                ? CgUi.MATCH
                : (int) (c.getResources().getDisplayMetrics().widthPixels * 0.72f * widthFraction);
        view.setLayoutParams(new LinearLayout.LayoutParams(width, CgUi.dp(c, heightDp)));
        return view;
    }

    /** "منذ ٣ دقائق" from an epoch-millis stamp (CgUi.ago takes an ISO string). */
    static String agoMs(long at) {
        if (at <= 0L) return "";
        long minutes = Math.max(0L, (System.currentTimeMillis() - at) / 60000L);
        if (minutes < 1L) return "الآن";
        if (minutes < 60L) return "منذ " + minutes + " د";
        long hours = minutes / 60L;
        if (hours < 24L) return "منذ " + hours + " س";
        long days = hours / 24L;
        if (days < 30L) return "منذ " + days + " ي";
        return "منذ " + (days / 30L) + " شهر";
    }

    static String clock(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        long minutes = total / 60L;
        long seconds = total % 60L;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    static String money(double value, String currencyLabel) {
        String number;
        if (Math.abs(value - Math.rint(value)) < 0.005) {
            number = String.format(java.util.Locale.US, "%,d", Math.round(value));
        } else {
            number = String.format(java.util.Locale.US, "%,.2f", value);
        }
        return number + " " + (currencyLabel == null ? "" : currencyLabel);
    }

    static String count(long value) {
        if (value >= 1000000L) return trim(value / 1000000.0) + "M";
        if (value >= 1000L) return trim(value / 1000.0) + "K";
        return String.valueOf(value);
    }

    private static String trim(double value) {
        String s = String.format(java.util.Locale.US, "%.1f", value);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    // ------------------------------------------------------------- filter bar

    /** A single-choice chip row that carries a value per chip. */
    static final class Filters {
        final HorizontalScrollView view;
        private final LinearLayout inner;
        private final List<TextView> chips = new ArrayList<TextView>();
        private final List<String> values = new ArrayList<String>();
        private String selected = "";
        private Picked listener;

        interface Picked {
            void onPicked(String value);
        }

        Filters(Context c) {
            view = new HorizontalScrollView(c);
            view.setHorizontalScrollBarEnabled(false);
            view.setOverScrollMode(View.OVER_SCROLL_NEVER);
            view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            inner = CgUi.hbox(c);
            inner.setPadding(CgUi.dp(c, 2), CgUi.dp(c, 2), CgUi.dp(c, 2), CgUi.dp(c, 2));
            view.addView(inner, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }

        Filters add(String label, final String value) {
            Context c = inner.getContext();
            boolean first = chips.isEmpty();
            final TextView chip = CgUi.chip(c, label, first);
            if (first) selected = value;
            chip.setMinHeight(CgUi.dp(c, 40));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pick(value);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMarginStart(chips.isEmpty() ? 0 : CgUi.dp(c, 6));
            inner.addView(chip, lp);
            chips.add(chip);
            values.add(value);
            return this;
        }

        Filters onPicked(Picked listener) {
            this.listener = listener;
            return this;
        }

        String selected() {
            return selected;
        }

        void pick(String value) {
            selected = value == null ? "" : value;
            for (int i = 0; i < chips.size(); i++) {
                CgUi.setChipState(chips.get(i), values.get(i).equals(selected));
            }
            if (listener != null) listener.onPicked(selected);
        }
    }

    // --------------------------------------------------------------- key/value

    /** One "label : value" line inside a card. */
    static View line(Context c, String label, String value) {
        LinearLayout row = CgUi.hbox(c);
        row.setGravity(Gravity.TOP);
        row.addView(CgUi.text(c, label, 12.5f, CgCfg.MUTED, false),
                new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        TextView content = CgUi.text(c, value == null ? "" : value, 13f, CgCfg.TEXT, false);
        content.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        lp.setMarginStart(CgUi.dp(c, 8));
        row.addView(content, lp);
        return row;
    }

    /** A number tile for the summary rows. */
    static View tile(Context c, String label, String value, int color) {
        LinearLayout tile = CgUi.vbox(c);
        tile.setBackground(CgUi.bg(c, CgCfg.CARD2, 12, CgCfg.STROKE));
        int p = CgUi.dp(c, 10);
        tile.setPadding(p, p, p, p);
        TextView number = CgUi.text(c, value, 17f, color, true);
        number.setGravity(Gravity.CENTER);
        tile.addView(number, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        TextView name = CgUi.text(c, label, 11.5f, CgCfg.MUTED, false);
        name.setGravity(Gravity.CENTER);
        tile.addView(name, CgUi.lp(c, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        tile.setContentDescription(label + " " + value);
        return tile;
    }

    // ------------------------------------------------------------- statuses

    static String statusLabel(String status) {
        if ("pending_review".equals(status)) return "قيد المراجعة";
        if ("approved".equals(status)) return "معتمدة";
        if ("scheduled".equals(status)) return "مجدولة";
        if ("active".equals(status)) return "نشطة";
        if ("paused".equals(status)) return "متوقفة";
        if ("ended".equals(status)) return "منتهية";
        if ("rejected".equals(status)) return "مرفوضة";
        if ("draft".equals(status)) return "مسودة";
        return status == null ? "" : status;
    }

    static int statusColor(String status) {
        if ("active".equals(status)) return CgCfg.GOOD;
        if ("rejected".equals(status)) return CgCfg.BAD;
        if ("paused".equals(status) || "pending_review".equals(status)) return CgCfg.WARN;
        if ("scheduled".equals(status) || "approved".equals(status)) return CgCfg.ACCENT;
        return CgCfg.GRAY;
    }

    static String paymentLabel(String status) {
        if ("paid".equals(status)) return "مدفوع";
        if ("partial".equals(status)) return "مدفوع جزئياً";
        if ("refunded".equals(status)) return "مُسترجع";
        return "غير مدفوع";
    }

    static int paymentColor(String status) {
        if ("paid".equals(status)) return CgCfg.GOOD;
        if ("partial".equals(status)) return CgCfg.WARN;
        if ("refunded".equals(status)) return CgCfg.GRAY;
        return CgCfg.BAD;
    }

    static String slotLabel(String slot) {
        if ("splash".equals(slot)) return "الافتتاح";
        if ("hero".equals(slot)) return "بانر الواجهة";
        if ("inline".equals(slot)) return "بطاقة داخلية";
        if ("popup".equals(slot)) return "نافذة منبثقة";
        if ("sticky".equals(slot)) return "شريط ثابت";
        if ("sponsor".equals(slot)) return "برعاية";
        return slot == null ? "" : slot;
    }

    static final String[] SLOT_IDS = {"splash", "hero", "inline", "popup", "sticky", "sponsor"};
}
