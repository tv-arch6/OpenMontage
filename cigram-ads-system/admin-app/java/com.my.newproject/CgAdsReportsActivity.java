package com.my.newproject;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * التقارير: نظرة عامة على كل الحملات، أو تقرير حملة واحدة مع رسم يومي ورابط عام.
 *
 * The public link is a read-only token URL the advertiser can open without an
 * account; it carries numbers only — never a price, a payment state or anything
 * about app users. "مشاركة كصورة" shares the numbers as text with the link, which
 * is what any chat app will render as a preview card.
 */
public class CgAdsReportsActivity extends CgBase {

    private String campaignId = "";
    private LinearLayout page;

    @Override protected String screenTitle() {
        return "تقارير الإعلانات";
    }

    @Override protected void onBuild() {
        campaignId = getIntent() == null ? "" : getIntent().getStringExtra("campaign_id");
        if (campaignId == null) campaignId = "";
        page = scrollBody();
        page.addView(CgAdsUi.skeleton(this, 3), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        load();
    }

    @Override protected void onResume() {
        super.onResume();
        CgAdsPresence.onScreenResumed(this);
    }

    @Override protected void onStop() {
        CgAdsPresence.onScreenStopped();
        super.onStop();
    }

    private void load() {
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgAdsApi.report(campaignId));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                JSONObject data = CgAdsApi.data(res);
                if (campaignId.length() > 0) renderCampaign(data);
                else renderOverview(data);
            }

            @Override public void fail(String message) {
                page.removeAllViews();
                page.addView(CgUi.errorCard(CgAdsReportsActivity.this, message, new Runnable() {
                    @Override public void run() {
                        page.removeAllViews();
                        page.addView(CgAdsUi.skeleton(CgAdsReportsActivity.this, 3),
                                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                        load();
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    // ------------------------------------------------------------- overview

    private void renderOverview(JSONObject data) {
        page.removeAllViews();
        JSONObject totals = data.optJSONObject("totals");
        long impressions = totals == null ? 0L : totals.optLong("impressions", 0L);
        long clicks = totals == null ? 0L : totals.optLong("clicks", 0L);

        LinearLayout summary = CgUi.card(this);
        summary.addView(CgUi.sectionTitle(this, "إجمالي النظام"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        LinearLayout tiles = CgUi.hbox(this);
        tiles.addView(CgAdsUi.tile(this, "مشاهدة", CgAdsUi.count(impressions), CgCfg.ACCENT),
                new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        mid.setMarginStart(CgUi.dp(this, 8));
        tiles.addView(CgAdsUi.tile(this, "نقرة", CgAdsUi.count(clicks), CgCfg.GOOD), mid);
        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        end.setMarginStart(CgUi.dp(this, 8));
        double ctr = impressions > 0L ? Math.round((clicks * 10000d) / impressions) / 100d : 0d;
        tiles.addView(CgAdsUi.tile(this, "نسبة النقر", ctr + "%", CgCfg.WARN), end);
        summary.addView(tiles, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        summary.addView(CgAdsUi.line(this, "عدد الحملات", String.valueOf(data.optInt("campaigns", 0))),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        summary.addView(CgAdsUi.line(this, "عدد المعلنين", String.valueOf(data.optInt("advertisers", 0))),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        page.addView(summary, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        JSONArray byCampaign = CgAdsApi.array(data, "by_campaign");
        if (byCampaign.length() == 0) {
            page.addView(CgUi.empty(this, "لا أرقام بعد", "ستظهر الأرقام بعد أول ظهور إعلان."),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            return;
        }
        LinearLayout list = CgUi.card(this);
        list.addView(CgUi.sectionTitle(this, "الأعلى مشاهدة"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        for (int i = 0; i < byCampaign.length() && i < 30; i++) {
            final JSONObject row = byCampaign.optJSONObject(i);
            if (row == null) continue;
            LinearLayout line = CgUi.hbox(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setClickable(true);
            final String id = row.optString("campaign_id", "");
            line.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent intent = new Intent(CgAdsReportsActivity.this, CgAdsReportsActivity.class);
                    intent.putExtra("campaign_id", id);
                    startActivity(intent);
                }
            });
            TextView name = CgUi.text(this, id, 12f, CgCfg.MUTED, false);
            CgUi.ltr(name);
            line.addView(name, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
            line.addView(CgUi.text(this, CgAdsUi.count(row.optLong("impressions", 0L)) + " / "
                            + CgAdsUi.count(row.optLong("clicks", 0L)), 12.5f, CgCfg.TEXT, true),
                    new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            list.addView(line, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        }
        page.addView(list, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 24));
    }

    // -------------------------------------------------------------- campaign

    private void renderCampaign(final JSONObject data) {
        page.removeAllViews();
        JSONObject campaign = data.optJSONObject("campaign");
        JSONObject totals = data.optJSONObject("totals");
        JSONObject advertiser = data.optJSONObject("advertiser");
        if (campaign == null) {
            page.addView(CgUi.empty(this, "لا يوجد تقرير", "تأكد من الحملة ثم أعد المحاولة."),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            return;
        }

        LinearLayout head = CgUi.card(this);
        head.addView(CgUi.text(this, campaign.optString("title", "حملة"), 17f, CgCfg.TEXT, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        head.addView(CgUi.text(this, (advertiser == null ? "—" : advertiser.optString("name", "—"))
                        + " · " + campaign.optString("slot_name", ""), 13f, CgCfg.MUTED, false),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        String status = campaign.optString("status", "");
        head.addView(CgUi.badge(this, CgAdsUi.statusLabel(status), CgAdsUi.statusColor(status)),
                CgUi.lp(this, CgUi.WRAP, CgUi.WRAP, 0, 8, 0, 0));

        long impressions = totals == null ? 0L : totals.optLong("impressions", 0L);
        long clicks = totals == null ? 0L : totals.optLong("clicks", 0L);
        double ctr = totals == null ? 0d : totals.optDouble("ctr", 0d);
        LinearLayout tiles = CgUi.hbox(this);
        tiles.addView(CgAdsUi.tile(this, "مشاهدة", CgAdsUi.count(impressions), CgCfg.ACCENT),
                new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        mid.setMarginStart(CgUi.dp(this, 8));
        tiles.addView(CgAdsUi.tile(this, "نقرة", CgAdsUi.count(clicks), CgCfg.GOOD), mid);
        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        end.setMarginStart(CgUi.dp(this, 8));
        tiles.addView(CgAdsUi.tile(this, "نسبة النقر", ctr + "%", CgCfg.WARN), end);
        head.addView(tiles, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 12, 0, 0));
        page.addView(head, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));

        final JSONArray days = CgAdsApi.array(data, "days");
        if (days.length() > 0) {
            LinearLayout chartCard = CgUi.card(this);
            chartCard.addView(CgUi.sectionTitle(this, "الرسم اليومي"),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            chartCard.addView(new Chart(this, days),
                    CgUi.lp(this, CgUi.MATCH, CgUi.dp(this, 120), 0, 10, 0, 0));
            chartCard.addView(CgUi.muted(this, "العمود الكامل مشاهدات، والجزء الملوّن نقرات."),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
            page.addView(chartCard, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        }

        final String publicPath = data.optString("public_url", "");
        LinearLayout share = CgUi.card(this);
        share.addView(CgUi.sectionTitle(this, "تقرير الشركة"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        share.addView(CgUi.muted(this,
                        "رابط للقراءة فقط بتوكن عشوائي. لا يحتوي سعراً ولا حالة دفع ولا أي بيانات مستخدمين."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 8));
        if (publicPath.length() > 0) {
            final String fullUrl = publicPath.startsWith("http") ? publicPath : CgCfg.API + publicPath;
            TextView link = CgUi.text(this, fullUrl, 12f, CgCfg.ACCENT, false);
            CgUi.ltr(link);
            link.setTextIsSelectable(true);
            share.addView(link, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            LinearLayout buttons = CgUi.hbox(this);
            TextView copy = CgUi.smallButton(this, "نسخ الرابط", 1);
            copy.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    copyText(fullUrl, "تم نسخ الرابط.");
                }
            });
            buttons.addView(copy, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));

            TextView open = CgUi.smallButton(this, "فتح", 1);
            open.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } catch (Exception ignored) {
                        CgUi.toast(CgAdsReportsActivity.this, "لا يوجد تطبيق يفتح الرابط.");
                    }
                }
            });
            LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            op.setMarginStart(CgUi.dp(this, 6));
            buttons.addView(open, op);

            final long shownImpressions = impressions;
            final long shownClicks = clicks;
            final double shownCtr = ctr;
            TextView shareButton = CgUi.smallButton(this, "مشاركة", 0);
            shareButton.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    StringBuilder text = new StringBuilder();
                    text.append("تقرير حملة: ").append(campaignTitle(data)).append("\n");
                    text.append("المشاهدات: ").append(shownImpressions).append("\n");
                    text.append("النقرات: ").append(shownClicks).append("\n");
                    text.append("نسبة النقر: ").append(shownCtr).append("%\n");
                    text.append(fullUrl);
                    try {
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("text/plain");
                        intent.putExtra(Intent.EXTRA_TEXT, text.toString());
                        startActivity(Intent.createChooser(intent, "مشاركة التقرير"));
                    } catch (Exception ignored) {
                        copyText(text.toString(), "تم نسخ التقرير.");
                    }
                }
            });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            sp.setMarginStart(CgUi.dp(this, 6));
            buttons.addView(shareButton, sp);
            share.addView(buttons, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        }
        page.addView(share, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 24));
    }

    private static String campaignTitle(JSONObject data) {
        JSONObject campaign = data.optJSONObject("campaign");
        return campaign == null ? "" : campaign.optString("title", "");
    }

    private void copyText(String text, String message) {
        try {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("cigram", text));
            CgUi.toast(this, message);
        } catch (Exception ignored) {
            CgUi.toast(this, "تعذر النسخ.");
        }
    }

    /** Impressions as the full bar, clicks as the coloured part of the same bar. */
    private static final class Chart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final JSONArray days;
        private final float density;
        private long peak = 1L;

        Chart(android.content.Context context, JSONArray days) {
            super(context);
            this.days = days == null ? new JSONArray() : days;
            this.density = context.getResources().getDisplayMetrics().density;
            for (int i = 0; i < this.days.length(); i++) {
                JSONObject day = this.days.optJSONObject(i);
                if (day != null) peak = Math.max(peak, day.optLong("impressions", 0L));
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            int count = days.length();
            if (width <= 0 || height <= 0 || count == 0) return;
            int from = Math.max(0, count - 30);
            int shown = count - from;
            float gap = 2f * density;
            float barWidth = Math.max(2f * density, (width - gap * (shown - 1)) / (float) shown);
            float bottom = height - 14f * density;

            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < shown; i++) {
                JSONObject day = days.optJSONObject(from + i);
                long impressions = day == null ? 0L : day.optLong("impressions", 0L);
                long clicks = day == null ? 0L : day.optLong("clicks", 0L);
                float full = Math.max(1.5f * density,
                        (impressions / (float) peak) * (bottom - 4f * density));
                float right = width - i * (barWidth + gap);
                float left = right - barWidth;
                paint.setColor(CgUi.alpha(CgCfg.ACCENT, 0x99));
                canvas.drawRoundRect(new RectF(left, bottom - full, right, bottom),
                        barWidth / 3f, barWidth / 3f, paint);
                if (clicks > 0L && impressions > 0L) {
                    float clickHeight = Math.max(1f * density, full * (clicks / (float) impressions));
                    paint.setColor(CgCfg.GOOD);
                    canvas.drawRoundRect(new RectF(left, bottom - clickHeight, right, bottom),
                            barWidth / 3f, barWidth / 3f, paint);
                }
            }
            paint.setColor(CgCfg.GRAY);
            paint.setTextSize(9f * density);
            JSONObject newest = days.optJSONObject(count - 1);
            JSONObject oldest = days.optJSONObject(from);
            paint.setTextAlign(Paint.Align.RIGHT);
            if (newest != null) {
                canvas.drawText(newest.optString("day", ""), width, height - 2f * density, paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
            if (oldest != null) {
                canvas.drawText(oldest.optString("day", ""), 0, height - 2f * density, paint);
            }
        }
    }
}
