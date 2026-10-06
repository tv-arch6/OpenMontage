package com.Cigram.vid;

import android.app.Activity;
import android.content.Context;

import org.json.JSONObject;

/**
 * «احذف بياناتي» — the advertiser deleting their own ads data, and the local
 * cleanup that goes with deleting the account.
 *
 * The split is deliberate and the UI says it out loud:
 *   - the conversation, its media and the policy consent ARE deleted,
 *   - campaigns and the company file are NOT: they are a commercial record of a
 *     paid agreement, and deleting them from the advertiser's phone would erase
 *     the admin's side of it too. Removing those is a conversation with the
 *     admin, not a button.
 */
public final class CigramAdsPrivacy {

    private CigramAdsPrivacy() { }

    /** Wipes every trace of the ads feature from this device. Never networks. */
    public static void wipeLocal(Context context) {
        if (context == null) return;
        try {
            CigramAdsChat.forget(context);
        } catch (Throwable ignored) { }
        try {
            CigramAdSlots.forget(context);
        } catch (Throwable ignored) { }
        try {
            CigramAdsMedia.clearCache(context);
        } catch (Throwable ignored) { }
        try {
            context.getApplicationContext()
                    .getSharedPreferences("cigram_ads_company_v1", Context.MODE_PRIVATE)
                    .edit().clear().apply();
        } catch (Throwable ignored) { }
    }

    /**
     * Asks, then deletes server-side and locally. Call this from the account page
     * next to the existing "حذف الحساب", or add it as its own row:
     *
     * <pre>CigramAdsPrivacy.confirmAndDelete(activity, null);</pre>
     */
    public static void confirmAndDelete(final Activity activity, final Runnable done) {
        if (activity == null || activity.isFinishing()) return;
        if (!CigramUserData.isLoggedIn(activity)) {
            wipeLocal(activity);
            CigramUI.info(activity, CigramUI.ICON_CHECK, CigramUI.GREEN, "تم",
                    "حُذفت بيانات الإعلانات المحفوظة على هذا الجهاز.", "حسناً").show();
            if (done != null) done.run();
            return;
        }
        CigramUI.sheet(activity)
                .icon(CigramUI.ICON_TRASH, CigramUI.DANGER)
                .title("حذف بيانات الإعلانات")
                .message("سيُحذف نهائياً: محادثتك مع إدارة الإعلانات، وكل الصور والفيديو "
                        + "والتسجيلات التي أرسلتها فيها، وموافقتك على السياسات.\n\n"
                        + "لن تُحذف: حملاتك وملف شركتك — فهي سجل اتفاق تجاري، "
                        + "ولحذفها راسل الإدارة.\n\nلا يمكن التراجع عن هذا.")
                .danger("حذف نهائي", new CigramUI.Click() {
                    @Override public boolean onClick(CigramUI.Sheet sheet) {
                        delete(activity, done);
                        return false;
                    }
                })
                .secondary("إلغاء", null)
                .show();
    }

    private static void delete(final Activity activity, final Runnable done) {
        final CigramUI.Sheet progress = CigramUI.sheet(activity)
                .cancelable(false)
                .title("جارٍ الحذف")
                .spinner("نحذف محادثتك ووسائطك…");
        progress.show();

        CigramAdsApi.post(activity, "/ads/forget-me", new JSONObject(), true,
                new CigramAdsApi.Alive() {
                    @Override public boolean alive() {
                        return !activity.isFinishing() && !activity.isDestroyed();
                    }
                },
                new CigramAdsApi.Callback() {
                    @Override public void done(CigramAdsApi.Result result) {
                        progress.dismiss();
                        // Local state goes either way: if the server call failed the
                        // device should still stop holding the cached conversation.
                        wipeLocal(activity);
                        if (!result.ok()) {
                            CigramUI.info(activity, CigramUI.ICON_WARN, CigramUI.AMBER,
                                    "لم يكتمل الحذف",
                                    (result.error == null ? "تعذر الوصول للخادم." : result.error)
                                            + "\n\nحُذفت النسخة المحفوظة على جهازك، "
                                            + "لكن محادثتك على الخادم لم تُحذف بعد. "
                                            + "أعد المحاولة عند توفر الاتصال.", "حسناً").show();
                            if (done != null) done.run();
                            return;
                        }
                        JSONObject data = result.data();
                        StringBuilder message = new StringBuilder("حُذفت محادثتك");
                        int media = data.optInt("removed_media", 0);
                        if (media > 0) message.append(" و").append(media).append(" ملف وسائط");
                        message.append(".");
                        if (data.optBoolean("campaigns_kept", false)) {
                            message.append("\n\nحملاتك وملف شركتك لم تُحذف — راسل الإدارة لحذفها.");
                        }
                        CigramUI.info(activity, CigramUI.ICON_CHECK, CigramUI.GREEN,
                                "تم الحذف", message.toString(), "حسناً").show();
                        if (done != null) done.run();
                    }
                });
    }

    /**
     * A ready-made account-page row. Add it where the other rows are built, e.g.
     * from {@link CigramAdsEntry}, or call {@link #confirmAndDelete} from your own
     * button. Returns null if the host is gone.
     */
    public static android.view.View row(final Activity activity) {
        if (activity == null) return null;
        return CigramAdsUi.listRow(activity, CigramAdsUi.ICON_SHIELD, CigramAdsUi.BAD,
                "حذف بيانات الإعلانات", "المحادثة والوسائط والموافقة", null,
                new android.view.View.OnClickListener() {
                    @Override public void onClick(android.view.View v) {
                        confirmAndDelete(activity, null);
                    }
                });
    }
}
