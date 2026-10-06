package com.Cigram.vid;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Account page footer rows.
 *
 * v3 change: this class no longer positions or styles the rows itself. It only
 * makes sure the "التحقق من التحديثات" row EXISTS, then hands the whole page to
 * {@link CigramAdsEntry}, which gives every row — this one, "تواصل معنا" built by
 * the Sketchware logic, and the rest of the list — one shared shape and groups
 * them into cards.
 *
 * Behaviour is unchanged: the update row still opens the same manual check, the
 * contact row still opens the same dialog, and both still sit above the logout
 * button (now inside the "الدعم والمساعدة" card).
 *
 * Callers do not change: {@code CigramAccountExtras.arrange(activity)} is still
 * the single entry point, and it is still idempotent.
 */
public final class CigramAccountExtras {

    private static final String TAG_UPDATE = "cigram_update_row";
    /** Children carrying this tag keep their own typography when a row is restyled. */
    static final String TAG_SKIP_STYLE = "cigram_ads_skip_style";

    private CigramAccountExtras() { }

    private static View byId(Activity a, String name) {
        int id = a.getResources().getIdentifier(name, "id", a.getPackageName());
        return id == 0 ? null : a.findViewById(id);
    }

    public static void arrange(final Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        try {
            View hostView = byId(activity, "linear_account_content");
            if (!(hostView instanceof LinearLayout)) return;
            LinearLayout host = (LinearLayout) hostView;

            // findViewWithTag searches the whole subtree, so the row is found again
            // after CigramAdsEntry has moved it into a group card: never duplicated.
            if (host.findViewWithTag(TAG_UPDATE) == null) {
                View update = buildUpdateRow(activity);
                host.addView(update, new LinearLayout.LayoutParams(-1, -2));
            }

            CigramAdsEntry.arrange(activity);
        } catch (Throwable error) {
            android.util.Log.w("CigramAccount", "arrange failed", error);
        }
    }

    /**
     * The row itself. Shaped like the Sketchware rows ([arrow][…][label]) so the
     * shared restyler in {@link CigramAdsEntry} treats it exactly like the others.
     */
    private static View buildUpdateRow(final Activity a) {
        LinearLayout row = new LinearLayout(a);
        row.setTag(TAG_UPDATE);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView arrow = new ImageView(a);
        int res = a.getResources().getIdentifier("hsi", "drawable", a.getPackageName());
        if (res != 0) arrow.setImageResource(res);
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        row.addView(arrow, new LinearLayout.LayoutParams(CigramUI.dp(a, 30), CigramUI.dp(a, 30)));

        String name = CigramUpdateChecker.installedName(a);
        TextView version = CigramUI.label(a, name.length() == 0 ? "" : "الإصدار " + name, 12f,
                CigramUI.MUTED, false);
        version.setTag(TAG_SKIP_STYLE); // stays small and muted, unlike the row title
        version.setGravity(Gravity.CENTER);
        version.setPadding(CigramUI.dp(a, 8), 0, CigramUI.dp(a, 8), 0);
        row.addView(version, new LinearLayout.LayoutParams(-2, -2));

        TextView label = CigramUI.label(a, "التحقق من التحديثات", 15.5f, CigramUI.TEXT, false);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        row.setContentDescription("التحقق من التحديثات");
        row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CigramUpdateChecker.manualCheck(a);
            }
        });
        return row;
    }

    /** Kept for any caller that still removes the footer rows by hand. */
    static void detach(View row) {
        if (row != null && row.getParent() instanceof ViewGroup) {
            ((ViewGroup) row.getParent()).removeView(row);
        }
    }
}
