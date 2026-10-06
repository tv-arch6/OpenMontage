package com.my.newproject;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * المعلنون: ملف لكل شركة (الاسم، الشعار، التواصل، ما دفعته، سجل حملاتها).
 *
 * "ما دفعته" is a running total the Worker maintains whenever a payment is
 * recorded on one of the company's campaigns, so it cannot drift from the
 * campaigns it is derived from.
 */
public class CgAdsAdvertisersActivity extends CgBase {

    private LinearLayout page;
    private LinearLayout list;
    private TextView summary;
    private boolean loading;

    @Override protected String screenTitle() {
        return "المعلنون";
    }

    @Override protected void onBuild() {
        page = scrollBody();
        TextView add = CgUi.button(this, "+ معلن جديد", 0);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                edit(null);
            }
        });
        page.addView(add, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        summary = CgUi.muted(this, "");
        page.addView(summary, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 0, 2, 8));
        list = CgUi.vbox(this);
        page.addView(list, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        list.addView(CgAdsUi.skeleton(this, 3), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        load();
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
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgHttp.require(CgAdsApi.advertisers());
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                loading = false;
                render(CgAdsApi.array(CgAdsApi.data(res), "items"));
            }

            @Override public void fail(String message) {
                loading = false;
                list.removeAllViews();
                list.addView(CgUi.errorCard(CgAdsAdvertisersActivity.this, message, new Runnable() {
                    @Override public void run() {
                        list.removeAllViews();
                        list.addView(CgAdsUi.skeleton(CgAdsAdvertisersActivity.this, 3),
                                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                        load();
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    private void render(JSONArray items) {
        list.removeAllViews();
        double totalPaid = 0d;
        for (int i = 0; i < items.length(); i++) {
            JSONObject advertiser = items.optJSONObject(i);
            if (advertiser == null) continue;
            totalPaid += advertiser.optDouble("total_paid", 0d);
            list.addView(card(advertiser), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));
        }
        summary.setText(items.length() + " معلن · الإجمالي المحصّل "
                + CgAdsUi.money(totalPaid, "ر.س"));
        if (items.length() == 0) {
            list.addView(CgUi.empty(this, "لا معلنين بعد",
                            "يُنشأ ملف المعلن تلقائياً عند أول رسالة منه، أو أضفه يدوياً."),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        }
    }

    private View card(final JSONObject advertiser) {
        LinearLayout card = CgUi.card(this);
        LinearLayout head = CgUi.hbox(this);
        head.setGravity(Gravity.CENTER_VERTICAL);

        String name = advertiser.optString("name", "معلن");
        TextView avatar = CgUi.text(this, name.length() > 0 ? name.substring(0, 1) : "?",
                18f, CgCfg.TEXT, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(CgUi.bg(this, CgUi.alpha(CgCfg.ACCENT, 0x33), 22, CgCfg.ACCENT));
        int size = CgUi.dp(this, 44);
        head.addView(avatar, new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = CgUi.vbox(this);
        texts.addView(CgUi.text(this, name, 15.5f, CgCfg.TEXT, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        texts.addView(CgUi.text(this, advertiser.optInt("campaign_count", 0) + " حملة · "
                        + advertiser.optInt("active_count", 0) + " نشطة", 12.5f, CgCfg.MUTED, false),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 2, 0, 0));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, CgUi.WRAP, 1f);
        tp.setMarginStart(CgUi.dp(this, 10));
        head.addView(texts, tp);

        if (advertiser.optBoolean("blocked", false)) {
            head.addView(CgUi.badge(this, "محظور", CgCfg.BAD),
                    new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));
        }
        card.addView(head, new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

        if (advertiser.optString("phone", "").length() > 0) {
            card.addView(CgAdsUi.line(this, "الهاتف", advertiser.optString("phone")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        }
        if (advertiser.optString("email", "").length() > 0) {
            card.addView(CgAdsUi.line(this, "البريد", advertiser.optString("email")),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }
        card.addView(CgAdsUi.line(this, "ما دفعته",
                        CgAdsUi.money(advertiser.optDouble("total_paid", 0d), "ر.س")),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        if (advertiser.optString("user_id", "").length() > 0) {
            card.addView(CgAdsUi.line(this, "مرتبط بحساب", "نعم"),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        }

        LinearLayout buttons = CgUi.hbox(this);
        TextView editButton = CgUi.smallButton(this, "تعديل", 1);
        editButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                edit(advertiser);
            }
        });
        buttons.addView(editButton, new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP));

        final String userId = advertiser.optString("user_id", "");
        if (userId.length() > 0) {
            TextView chat = CgUi.smallButton(this, "المحادثة", 1);
            chat.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    Intent intent = new Intent(CgAdsAdvertisersActivity.this, CgAdsChatActivity.class);
                    intent.putExtra("user", "u_" + userId);
                    intent.putExtra("name", advertiser.optString("name", ""));
                    startActivity(intent);
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
            cp.setMarginStart(CgUi.dp(this, 6));
            buttons.addView(chat, cp);
        }

        TextView remove = CgUi.smallButton(this, "حذف", 2);
        remove.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                confirmDelete(advertiser);
            }
        });
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(CgUi.WRAP, CgUi.WRAP);
        rp.setMarginStart(CgUi.dp(this, 6));
        buttons.addView(remove, rp);
        card.addView(buttons, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        return card;
    }

    private void edit(final JSONObject existing) {
        LinearLayout form = CgUi.vbox(this);
        final EditText name = CgUi.field(this, "اسم الشركة", false);
        final EditText contact = CgUi.field(this, "اسم المسؤول", false);
        final EditText phone = CgUi.field(this, "رقم الهاتف", false);
        final EditText email = CgUi.field(this, "البريد الإلكتروني", false);
        final EditText website = CgUi.urlField(this, "https://…");
        final EditText logo = CgUi.urlField(this, "رابط الشعار https://…");
        final EditText note = CgUi.field(this, "ملاحظة داخلية", true);
        if (existing != null) {
            name.setText(existing.optString("name", ""));
            contact.setText(existing.optString("contact_name", ""));
            phone.setText(existing.optString("phone", ""));
            email.setText(existing.optString("email", ""));
            website.setText(existing.optString("website", ""));
            logo.setText(existing.optString("logo_url", ""));
            note.setText(existing.optString("note", ""));
        }
        form.addView(CgUi.labeled(this, "الاسم", name),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        form.addView(CgUi.labeled(this, "المسؤول", contact),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "الهاتف", phone),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "البريد", email),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "الموقع", website),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "الشعار", logo),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "ملاحظة", note),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        final CgUi.Toggle blocked = CgUi.toggle(this, "محظور من المحادثة",
                existing != null && existing.optBoolean("blocked", false));
        form.addView(blocked, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        CgUi.dialog(this)
                .setTitle(existing == null ? "معلن جديد" : "تعديل المعلن")
                .setView(scroll)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        if (text(name).length() == 0) {
                            CgUi.info(CgAdsAdvertisersActivity.this, "الاسم مطلوب",
                                    "اكتب اسم الشركة.");
                            return;
                        }
                        final JSONObject body = new JSONObject();
                        try {
                            if (existing != null) body.put("id", existing.optString("id", ""));
                            body.put("name", text(name));
                            body.put("contact_name", text(contact));
                            body.put("phone", text(phone));
                            body.put("email", text(email));
                            body.put("website", text(website));
                            body.put("logo_url", text(logo));
                            body.put("note", text(note));
                            body.put("blocked", blocked.on);
                        } catch (Exception ignored) { }
                        showLoading("جارٍ الحفظ...");
                        CgAdsApi.async(CgAdsAdvertisersActivity.this, new CgAdsApi.Call() {
                            @Override public CgHttp.Res run() {
                                return CgAdsApi.saveAdvertiser(body);
                            }
                        }, new CgHttp.Done<CgHttp.Res>() {
                            @Override public void ok(CgHttp.Res res) {
                                hideLoading();
                                CgUi.toast(CgAdsAdvertisersActivity.this, "تم الحفظ.");
                                load();
                            }

                            @Override public void fail(String message) {
                                hideLoading();
                                CgUi.info(CgAdsAdvertisersActivity.this, "تعذر الحفظ", message);
                            }
                        });
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void confirmDelete(final JSONObject advertiser) {
        final int campaigns = advertiser.optInt("campaign_count", 0);
        CgUi.confirm(this, "حذف المعلن",
                campaigns > 0
                        ? "لهذا المعلن " + campaigns + " حملة. الحذف لن يحذفها، "
                                + "وسيُطلب تأكيد قسري من الخادم."
                        : "سيُحذف ملف الشركة. محادثته لا تُحذف من هنا.",
                "حذف", true, new Runnable() {
                    @Override public void run() {
                        delete(advertiser.optString("id", ""), false);
                    }
                });
    }

    private void delete(final String id, final boolean force) {
        showLoading("جارٍ الحذف...");
        CgHttp.async(this, new CgHttp.Work<CgHttp.Res>() {
            @Override public CgHttp.Res run() throws Exception {
                return CgAdsApi.deleteAdvertiser(id, force);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                if (res != null && res.ok()) {
                    CgUi.toast(CgAdsAdvertisersActivity.this, "تم الحذف.");
                    load();
                    return;
                }
                // 409 has_campaigns: ask once, then force.
                if (res != null && "has_campaigns".equals(res.errorCode())) {
                    CgUi.confirm(CgAdsAdvertisersActivity.this, "للمعلن حملات",
                            res.message() + "\n\nهل تريد حذف ملف المعلن على أي حال؟ "
                                    + "حملاته ستبقى بلا ملف معلن.",
                            "حذف قسري", true, new Runnable() {
                                @Override public void run() {
                                    delete(id, true);
                                }
                            });
                    return;
                }
                CgUi.info(CgAdsAdvertisersActivity.this, "تعذر الحذف",
                        res == null ? "خطأ غير معروف." : res.message());
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsAdvertisersActivity.this, "تعذر الحذف", message);
            }
        });
    }

    private static String text(EditText field) {
        if (field == null || field.getText() == null) return "";
        return field.getText().toString().trim();
    }
}
