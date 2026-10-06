package com.Cigram.vid;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * «إعلاناتي» — what the advertiser sees about their own campaigns.
 *
 * Everything on this screen comes from GET /ads/my-campaigns, which the Worker
 * scopes to the signed-in account: another advertiser's campaigns, prices and
 * numbers can never appear here, and no app-user data is in the response at all.
 *
 * Per campaign: its state in plain Arabic (with the rejection reason when there
 * is one), impressions, clicks, click-through rate, a daily bar chart drawn on a
 * Canvas, a renew button that opens the chat with the request prefilled, and a
 * link to the read-only report page.
 */
public class CigramMyAdsActivity extends Activity implements CigramAdsApi.Alive {

    private LinearLayout content;
    private ScrollView scroll;

    @Override public boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().setStatusBarColor(CigramAdsUi.PAGE);
            getWindow().setNavigationBarColor(Color.BLACK);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignored) { }

        LinearLayout column = CigramAdsUi.column(this);
        column.setBackgroundColor(CigramAdsUi.PAGE);
        column.addView(buildToolbar(), new LinearLayout.LayoutParams(-1, -2));

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        content = CigramAdsUi.column(this);
        content.setPadding(CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 10),
                CigramAdsUi.dp(this, 14), CigramAdsUi.dp(this, 32));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        column.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(column);
        try {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } catch (Throwable ignored) { }

        if (!CigramUserData.isLoggedIn(this)) {
            showSignIn();
            return;
        }
        showSkeleton();
        load();
    }

    private View buildToolbar() {
        LinearLayout bar = CigramAdsUi.row(this);
        bar.setMinimumHeight(CigramAdsUi.dp(this, 56));
        bar.setPadding(CigramAdsUi.dp(this, 6), CigramAdsUi.dp(this, 6),
                CigramAdsUi.dp(this, 12), CigramAdsUi.dp(this, 6));
        FrameLayout back = new FrameLayout(this);
        CigramAdsUi.Icon icon = new CigramAdsUi.Icon(this, CigramAdsUi.ICON_CHEVRON_START, CigramAdsUi.TEXT);
        icon.setRotation(180f);
        int inner = CigramAdsUi.dp(this, 22);
        back.addView(icon, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        CigramAdsUi.pressable(back, CigramAdsUi.round(this, Color.TRANSPARENT, 24), CigramAdsUi.ACCENT, 24);
        back.setContentDescription("رجوع");
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        int size = CigramAdsUi.dp(this, 48);
        bar.addView(back, new LinearLayout.LayoutParams(size, size));
        TextView title = CigramAdsUi.text(this, "إعلاناتي", 18f, CigramAdsUi.TEXT, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.setMarginStart(CigramAdsUi.dp(this, 6));
        bar.addView(title, tp);
        return bar;
    }

    private void showSkeleton() {
        content.removeAllViews();
        for (int i = 0; i < 3; i++) {
            content.addView(CigramAdsUi.skeletonCard(this, 3),
                    CigramAdsUi.lp(this, -1, -2, 0, i == 0 ? 0 : 12, 0, 0));
        }
    }

    private void load() {
        CigramAdsApi.myCampaigns(this, this, new CigramAdsApi.Callback() {
            @Override public void done(CigramAdsApi.Result result) {
                if (!result.ok()) {
                    showError(result.error);
                    return;
                }
                render(result.data().optJSONArray("campaigns"));
            }
        });
    }

    private void showError(String message) {
        content.removeAllViews();
        content.addView(CigramAdsUi.errorState(this,
                message == null ? "تعذر تحميل حملاتك." : message, new Runnable() {
                    @Override public void run() {
                        showSkeleton();
                        load();
                    }
                }), new LinearLayout.LayoutParams(-1, -2));
    }

    private void showSignIn() {
        content.removeAllViews();
        LinearLayout empty = CigramAdsUi.emptyState(this, CigramAdsUi.ICON_USERS,
                "تحتاج حساباً لعرض حملاتك", "سجّل الدخول من قسم الحساب ثم عد إلى هنا.");
        content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
    }

    private void render(JSONArray campaigns) {
        content.removeAllViews();
        if (campaigns == null || campaigns.length() == 0) {
            LinearLayout empty = CigramAdsUi.emptyState(this, CigramAdsUi.ICON_MEGAPHONE,
                    "لا توجد حملات بعد",
                    "ابدأ من صفحة «أعلن هنا»: احسب التكلفة وأرسل طلبك، وستظهر حملتك هنا.");
            content.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            TextView button = CigramAdsUi.primaryButton(this, "افتح «أعلن هنا»", CigramAdsUi.PRIMARY);
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    startActivity(new Intent(CigramMyAdsActivity.this, CigramAdvertiseActivity.class));
                    finish();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 52));
            lp.topMargin = CigramAdsUi.dp(this, 10);
            content.addView(button, lp);
            return;
        }

        long totalImpressions = 0L;
        long totalClicks = 0L;
        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject campaign = campaigns.optJSONObject(i);
            if (campaign == null) continue;
            JSONObject totals = campaign.optJSONObject("totals");
            if (totals == null) continue;
            totalImpressions += totals.optLong("impressions", 0L);
            totalClicks += totals.optLong("clicks", 0L);
        }
        content.addView(summaryCard(campaigns.length(), totalImpressions, totalClicks),
                new LinearLayout.LayoutParams(-1, -2));

        for (int i = 0; i < campaigns.length(); i++) {
            JSONObject campaign = campaigns.optJSONObject(i);
            if (campaign == null) continue;
            content.addView(campaignCard(campaign), CigramAdsUi.lp(this, -1, -2, 0, 12, 0, 0));
        }
    }

    private View summaryCard(int count, long impressions, long clicks) {
        LinearLayout card = CigramAdsUi.card(this);
        card.addView(CigramAdsUi.text(this, "ملخص حملاتك", 15f, CigramAdsUi.TEXT, true),
                new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = CigramAdsUi.row(this);
        row.addView(statTile("حملة", String.valueOf(count)), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, -2, 1f);
        mid.setMarginStart(CigramAdsUi.dp(this, 8));
        row.addView(statTile("مشاهدة", CigramAdsUi.formatNumber(impressions)), mid);
        LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(0, -2, 1f);
        end.setMarginStart(CigramAdsUi.dp(this, 8));
        row.addView(statTile("نقرة", CigramAdsUi.formatNumber(clicks)), end);
        card.addView(row, CigramAdsUi.lp(this, -1, -2, 0, 12, 0, 0));
        if (impressions > 0L) {
            double ctr = Math.round((clicks * 10000d) / impressions) / 100d;
            card.addView(CigramAdsUi.muted(this, "نسبة النقر الكلية " + ctr + "%"),
                    CigramAdsUi.lp(this, -1, -2, 0, 10, 0, 0));
        }
        return card;
    }

    private View statTile(String label, String value) {
        LinearLayout tile = CigramAdsUi.column(this);
        tile.setBackground(CigramAdsUi.round(this, CigramAdsUi.CARD_SOFT, CigramAdsUi.STROKE, 14));
        int p = CigramAdsUi.dp(this, 10);
        tile.setPadding(p, p, p, p);
        TextView number = CigramAdsUi.text(this, value, 18f, CigramAdsUi.TEXT, true);
        number.setGravity(Gravity.CENTER);
        tile.addView(number, new LinearLayout.LayoutParams(-1, -2));
        TextView name = CigramAdsUi.text(this, label, 11.5f, CigramAdsUi.MUTED, false);
        name.setGravity(Gravity.CENTER);
        tile.addView(name, CigramAdsUi.lp(this, -1, -2, 0, 2, 0, 0));
        tile.setContentDescription(label + " " + value);
        return tile;
    }

    private View campaignCard(final JSONObject campaign) {
        LinearLayout card = CigramAdsUi.card(this);

        LinearLayout head = CigramAdsUi.row(this);
        LinearLayout titles = CigramAdsUi.column(this);
        titles.addView(CigramAdsUi.text(this, campaign.optString("title", "حملة"), 16f,
                CigramAdsUi.TEXT, true), new LinearLayout.LayoutParams(-1, -2));
        titles.addView(CigramAdsUi.muted(this, campaign.optString("slot_name", "")),
                CigramAdsUi.lp(this, -1, -2, 0, 2, 0, 0));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));

        String status = campaign.optString("status", "");
        head.addView(CigramAdsUi.badge(this, statusLabel(status), statusColor(status)),
                new LinearLayout.LayoutParams(-2, -2));
        card.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if ("rejected".equals(status)) {
            String reason = campaign.optString("reject_reason", "");
            LinearLayout box = CigramAdsUi.column(this);
            box.setBackground(CigramAdsUi.round(this, CigramAdsUi.alpha(CigramAdsUi.BAD, 0x18),
                    CigramAdsUi.alpha(CigramAdsUi.BAD, 0x55), 12));
            int p = CigramAdsUi.dp(this, 10);
            box.setPadding(p, p, p, p);
            box.addView(CigramAdsUi.text(this,
                            reason.length() > 0 ? reason : "لم يُذكر سبب. راسل الإدارة في المحادثة.",
                            13f, 0xEEFFFFFF, false),
                    new LinearLayout.LayoutParams(-1, -2));
            card.addView(box, CigramAdsUi.lp(this, -1, -2, 0, 10, 0, 0));
        }

        String window = formatWindow(campaign.optString("start_at", ""), campaign.optString("end_at", ""));
        if (window.length() > 0) {
            card.addView(CigramAdsUi.muted(this, window), CigramAdsUi.lp(this, -1, -2, 0, 10, 0, 0));
        }

        double price = campaign.optDouble("price_final", 0d);
        if (price > 0d) {
            LinearLayout priceRow = CigramAdsUi.row(this);
            priceRow.addView(CigramAdsUi.muted(this, "التكلفة"), new LinearLayout.LayoutParams(-2, -2));
            TextView value = CigramAdsUi.text(this,
                    CigramAdsUi.money(price, currencyOf(campaign)), 13.5f, CigramAdsUi.TEXT, true);
            value.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            priceRow.addView(value, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(priceRow, CigramAdsUi.lp(this, -1, -2, 0, 8, 0, 0));

            String payment = campaign.optString("payment_status", "");
            card.addView(CigramAdsUi.badge(this, paymentLabel(payment), paymentColor(payment)),
                    CigramAdsUi.lp(this, -2, -2, 0, 8, 0, 0));
        }

        JSONObject totals = campaign.optJSONObject("totals");
        if (totals != null) {
            LinearLayout stats = CigramAdsUi.row(this);
            stats.addView(statTile("مشاهدة", CigramAdsUi.formatNumber(totals.optLong("impressions", 0L))),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(0, -2, 1f);
            mid.setMarginStart(CigramAdsUi.dp(this, 8));
            stats.addView(statTile("نقرة", CigramAdsUi.formatNumber(totals.optLong("clicks", 0L))), mid);
            LinearLayout.LayoutParams end = new LinearLayout.LayoutParams(0, -2, 1f);
            end.setMarginStart(CigramAdsUi.dp(this, 8));
            stats.addView(statTile("نسبة النقر", totals.optDouble("ctr", 0d) + "%"), end);
            card.addView(stats, CigramAdsUi.lp(this, -1, -2, 0, 12, 0, 0));
        }

        JSONArray days = campaign.optJSONArray("days");
        if (days != null && days.length() > 0) {
            card.addView(CigramAdsUi.muted(this, "آخر الأيام"), CigramAdsUi.lp(this, -1, -2, 0, 14, 0, 6));
            DailyChart chart = new DailyChart(this, days);
            card.addView(chart, new LinearLayout.LayoutParams(-1, CigramAdsUi.dp(this, 92)));
        }

        LinearLayout buttons = CigramAdsUi.row(this);
        TextView renew = CigramAdsUi.ghostButton(this, "تجديد");
        renew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                renew(campaign);
            }
        });
        buttons.addView(renew, new LinearLayout.LayoutParams(0, CigramAdsUi.dp(this, 46), 1f));

        final String reportUrl = campaign.optString("report_url", "");
        if (reportUrl.length() > 0) {
            TextView report = CigramAdsUi.ghostButton(this, "التقرير");
            report.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    openReport(reportUrl);
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, CigramAdsUi.dp(this, 46), 1f);
            rp.setMarginStart(CigramAdsUi.dp(this, 8));
            buttons.addView(report, rp);
        }
        card.addView(buttons, CigramAdsUi.lp(this, -1, -2, 0, 14, 0, 0));
        return card;
    }

    private static String currencyOf(JSONObject campaign) {
        String code = campaign.optString("currency", "SAR");
        return "SAR".equals(code) ? "ر.س" : code;
    }

    /** Renew = open the chat with a request for the same slot and duration. */
    private void renew(JSONObject campaign) {
        try {
            JSONObject order = new JSONObject();
            order.put("type", "ad_request");
            order.put("slot", campaign.optString("slot", ""));
            order.put("slot_name", campaign.optString("slot_name", ""));
            order.put("days", daysBetween(campaign.optString("start_at", ""),
                    campaign.optString("end_at", "")));
            order.put("renewal_of", campaign.optString("id", ""));
            Intent intent = new Intent(this, CigramAdsChatActivity.class);
            intent.putExtra("order", order.toString());
            startActivity(intent);
        } catch (Throwable error) {
            toast("تعذر تجهيز طلب التجديد.");
        }
    }

    private void openReport(String path) {
        String url = path.startsWith("http") ? path : CigramAdsApi.BASE + path;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable error) {
            toast("لا يوجد تطبيق يستطيع فتح التقرير.");
        }
    }

    // ------------------------------------------------------------- labels

    private static String statusLabel(String status) {
        if ("pending_review".equals(status)) return "قيد المراجعة";
        if ("approved".equals(status)) return "معتمدة";
        if ("scheduled".equals(status)) return "مجدولة";
        if ("active".equals(status)) return "تعمل الآن";
        if ("paused".equals(status)) return "متوقفة";
        if ("ended".equals(status)) return "منتهية";
        if ("rejected".equals(status)) return "مرفوضة";
        if ("draft".equals(status)) return "مسودة";
        return "مستلم";
    }

    private static int statusColor(String status) {
        if ("active".equals(status)) return CigramAdsUi.GOOD;
        if ("rejected".equals(status)) return CigramAdsUi.BAD;
        if ("paused".equals(status) || "pending_review".equals(status)) return CigramAdsUi.WARN;
        if ("scheduled".equals(status) || "approved".equals(status)) return CigramAdsUi.ACCENT;
        return CigramAdsUi.MUTED;
    }

    private static String paymentLabel(String status) {
        if ("paid".equals(status)) return "مدفوع";
        if ("partial".equals(status)) return "مدفوع جزئياً";
        if ("refunded".equals(status)) return "مُسترجع";
        return "بانتظار الدفع";
    }

    private static int paymentColor(String status) {
        if ("paid".equals(status)) return CigramAdsUi.GOOD;
        if ("partial".equals(status)) return CigramAdsUi.WARN;
        if ("refunded".equals(status)) return CigramAdsUi.MUTED;
        return CigramAdsUi.WARN;
    }

    private static long parseIso(String iso) {
        if (iso == null || iso.length() == 0) return 0L;
        try {
            java.text.SimpleDateFormat format =
                    new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            String trimmed = iso.length() > 19 ? iso.substring(0, 19) : iso;
            java.util.Date date = format.parse(trimmed);
            return date == null ? 0L : date.getTime();
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static int daysBetween(String from, String to) {
        long a = parseIso(from);
        long b = parseIso(to);
        if (a <= 0L || b <= a) return 7;
        return Math.max(1, (int) ((b - a) / 86400000L));
    }

    private static String formatWindow(String from, String to) {
        long a = parseIso(from);
        long b = parseIso(to);
        if (a <= 0L && b <= 0L) return "";
        java.text.SimpleDateFormat day =
                new java.text.SimpleDateFormat("d MMM yyyy", new java.util.Locale("ar"));
        if (a > 0L && b > 0L) {
            long left = b - System.currentTimeMillis();
            String tail = left > 0L
                    ? " · بقي " + Math.max(1L, left / 86400000L) + " يوم"
                    : " · انتهت";
            return day.format(new java.util.Date(a)) + " ← " + day.format(new java.util.Date(b)) + tail;
        }
        return day.format(new java.util.Date(a > 0L ? a : b));
    }

    private void toast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) { }
    }

    // --------------------------------------------------------------- chart

    /** A small daily bar chart, drawn rather than shipped as a charting library. */
    private static final class DailyChart extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final JSONArray days;
        private final float density;
        private long peak = 1L;

        DailyChart(Activity activity, JSONArray days) {
            super(activity);
            this.days = days == null ? new JSONArray() : days;
            this.density = activity.getResources().getDisplayMetrics().density;
            for (int i = 0; i < this.days.length(); i++) {
                JSONObject day = this.days.optJSONObject(i);
                if (day == null) continue;
                peak = Math.max(peak, day.optLong("impressions", 0L));
            }
            StringBuilder description = new StringBuilder("رسم المشاهدات اليومية: ");
            for (int i = Math.max(0, this.days.length() - 7); i < this.days.length(); i++) {
                JSONObject day = this.days.optJSONObject(i);
                if (day == null) continue;
                description.append(day.optString("day", "")).append(" ")
                        .append(day.optLong("impressions", 0L)).append(" مشاهدة. ");
            }
            setContentDescription(description.toString());
        }

        @Override protected void onDraw(Canvas canvas) {
            int width = getWidth();
            int height = getHeight();
            int count = days.length();
            if (width <= 0 || height <= 0 || count == 0) return;

            // Show at most the last 30 days so the bars stay readable.
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
                float full = Math.max(1.5f * density, (impressions / (float) peak) * (bottom - 4f * density));
                // RTL: the newest day sits on the left, matching the reading order.
                float right = width - i * (barWidth + gap);
                float left = right - barWidth;
                paint.setColor(CigramAdsUi.alpha(CigramAdsUi.ACCENT, 0x99));
                canvas.drawRoundRect(new RectF(left, bottom - full, right, bottom),
                        barWidth / 3f, barWidth / 3f, paint);
                if (clicks > 0L && impressions > 0L) {
                    float clickHeight = Math.max(1f * density, full * (clicks / (float) impressions));
                    paint.setColor(CigramAdsUi.PRIMARY);
                    canvas.drawRoundRect(new RectF(left, bottom - clickHeight, right, bottom),
                            barWidth / 3f, barWidth / 3f, paint);
                }
            }

            paint.setColor(CigramAdsUi.MUTED);
            paint.setTextSize(9f * density);
            paint.setTextAlign(Paint.Align.RIGHT);
            JSONObject newest = days.optJSONObject(count - 1);
            if (newest != null) {
                canvas.drawText(newest.optString("day", ""), width, height - 2f * density, paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
            JSONObject oldest = days.optJSONObject(from);
            if (oldest != null) {
                canvas.drawText(oldest.optString("day", ""), 0, height - 2f * density, paint);
            }
        }
    }
}
