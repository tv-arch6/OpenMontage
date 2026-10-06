package com.Cigram.vid;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Section 3 of "أعلن هنا": the interactive cost calculator.
 *
 * Every price shown here comes from POST /ads/quote. The app holds no price table
 * and performs no arithmetic, so changing a price in the admin panel changes what
 * advertisers see immediately, with no app update.
 *
 * Requests are debounced ({@link #DEBOUNCE_MS}) and the newest one wins: a slow
 * reply for an older selection is dropped instead of overwriting a newer total.
 */
public final class CigramAdsCalculator {

    private static final long DEBOUNCE_MS = 350L;

    /** Notified whenever a fresh, valid quote arrives (used to enable "أرسل هذا الطلب"). */
    public interface Listener {
        void onQuote(JSONObject quote);

        void onSlotChanged(String slotId);
    }

    private final Activity activity;
    private final JSONObject config;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CigramAdsApi.Alive alive;

    // selection state
    private String slotId = "";
    private int days = 1;
    private boolean customDays = false;
    private final LinkedHashSet<String> cities = new LinkedHashSet<String>();
    private final LinkedHashSet<String> langs = new LinkedHashSet<String>();
    private final LinkedHashSet<Integer> hours = new LinkedHashSet<Integer>();
    private final LinkedHashSet<String> addons = new LinkedHashSet<String>();

    // views
    private LinearLayout root;
    private LinearLayout slotChips;
    private LinearLayout slotExplain;
    private LinearLayout durationChips;
    private EditText customDaysField;
    private LinearLayout cityChips;
    private LinearLayout langChips;
    private LinearLayout hourChips;
    private LinearLayout addonList;
    private LinearLayout breakdown;
    private TextView totalView;
    private TextView estimateView;
    private TextView errorView;

    private Runnable pending;
    private long requestSeq = 0L;
    private JSONObject lastQuote;

    public CigramAdsCalculator(Activity activity, JSONObject config,
                               CigramAdsApi.Alive alive, Listener listener) {
        this.activity = activity;
        this.config = config == null ? new JSONObject() : config;
        this.alive = alive;
        this.listener = listener;
    }

    public JSONObject quote() {
        return lastQuote;
    }

    public String slotId() {
        return slotId;
    }

    /** The structured request the "أرسل هذا الطلب" button hands to the chat. */
    public JSONObject order() {
        JSONObject o = new JSONObject();
        try {
            o.put("type", "ad_request");
            o.put("slot", slotId);
            o.put("slot_name", slotName(slotId));
            o.put("days", days);
            o.put("cities", new JSONArray(new ArrayList<String>(cities)));
            o.put("langs", new JSONArray(new ArrayList<String>(langs)));
            o.put("hours", new JSONArray(new ArrayList<Integer>(hours)));
            o.put("addons", new JSONArray(new ArrayList<String>(addons)));
            if (lastQuote != null) o.put("quote", lastQuote);
            o.put("created_at", System.currentTimeMillis());
        } catch (Throwable ignored) { }
        return o;
    }

    // ------------------------------------------------------------------ build

    public View build() {
        root = CigramAdsUi.column(activity);

        root.addView(CigramAdsUi.sectionHeader(activity, "احسب تكلفة إعلانك",
                        "اختر المساحة والمدة والاستهداف، ويظهر السعر فوراً."),
                CigramAdsUi.lp(activity, -1, -2, 4, 0, 4, 14));

        LinearLayout card = CigramAdsUi.card(activity);

        card.addView(fieldLabel("المساحة الإعلانية"));
        slotChips = wrap();
        card.addView(slotChips, CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 0));
        slotExplain = CigramAdsUi.column(activity);
        slotExplain.setBackground(CigramAdsUi.round(activity,
                CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x14), CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x40), 12));
        int p = CigramAdsUi.dp(activity, 12);
        slotExplain.setPadding(p, p, p, p);
        card.addView(slotExplain, CigramAdsUi.lp(activity, -1, -2, 0, 10, 0, 0));

        card.addView(fieldLabel("المدة"), topped(16));
        durationChips = wrap();
        card.addView(durationChips, CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 0));
        card.addView(buildCustomDays(), CigramAdsUi.lp(activity, -1, -2, 0, 8, 0, 0));

        card.addView(fieldLabel("الاستهداف (اختياري)"), topped(16));
        card.addView(CigramAdsUi.muted(activity, "اتركه فارغاً ليظهر إعلانك للجميع."),
                CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 8));

        card.addView(subLabel("المدن"));
        cityChips = wrap();
        card.addView(cityChips, CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 0));

        card.addView(subLabel("اللغة"), topped(12));
        langChips = wrap();
        card.addView(langChips, CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 0));

        card.addView(subLabel("ساعات الظهور"), topped(12));
        hourChips = wrap();
        card.addView(hourChips, CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 0));

        card.addView(fieldLabel("إضافات"), topped(16));
        addonList = CigramAdsUi.column(activity);
        card.addView(addonList, CigramAdsUi.lp(activity, -1, -2, 0, 4, 0, 0));

        root.addView(card, CigramAdsUi.lp(activity, -1, -2, 4, 0, 4, 12));
        root.addView(buildTotalCard(), CigramAdsUi.lp(activity, -1, -2, 4, 0, 4, 0));

        fillSlots();
        fillDurations();
        fillCities();
        fillLangs();
        fillHours();
        fillAddons();
        requestQuote(true);
        return root;
    }

    // ------------------------------------------------------------- sub-views

    private TextView fieldLabel(String value) {
        return CigramAdsUi.text(activity, value, 14.5f, CigramAdsUi.TEXT, true);
    }

    private TextView subLabel(String value) {
        return CigramAdsUi.text(activity, value, 12.5f, CigramAdsUi.MUTED, false);
    }

    private LinearLayout.LayoutParams topped(float dp) {
        return CigramAdsUi.lp(activity, -1, -2, 0, dp, 0, 0);
    }

    /** A chip container that wraps onto new lines instead of scrolling sideways. */
    private LinearLayout wrap() {
        LinearLayout box = CigramAdsUi.column(activity);
        box.setTag("wrap");
        return box;
    }

    /** Lays chips out into rows, measuring against the available width. */
    private void layoutChips(final LinearLayout container, final List<TextView> chips) {
        container.removeAllViews();
        final int gap = CigramAdsUi.dp(activity, 8);
        container.post(new Runnable() {
            @Override public void run() {
                if (alive != null && !alive.alive()) return;
                int available = container.getWidth();
                if (available <= 0) available = activity.getResources().getDisplayMetrics().widthPixels
                        - CigramAdsUi.dp(activity, 60);
                container.removeAllViews();
                LinearLayout line = CigramAdsUi.row(activity);
                int used = 0;
                for (TextView chip : chips) {
                    chip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    int w = chip.getMeasuredWidth();
                    if (used > 0 && used + gap + w > available) {
                        container.addView(line, CigramAdsUi.lp(activity, -1, -2, 0, 0, 0, 8));
                        line = CigramAdsUi.row(activity);
                        used = 0;
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                    if (used > 0) lp.setMarginStart(gap);
                    if (chip.getParent() instanceof LinearLayout) {
                        ((LinearLayout) chip.getParent()).removeView(chip);
                    }
                    line.addView(chip, lp);
                    used += (used > 0 ? gap : 0) + w;
                }
                if (line.getChildCount() > 0) {
                    container.addView(line, CigramAdsUi.lp(activity, -1, -2, 0, 0, 0, 0));
                }
            }
        });
    }

    // --------------------------------------------------------------- filling

    private JSONArray slots() {
        JSONArray a = config.optJSONArray("slots");
        return a == null ? new JSONArray() : a;
    }

    private JSONObject slot(String id) {
        JSONArray all = slots();
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.optJSONObject(i);
            if (s != null && id.equals(s.optString("id"))) return s;
        }
        return null;
    }

    private String slotName(String id) {
        JSONObject s = slot(id);
        return s == null ? id : s.optString("name", id);
    }

    private void fillSlots() {
        JSONArray all = slots();
        List<TextView> chips = new ArrayList<TextView>();
        if (all.length() > 0 && slotId.length() == 0) {
            JSONObject first = all.optJSONObject(0);
            slotId = first == null ? "" : first.optString("id", "");
        }
        for (int i = 0; i < all.length(); i++) {
            final JSONObject s = all.optJSONObject(i);
            if (s == null) continue;
            final String id = s.optString("id", "");
            final TextView chip = CigramAdsUi.chip(activity, s.optString("name", id), id.equals(slotId));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (id.equals(slotId)) return;
                    slotId = id;
                    for (int k = 0; k < slotChips.getChildCount(); k++) {
                        View line = slotChips.getChildAt(k);
                        if (!(line instanceof LinearLayout)) continue;
                        LinearLayout row = (LinearLayout) line;
                        for (int j = 0; j < row.getChildCount(); j++) {
                            View child = row.getChildAt(j);
                            if (child instanceof TextView) {
                                CigramAdsUi.chipState((TextView) child, child == chip);
                            }
                        }
                    }
                    clampDaysToSlot();
                    showSlotExplain();
                    if (listener != null) listener.onSlotChanged(slotId);
                    requestQuote(false);
                }
            });
            chips.add(chip);
        }
        layoutChips(slotChips, chips);
        clampDaysToSlot();
        showSlotExplain();
    }

    private void showSlotExplain() {
        slotExplain.removeAllViews();
        JSONObject s = slot(slotId);
        if (s == null) {
            slotExplain.setVisibility(View.GONE);
            return;
        }
        slotExplain.setVisibility(View.VISIBLE);
        slotExplain.addView(CigramAdsUi.text(activity, s.optString("desc", ""), 13.5f, 0xDDFFFFFF, false),
                new LinearLayout.LayoutParams(-1, -2));
        String suits = s.optString("suits", "");
        if (suits.length() > 0) {
            TextView t = CigramAdsUi.text(activity, "تناسب: " + suits, 12.5f, CigramAdsUi.ACCENT, true);
            slotExplain.addView(t, CigramAdsUi.lp(activity, -1, -2, 0, 6, 0, 0));
        }
        int min = s.optInt("min_days", 1);
        if (min > 1) {
            slotExplain.addView(CigramAdsUi.muted(activity, "أقل مدة لهذه المساحة " + min + " أيام."),
                    CigramAdsUi.lp(activity, -1, -2, 0, 6, 0, 0));
        }
    }

    private void clampDaysToSlot() {
        JSONObject s = slot(slotId);
        if (s == null) return;
        int min = Math.max(1, s.optInt("min_days", 1));
        int max = Math.max(min, s.optInt("max_days", 365));
        if (days < min) days = min;
        if (days > max) days = max;
        if (customDaysField != null && customDays) {
            String current = customDaysField.getText() == null ? "" : customDaysField.getText().toString();
            if (!current.equals(String.valueOf(days))) customDaysField.setText(String.valueOf(days));
        }
        markDurationChips();
    }

    private void fillDurations() {
        JSONArray all = config.optJSONArray("durations");
        if (all == null) all = new JSONArray();
        List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject d = all.optJSONObject(i);
            if (d == null) continue;
            final int value = d.optInt("days", 0);
            if (value <= 0) continue;
            String label = d.optString("label", "");
            if (label.length() == 0) label = value + " يوم";
            final TextView chip = CigramAdsUi.chip(activity, label, !customDays && days == value);
            chip.setTag(Integer.valueOf(value));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    customDays = false;
                    days = value;
                    clampDaysToSlot();
                    if (customDaysField != null) customDaysField.setEnabled(false);
                    requestQuote(false);
                }
            });
            chips.add(chip);
        }
        final TextView custom = CigramAdsUi.chip(activity, "مخصص", customDays);
        custom.setTag("custom");
        custom.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                customDays = true;
                if (customDaysField != null) {
                    customDaysField.setEnabled(true);
                    customDaysField.requestFocus();
                }
                markDurationChips();
                requestQuote(false);
            }
        });
        chips.add(custom);
        layoutChips(durationChips, chips);
    }

    private void markDurationChips() {
        if (durationChips == null) return;
        for (int k = 0; k < durationChips.getChildCount(); k++) {
            View line = durationChips.getChildAt(k);
            if (!(line instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) line;
            for (int j = 0; j < row.getChildCount(); j++) {
                View child = row.getChildAt(j);
                if (!(child instanceof TextView)) continue;
                Object tag = child.getTag();
                boolean on;
                if ("custom".equals(tag)) {
                    on = customDays;
                } else if (tag instanceof Integer) {
                    on = !customDays && ((Integer) tag).intValue() == days;
                } else {
                    on = false;
                }
                CigramAdsUi.chipState((TextView) child, on);
            }
        }
    }

    private View buildCustomDays() {
        LinearLayout box = CigramAdsUi.row(activity);
        TextView label = CigramAdsUi.text(activity, "عدد الأيام", 13f, CigramAdsUi.MUTED, false);
        box.addView(label, new LinearLayout.LayoutParams(-2, -2));

        customDaysField = new EditText(activity);
        customDaysField.setInputType(InputType.TYPE_CLASS_NUMBER);
        customDaysField.setEnabled(false);
        CigramUI.styleInput(customDaysField, "مثال: 45");
        customDaysField.setText(String.valueOf(days));
        customDaysField.setGravity(Gravity.CENTER);
        customDaysField.setMinHeight(CigramAdsUi.dp(activity, 48));
        customDaysField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override public void afterTextChanged(Editable e) {
                if (!customDays) return;
                int value = 0;
                try {
                    value = Integer.parseInt(e.toString().trim());
                } catch (Throwable ignored) { }
                if (value <= 0) return;
                days = value;
                requestQuote(false);
            }
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, -2, 1f);
        fp.setMarginStart(CigramAdsUi.dp(activity, 10));
        box.addView(customDaysField, fp);
        return box;
    }

    private void fillCities() {
        JSONObject targeting = config.optJSONObject("targeting");
        JSONArray all = targeting == null ? null : targeting.optJSONArray("cities");
        if (all == null) all = new JSONArray();
        List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < all.length(); i++) {
            final String city = all.optString(i, "");
            if (city.length() == 0) continue;
            final TextView chip = CigramAdsUi.chip(activity, city, false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean on = cities.contains(city);
                    if (on) cities.remove(city);
                    else cities.add(city);
                    CigramAdsUi.chipState(chip, !on);
                    requestQuote(false);
                }
            });
            chips.add(chip);
        }
        layoutChips(cityChips, chips);
    }

    private void fillLangs() {
        JSONObject targeting = config.optJSONObject("targeting");
        JSONArray all = targeting == null ? null : targeting.optJSONArray("languages");
        if (all == null) all = new JSONArray();
        List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject l = all.optJSONObject(i);
            if (l == null) continue;
            final String id = l.optString("id", "");
            if (id.length() == 0) continue;
            final TextView chip = CigramAdsUi.chip(activity, l.optString("label", id), false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean on = langs.contains(id);
                    if (on) langs.remove(id);
                    else langs.add(id);
                    CigramAdsUi.chipState(chip, !on);
                    requestQuote(false);
                }
            });
            chips.add(chip);
        }
        layoutChips(langChips, chips);
    }

    /** Hour targeting is offered as readable bands, not 24 separate switches. */
    private void fillHours() {
        final int[][] bands = new int[][]{
                {6, 12}, {12, 18}, {18, 24}, {0, 6}
        };
        final String[] labels = new String[]{"الصباح ٦–١٢", "الظهيرة ١٢–٦", "المساء ٦–١٢", "الليل ١٢–٦"};
        List<TextView> chips = new ArrayList<TextView>();
        for (int i = 0; i < bands.length; i++) {
            final int from = bands[i][0];
            final int to = bands[i][1];
            final TextView chip = CigramAdsUi.chip(activity, labels[i], false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean on = hours.contains(Integer.valueOf(from));
                    for (int h = from; h < to; h++) {
                        if (on) hours.remove(Integer.valueOf(h));
                        else hours.add(Integer.valueOf(h));
                    }
                    CigramAdsUi.chipState(chip, !on);
                    requestQuote(false);
                }
            });
            chips.add(chip);
        }
        layoutChips(hourChips, chips);
    }

    private void fillAddons() {
        addonList.removeAllViews();
        JSONArray all = config.optJSONArray("addons");
        if (all == null) all = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            JSONObject a = all.optJSONObject(i);
            if (a == null) continue;
            final String id = a.optString("id", "");
            if (id.length() == 0) continue;
            addonList.addView(buildAddonRow(a, id), CigramAdsUi.lp(activity, -1, -2, 0, 8, 0, 0));
        }
    }

    private View buildAddonRow(JSONObject addon, final String id) {
        final LinearLayout row = CigramAdsUi.row(activity);
        row.setMinimumHeight(CigramAdsUi.dp(activity, 56));
        int p = CigramAdsUi.dp(activity, 12);
        row.setPadding(p, CigramAdsUi.dp(activity, 10), p, CigramAdsUi.dp(activity, 10));

        final CigramAdsUi.Icon tick = new CigramAdsUi.Icon(activity, CigramAdsUi.ICON_CHECK, CigramAdsUi.DIM);
        row.addView(tick, new LinearLayout.LayoutParams(CigramAdsUi.dp(activity, 22), CigramAdsUi.dp(activity, 22)));

        LinearLayout texts = CigramAdsUi.column(activity);
        texts.addView(CigramAdsUi.text(activity, addon.optString("label", id), 14.5f, CigramAdsUi.TEXT, true),
                new LinearLayout.LayoutParams(-1, -2));
        String desc = addon.optString("desc", "");
        if (desc.length() > 0) {
            texts.addView(CigramAdsUi.text(activity, desc, 12.5f, CigramAdsUi.MUTED, false),
                    CigramAdsUi.lp(activity, -1, -2, 0, 2, 0, 0));
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(activity, 10));
        tp.setMarginEnd(CigramAdsUi.dp(activity, 10));
        row.addView(texts, tp);

        row.setContentDescription(addon.optString("label", id));
        final Runnable paint = new Runnable() {
            @Override public void run() {
                boolean on = addons.contains(id);
                tick.set(on ? CigramAdsUi.ICON_CHECK : CigramAdsUi.ICON_CHECK, on ? CigramAdsUi.GOOD : CigramAdsUi.DIM);
                row.setBackground(CigramAdsUi.ripple(activity,
                        on ? CigramAdsUi.round(activity, CigramAdsUi.alpha(CigramAdsUi.GOOD, 0x1A),
                                CigramAdsUi.alpha(CigramAdsUi.GOOD, 0x55), 14)
                                : CigramAdsUi.round(activity, CigramAdsUi.CARD_SOFT, CigramAdsUi.STROKE, 14),
                        CigramAdsUi.ACCENT, 14));
                row.setSelected(on);
            }
        };
        paint.run();
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (addons.contains(id)) addons.remove(id);
                else addons.add(id);
                paint.run();
                requestQuote(false);
            }
        });
        return row;
    }

    // ----------------------------------------------------------- total card

    private View buildTotalCard() {
        LinearLayout card = CigramAdsUi.card(activity);
        card.setBackground(CigramAdsUi.round(activity, CigramAdsUi.CARD_SOFT,
                CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x55), 20));

        card.addView(CigramAdsUi.text(activity, "التكلفة", 14f, CigramAdsUi.MUTED, true),
                new LinearLayout.LayoutParams(-1, -2));

        breakdown = CigramAdsUi.column(activity);
        card.addView(breakdown, CigramAdsUi.lp(activity, -1, -2, 0, 10, 0, 0));

        card.addView(CigramAdsUi.divider(activity, 0), CigramAdsUi.lp(activity, -1, -2, 0, 10, 0, 10));

        LinearLayout totalRow = CigramAdsUi.row(activity);
        totalRow.addView(CigramAdsUi.text(activity, "الإجمالي", 16f, CigramAdsUi.TEXT, true),
                new LinearLayout.LayoutParams(-2, -2));
        totalView = CigramAdsUi.text(activity, "—", 22f, CigramAdsUi.ACCENT, true);
        totalView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        totalRow.addView(totalView, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(totalRow, new LinearLayout.LayoutParams(-1, -2));

        estimateView = CigramAdsUi.muted(activity, "");
        card.addView(estimateView, CigramAdsUi.lp(activity, -1, -2, 0, 8, 0, 0));

        errorView = CigramAdsUi.text(activity, "", 13f, CigramAdsUi.WARN, false);
        errorView.setVisibility(View.GONE);
        card.addView(errorView, CigramAdsUi.lp(activity, -1, -2, 0, 8, 0, 0));
        return card;
    }

    // ---------------------------------------------------------------- quote

    private void requestQuote(boolean immediate) {
        if (pending != null) main.removeCallbacks(pending);
        showBreakdownSkeleton();
        pending = new Runnable() {
            @Override public void run() {
                pending = null;
                sendQuote();
            }
        };
        main.postDelayed(pending, immediate ? 0L : DEBOUNCE_MS);
    }

    private void sendQuote() {
        if (slotId.length() == 0) return;
        final long seq = ++requestSeq;
        JSONObject body = new JSONObject();
        try {
            body.put("slot", slotId);
            body.put("days", days);
            body.put("cities", new JSONArray(new ArrayList<String>(cities)));
            body.put("langs", new JSONArray(new ArrayList<String>(langs)));
            body.put("hours", new JSONArray(new ArrayList<Integer>(hours)));
            body.put("addons", new JSONArray(new ArrayList<String>(addons)));
        } catch (Throwable ignored) { }

        CigramAdsApi.quote(activity, body, alive, new CigramAdsApi.Callback() {
            @Override public void done(CigramAdsApi.Result result) {
                if (seq != requestSeq) return; // a newer selection already won
                if (!result.ok()) {
                    showQuoteError(result.error);
                    return;
                }
                JSONObject data = result.data();
                lastQuote = data;
                renderQuote(data);
                if (listener != null) listener.onQuote(data);
            }
        });
    }

    private void showBreakdownSkeleton() {
        if (breakdown == null) return;
        breakdown.removeAllViews();
        for (int i = 0; i < 3; i++) {
            breakdown.addView(CigramAdsUi.shimmer(activity, -1, 13, 5, i == 0 ? 0 : 8));
        }
        if (totalView != null) totalView.setAlpha(0.45f);
    }

    private void showQuoteError(String message) {
        if (errorView == null) return;
        errorView.setVisibility(View.VISIBLE);
        errorView.setText(message == null ? "تعذر حساب السعر الآن." : message);
        if (lastQuote != null) {
            renderQuote(lastQuote);
            errorView.setText((message == null ? "تعذر تحديث السعر." : message) + " (المعروض آخر سعر محسوب)");
            errorView.setVisibility(View.VISIBLE);
        } else {
            breakdown.removeAllViews();
            if (totalView != null) {
                totalView.setAlpha(1f);
                totalView.setText("—");
            }
        }
    }

    private void renderQuote(JSONObject q) {
        if (breakdown == null || q == null) return;
        errorView.setVisibility(View.GONE);
        breakdown.removeAllViews();
        totalView.setAlpha(1f);

        String currency = q.optString("currency_label", "");
        int quotedDays = q.optInt("days", days);

        line("السعر الأساسي (" + quotedDays + " يوم × "
                        + CigramAdsUi.money(q.optDouble("base_price_day", 0d), currency) + ")",
                CigramAdsUi.money(q.optDouble("base", 0d), currency), CigramAdsUi.TEXT);

        double discount = q.optDouble("discount_value", 0d);
        if (discount > 0d) {
            line("خصم المدة الطويلة (" + fmtPercent(q.optDouble("discount_percent", 0d)) + "%)",
                    "- " + CigramAdsUi.money(discount, currency), CigramAdsUi.GOOD);
        }

        JSONArray targeting = q.optJSONArray("targeting_lines");
        if (targeting != null) {
            for (int i = 0; i < targeting.length(); i++) {
                JSONObject t = targeting.optJSONObject(i);
                if (t == null) continue;
                line(t.optString("label", ""), "+ " + CigramAdsUi.money(t.optDouble("value", 0d), currency),
                        CigramAdsUi.TEXT);
            }
        }

        JSONArray addonLines = q.optJSONArray("addons");
        if (addonLines != null) {
            for (int i = 0; i < addonLines.length(); i++) {
                JSONObject a = addonLines.optJSONObject(i);
                if (a == null) continue;
                line(a.optString("label", ""), "+ " + CigramAdsUi.money(a.optDouble("value", 0d), currency),
                        CigramAdsUi.TEXT);
            }
        }

        totalView.setText(CigramAdsUi.money(q.optDouble("total", 0d), currency));

        long views = q.optLong("est_views", 0L);
        if (views > 0L) {
            estimateView.setText("مشاهدات متوقعة: نحو " + CigramAdsUi.formatNumber(views)
                    + " — " + q.optString("est_views_note", ""));
        } else {
            estimateView.setText(q.optString("est_views_note", ""));
        }

        // The whole card is one announcement for a screen reader, not a dozen rows.
        if (root != null) {
            root.setContentDescription("التكلفة الإجمالية "
                    + CigramAdsUi.money(q.optDouble("total", 0d), currency)
                    + " لمدة " + quotedDays + " يوم في مساحة " + slotName(slotId));
        }
    }

    private void line(String label, String value, int color) {
        LinearLayout row = CigramAdsUi.row(activity);
        TextView l = CigramAdsUi.text(activity, label, 13.5f, CigramAdsUi.MUTED, false);
        l.setMaxLines(2);
        row.addView(l, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView v = CigramAdsUi.text(activity, value, 13.5f, color, true);
        v.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(-2, -2);
        vp.setMarginStart(CigramAdsUi.dp(activity, 10));
        row.addView(v, vp);
        breakdown.addView(row, CigramAdsUi.lp(activity, -1, -2, 0,
                breakdown.getChildCount() == 0 ? 0 : 7, 0, 0));
    }

    private static String fmtPercent(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.01) return String.valueOf((long) Math.rint(value));
        return String.valueOf(Math.round(value * 10d) / 10d);
    }

    /** Called from the Activity's onDestroy: drops any pending debounce. */
    public void release() {
        if (pending != null) main.removeCallbacks(pending);
        pending = null;
        requestSeq++;
    }

    /** Lets the hero CTA preselect a slot before the user reaches the calculator. */
    public void selectSlot(String id) {
        if (id == null || id.length() == 0 || id.equals(slotId)) return;
        slotId = id;
        fillSlots();
        requestQuote(false);
    }
}
