package com.my.newproject;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * Base for every new admin screen: RTL, same colors, toolbar, loading overlay,
 * "تم التنفيذ — تراجع" bar and shared data loading.
 */
public abstract class CgBase extends Activity {

    protected FrameLayout frame;
    protected LinearLayout column;
    protected LinearLayout body;
    protected CgUi.Toolbar bar;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private View undoBar;
    private View loadingOverlay;
    private TextView loadingText;
    private Runnable undoHide;

    protected abstract String screenTitle();

    protected abstract void onBuild();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            getWindow().setStatusBarColor(CgCfg.PAGE);
            getWindow().setNavigationBarColor(Color.BLACK);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Exception ignored) { }

        frame = new FrameLayout(this);
        frame.setBackgroundColor(CgCfg.PAGE);
        column = CgUi.vbox(this);
        bar = new CgUi.Toolbar(this, screenTitle(), true);
        column.addView(bar.view, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body = CgUi.vbox(this);
        column.addView(body, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        frame.addView(column, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        setContentView(frame);
        try {
            getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        } catch (Exception ignored) { }
        onBuild();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    protected boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    protected Handler ui() {
        return handler;
    }

    /** Replaces body content with a padded scroll view and returns its inner column. */
    protected LinearLayout scrollBody() {
        body.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(false);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout inner = CgUi.vbox(this);
        int p = CgUi.dp(this, 12);
        inner.setPadding(p, p, p, CgUi.dp(this, 90));
        sv.addView(inner, new ScrollView.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        body.addView(sv, new LinearLayout.LayoutParams(CgUi.MATCH, 0, 1f));
        return inner;
    }

    // ----------------------------------------------------------- loading

    protected void showLoading(String msg) {
        if (loadingOverlay == null) {
            FrameLayout o = new FrameLayout(this);
            o.setBackgroundColor(CgUi.alpha(CgCfg.PAGE, 0xCC));
            o.setClickable(true);
            LinearLayout box = CgUi.card(this);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.addView(CgUi.spinner(this), new LinearLayout.LayoutParams(CgUi.dp(this, 40), CgUi.dp(this, 40)));
            loadingText = CgUi.text(this, "", 14, CgCfg.TEXT, false);
            loadingText.setGravity(Gravity.CENTER);
            loadingText.setPadding(0, CgUi.dp(this, 10), 0, 0);
            box.addView(loadingText, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP, Gravity.CENTER);
            o.addView(box, lp);
            loadingOverlay = o;
        }
        loadingText.setText(msg == null ? "جارٍ التنفيذ..." : msg);
        if (loadingOverlay.getParent() == null) {
            frame.addView(loadingOverlay, new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.MATCH));
        }
    }

    protected void loadingMessage(String msg) {
        if (loadingText != null) loadingText.setText(msg);
    }

    protected void hideLoading() {
        if (loadingOverlay != null && loadingOverlay.getParent() != null) {
            frame.removeView(loadingOverlay);
        }
    }

    // -------------------------------------------------------------- data

    protected void ensureData(final Runnable ready) {
        if (CgData.loaded()) {
            ready.run();
            return;
        }
        reloadData(ready);
    }

    protected void reloadData(final Runnable ready) {
        showLoading("جارٍ تحميل البيانات...");
        CgData.load(this, new CgHttp.Done<CgData>() {
            @Override
            public void ok(CgData d) {
                hideLoading();
                ready.run();
            }

            @Override
            public void fail(String message) {
                hideLoading();
                body.removeAllViews();
                LinearLayout box = CgUi.vbox(CgBase.this);
                box.setPadding(CgUi.dp(CgBase.this, 12), CgUi.dp(CgBase.this, 12), CgUi.dp(CgBase.this, 12), 0);
                box.addView(CgUi.errorCard(CgBase.this, message, new Runnable() {
                    @Override
                    public void run() {
                        reloadData(ready);
                    }
                }));
                body.addView(box);
            }
        });
    }

    /** Shows the Worker-too-old warning if the v2 routes are missing. */
    protected View warningCard() {
        CgData d = CgData.cur();
        if (d == null || d.warning.length() == 0) return null;
        LinearLayout c = CgUi.card(this);
        c.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.WARN, 0x1A), 14, CgCfg.WARN));
        c.addView(CgUi.text(this, d.warning, 13, CgCfg.WARN, false));
        c.setLayoutParams(CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        return c;
    }

    // -------------------------------------------------------------- undo

    /** Shows the "تم التنفيذ — تراجع" snackbar for an operation that returned an undo id. */
    protected void showUndo(String label, final String undoId, final Runnable afterUndo) {
        if (undoId == null || undoId.length() == 0) {
            CgUi.toast(this, label);
            return;
        }
        hideUndo();
        LinearLayout bar2 = CgUi.hbox(this);
        bar2.setBackground(CgUi.bg(this, CgCfg.CARD2, 14, CgCfg.ACCENT));
        int pad = CgUi.dp(this, 12);
        bar2.setPadding(pad, CgUi.dp(this, 8), CgUi.dp(this, 8), CgUi.dp(this, 8));
        TextView t = CgUi.text(this, label + " — تم التنفيذ", 13.5f, CgCfg.TEXT, false);
        bar2.addView(t, new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f));
        TextView undo = CgUi.button(this, "تراجع", 0);
        undo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideUndo();
                doUndo(undoId, afterUndo);
            }
        });
        bar2.addView(undo, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP, Gravity.BOTTOM);
        int m = CgUi.dp(this, 12);
        lp.setMargins(m, 0, m, m);
        frame.addView(bar2, lp);
        undoBar = bar2;
        undoHide = new Runnable() {
            @Override
            public void run() {
                hideUndo();
            }
        };
        handler.postDelayed(undoHide, 12000);
    }

    protected void hideUndo() {
        if (undoHide != null) handler.removeCallbacks(undoHide);
        if (undoBar != null && undoBar.getParent() != null) frame.removeView(undoBar);
        undoBar = null;
    }

    protected void doUndo(final String undoId, final Runnable afterUndo) {
        showLoading("جارٍ التراجع...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override
            public CgHttp.Res run() throws Exception {
                JSONObject b = new JSONObject();
                b.put("id", undoId);
                return CgHttp.require(CgHttp.apiPost("/admin/undo", b));
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override
            public void ok(CgHttp.Res r) {
                hideLoading();
                int conflicts = r.json == null ? 0 : r.json.optInt("conflicts", 0);
                CgUi.toast(CgBase.this, conflicts > 0
                        ? "تم التراجع (تنبيه: " + conflicts + " عنصر كان قد تغيّر بعد العملية)."
                        : "تم التراجع بنجاح.");
                reloadData(new Runnable() {
                    @Override
                    public void run() {
                        if (afterUndo != null) afterUndo.run();
                    }
                });
            }

            @Override
            public void fail(String message) {
                hideLoading();
                CgUi.info(CgBase.this, "تعذر التراجع", message);
            }
        });
    }
}
