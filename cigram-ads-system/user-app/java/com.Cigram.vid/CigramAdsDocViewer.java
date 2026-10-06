package com.Cigram.vid;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows a PDF sent in the chat, inside the app.
 *
 * It uses {@link PdfRenderer}, which is part of the framework (API 21+), so there
 * is no library to add and no FileProvider to declare — handing the file to
 * another app would need one, and a Sketchware project cannot add the XML
 * resource a FileProvider requires.
 *
 * The chat only accepts PDFs and images as documents (the Worker enforces that
 * on the uploaded bytes), so between this and the image viewer every document an
 * advertiser can send can be opened.
 *
 * One page is rendered at a time, bounded by the screen width, and the bitmap of
 * the previous page is recycled, so a 200-page file costs the same as a 2-page one.
 */
public final class CigramAdsDocViewer {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private CigramAdsDocViewer() { }

    public static void show(final Activity activity, final File file, final String name) {
        if (activity == null || activity.isFinishing() || file == null || !file.exists()) return;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout column = CigramAdsUi.column(activity);
        column.setBackgroundColor(0xF2041018);

        LinearLayout bar = CigramAdsUi.row(activity);
        bar.setPadding(CigramAdsUi.dp(activity, 12), CigramAdsUi.dp(activity, 10),
                CigramAdsUi.dp(activity, 6), CigramAdsUi.dp(activity, 10));
        TextView title = CigramAdsUi.text(activity, name == null || name.length() == 0 ? "مستند" : name,
                14.5f, CigramAdsUi.TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        FrameLayout close = new FrameLayout(activity);
        CigramAdsUi.Icon cross = new CigramAdsUi.Icon(activity, CigramAdsUi.ICON_X, CigramAdsUi.TEXT);
        int inner = CigramAdsUi.dp(activity, 16);
        close.addView(cross, new FrameLayout.LayoutParams(inner, inner, Gravity.CENTER));
        CigramAdsUi.pressable(close, CigramAdsUi.round(activity, Color.TRANSPARENT, 22),
                CigramAdsUi.ACCENT, 22);
        close.setContentDescription("إغلاق");
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                dialog.dismiss();
            }
        });
        int size = CigramAdsUi.dp(activity, 44);
        bar.addView(close, new LinearLayout.LayoutParams(size, size));
        column.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        final ImageView page = new ImageView(activity);
        page.setScaleType(ImageView.ScaleType.FIT_CENTER);
        page.setBackgroundColor(0xFF101E26);
        column.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));

        final TextView counter = CigramAdsUi.text(activity, "", 12.5f, CigramAdsUi.MUTED, false);
        counter.setGravity(Gravity.CENTER);
        final LinearLayout nav = CigramAdsUi.row(activity);
        nav.setPadding(CigramAdsUi.dp(activity, 12), CigramAdsUi.dp(activity, 8),
                CigramAdsUi.dp(activity, 12), CigramAdsUi.dp(activity, 14));
        final TextView previous = CigramAdsUi.ghostButton(activity, "السابق");
        final TextView next = CigramAdsUi.ghostButton(activity, "التالي");
        nav.addView(previous, new LinearLayout.LayoutParams(0, CigramAdsUi.dp(activity, 44), 1f));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1.2f);
        cp.setMarginStart(CigramAdsUi.dp(activity, 8));
        cp.setMarginEnd(CigramAdsUi.dp(activity, 8));
        nav.addView(counter, cp);
        nav.addView(next, new LinearLayout.LayoutParams(0, CigramAdsUi.dp(activity, 44), 1f));
        column.addView(nav, new LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(column);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        try {
            dialog.show();
            if (window != null) window.setLayout(-1, -1);
        } catch (Throwable ignored) {
            return;
        }

        final Session session = new Session();
        dialog.setOnDismissListener(new Dialog.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface d) {
                session.close();
                android.graphics.drawable.Drawable drawable = page.getDrawable();
                page.setImageDrawable(null);
                if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                    Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
                }
            }
        });

        final int targetWidth = Math.min(1600, activity.getResources().getDisplayMetrics().widthPixels);
        IO.execute(new Runnable() {
            @Override public void run() {
                if (!session.open(file)) {
                    MAIN.post(new Runnable() {
                        @Override public void run() {
                            counter.setText("تعذر فتح هذا المستند.");
                            nav.setVisibility(View.GONE);
                        }
                    });
                    return;
                }
                render(session, 0, targetWidth, activity, page, counter, previous, next);
            }
        });

        previous.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IO.execute(new Runnable() {
                    @Override public void run() {
                        render(session, session.index - 1, targetWidth, activity, page, counter,
                                previous, next);
                    }
                });
            }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                IO.execute(new Runnable() {
                    @Override public void run() {
                        render(session, session.index + 1, targetWidth, activity, page, counter,
                                previous, next);
                    }
                });
            }
        });
    }

    /** Holds the renderer and the page cursor; closed exactly once. */
    private static final class Session {
        ParcelFileDescriptor descriptor;
        PdfRenderer renderer;
        int index = 0;
        int count = 0;
        boolean closed;

        boolean open(File file) {
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                renderer = new PdfRenderer(descriptor);
                count = renderer.getPageCount();
                return count > 0;
            } catch (Throwable error) {
                close();
                return false;
            }
        }

        synchronized void close() {
            if (closed) return;
            closed = true;
            try {
                if (renderer != null) renderer.close();
            } catch (Throwable ignored) { }
            try {
                if (descriptor != null) descriptor.close();
            } catch (Throwable ignored) { }
            renderer = null;
            descriptor = null;
        }
    }

    private static void render(final Session session, int wanted, int targetWidth,
                               final Activity activity, final ImageView view, final TextView counter,
                               final TextView previous, final TextView next) {
        if (session.closed || session.renderer == null) return;
        final int index = Math.max(0, Math.min(session.count - 1, wanted));
        Bitmap bitmap = null;
        synchronized (session) {
            if (session.closed || session.renderer == null) return;
            PdfRenderer.Page page = null;
            try {
                page = session.renderer.openPage(index);
                int width = Math.max(1, targetWidth);
                int height = Math.max(1, (int) ((long) width * page.getHeight() / page.getWidth()));
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                session.index = index;
            } catch (Throwable error) {
                bitmap = null;
            } finally {
                try {
                    if (page != null) page.close();
                } catch (Throwable ignored) { }
            }
        }
        final Bitmap ready = bitmap;
        MAIN.post(new Runnable() {
            @Override public void run() {
                if (activity.isFinishing()) {
                    if (ready != null && !ready.isRecycled()) ready.recycle();
                    return;
                }
                if (ready != null) {
                    android.graphics.drawable.Drawable old = view.getDrawable();
                    view.setImageBitmap(ready);
                    if (old instanceof android.graphics.drawable.BitmapDrawable) {
                        Bitmap previousBitmap = ((android.graphics.drawable.BitmapDrawable) old).getBitmap();
                        if (previousBitmap != null && !previousBitmap.isRecycled()) previousBitmap.recycle();
                    }
                }
                counter.setText("صفحة " + (index + 1) + " من " + session.count);
                CigramAdsUi.setEnabled(previous, index > 0, null);
                CigramAdsUi.setEnabled(next, index < session.count - 1, null);
                ViewGroup.LayoutParams lp = view.getLayoutParams();
                if (lp != null) view.setLayoutParams(lp);
            }
        });
    }
}
