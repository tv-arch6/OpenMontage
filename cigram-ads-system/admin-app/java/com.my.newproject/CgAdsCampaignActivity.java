package com.my.newproject;

import android.content.Intent;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * حملة واحدة: الأساسيات، الاستهداف، المواد الإعلانية مع معاينة تحاكي المساحة،
 * الدفع، ثم المراجعة (اعتماد / رفض بسبب) والتمديد.
 *
 * Two rules are enforced here as well as on the Worker, because the admin should
 * see them before saving rather than as a refusal afterwards:
 *   - a campaign cannot be approved with no creative,
 *   - changing the creative, the slot or the title sends it back to review (the
 *     Worker does that; the screen says so before you save).
 *
 * Prices are never computed locally: the "احسب" button asks POST /admin/ads/quote
 * and fills the field, and the admin may then override it by hand.
 */
public class CgAdsCampaignActivity extends CgBase {

    private static final int REQ_CREATIVE = 6101;

    private String campaignId = "";
    private JSONObject campaign;
    private JSONObject settings;
    private JSONArray advertisers = new JSONArray();
    private final List<JSONObject> creatives = new ArrayList<JSONObject>();

    private LinearLayout page;
    private TextView advertiserButton;
    private String advertiserId = "";
    private EditText title;
    private CgAdsUi.Filters slotPicker;
    private TextView startButton;
    private TextView endButton;
    private String startAt = "";
    private String endAt = "";
    private EditText price;
    private EditText priority;
    private EditText dailyCap;
    private CgUi.Toggle exclusive;
    private EditText cities;
    private EditText minVersion;
    private EditText maxVersion;
    private LinearLayout creativeList;
    private TextView statusLine;

    @Override protected String screenTitle() {
        return "حملة إعلانية";
    }

    @Override protected void onBuild() {
        campaignId = getIntent() == null ? "" : getIntent().getStringExtra("campaign_id");
        if (campaignId == null) campaignId = "";
        page = scrollBody();
        page.addView(CgAdsUi.skeleton(this, 4), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        loadEverything();
    }

    @Override protected void onResume() {
        super.onResume();
        CgAdsPresence.onScreenResumed(this);
    }

    @Override protected void onStop() {
        CgAdsPresence.onScreenStopped();
        super.onStop();
    }

    // ------------------------------------------------------------------ load

    private void loadEverything() {
        CgHttp.async(this, new CgHttp.Work<JSONObject>() {
            @Override public JSONObject run() throws Exception {
                JSONObject bundle = new JSONObject();
                bundle.put("settings", CgAdsApi.data(CgHttp.require(CgAdsApi.settings())));
                bundle.put("advertisers", CgAdsApi.array(
                        CgAdsApi.data(CgHttp.require(CgAdsApi.advertisers())), "items"));
                if (campaignId.length() > 0) {
                    JSONArray items = CgAdsApi.array(
                            CgAdsApi.data(CgHttp.require(CgAdsApi.campaigns("", ""))), "items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item != null && campaignId.equals(item.optString("id", ""))) {
                            bundle.put("campaign", item);
                            break;
                        }
                    }
                }
                return bundle;
            }
        }, new CgHttp.Done<JSONObject>() {
            @Override public void ok(JSONObject bundle) {
                settings = bundle.optJSONObject("settings");
                advertisers = CgAdsApi.array(bundle, "advertisers");
                campaign = bundle.optJSONObject("campaign");
                build();
            }

            @Override public void fail(String message) {
                page.removeAllViews();
                page.addView(CgUi.errorCard(CgAdsCampaignActivity.this, message, new Runnable() {
                    @Override public void run() {
                        page.removeAllViews();
                        page.addView(CgAdsUi.skeleton(CgAdsCampaignActivity.this, 4),
                                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                        loadEverything();
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    // ----------------------------------------------------------------- build

    private void build() {
        page.removeAllViews();
        creatives.clear();

        if (campaign != null) {
            advertiserId = campaign.optString("advertiser_id", "");
            startAt = campaign.optString("start_at", "");
            endAt = campaign.optString("end_at", "");
            JSONArray existing = campaign.optJSONArray("creatives");
            if (existing != null) {
                for (int i = 0; i < existing.length(); i++) {
                    JSONObject creative = existing.optJSONObject(i);
                    if (creative != null) creatives.add(creative);
                }
            }
        }

        statusLine = CgUi.text(this, "", 13f, CgCfg.MUTED, true);
        page.addView(statusLine, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 0, 2, 10));
        paintStatus();

        page.addView(basicsCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(scheduleCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(targetingCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        page.addView(creativesCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        if (campaign != null) {
            page.addView(paymentCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
            page.addView(reviewCard(), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        }

        TextView save = CgUi.button(this, campaign == null ? "إنشاء الحملة" : "حفظ التعديلات", 0);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                save();
            }
        });
        page.addView(save, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 24));
    }

    private void paintStatus() {
        if (statusLine == null) return;
        if (campaign == null) {
            statusLine.setText("حملة جديدة — تُحفظ كـ«قيد المراجعة» حتى تعتمدها.");
            statusLine.setTextColor(CgCfg.MUTED);
            return;
        }
        String status = campaign.optString("effective_status", campaign.optString("status", ""));
        statusLine.setText("الحالة: " + CgAdsUi.statusLabel(status));
        statusLine.setTextColor(CgAdsUi.statusColor(status));
    }

    private View basicsCard() {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.sectionTitle(this, "الأساسيات"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        advertiserButton = CgUi.button(this, advertiserName(), 1);
        advertiserButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                pickAdvertiser();
            }
        });
        card.addView(CgUi.labeled(this, "المعلن", advertiserButton),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        title = CgUi.field(this, "عنوان داخلي للحملة", false);
        if (campaign != null) title.setText(campaign.optString("title", ""));
        card.addView(CgUi.labeled(this, "العنوان", title),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        card.addView(CgUi.text(this, "المساحة", 12.5f, CgCfg.MUTED, false),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 4));
        slotPicker = new CgAdsUi.Filters(this);
        for (String slot : CgAdsUi.SLOT_IDS) slotPicker.add(CgAdsUi.slotLabel(slot), slot);
        if (campaign != null) slotPicker.pick(campaign.optString("slot", "hero"));
        card.addView(slotPicker.view, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        LinearLayout priceRow = CgUi.hbox(this);
        price = CgUi.numberField(this, "السعر النهائي");
        if (campaign != null && campaign.optDouble("price_final", 0d) > 0d) {
            price.setText(String.valueOf(Math.round(campaign.optDouble("price_final", 0d))));
        }
        priceRow.addView(price, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView compute = CgUi.smallButton(this, "احسب", 1);
        compute.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                computePrice();
            }
        });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        cp.setMarginStart(CgUi.dp(this, 8));
        priceRow.addView(compute, cp);
        card.addView(CgUi.labeled(this, "السعر (قابل للتعديل يدوياً)", priceRow),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        return card;
    }

    private View scheduleCard() {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.sectionTitle(this, "المدة والجدولة"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        startButton = CgUi.button(this, startAt.length() > 0 ? CgUi.localText(startAt) : "اختر البداية", 1);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.pickDateTime(CgAdsCampaignActivity.this, startAt, new CgUi.Callback<String>() {
                    @Override public void run(String iso) {
                        startAt = iso == null ? "" : iso;
                        startButton.setText(startAt.length() > 0 ? CgUi.localText(startAt) : "اختر البداية");
                    }
                });
            }
        });
        card.addView(CgUi.labeled(this, "تبدأ", startButton),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        endButton = CgUi.button(this, endAt.length() > 0 ? CgUi.localText(endAt) : "اختر النهاية", 1);
        endButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.pickDateTime(CgAdsCampaignActivity.this, endAt, new CgUi.Callback<String>() {
                    @Override public void run(String iso) {
                        endAt = iso == null ? "" : iso;
                        endButton.setText(endAt.length() > 0 ? CgUi.localText(endAt) : "اختر النهاية");
                    }
                });
            }
        });
        card.addView(CgUi.labeled(this, "تنتهي", endButton),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        LinearLayout quick = CgUi.hbox(this);
        int[] presets = {1, 3, 7, 30, 90, 180, 365};
        for (final int days : presets) {
            TextView chip = CgUi.chip(this, days + " يوم", false);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    long from = startAt.length() > 0 ? CgUi.parseIso(startAt) : System.currentTimeMillis();
                    if (from <= 0L) from = System.currentTimeMillis();
                    startAt = CgUi.toIso(from);
                    endAt = CgUi.toIso(from + (long) days * 86400000L);
                    startButton.setText(CgUi.localText(startAt));
                    endButton.setText(CgUi.localText(endAt));
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            lp.setMarginStart(quick.getChildCount() == 0 ? 0 : CgUi.dp(this, 5));
            quick.addView(chip, lp);
        }
        android.widget.HorizontalScrollView quickScroll = new android.widget.HorizontalScrollView(this);
        quickScroll.setHorizontalScrollBarEnabled(false);
        quickScroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        quickScroll.addView(quick, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        card.addView(quickScroll, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        if (campaign != null) {
            TextView extend = CgUi.button(this, "تمديد الحملة", 1);
            extend.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    extend();
                }
            });
            card.addView(extend, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        }
        return card;
    }

    private View targetingCard() {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.sectionTitle(this, "الاستهداف والعرض"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        JSONObject targeting = campaign == null ? null : campaign.optJSONObject("targeting");
        cities = CgUi.field(this, "اتركه فارغاً لكل المدن", false);
        if (targeting != null) {
            JSONArray list = targeting.optJSONArray("cities");
            if (list != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < list.length(); i++) {
                    if (sb.length() > 0) sb.append("، ");
                    sb.append(list.optString(i, ""));
                }
                cities.setText(sb.toString());
            }
        }
        card.addView(CgUi.labeled(this, "المدن (مفصولة بفاصلة)", cities),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        LinearLayout versions = CgUi.hbox(this);
        minVersion = CgUi.field(this, "أقل نسخة (1.0.0)", false);
        maxVersion = CgUi.field(this, "أعلى نسخة", false);
        if (targeting != null) {
            minVersion.setText(targeting.optString("min_app_version", ""));
            maxVersion.setText(targeting.optString("max_app_version", ""));
        }
        versions.addView(minVersion, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        vp.setMarginStart(CgUi.dp(this, 8));
        versions.addView(maxVersion, vp);
        card.addView(CgUi.labeled(this, "نسخة التطبيق", versions),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        priority = CgUi.numberField(this, "1 إلى 10");
        priority.setText(String.valueOf(campaign == null ? 5 : campaign.optInt("priority", 5)));
        card.addView(CgUi.labeled(this, "الأولوية في التناوب", priority),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        dailyCap = CgUi.numberField(this, "0 = حسب إعداد المساحة");
        dailyCap.setText(String.valueOf(campaign == null ? 0 : campaign.optInt("daily_cap_per_user", 0)));
        card.addView(CgUi.labeled(this, "حد الظهور اليومي لكل مستخدم", dailyCap),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        exclusive = CgUi.toggle(this, "حصرية — تمنع أي معلن آخر في نفس المساحة",
                campaign != null && campaign.optBoolean("exclusive", false));
        card.addView(exclusive, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        return card;
    }

    // ------------------------------------------------------------- creatives

    private View creativesCard() {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.sectionTitle(this, "المواد الإعلانية"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(CgUi.muted(this,
                        "لا يُعتمد أي إعلان بدون مادة. تغيير المادة يعيد الحملة للمراجعة."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 6, 0, 10));

        creativeList = CgUi.vbox(this);
        card.addView(creativeList, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        renderCreatives();

        TextView add = CgUi.button(this, "+ إضافة مادة", 1);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                addCreative();
            }
        });
        card.addView(add, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        return card;
    }

    private void renderCreatives() {
        creativeList.removeAllViews();
        if (creatives.isEmpty()) {
            creativeList.addView(CgUi.text(this, "لا توجد مواد بعد.", 13f, CgCfg.BAD, false),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            return;
        }
        for (int i = 0; i < creatives.size(); i++) {
            final int index = i;
            final JSONObject creative = creatives.get(i);
            LinearLayout box = CgUi.vbox(this);
            box.setBackground(CgUi.bg(this, CgCfg.CARD2, 12, CgCfg.STROKE));
            int p = CgUi.dp(this, 10);
            box.setPadding(p, p, p, p);

            box.addView(CgAdsUi.line(this, "النوع",
                            "video".equals(creative.optString("type")) ? "فيديو" : "صورة"),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            box.addView(CgAdsUi.line(this, "العنوان", creative.optString("title", "—")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
            JSONObject action = creative.optJSONObject("action");
            box.addView(CgAdsUi.line(this, "الإجراء", actionLabel(action)),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));

            box.addView(new CgAdsSlotPreview(this, slotPicker.selected(), creative),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

            LinearLayout buttons = CgUi.hbox(this);
            TextView edit = CgUi.smallButton(this, "تعديل النصوص", 1);
            edit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    editCreative(index);
                }
            });
            buttons.addView(edit, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            TextView remove = CgUi.smallButton(this, "حذف", 2);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    creatives.remove(index);
                    renderCreatives();
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            rp.setMarginStart(CgUi.dp(this, 6));
            buttons.addView(remove, rp);
            box.addView(buttons, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

            creativeList.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
    }

    private static String actionLabel(JSONObject action) {
        if (action == null) return "بلا إجراء";
        String type = action.optString("type", "none");
        String value = action.optString("value", "");
        if ("url".equals(type)) return "رابط: " + value;
        if ("store".equals(type)) return "متجر: " + value;
        if ("whatsapp".equals(type)) return "واتساب: " + value;
        if ("call".equals(type)) return "اتصال: " + value;
        if ("coupon".equals(type)) return "كوبون: " + value;
        return "بلا إجراء";
    }

    private void addCreative() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/mp4", "video/webm"});
        startActivityForResult(intent, REQ_CREATIVE);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_CREATIVE || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        showLoading("جارٍ رفع المادة...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                byte[] bytes = readUri(uri, 40 * 1024 * 1024);
                if (bytes == null) throw new Exception("تعذر قراءة الملف أو أنه أكبر من المسموح.");
                return CgHttp.require(CgAdsApi.uploadCreative(bytes, "application/octet-stream"));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                JSONObject uploaded = CgAdsApi.data(res);
                JSONObject creative = new JSONObject();
                try {
                    creative.put("type", uploaded.optBoolean("is_video", false) ? "video" : "image");
                    creative.put("url", uploaded.optString("url", ""));
                    creative.put("title", "");
                    creative.put("body", "");
                    creative.put("cta_label", "");
                    JSONObject action = new JSONObject();
                    action.put("type", "none");
                    action.put("value", "");
                    creative.put("action", action);
                } catch (Exception ignored) { }
                creatives.add(creative);
                renderCreatives();
                editCreative(creatives.size() - 1);
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsCampaignActivity.this, "تعذر الرفع", message);
            }
        });
    }

    private void editCreative(final int index) {
        if (index < 0 || index >= creatives.size()) return;
        final JSONObject creative = creatives.get(index);

        LinearLayout form = CgUi.vbox(this);
        final EditText headline = CgUi.field(this, "عنوان قصير يظهر على الإعلان", false);
        headline.setText(creative.optString("title", ""));
        form.addView(CgUi.labeled(this, "العنوان", headline),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final EditText bodyText = CgUi.field(this, "سطر وصف (اختياري)", true);
        bodyText.setText(creative.optString("body", ""));
        form.addView(CgUi.labeled(this, "الوصف", bodyText),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        final EditText cta = CgUi.field(this, "نص الزر، مثال: اطلب الآن", false);
        cta.setText(creative.optString("cta_label", ""));
        form.addView(CgUi.labeled(this, "نص الزر", cta),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        JSONObject action = creative.optJSONObject("action");
        final String[] types = {"none", "url", "whatsapp", "call", "store", "coupon"};
        final CharSequence[] typeLabels = {"بلا إجراء", "رابط", "واتساب", "اتصال", "متجر", "كوبون"};
        final String[] chosen = {action == null ? "none" : action.optString("type", "none")};
        final TextView typeButton = CgUi.button(this, labelFor(types, typeLabels, chosen[0]), 1);
        typeButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.choose(CgAdsCampaignActivity.this, "إجراء الزر", typeLabels, new CgUi.IntCb() {
                    @Override public void run(int which) {
                        if (which < 0 || which >= types.length) return;
                        chosen[0] = types[which];
                        typeButton.setText(typeLabels[which]);
                    }
                });
            }
        });
        form.addView(CgUi.labeled(this, "الإجراء", typeButton),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        final EditText actionValue = CgUi.field(this, "الرابط أو الرقم أو الكوبون", false);
        actionValue.setText(action == null ? "" : action.optString("value", ""));
        form.addView(CgUi.labeled(this, "قيمة الإجراء", actionValue),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(form);
        CgUi.dialog(this)
                .setTitle("نصوص المادة الإعلانية")
                .setView(scroll)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        try {
                            creative.put("title", text(headline));
                            creative.put("body", text(bodyText));
                            creative.put("cta_label", text(cta));
                            JSONObject newAction = new JSONObject();
                            newAction.put("type", chosen[0]);
                            newAction.put("value", text(actionValue));
                            creative.put("action", newAction);
                        } catch (Exception ignored) { }
                        renderCreatives();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private static String labelFor(String[] values, CharSequence[] labels, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) return labels[i].toString();
        }
        return labels[0].toString();
    }

    // --------------------------------------------------------------- payment

    private View paymentCard() {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.sectionTitle(this, "الدفع"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        JSONObject payment = campaign.optJSONObject("payment");
        final String status = payment == null ? "unpaid" : payment.optString("status", "unpaid");
        card.addView(CgUi.badge(this, CgAdsUi.paymentLabel(status), CgAdsUi.paymentColor(status)),
                CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));
        card.addView(CgAdsUi.line(this, "المدفوع",
                        CgAdsUi.money(payment == null ? 0d : payment.optDouble("amount_paid", 0d), "ر.س")),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        if (payment != null && payment.optString("method", "").length() > 0) {
            card.addView(CgAdsUi.line(this, "الطريقة", payment.optString("method")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }
        card.addView(CgUi.muted(this, "الدفع يدوي حالياً (لا بوابة دفع)."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        TextView record = CgUi.button(this, "تسجيل حالة الدفع", 1);
        record.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                recordPayment();
            }
        });
        card.addView(record, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        return card;
    }

    private void recordPayment() {
        final String[] values = {"unpaid", "partial", "paid", "refunded"};
        final CharSequence[] labels = {"غير مدفوع", "مدفوع جزئياً", "مدفوع", "مُسترجع"};
        CgUi.choose(this, "حالة الدفع", labels, new CgUi.IntCb() {
            @Override public void run(int index) {
                if (index < 0 || index >= values.length) return;
                final String status = values[index];
                LinearLayout form = CgUi.vbox(this == null ? CgAdsCampaignActivity.this
                        : CgAdsCampaignActivity.this);
                final EditText amount = CgUi.numberField(CgAdsCampaignActivity.this, "المبلغ المدفوع");
                JSONObject payment = campaign.optJSONObject("payment");
                amount.setText(String.valueOf(Math.round(
                        payment == null ? 0d : payment.optDouble("amount_paid", 0d))));
                form.addView(CgUi.labeled(CgAdsCampaignActivity.this, "المبلغ", amount),
                        new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                final EditText method = CgUi.field(CgAdsCampaignActivity.this, "تحويل بنكي…", false);
                form.addView(CgUi.labeled(CgAdsCampaignActivity.this, "الطريقة", method),
                        CgUi.lp(CgAdsCampaignActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
                final EditText note = CgUi.field(CgAdsCampaignActivity.this, "ملاحظة", true);
                form.addView(CgUi.labeled(CgAdsCampaignActivity.this, "ملاحظة", note),
                        CgUi.lp(CgAdsCampaignActivity.this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
                android.widget.ScrollView scroll = new android.widget.ScrollView(CgAdsCampaignActivity.this);
                scroll.addView(form);
                CgUi.dialog(CgAdsCampaignActivity.this)
                        .setTitle(labels[index].toString())
                        .setView(scroll)
                        .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                final double value = parse(amount);
                                showLoading("جارٍ الحفظ...");
                                CgAdsApi.async(CgAdsCampaignActivity.this, new CgAdsApi.Call() {
                                    @Override public CgHttp.Res run() {
                                        return CgAdsApi.payment(campaignId, status, value,
                                                text(method), text(note));
                                    }
                                }, reload("تم تسجيل حالة الدفع."));
                            }
                        })
                        .setNegativeButton("إلغاء", null)
                        .show();
            }
        });
    }

    // ---------------------------------------------------------------- review

    private View reviewCard() {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.sectionTitle(this, "المراجعة والتشغيل"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        JSONObject review = campaign.optJSONObject("review");
        boolean approved = review != null && review.optBoolean("approved", false);
        card.addView(CgAdsUi.line(this, "الاعتماد", approved ? "معتمدة" : "غير معتمدة"),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        if (review != null && review.optString("reject_reason", "").length() > 0) {
            card.addView(CgAdsUi.line(this, "سبب الرفض", review.optString("reject_reason")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }

        LinearLayout buttons = CgUi.hbox(this);
        if (!approved) {
            TextView approve = CgUi.smallButton(this, "اعتماد", 0);
            approve.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (creatives.isEmpty()) {
                        CgUi.info(CgAdsCampaignActivity.this, "لا توجد مادة",
                                "أضف مادة إعلانية واحفظ الحملة قبل الاعتماد.");
                        return;
                    }
                    showLoading("جارٍ الاعتماد...");
                    CgAdsApi.async(CgAdsCampaignActivity.this, new CgAdsApi.Call() {
                        @Override public CgHttp.Res run() {
                            return CgAdsApi.approve(campaignId);
                        }
                    }, reload("تم الاعتماد."));
                }
            });
            buttons.addView(approve, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }

        TextView reject = CgUi.smallButton(this, "رفض بسبب", 2);
        reject.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.input(CgAdsCampaignActivity.this, "رفض الحملة", "السبب — يصل للمعلن", "", true,
                        new CgUi.Callback<String>() {
                            @Override public void run(final String reason) {
                                if (reason == null || reason.trim().length() == 0) {
                                    CgUi.info(CgAdsCampaignActivity.this, "السبب مطلوب",
                                            "اكتب سبباً واضحاً؛ سيظهر للمعلن.");
                                    return;
                                }
                                showLoading("جارٍ الرفض...");
                                CgAdsApi.async(CgAdsCampaignActivity.this, new CgAdsApi.Call() {
                                    @Override public CgHttp.Res run() {
                                        return CgAdsApi.reject(campaignId, reason.trim());
                                    }
                                }, reload("تم الرفض وإبلاغ المعلن."));
                            }
                        });
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        rp.setMarginStart(CgUi.dp(this, 6));
        buttons.addView(reject, rp);

        final String status = campaign.optString("effective_status", "");
        TextView toggle = CgUi.smallButton(this, "paused".equals(status) ? "تشغيل" : "إيقاف مؤقت", 1);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String wanted = "paused".equals(status) ? "active" : "paused";
                showLoading("جارٍ التحديث...");
                CgAdsApi.async(CgAdsCampaignActivity.this, new CgAdsApi.Call() {
                    @Override public CgHttp.Res run() {
                        return CgAdsApi.status(campaignId, wanted);
                    }
                }, reload("تم التحديث."));
            }
        });
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        tp.setMarginStart(CgUi.dp(this, 6));
        buttons.addView(toggle, tp);
        card.addView(buttons, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));

        TextView remove = CgUi.button(this, "حذف الحملة ومواد إعلانها", 2);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                CgUi.confirm(CgAdsCampaignActivity.this, "حذف الحملة",
                        "سيُحذف كل شيء نهائياً، مع ملفات المواد من التخزين.", "حذف", true,
                        new Runnable() {
                            @Override public void run() {
                                showLoading("جارٍ الحذف...");
                                CgAdsApi.async(CgAdsCampaignActivity.this, new CgAdsApi.Call() {
                                    @Override public CgHttp.Res run() {
                                        return CgAdsApi.deleteCampaign(campaignId);
                                    }
                                }, new CgHttp.Done<CgHttp.Res>() {
                                    @Override public void ok(CgHttp.Res res) {
                                        hideLoading();
                                        CgUi.toast(CgAdsCampaignActivity.this, "تم الحذف.");
                                        finish();
                                    }

                                    @Override public void fail(String message) {
                                        hideLoading();
                                        CgUi.info(CgAdsCampaignActivity.this, "تعذر الحذف", message);
                                    }
                                });
                            }
                        });
            }
        });
        card.addView(remove, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        return card;
    }

    private void extend() {
        CgUi.input(this, "تمديد الحملة", "عدد الأيام", "7", false, new CgUi.Callback<String>() {
            @Override public void run(String value) {
                int days;
                try {
                    days = Integer.parseInt(value.trim());
                } catch (Exception ignored) {
                    days = 0;
                }
                if (days <= 0) {
                    CgUi.info(CgAdsCampaignActivity.this, "عدد غير صالح", "أدخل عدداً أكبر من صفر.");
                    return;
                }
                final int wanted = days;
                showLoading("جارٍ التمديد...");
                CgAdsApi.async(CgAdsCampaignActivity.this, new CgAdsApi.Call() {
                    @Override public CgHttp.Res run() {
                        return CgAdsApi.extend(campaignId, wanted);
                    }
                }, reload("تم التمديد."));
            }
        });
    }

    // ------------------------------------------------------------------ save

    private void computePrice() {
        long from = startAt.length() > 0 ? CgUi.parseIso(startAt) : 0L;
        long to = endAt.length() > 0 ? CgUi.parseIso(endAt) : 0L;
        final int days = (from > 0L && to > from) ? Math.max(1, (int) ((to - from) / 86400000L)) : 7;
        final String slot = slotPicker.selected();
        showLoading("جارٍ الحساب...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                JSONObject request = new JSONObject();
                try {
                    request.put("slot", slot);
                    request.put("days", days);
                    request.put("cities", splitList(text(cities)));
                    if (exclusive != null && exclusive.on) {
                        JSONArray addons = new JSONArray();
                        addons.put("exclusive");
                        request.put("addons", addons);
                    }
                } catch (Exception ignored) { }
                return CgAdsApi.quote(request);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                JSONObject quote = CgAdsApi.data(res);
                price.setText(String.valueOf(Math.round(quote.optDouble("total", 0d))));
                CgUi.toast(CgAdsCampaignActivity.this,
                        days + " يوم · " + CgAdsUi.money(quote.optDouble("total", 0d),
                                quote.optString("currency_label", "ر.س")));
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsCampaignActivity.this, "تعذر الحساب", message);
            }
        });
    }

    private void save() {
        if (advertiserId.length() == 0) {
            CgUi.info(this, "المعلن مطلوب", "اختر المعلن الذي تنتمي له الحملة.");
            return;
        }
        if (startAt.length() > 0 && endAt.length() > 0 && CgUi.parseIso(endAt) <= CgUi.parseIso(startAt)) {
            CgUi.info(this, "تواريخ غير صحيحة", "تاريخ النهاية يجب أن يكون بعد البداية.");
            return;
        }
        final JSONObject body = new JSONObject();
        try {
            if (campaignId.length() > 0) body.put("id", campaignId);
            body.put("advertiser_id", advertiserId);
            body.put("slot", slotPicker.selected());
            body.put("title", text(title));
            body.put("start_at", startAt);
            body.put("end_at", endAt);
            body.put("price_final", parse(price));
            body.put("priority", (int) parse(priority));
            body.put("daily_cap_per_user", (int) parse(dailyCap));
            body.put("exclusive", exclusive != null && exclusive.on);

            JSONObject targeting = new JSONObject();
            targeting.put("cities", splitList(text(cities)));
            targeting.put("min_app_version", text(minVersion));
            targeting.put("max_app_version", text(maxVersion));
            body.put("targeting", targeting);

            JSONArray list = new JSONArray();
            for (JSONObject creative : creatives) list.put(creative);
            body.put("creatives", list);
        } catch (Exception ignored) { }

        showLoading("جارٍ الحفظ...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                return CgAdsApi.saveCampaign(body);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                JSONObject saved = CgAdsApi.data(res).optJSONObject("campaign");
                if (saved != null) {
                    campaignId = saved.optString("id", campaignId);
                    campaign = saved;
                }
                CgUi.toast(CgAdsCampaignActivity.this, "تم الحفظ.");
                loadEverything();
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsCampaignActivity.this, "تعذر الحفظ", message);
            }
        });
    }

    private CgHttp.Done<CgHttp.Res> reload(final String success) {
        return new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                CgUi.toast(CgAdsCampaignActivity.this, success);
                loadEverything();
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsCampaignActivity.this, "تعذر التنفيذ", message);
            }
        };
    }

    // --------------------------------------------------------------- helpers

    private String advertiserName() {
        for (int i = 0; i < advertisers.length(); i++) {
            JSONObject advertiser = advertisers.optJSONObject(i);
            if (advertiser != null && advertiserId.equals(advertiser.optString("id", ""))) {
                return advertiser.optString("name", "معلن");
            }
        }
        return "اختر المعلن";
    }

    private void pickAdvertiser() {
        if (advertisers.length() == 0) {
            CgUi.info(this, "لا يوجد معلنون", "أضف معلناً من شاشة «المعلنون» أولاً.");
            return;
        }
        CharSequence[] labels = new CharSequence[advertisers.length()];
        final String[] ids = new String[advertisers.length()];
        for (int i = 0; i < advertisers.length(); i++) {
            JSONObject advertiser = advertisers.optJSONObject(i);
            labels[i] = advertiser == null ? "—" : advertiser.optString("name", "—");
            ids[i] = advertiser == null ? "" : advertiser.optString("id", "");
        }
        CgUi.choose(this, "المعلن", labels, new CgUi.IntCb() {
            @Override public void run(int index) {
                if (index < 0 || index >= ids.length) return;
                advertiserId = ids[index];
                advertiserButton.setText(advertiserName());
            }
        });
    }

    private static String text(EditText field) {
        if (field == null || field.getText() == null) return "";
        return field.getText().toString().trim();
    }

    private static double parse(EditText field) {
        try {
            return Double.parseDouble(text(field));
        } catch (Exception ignored) {
            return 0d;
        }
    }

    private static JSONArray splitList(String raw) {
        JSONArray list = new JSONArray();
        if (raw == null) return list;
        for (String part : raw.split("[،,]")) {
            String trimmed = part.trim();
            if (trimmed.length() > 0) list.put(trimmed);
        }
        return list;
    }

    private byte[] readUri(Uri uri, long limit) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int n;
                long total = 0L;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > limit) return null;
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            } finally {
                in.close();
            }
        } catch (Exception ignored) {
            return null;
        }
    }
}
