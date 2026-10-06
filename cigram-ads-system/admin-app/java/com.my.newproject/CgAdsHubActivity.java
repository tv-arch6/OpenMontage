package com.my.newproject;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * مركز الإعلانات: البوابة الواحدة لكل شاشات نظام الإعلانات.
 *
 * Add ONE entry to the existing admin home (see admin-app/INSTALL.md) and
 * everything else is reachable from here, so the home screen keeps its shape.
 * The pinned inbox card with its unread count sits at the top, as the spec asks.
 */
public class CgAdsHubActivity extends CgBase {

    private LinearLayout page;
    private TextView securityLine;

    @Override protected String screenTitle() {
        return "مركز الإعلانات";
    }

    @Override protected void onBuild() {
        page = scrollBody();

        page.addView(CgAdsInboxActivity.entryCard(this),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        page.addView(statusCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        page.addView(tile("الحملات", "إنشاء ومراجعة واعتماد وتمديد",
                CgAdsCampaignsActivity.class), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        page.addView(tile("المعلنون", "ملفات الشركات وما دفعته",
                CgAdsAdvertisersActivity.class), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        page.addView(tile("التقارير", "أرقام ورسم يومي وروابط للشركات",
                CgAdsReportsActivity.class), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        page.addView(tile("الإعدادات", "الأسعار والسياسات والتواصل وساعات العمل",
                CgAdsSettingsActivity.class), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 12));

        page.addView(securityCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 24));
    }

    @Override protected void onResume() {
        super.onResume();
        CgAdsPresence.onScreenResumed(this);
        loadSecurity();
    }

    @Override protected void onStop() {
        CgAdsPresence.onScreenStopped();
        super.onStop();
    }

    private View statusCard() {
        LinearLayout card = CgUi.card(this);
        LinearLayout row = CgUi.hbox(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = CgUi.vbox(this);
        texts.addView(CgUi.text(this, "حالتي للمعلنين", 13f, CgCfg.MUTED, false),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final TextView status = CgUi.text(this, CgAdsPresence.label(CgAdsPresence.current(this)),
                16f, CgAdsPresence.color(CgAdsPresence.current(this)), true);
        texts.addView(status, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        row.addView(texts, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));

        TextView toggle = CgUi.button(this, "تبديل", 1);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String next = CgAdsPresence.next(CgAdsPresence.current(CgAdsHubActivity.this));
                CgAdsPresence.set(CgAdsHubActivity.this, next, new Runnable() {
                    @Override public void run() {
                        String now = CgAdsPresence.current(CgAdsHubActivity.this);
                        status.setText(CgAdsPresence.label(now));
                        status.setTextColor(CgAdsPresence.color(now));
                    }
                });
            }
        });
        row.addView(toggle, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        card.addView(row, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        return card;
    }

    private View tile(String title, String subtitle, final Class<?> target) {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.text(this, title, 15.5f, CgCfg.TEXT, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(CgUi.muted(this, subtitle), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        card.setClickable(true);
        card.setContentDescription(title + ". " + subtitle);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(CgAdsHubActivity.this, target));
            }
        });
        return card;
    }

    private View securityCard() {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.sectionTitle(this, "الأمان والخصوصية"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        securityLine = CgUi.muted(this, "جارٍ التحقق…");
        card.addView(securityLine, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        TextView details = CgUi.button(this, "تفاصيل التشفير", 1);
        details.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showSecurity();
            }
        });
        card.addView(details, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        return card;
    }

    private JSONObject security;

    private void loadSecurity() {
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgAdsApi.security();
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                if (res == null || !res.ok()) {
                    securityLine.setText("تعذر التحقق من حالة التشفير.");
                    return;
                }
                security = CgAdsApi.data(res);
                boolean key = security.optBoolean("key_configured", false);
                securityLine.setText(key
                        ? "التشفير أثناء التخزين مُفعّل (AES-GCM). ليس تشفيراً طرفياً."
                        : "⚠ التشفير غير مُفعّل — اضبط ADS_MEDIA_KEY في الـ Worker.");
                securityLine.setTextColor(key ? CgCfg.GOOD : CgCfg.BAD);
            }

            @Override public void fail(String message) {
                securityLine.setText("تعذر التحقق: " + message);
            }
        });
    }

    private void showSecurity() {
        if (security == null) {
            CgUi.info(this, "الأمان", "لم تُقرأ حالة التشفير بعد. أعد المحاولة.");
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append("النقل: ").append(security.optString("transport", "")).append("\n\n");
        text.append("الرسائل: ").append(security.optString("messages_at_rest", "")).append("\n\n");
        text.append("الوسائط: ").append(security.optString("media_at_rest", "")).append("\n\n");
        text.append("الوصول للوسائط: ").append(security.optString("media_access", "")).append("\n\n");
        text.append("تشفير طرفي (E2E): ")
                .append(security.optBoolean("end_to_end", false) ? "نعم" : "لا").append("\n");
        text.append(security.optString("end_to_end_note", ""));

        ScrollView scroll = new ScrollView(this);
        TextView body = CgUi.text(this, text.toString(), 13f, CgCfg.TEXT, false);
        int p = CgUi.dp(this, 10);
        body.setPadding(p, p, p, p);
        body.setTextIsSelectable(true);
        scroll.addView(body);
        CgUi.dialog(this)
                .setTitle("الأمان والخصوصية")
                .setView(scroll)
                .setPositiveButton("إغلاق", null)
                .show();
    }

    /**
     * The one entry to drop into the existing admin home screen.
     * See admin-app/INSTALL.md for where to add it.
     */
    static View entry(final android.app.Activity activity) {
        LinearLayout card = CgUi.card(activity);
        card.setBackground(CgUi.bg(activity, CgUi.alpha(CgCfg.WARN, 0x14), 16, CgCfg.WARN));
        card.addView(CgUi.text(activity, "مركز الإعلانات", 16f, CgCfg.TEXT, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(CgUi.muted(activity,
                        "صندوق الوارد، الحملات، المعلنون، التقارير، الإعدادات"),
                CgUi.lp(activity, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        card.setClickable(true);
        card.setContentDescription("مركز الإعلانات");
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                activity.startActivity(new Intent(activity, CgAdsHubActivity.class));
            }
        });
        return card;
    }
}
