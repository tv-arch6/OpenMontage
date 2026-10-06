package com.my.newproject;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Small set of line icons drawn with Canvas (24x24 grid, round caps). No images, no fonts, no emoji:
 * they scale with the view bounds and take any color.
 */
final class CgIcons {

    private CgIcons() {}

    static final int UPDATE = 0;
    static final int UPLOAD = 1;
    static final int HISTORY = 2;
    static final int BELL = 3;
    static final int WARNING = 4;
    static final int FILE = 5;
    static final int CALENDAR = 6;
    static final int EYE = 7;
    static final int PUBLISH = 8;
    static final int CHECK = 9;
    static final int CLOSE = 10;
    static final int CHEVRON = 11;
    static final int TRASH = 12;
    static final int EDIT = 13;
    static final int LINK = 14;
    static final int STOP = 15;
    static final int SHIELD = 16;
    static final int CHART = 17;
    static final int DRAFT = 18;
    static final int COPY = 19;
    static final int PLUS = 20;
    static final int INFO = 21;
    static final int REFRESH = 22;

    static Drawable icon(int kind, int color) {
        return new IconDrawable(kind, color);
    }

    static final class IconDrawable extends Drawable {
        private final int kind;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();
        private int alpha = 255;

        IconDrawable(int kind, int color) {
            this.kind = kind;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(2f);
            stroke.setColor(color);
            fill.setStyle(Paint.Style.FILL);
            fill.setColor(color);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float s = Math.min(b.width(), b.height()) / 24f;
            stroke.setAlpha(alpha);
            fill.setAlpha(alpha);
            c.save();
            c.translate(b.left + (b.width() - 24f * s) / 2f, b.top + (b.height() - 24f * s) / 2f);
            c.scale(s, s);
            path.reset();
            switch (kind) {
                case UPDATE:
                    c.drawCircle(12, 12, 9, stroke);
                    c.drawLine(12, 16.5f, 12, 7.5f, stroke);
                    path.moveTo(8, 11.5f);
                    path.lineTo(12, 7.5f);
                    path.lineTo(16, 11.5f);
                    c.drawPath(path, stroke);
                    break;
                case UPLOAD:
                    path.moveTo(4, 15);
                    path.lineTo(4, 19);
                    path.lineTo(20, 19);
                    path.lineTo(20, 15);
                    c.drawPath(path, stroke);
                    c.drawLine(12, 15.5f, 12, 4, stroke);
                    path.reset();
                    path.moveTo(7.5f, 8.5f);
                    path.lineTo(12, 4);
                    path.lineTo(16.5f, 8.5f);
                    c.drawPath(path, stroke);
                    break;
                case HISTORY:
                    c.drawCircle(12, 12, 9, stroke);
                    path.moveTo(12, 7);
                    path.lineTo(12, 12);
                    path.lineTo(15.5f, 14);
                    c.drawPath(path, stroke);
                    break;
                case BELL:
                    path.moveTo(7, 17);
                    path.lineTo(7, 11);
                    rect.set(7, 6, 17, 16);
                    path.arcTo(rect, 180, 180);
                    path.lineTo(17, 17);
                    c.drawPath(path, stroke);
                    c.drawLine(5, 17, 19, 17, stroke);
                    c.drawLine(10, 20.5f, 14, 20.5f, stroke);
                    c.drawLine(12, 3.2f, 12, 6, stroke);
                    break;
                case WARNING:
                    path.moveTo(12, 3.8f);
                    path.lineTo(21, 19.5f);
                    path.lineTo(3, 19.5f);
                    path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(12, 9.5f, 12, 14, stroke);
                    c.drawCircle(12, 16.9f, 0.9f, fill);
                    break;
                case FILE:
                    path.moveTo(6, 3);
                    path.lineTo(14, 3);
                    path.lineTo(19, 8);
                    path.lineTo(19, 21);
                    path.lineTo(6, 21);
                    path.close();
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(14, 3);
                    path.lineTo(14, 8);
                    path.lineTo(19, 8);
                    c.drawPath(path, stroke);
                    c.drawLine(9, 13, 16, 13, stroke);
                    c.drawLine(9, 17, 16, 17, stroke);
                    break;
                case CALENDAR:
                    rect.set(4, 5.5f, 20, 20.5f);
                    c.drawRoundRect(rect, 2.5f, 2.5f, stroke);
                    c.drawLine(4, 10, 20, 10, stroke);
                    c.drawLine(8, 3, 8, 7, stroke);
                    c.drawLine(16, 3, 16, 7, stroke);
                    break;
                case EYE:
                    path.moveTo(2.5f, 12);
                    path.quadTo(12, 3, 21.5f, 12);
                    path.quadTo(12, 21, 2.5f, 12);
                    c.drawPath(path, stroke);
                    c.drawCircle(12, 12, 3, stroke);
                    break;
                case PUBLISH:
                    path.moveTo(21, 3);
                    path.lineTo(3, 10.5f);
                    path.lineTo(10.5f, 13.5f);
                    path.lineTo(13.5f, 21);
                    path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(21, 3, 10.5f, 13.5f, stroke);
                    break;
                case CHECK:
                    path.moveTo(5, 12.5f);
                    path.lineTo(10, 17.5f);
                    path.lineTo(19, 7);
                    c.drawPath(path, stroke);
                    break;
                case CLOSE:
                    c.drawLine(6, 6, 18, 18, stroke);
                    c.drawLine(18, 6, 6, 18, stroke);
                    break;
                case CHEVRON:
                    path.moveTo(6, 9);
                    path.lineTo(12, 15);
                    path.lineTo(18, 9);
                    c.drawPath(path, stroke);
                    break;
                case TRASH:
                    c.drawLine(4, 7, 20, 7, stroke);
                    path.moveTo(6, 7);
                    path.lineTo(7, 20);
                    path.lineTo(17, 20);
                    path.lineTo(18, 7);
                    c.drawPath(path, stroke);
                    path.reset();
                    path.moveTo(9.5f, 7);
                    path.lineTo(9.5f, 4);
                    path.lineTo(14.5f, 4);
                    path.lineTo(14.5f, 7);
                    c.drawPath(path, stroke);
                    c.drawLine(10, 11, 10, 16, stroke);
                    c.drawLine(14, 11, 14, 16, stroke);
                    break;
                case EDIT:
                    path.moveTo(4, 20);
                    path.lineTo(4, 16);
                    path.lineTo(16, 4);
                    path.lineTo(20, 8);
                    path.lineTo(8, 20);
                    path.close();
                    c.drawPath(path, stroke);
                    c.drawLine(13, 7, 17, 11, stroke);
                    break;
                case LINK:
                    c.save();
                    c.rotate(-45, 12, 12);
                    rect.set(1.5f, 9.2f, 13.5f, 14.8f);
                    c.drawRoundRect(rect, 2.8f, 2.8f, stroke);
                    rect.set(10.5f, 9.2f, 22.5f, 14.8f);
                    c.drawRoundRect(rect, 2.8f, 2.8f, stroke);
                    c.restore();
                    break;
                case STOP:
                    c.drawCircle(12, 12, 9, stroke);
                    rect.set(9, 9, 15, 15);
                    c.drawRoundRect(rect, 1, 1, stroke);
                    break;
                case SHIELD:
                    path.moveTo(12, 3);
                    path.lineTo(20, 6);
                    path.lineTo(20, 12);
                    path.quadTo(20, 18, 12, 21);
                    path.quadTo(4, 18, 4, 12);
                    path.lineTo(4, 6);
                    path.close();
                    c.drawPath(path, stroke);
                    break;
                case CHART:
                    c.drawLine(3.5f, 20.5f, 20.5f, 20.5f, stroke);
                    c.drawLine(7, 17, 7, 12, stroke);
                    c.drawLine(12, 17, 12, 5, stroke);
                    c.drawLine(17, 17, 17, 9, stroke);
                    break;
                case DRAFT:
                    rect.set(5.5f, 5, 18.5f, 21);
                    c.drawRoundRect(rect, 2, 2, stroke);
                    rect.set(9, 3, 15, 7);
                    c.drawRoundRect(rect, 1.5f, 1.5f, stroke);
                    c.drawLine(9, 12, 15, 12, stroke);
                    c.drawLine(9, 16, 13, 16, stroke);
                    break;
                case COPY:
                    rect.set(9, 9, 20, 20);
                    c.drawRoundRect(rect, 2, 2, stroke);
                    path.moveTo(15, 9);
                    path.lineTo(15, 5);
                    path.lineTo(5, 5);
                    path.lineTo(5, 15);
                    path.lineTo(9, 15);
                    c.drawPath(path, stroke);
                    break;
                case PLUS:
                    c.drawLine(12, 5, 12, 19, stroke);
                    c.drawLine(5, 12, 19, 12, stroke);
                    break;
                case INFO:
                    c.drawCircle(12, 12, 9, stroke);
                    c.drawLine(12, 11, 12, 16.5f, stroke);
                    c.drawCircle(12, 7.8f, 0.9f, fill);
                    break;
                case REFRESH:
                    rect.set(4.5f, 4.5f, 19.5f, 19.5f);
                    c.drawArc(rect, 200, 270, false, stroke);
                    path.moveTo(4, 4.5f);
                    path.lineTo(4.8f, 9.5f);
                    path.lineTo(9.8f, 8.5f);
                    c.drawPath(path, stroke);
                    break;
                default:
                    break;
            }
            c.restore();
        }

        @Override
        public void setAlpha(int a) {
            alpha = a;
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            stroke.setColorFilter(cf);
            fill.setColorFilter(cf);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
