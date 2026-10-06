package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Account page, v3: one shape for every row, logical groups, and the new
 * "أعلن هنا" entry as the last item of the list.
 *
 * What this class does NOT do, on purpose:
 *   - it never touches account.xml (that layout is inside the encrypted Sketchware
 *     project and is edited only in Sketchware),
 *   - it never replaces, removes or re-wires an existing row: every row keeps the
 *     exact click listener Sketchware attached to it. Only padding, background,
 *     typography, the icon and the order change,
 *   - it never moves a row out of a container whose visibility Sketchware controls
 *     (notably `linear_account_settings`, which lives inside `linear_profile` and
 *     must stay hidden for guests). Such a row is restyled where it stands.
 *
 * {@link #arrange(Activity)} is idempotent: it runs again on every resume and on
 * every re-render of the "watch later" strip, and converges to the same layout.
 */
public final class CigramAdsEntry {

    private static final String TAG_GROUP_CONTENT = "cigram_ads_group_content";
    private static final String TAG_GROUP_SUPPORT = "cigram_ads_group_support";
    private static final String TAG_GROUP_ADS = "cigram_ads_group_ads";
    private static final String TAG_ADS_ROW = "cigram_ads_row";
    private static final String TAG_ICON = "cigram_ads_icon";
    private static final String TAG_DIVIDER = "cigram_ads_divider";

    private CigramAdsEntry() { }

    // ----------------------------------------------------------------- entry

    public static void arrange(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            LinearLayout host = host(activity);
            if (host == null) return;

            // 1) rows that stay where they are, restyled only
            styleExistingRow(activity, byId(activity, "linear_account_settings"),
                    CigramAdsUi.ICON_GEAR, CigramAdsUi.MUTED);

            // 2) the grouped rows
            LinearLayout content = group(activity, host, TAG_GROUP_CONTENT, "المحتوى");
            LinearLayout support = group(activity, host, TAG_GROUP_SUPPORT, "الدعم والمساعدة");
            LinearLayout ads = group(activity, host, TAG_GROUP_ADS, "أعلن معنا");

            adopt(activity, content, byId(activity, "linear_account_subtitle_font"),
                    CigramAdsUi.ICON_FONT, CigramAdsUi.ACCENT);
            adopt(activity, content, byId(activity, "linear_my_content"),
                    CigramAdsUi.ICON_BOOKMARK, CigramAdsUi.ACCENT);

            adopt(activity, support, byId(activity, "linear_help_support"),
                    CigramAdsUi.ICON_QUESTION, CigramAdsUi.MUTED);
            adopt(activity, support, host.findViewWithTag("cigram_update_row"),
                    CigramAdsUi.ICON_UPDATE, CigramAdsUi.MUTED);
            adopt(activity, support, host.findViewWithTag("cigram_contact_us"),
                    CigramAdsUi.ICON_MAIL, CigramAdsUi.MUTED);

            View adsRow = ads.findViewWithTag(TAG_ADS_ROW);
            if (adsRow == null) {
                adsRow = buildAdvertiseRow(activity);
                ads.addView(adsRow, new LinearLayout.LayoutParams(-1, -2));
            }

            // 3) order: the groups sit at the bottom of the list, ads last of all
            place(activity, host, content);
            place(activity, host, support);
            place(activity, host, ads);

            refresh(activity, content);
            refresh(activity, support);
            refresh(activity, ads);

            // Sketchware may flip row visibility after us (guest vs. signed in),
            // so recompute once more after this layout pass settles.
            host.post(new Runnable() {
                @Override public void run() {
                    if (activity.isFinishing()) return;
                    LinearLayout h = host(activity);
                    if (h == null) return;
                    refresh(activity, (LinearLayout) h.findViewWithTag(TAG_GROUP_CONTENT));
                    refresh(activity, (LinearLayout) h.findViewWithTag(TAG_GROUP_SUPPORT));
                    refresh(activity, (LinearLayout) h.findViewWithTag(TAG_GROUP_ADS));
                }
            });
        } catch (Throwable error) {
            // The account page must open even if this decoration fails entirely.
            android.util.Log.w("CigramAdsEntry", "arrange failed", error);
        }
    }

    // --------------------------------------------------------------- lookups

    private static View byId(Activity a, String name) {
        try {
            int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
            return id == 0 ? null : a.findViewById(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static LinearLayout host(Activity a) {
        View preferred = byId(a, "linear_account_content");
        if (preferred instanceof LinearLayout) return (LinearLayout) preferred;
        View scroll = byId(a, "vscroll_account");
        if (scroll instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) scroll;
            if (group.getChildCount() > 0 && group.getChildAt(0) instanceof LinearLayout) {
                return (LinearLayout) group.getChildAt(0);
            }
        }
        return null;
    }

    /** The direct child of {@code host} that contains {@code view}, or null. */
    private static View directChild(LinearLayout host, View view) {
        View current = view;
        while (current != null && current.getParent() != host) {
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return current;
    }

    // ---------------------------------------------------------------- groups

    /** A titled card that collects rows. Created once, then reused. */
    private static LinearLayout group(Activity a, LinearLayout host, String tag, String heading) {
        View existing = host.findViewWithTag(tag);
        if (existing instanceof LinearLayout) return (LinearLayout) existing;

        LinearLayout box = CigramAdsUi.column(a);
        box.setTag(tag);

        TextView label = CigramAdsUi.text(a, heading, 13f, CigramAdsUi.MUTED, true);
        label.setPadding(CigramAdsUi.dp(a, 18), 0, CigramAdsUi.dp(a, 18), CigramAdsUi.dp(a, 8));
        box.addView(label, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout card = CigramAdsUi.column(a);
        card.setTag(tag + "_card");
        card.setBackground(CigramAdsUi.round(a, CigramAdsUi.CARD, CigramAdsUi.STROKE, 18));
        card.setClipToOutline(true);
        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMarginStart(CigramAdsUi.dp(a, 12));
        lp.setMarginEnd(CigramAdsUi.dp(a, 12));
        lp.topMargin = CigramAdsUi.dp(a, 14);
        host.addView(box, lp);
        return box;
    }

    private static LinearLayout cardOf(LinearLayout group) {
        if (group == null) return null;
        View card = group.findViewWithTag(group.getTag() + "_card");
        return card instanceof LinearLayout ? (LinearLayout) card : null;
    }

    /** Moves an existing row into a group card, restyling it on the way in. */
    private static void adopt(Activity a, LinearLayout group, View row, int iconKind, int iconColor) {
        if (group == null || row == null) return;
        LinearLayout card = cardOf(group);
        if (card == null) return;
        styleExistingRow(a, row, iconKind, iconColor);
        if (row.getParent() == card) return;
        if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).removeView(row);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        card.addView(row, lp);
    }

    /** Re-adds the group at the end of the list, just above the logout button. */
    private static void place(Activity a, LinearLayout host, LinearLayout group) {
        if (group == null) return;
        if (group.getParent() instanceof ViewGroup) ((ViewGroup) group.getParent()).removeView(group);
        int at = host.getChildCount();
        View raw = byId(a, "button_logout");
        View logout = raw == null ? null : directChild(host, raw);
        if (logout != null) {
            int index = host.indexOfChild(logout);
            if (index >= 0) at = index;
        }
        host.addView(group, Math.min(Math.max(0, at), host.getChildCount()));
    }

    /** Hides a group whose rows were all hidden, and redraws the dividers between them. */
    private static void refresh(Activity a, LinearLayout group) {
        if (group == null) return;
        LinearLayout card = cardOf(group);
        if (card == null) return;

        for (int i = card.getChildCount() - 1; i >= 0; i--) {
            View child = card.getChildAt(i);
            if (TAG_DIVIDER.equals(child.getTag())) card.removeViewAt(i);
        }

        int visible = 0;
        View previous = null;
        for (int i = 0; i < card.getChildCount(); i++) {
            View child = card.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            if (previous != null) {
                View divider = CigramAdsUi.divider(a, 62);
                divider.setTag(TAG_DIVIDER);
                card.addView(divider, card.indexOfChild(child));
                i++;
            }
            previous = child;
            visible++;
        }
        group.setVisibility(visible == 0 ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------- restyling

    /**
     * Gives one existing Sketchware row the shared shape. The row keeps all of its
     * children and its click listener; the old arrow ImageView becomes the chevron
     * and a tinted icon box is appended on the reading side.
     */
    private static void styleExistingRow(Activity a, View view, int iconKind, int iconColor) {
        if (!(view instanceof LinearLayout)) return;
        LinearLayout row = (LinearLayout) view;
        try {
            // Children were authored as [arrow][label]; LTR keeps the arrow on the
            // far left, which in an RTL page reads as "forward".
            row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(CigramAdsUi.dp(a, 56));
            row.setPadding(CigramAdsUi.dp(a, 12), CigramAdsUi.dp(a, 8),
                    CigramAdsUi.dp(a, 12), CigramAdsUi.dp(a, 8));

            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp != null && lp.height > 0) {
                // A fixed 56dp height clips at large font sizes; let it grow instead.
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                row.setLayoutParams(lp);
            }

            CigramAdsUi.pressable(row, CigramAdsUi.round(a, Color.TRANSPARENT, 14),
                    CigramAdsUi.ACCENT, 14);

            for (int i = 0; i < row.getChildCount(); i++) {
                View child = row.getChildAt(i);
                if (TAG_ICON.equals(child.getTag())) continue;
                // e.g. the small version label on the update row keeps its own look
                if (CigramAccountExtras.TAG_SKIP_STYLE.equals(child.getTag())) continue;
                if (child instanceof ImageView) {
                    ImageView arrow = (ImageView) child;
                    arrow.setColorFilter(CigramAdsUi.DIM, PorterDuff.Mode.SRC_IN);
                    arrow.setAlpha(0.75f);
                    ViewGroup.LayoutParams alp = arrow.getLayoutParams();
                    if (alp != null) {
                        alp.width = CigramAdsUi.dp(a, 20);
                        alp.height = CigramAdsUi.dp(a, 20);
                        arrow.setLayoutParams(alp);
                    }
                } else if (child instanceof TextView) {
                    TextView label = (TextView) child;
                    label.setTypeface(CigramAdsUi.font(a), Typeface.NORMAL);
                    label.setTextSize(15.5f);
                    label.setTextColor(CigramAdsUi.TEXT);
                    label.setTextDirection(View.TEXT_DIRECTION_RTL);
                    label.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    label.setPadding(CigramAdsUi.dp(a, 10), 0, CigramAdsUi.dp(a, 10), 0);
                    if (row.getContentDescription() == null) {
                        row.setContentDescription(label.getText());
                    }
                }
            }

            if (row.findViewWithTag(TAG_ICON) == null) {
                FrameLayout icon = CigramAdsUi.iconBox(a, iconKind, iconColor, 38f);
                icon.setTag(TAG_ICON);
                int size = CigramAdsUi.dp(a, 38);
                row.addView(icon, row.getChildCount(), new LinearLayout.LayoutParams(size, size));
            }
        } catch (Throwable error) {
            android.util.Log.w("CigramAdsEntry", "row restyle failed", error);
        }
    }

    // ------------------------------------------------------------ new row

    /** "أعلن هنا" — same shape as every other row, plus a "جديد" badge. */
    private static View buildAdvertiseRow(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(TAG_ADS_ROW);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(CigramAdsUi.dp(a, 60));
        row.setPadding(CigramAdsUi.dp(a, 12), CigramAdsUi.dp(a, 9),
                CigramAdsUi.dp(a, 12), CigramAdsUi.dp(a, 9));
        CigramAdsUi.pressable(row, CigramAdsUi.round(a, Color.TRANSPARENT, 14),
                CigramAdsUi.PRIMARY, 14);

        CigramAdsUi.Icon chevron = new CigramAdsUi.Icon(a, CigramAdsUi.ICON_CHEVRON_START, CigramAdsUi.DIM);
        row.addView(chevron, new LinearLayout.LayoutParams(CigramAdsUi.dp(a, 20), CigramAdsUi.dp(a, 20)));

        LinearLayout texts = CigramAdsUi.column(a);
        LinearLayout titleRow = CigramAdsUi.row(a);
        TextView title = CigramAdsUi.text(a, "أعلن هنا", 15.5f, CigramAdsUi.TEXT, true);
        titleRow.addView(title, new LinearLayout.LayoutParams(-2, -2));
        TextView badge = CigramAdsUi.badge(a, "جديد", CigramAdsUi.PRIMARY);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.setMarginStart(CigramAdsUi.dp(a, 8));
        titleRow.addView(badge, bp);
        texts.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

        TextView sub = CigramAdsUi.text(a, "مساحات إعلانية لشركتك داخل التطبيق", 12.5f,
                CigramAdsUi.MUTED, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = CigramAdsUi.dp(a, 2);
        texts.addView(sub, sp);

        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(a, 10));
        tp.setMarginEnd(CigramAdsUi.dp(a, 10));
        row.addView(texts, tp);

        FrameLayout icon = CigramAdsUi.iconBox(a, CigramAdsUi.ICON_MEGAPHONE, CigramAdsUi.PRIMARY, 38f);
        int size = CigramAdsUi.dp(a, 38);
        row.addView(icon, new LinearLayout.LayoutParams(size, size));

        row.setContentDescription("أعلن هنا، مساحات إعلانية لشركتك داخل التطبيق");
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open(a);
            }
        });
        return row;
    }

    /** Opens the "أعلن هنا" page. Safe to call from anywhere. */
    public static void open(Activity a) {
        if (a == null || a.isFinishing()) return;
        try {
            a.startActivity(new Intent(a, CigramAdvertiseActivity.class));
        } catch (Throwable error) {
            android.util.Log.w("CigramAdsEntry", "open failed", error);
            CigramUI.info(a, CigramUI.ICON_WARN, CigramUI.AMBER, "تعذر الفتح",
                    "تعذر فتح صفحة الإعلانات الآن. أعد المحاولة.", "حسناً").show();
        }
    }
}
