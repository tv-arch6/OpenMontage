package com.my.newproject;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import org.json.JSONObject;

/**
 * معاينة مباشرة تحاكي شكل المساحة داخل التطبيق.
 *
 * It draws a phone, the slot's real shape inside it, and the creative's own title,
 * body and button text — so the admin sees how long a headline is before approving
 * it, without installing the user app. Drawn on a Canvas: no image assets, and it
 * scales to any density.
 *
 * The creative's image is NOT fetched here on purpose: the preview is about shape
 * and text length, and an admin reviewing twenty campaigns should not pull twenty
 * images over a phone connection.
 */
final class CgAdsSlotPreview extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final String slot;
    private final String title;
    private final String body;
    private final String cta;
    private final boolean video;

    CgAdsSlotPreview(Context context, String slot, JSONObject creative) {
        super(context);
        this.density = context.getResources().getDisplayMetrics().density;
        this.slot = slot == null ? "hero" : slot;
        this.title = creative == null ? "" : creative.optString("title", "");
        this.body = creative == null ? "" : creative.optString("body", "");
        this.cta = creative == null ? "" : creative.optString("cta_label", "");
        this.video = creative != null && "video".equals(creative.optString("type", "image"));
        setMinimumHeight(dp(190));
        setContentDescription("معاينة " + CgAdsUi.slotLabel(this.slot));
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(dp(190), MeasureSpec.EXACTLY));
    }

    @Override protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;

        float phoneH = height - dp(8);
        float phoneW = Math.min(width * 0.52f, phoneH * 0.52f);
        float left = (width - phoneW) / 2f;
        float top = dp(4);
        RectF phone = new RectF(left, top, left + phoneW, top + phoneH);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF081A22);
        canvas.drawRoundRect(phone, dp(10), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.2f));
        paint.setColor(CgCfg.STROKE);
        canvas.drawRoundRect(phone, dp(10), dp(10), paint);

        float pad = dp(5);
        float innerLeft = phone.left + pad;
        float innerRight = phone.right - pad;
        float cursor = phone.top + dp(9);
        float rowH = phoneH * 0.085f;
        float gap = dp(4);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF15303B);
        canvas.drawRoundRect(new RectF(innerLeft, cursor, innerRight, cursor + rowH * 0.6f),
                dp(3), dp(3), paint);
        cursor += rowH * 0.6f + gap;

        RectF target = null;
        if ("hero".equals(slot)) {
            target = new RectF(innerLeft, cursor, innerRight, cursor + rowH * 1.9f);
            cursor += rowH * 1.9f + gap;
        } else {
            paint.setColor(0xFF112A33);
            canvas.drawRoundRect(new RectF(innerLeft, cursor, innerRight, cursor + rowH * 1.9f),
                    dp(4), dp(4), paint);
            cursor += rowH * 1.9f + gap;
        }

        for (int i = 0; i < 3; i++) {
            boolean inlineHere = "inline".equals(slot) && i == 1;
            boolean sponsorHere = "sponsor".equals(slot) && i == 0;
            float thisH = sponsorHere ? rowH * 0.5f : rowH;
            RectF row = new RectF(innerLeft, cursor, innerRight, cursor + thisH);
            if (inlineHere || sponsorHere) {
                target = row;
            } else {
                paint.setColor(0xFF0F262E);
                canvas.drawRoundRect(row, dp(3), dp(3), paint);
            }
            cursor += thisH + gap;
        }

        if ("sticky".equals(slot)) {
            target = new RectF(innerLeft, phone.bottom - dp(9) - rowH * 0.7f, innerRight,
                    phone.bottom - dp(9));
        } else if ("splash".equals(slot)) {
            target = new RectF(innerLeft, phone.top + dp(9), innerRight, phone.bottom - dp(9));
        } else if ("popup".equals(slot)) {
            float popupH = phoneH * 0.34f;
            float popupW = phoneW * 0.78f;
            target = new RectF(phone.centerX() - popupW / 2f, phone.centerY() - popupH / 2f,
                    phone.centerX() + popupW / 2f, phone.centerY() + popupH / 2f);
        }

        if (target != null) drawSlot(canvas, target);

        // the creative's own text, beside the phone, so long copy is visible
        float textLeft = dp(6);
        float textRight = phone.left - dp(8);
        if (textRight - textLeft > dp(60)) {
            paint.setStyle(Paint.Style.FILL);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(dp(11));
            paint.setColor(CgCfg.TEXT);
            float y = phone.top + dp(14);
            y = drawWrapped(canvas, title.length() > 0 ? title : "(بلا عنوان)",
                    textLeft, y, textRight - textLeft, 2);
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(dp(9.5f));
            paint.setColor(CgCfg.MUTED);
            y = drawWrapped(canvas, body.length() > 0 ? body : "(بلا وصف)",
                    textLeft, y + dp(4), textRight - textLeft, 3);
            paint.setColor(CgCfg.ACCENT);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(cta.length() > 0 ? "[ " + cta + " ]" : "[ بلا زر ]",
                    textLeft, y + dp(14), paint);
            if (video) {
                paint.setColor(CgCfg.WARN);
                paint.setTextSize(dp(9));
                canvas.drawText("فيديو", textLeft, y + dp(28), paint);
            }
        }
    }

    private void drawSlot(Canvas canvas, RectF target) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(CgUi.alpha(CgCfg.ACCENT, 0x55));
        canvas.drawRoundRect(target, dp(4), dp(4), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.6f));
        paint.setColor(CgCfg.ACCENT);
        canvas.drawRoundRect(target, dp(4), dp(4), paint);

        if (target.height() < dp(14)) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(Math.max(dp(7), Math.min(dp(10), target.height() * 0.28f)));
        String label = title.length() > 0 ? clip(title, 18) : "إعلان";
        canvas.drawText(label, target.centerX(), target.centerY() + paint.getTextSize() * 0.35f, paint);
        if (target.height() > dp(40) && cta.length() > 0) {
            paint.setTextSize(dp(7.5f));
            paint.setColor(0xFFCCF2F7);
            canvas.drawText(clip(cta, 14), target.centerX(),
                    target.centerY() + paint.getTextSize() * 2.6f, paint);
        }
        paint.setTextAlign(Paint.Align.LEFT);
    }

    /** Word-wraps into at most {@code maxLines}; returns the baseline it stopped at. */
    private float drawWrapped(Canvas canvas, String text, float x, float y, float width, int maxLines) {
        if (text == null || text.length() == 0) return y;
        String[] words = text.split("\\s+");
        StringBuilder line = new StringBuilder();
        int lines = 0;
        float baseline = y;
        for (String word : words) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(candidate) > width && line.length() > 0) {
                canvas.drawText(line.toString(), x, baseline, paint);
                baseline += paint.getTextSize() * 1.35f;
                lines++;
                line.setLength(0);
                if (lines >= maxLines) return baseline;
                line.append(word);
            } else {
                line.setLength(0);
                line.append(candidate);
            }
        }
        if (line.length() > 0) {
            canvas.drawText(line.toString(), x, baseline, paint);
            baseline += paint.getTextSize() * 1.35f;
        }
        return baseline;
    }

    private static String clip(String value, int max) {
        if (value == null) return "";
        return value.length() > max ? value.substring(0, max) + "…" : value;
    }
}
