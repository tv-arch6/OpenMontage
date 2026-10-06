package com.my.newproject;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * قائمة الحملات: فلاتر بالحالة والمساحة، ملخص أعلى الصفحة، وتنبيه قبل الانتهاء.
 *
 * Filtering happens on the Worker, so the list is never trimmed client-side and a
 * campaign cannot be missed because of a stale local copy.
 */
public class CgAdsCampaignsActivity extends CgBase {

    private LinearLayout page;
    private LinearLayout list;
    private TextView summary;
    private CgAdsUi.Filters statusFilters;
    private CgAdsUi.Filters slotFilters;
    private String onlyUser = "";
    /** Resolved from onlyUser: campaigns are keyed by advertiser id, not account id. */
    private String onlyAdvertiserId = "";
    private boolean loading;

    @Override protected String screenTitle() {
        return "الحملات الإعلانية";
    }

    @Override protected void onBuild() {
        onlyUser = getIntent() == null ? "" : getIntent().getStringExtra("user");
        if (onlyUser == null) onlyUser = "";

        page = scrollBody();

        TextView create = CgUi.button(this, "+ حملة جديدة", 0);
        create.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(CgAdsCampaignsActivity.this, CgAdsCampaignActivity.class));
            }
        });
        page.addView(create, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        statusFilters = new CgAdsUi.Filters(this);
        statusFilters.add("الكل", "")
                .add("قيد المراجعة", "pending_review")
                .add("نشطة", "active")
                .add("مجدولة", "scheduled")
                .add("متوقفة", "paused")
                .add("منتهية", "ended")
                .add("مرفوضة", "rejected")
                .add("مسودات", "draft")
                .onPicked(new CgAdsUi.Filters.Picked() {
                    @Override public void onPicked(String value) {
                        load();
                    }
                });
        page.addView(statusFilters.view, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 6));

        slotFilters = new CgAdsUi.Filters(this);
        slotFilters.add("كل المساحات", "");
        for (String slot : CgAdsUi.SLOT_IDS) slotFilters.add(CgAdsUi.slotLabel(slot), slot);
        slotFilters.onPicked(new CgAdsUi.Filters.Picked() {
            @Override public void onPicked(String value) {
                load();
            }
        });
        page.addView(slotFilters.view, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        summary = CgUi.muted(this, "");
        page.addView(summary, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 0, 2, 8));

        list = CgUi.vbox(this);
        page.addView(list, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        list.addView(CgAdsUi.skeleton(this, 4), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        if (onlyUser.length() > 0) resolveAdvertiserThenLoad();
        else load();
    }

    /**
     * "حملات هذا المعلن" arrives as a thread id (u_&lt;account id&gt;), but a campaign
     * carries an advertiser id. Resolve one to the other before filtering, instead
     * of showing everybody's campaigns under one advertiser's name.
     */
    private void resolveAdvertiserThenLoad() {
        final String accountId = onlyUser.startsWith("u_") ? onlyUser.substring(2) : onlyUser;
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgAdsApi.advertisers());
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                JSONArray items = CgAdsApi.array(CgAdsApi.data(res), "items");
                for (int i = 0; i < items.length(); i++) {
                    JSONObject advertiser = items.optJSONObject(i);
                    if (advertiser == null) continue;
                    if (accountId.equals(advertiser.optString("user_id", ""))) {
                        onlyAdvertiserId = advertiser.optString("id", "");
                        bar.titleView.setText("حملات " + advertiser.optString("name", ""));
                        break;
                    }
                }
                if (onlyAdvertiserId.length() == 0) {
                    CgUi.toast(CgAdsCampaignsActivity.this,
                            "لا يوجد ملف معلن مرتبط بهذا الحساب بعد — تُعرض كل الحملات.");
                }
                load();
            }

            @Override public void fail(String message) {
                CgUi.toast(CgAdsCampaignsActivity.this, message);
                load();
            }
        });
    }

    @Override protected void onResume() {
        super.onResume();
        CgAdsPresence.onScreenResumed(this);
        if (!loading) load();
    }

    @Override protected void onStop() {
        CgAdsPresence.onScreenStopped();
        super.onStop();
    }

    private void load() {
        if (loading) return;
        loading = true;
        final String status = statusFilters == null ? "" : statusFilters.selected();
        final String slot = slotFilters == null ? "" : slotFilters.selected();
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgAdsApi.campaigns(status, slot));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                loading = false;
                render(CgAdsApi.array(CgAdsApi.data(res), "items"));
            }

            @Override public void fail(String message) {
                loading = false;
                list.removeAllViews();
                list.addView(CgUi.errorCard(CgAdsCampaignsActivity.this, message, new Runnable() {
                    @Override public void run() {
                        list.removeAllViews();
                        list.addView(CgAdsUi.skeleton(CgAdsCampaignsActivity.this, 4),
                                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                        load();
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    private void render(JSONArray items) {
        list.removeAllViews();
        int pending = 0;
        int expiring = 0;
        int shown = 0;
        for (int i = 0; i < items.length(); i++) {
            JSONObject campaign = items.optJSONObject(i);
            if (campaign == null) continue;
            if (onlyAdvertiserId.length() > 0
                    && !onlyAdvertiserId.equals(campaign.optString("advertiser_id", ""))) {
                continue;
            }
            shown++;
            if ("pending_review".equals(campaign.optString("effective_status"))) pending++;
            if (!campaign.isNull("ends_in_days")) {
                int days = campaign.optInt("ends_in_days", 999);
                if (days >= 0 && days <= 3 && "active".equals(campaign.optString("effective_status"))) {
                    expiring++;
                }
            }
            list.addView(card(campaign), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        StringBuilder text = new StringBuilder(shown + " حملة");
        if (pending > 0) text.append(" · ").append(pending).append(" بانتظار مراجعتك");
        if (expiring > 0) text.append(" · ").append(expiring).append(" تنتهي خلال ٣ أيام");
        summary.setText(text.toString());

        if (shown == 0) {
            list.addView(CgUi.empty(this, "لا حملات بهذا الفلتر",
                            "جرّب فلتراً آخر، أو أنشئ حملة جديدة."),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
    }

    private View card(final JSONObject campaign) {
        LinearLayout card = CgUi.card(this);
        String status = campaign.optString("effective_status", campaign.optString("status", ""));

        LinearLayout head = CgUi.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout texts = CgUi.vbox(this);
        TextView title = CgUi.text(this, campaign.optString("title", "حملة"), 15.5f, CgCfg.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(title, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        texts.addView(CgUi.text(this, campaign.optString("advertiser_name", "—") + " · "
                        + CgAdsUi.slotLabel(campaign.optString("slot", "")), 12.5f, CgCfg.MUTED, false),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        head.addView(texts, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        head.addView(CgUi.badge(this, CgAdsUi.statusLabel(status), CgAdsUi.statusColor(status)),
                new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        card.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        if (!campaign.isNull("ends_in_days")) {
            int days = campaign.optInt("ends_in_days", 999);
            if (days >= 0 && days <= 3 && "active".equals(status)) {
                TextView warning = CgUi.text(this,
                        days == 0 ? "تنتهي اليوم" : "تنتهي خلال " + days + " يوم", 12f, CgCfg.WARN, true);
                warning.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x1F), 10, CgCfg.WARN));
                warning.setPadding(CgUi.dp(this, 8), CgUi.dp(this, 4), CgUi.dp(this, 8), CgUi.dp(this, 5));
                card.addView(warning, CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
            }
        }

        LinearLayout numbers = CgUi.hbox(this);
        String currency = "SAR".equals(campaign.optString("currency", "SAR")) ? "ر.س"
                : campaign.optString("currency", "");
        numbers.addView(CgAdsUi.tile(this, "السعر",
                        CgAdsUi.money(campaign.optDouble("price_final", 0d), currency), CgCfg.TEXT),
                new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        JSONObject payment = campaign.optJSONObject("payment");
        String paymentStatus = payment == null ? "unpaid" : payment.optString("status", "unpaid");
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        mid.setMarginStart(CgUi.dp(this, 8));
        numbers.addView(CgAdsUi.tile(this, "الدفع", CgAdsUi.paymentLabel(paymentStatus),
                CgAdsUi.paymentColor(paymentStatus)), mid);
        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        end.setMarginStart(CgUi.dp(this, 8));
        numbers.addView(CgAdsUi.tile(this, "الأولوية",
                String.valueOf(campaign.optInt("priority", 5)), CgCfg.ACCENT), end);
        card.addView(numbers, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        if (campaign.optBoolean("exclusive", false)) {
            card.addView(CgUi.badge(this, "حصرية", CgCfg.ACCENT),
                    CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        }

        LinearLayout buttons = CgUi.hbox(this);
        TextView open = CgUi.smallButton(this, "تعديل", 1);
        final String id = campaign.optString("id", "");
        open.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(CgAdsCampaignsActivity.this, CgAdsCampaignActivity.class);
                intent.putExtra("campaign_id", id);
                startActivity(intent);
            }
        });
        buttons.addView(open, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));

        if ("pending_review".equals(status)) {
            TextView approve = CgUi.smallButton(this, "اعتماد", 0);
            approve.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    approve(id);
                }
            });
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            ap.setMarginStart(CgUi.dp(this, 6));
            buttons.addView(approve, ap);

            TextView reject = CgUi.smallButton(this, "رفض", 2);
            reject.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    reject(id);
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            rp.setMarginStart(CgUi.dp(this, 6));
            buttons.addView(reject, rp);
        }

        TextView report = CgUi.smallButton(this, "التقرير", 1);
        report.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent intent = new Intent(CgAdsCampaignsActivity.this, CgAdsReportsActivity.class);
                intent.putExtra("campaign_id", id);
                startActivity(intent);
            }
        });
        LinearLayout.LayoutParams rpp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        rpp.setMarginStart(CgUi.dp(this, 6));
        buttons.addView(report, rpp);

        card.addView(buttons, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        return card;
    }

    private void approve(final String id) {
        CgUi.confirm(this, "اعتماد الحملة",
                "بعد الاعتماد تبدأ الحملة في موعدها المحدد ويراها المستخدمون.",
                "اعتماد", false, new Runnable() {
                    @Override public void run() {
                        showLoading("جارٍ الاعتماد...");
                        CgAdsApi.async(CgAdsCampaignsActivity.this, new CgAdsApi.Call() {
                            @Override public CgHttp.Res run() {
                                return CgAdsApi.approve(id);
                            }
                        }, new CgHttp.Done<CgHttp.Res>() {
                            @Override public void ok(CgHttp.Res res) {
                                hideLoading();
                                CgUi.toast(CgAdsCampaignsActivity.this, "تم الاعتماد.");
                                load();
                            }

                            @Override public void fail(String message) {
                                hideLoading();
                                CgUi.info(CgAdsCampaignsActivity.this, "تعذر الاعتماد", message);
                            }
                        });
                    }
                });
    }

    private void reject(final String id) {
        CgUi.input(this, "رفض الحملة", "سبب الرفض — يصل للمعلن", "", true,
                new CgUi.Callback<String>() {
                    @Override public void run(final String reason) {
                        if (reason == null || reason.trim().length() == 0) {
                            CgUi.info(CgAdsCampaignsActivity.this, "السبب مطلوب",
                                    "اكتب سبباً واضحاً؛ سيظهر للمعلن في «إعلاناتي».");
                            return;
                        }
                        showLoading("جارٍ الرفض...");
                        CgAdsApi.async(CgAdsCampaignsActivity.this, new CgAdsApi.Call() {
                            @Override public CgHttp.Res run() {
                                return CgAdsApi.reject(id, reason.trim());
                            }
                        }, new CgHttp.Done<CgHttp.Res>() {
                            @Override public void ok(CgHttp.Res res) {
                                hideLoading();
                                CgUi.toast(CgAdsCampaignsActivity.this, "تم الرفض وإبلاغ المعلن.");
                                load();
                            }

                            @Override public void fail(String message) {
                                hideLoading();
                                CgUi.info(CgAdsCampaignsActivity.this, "تعذر الرفض", message);
                            }
                        });
                    }
                });
    }
}
