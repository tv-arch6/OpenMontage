package com.my.newproject;

import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * إعدادات الإعلانات: الأسعار والخصومات والإضافات، نصوص المقبول والمرفوض
 * والسياسات والأسئلة الشائعة، أرقام التواصل، ساعات العمل والرد التلقائي،
 * وأرقام الغلاف.
 *
 * Everything the "أعلن هنا" page shows lives here, so wording and prices change
 * without an app update. Two safety rules:
 *   - the whole settings object is read, edited and written back, so saving one
 *     section cannot blank another,
 *   - raising the policy version is an explicit switch: it invalidates every
 *     advertiser's previous acceptance and asks them to accept again, so it is
 *     never done by accident.
 */
public class CgAdsSettingsActivity extends CgBase {

    private JSONObject settings;
    private LinearLayout page;

    @Override protected String screenTitle() {
        return "إعدادات الإعلانات";
    }

    @Override protected void onBuild() {
        page = scrollBody();
        page.addView(CgAdsUi.skeleton(this, 4), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
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
                return CgHttp.require(CgAdsApi.settings());
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                settings = CgAdsApi.data(res).optJSONObject("settings");
                if (settings == null) settings = new JSONObject();
                build();
            }

            @Override public void fail(String message) {
                page.removeAllViews();
                page.addView(CgUi.errorCard(CgAdsSettingsActivity.this, message, new Runnable() {
                    @Override public void run() {
                        page.removeAllViews();
                        page.addView(CgAdsUi.skeleton(CgAdsSettingsActivity.this, 4),
                                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
                        load();
                    }
                }), new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
            }
        });
    }

    private void build() {
        page.removeAllViews();
        page.addView(CgUi.muted(this, "نسخة السياسة الحالية: "
                        + settings.optInt("policy_version", 1)
                        + " · آخر تحديث: " + CgUi.localText(settings.optString("updated_at", ""))),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 2, 0, 2, 10));

        page.addView(section("أسعار المساحات",
                "سعر اليوم الواحد لكل مساحة، وأقل مدة، والمشاهدات المتوقعة.", new Runnable() {
                    @Override public void run() {
                        editSlots();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("خصومات المدد",
                "خصم تلقائي عند بلوغ عدد أيام معين.", new Runnable() {
                    @Override public void run() {
                        editDiscounts();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("الإضافات",
                "الحصرية والفيديو والتصميم — نسبة أو مبلغ ثابت.", new Runnable() {
                    @Override public void run() {
                        editAddons();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("أرقام الغلاف",
                "المستخدمون النشطون والمشاهدات اليومية المعروضة للمعلن.", new Runnable() {
                    @Override public void run() {
                        editHero();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("التواصل",
                "رقم واتساب الإعلانات وحساب X والبريد.", new Runnable() {
                    @Override public void run() {
                        editContact();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("ساعات العمل والرد التلقائي",
                "خارجها تظهر حالتك «خارج ساعات العمل» مع ردك التلقائي.", new Runnable() {
                    @Override public void run() {
                        editHours();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("المقبول والمرفوض",
                "القوائم التي يراها المعلن قبل الإرسال.", new Runnable() {
                    @Override public void run() {
                        editRules();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("السياسات",
                "نص كل سياسة. تغييرها يستدعي رفع رقم النسخة.", new Runnable() {
                    @Override public void run() {
                        editPolicies();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 8));

        page.addView(section("الأسئلة الشائعة",
                "سؤال وجواب يظهران في صفحة «أعلن هنا».", new Runnable() {
                    @Override public void run() {
                        editFaq();
                    }
                }), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 20));
    }

    private View section(String title, String subtitle, final Runnable onClick) {
        LinearLayout card = CgUi.card(this);
        card.addView(CgUi.text(this, title, 15f, CgCfg.TEXT, true),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        card.addView(CgUi.muted(this, subtitle), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 4, 0, 0));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                onClick.run();
            }
        });
        card.setContentDescription(title);
        return card;
    }

    // ------------------------------------------------------------- sections

    private void editSlots() {
        final JSONArray slots = settings.optJSONArray("slots");
        if (slots == null || slots.length() == 0) {
            CgUi.info(this, "لا توجد مساحات", "احفظ الإعدادات مرة واحدة لتوليد الافتراضيات.");
            return;
        }
        LinearLayout form = CgUi.vbox(this);
        final EditText[] prices = new EditText[slots.length()];
        final EditText[] minDays = new EditText[slots.length()];
        final EditText[] views = new EditText[slots.length()];
        final CgUi.Toggle[] enabled = new CgUi.Toggle[slots.length()];

        for (int i = 0; i < slots.length(); i++) {
            JSONObject slot = slots.optJSONObject(i);
            if (slot == null) continue;
            LinearLayout box = CgUi.vbox(this);
            box.setBackground(CgUi.bg(this, CgCfg.CARD2, 12, CgCfg.STROKE));
            int p = CgUi.dp(this, 10);
            box.setPadding(p, p, p, p);
            box.addView(CgUi.text(this, slot.optString("name", slot.optString("id", "")),
                            14.5f, CgCfg.TEXT, true),
                    new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));

            prices[i] = CgUi.numberField(this, "سعر اليوم");
            prices[i].setText(String.valueOf(Math.round(slot.optDouble("base_price_day", 0d))));
            box.addView(CgUi.labeled(this, "سعر اليوم", prices[i]),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

            minDays[i] = CgUi.numberField(this, "أقل مدة بالأيام");
            minDays[i].setText(String.valueOf(slot.optInt("min_days", 1)));
            box.addView(CgUi.labeled(this, "أقل مدة", minDays[i]),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

            views[i] = CgUi.numberField(this, "مشاهدات متوقعة يومياً");
            views[i].setText(String.valueOf(slot.optInt("est_views_per_day", 0)));
            box.addView(CgUi.labeled(this, "مشاهدات/يوم", views[i]),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

            enabled[i] = CgUi.toggle(this, "معروضة للمعلنين", slot.optBoolean("enabled", true));
            box.addView(enabled[i], CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

            form.addView(box, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
        }

        showForm("أسعار المساحات", form, new Runnable() {
            @Override public void run() {
                for (int i = 0; i < slots.length(); i++) {
                    JSONObject slot = slots.optJSONObject(i);
                    if (slot == null || prices[i] == null) continue;
                    try {
                        slot.put("base_price_day", number(prices[i]));
                        slot.put("min_days", Math.max(1, (int) number(minDays[i])));
                        slot.put("est_views_per_day", (int) number(views[i]));
                        slot.put("enabled", enabled[i].on);
                    } catch (Exception ignored) { }
                }
                save(false);
            }
        });
    }

    private void editDiscounts() {
        final JSONArray discounts = settings.optJSONArray("discounts") == null
                ? new JSONArray() : settings.optJSONArray("discounts");
        LinearLayout form = CgUi.vbox(this);
        form.addView(CgUi.muted(this,
                        "اكتب سطراً لكل خصم بالشكل: عدد الأيام = النسبة، مثال: 30 = 12"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final EditText text = CgUi.field(this, "30 = 12", true);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < discounts.length(); i++) {
            JSONObject discount = discounts.optJSONObject(i);
            if (discount == null) continue;
            if (current.length() > 0) current.append("\n");
            current.append(discount.optInt("min_days", 0)).append(" = ")
                    .append(discount.optDouble("percent", 0d));
        }
        text.setText(current.toString());
        form.addView(text, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        showForm("خصومات المدد", form, new Runnable() {
            @Override public void run() {
                JSONArray list = new JSONArray();
                for (String line : value(text).split("\n")) {
                    String[] parts = line.split("=");
                    if (parts.length != 2) continue;
                    try {
                        JSONObject discount = new JSONObject();
                        discount.put("min_days", Integer.parseInt(parts[0].trim()));
                        discount.put("percent", Double.parseDouble(parts[1].trim()));
                        list.put(discount);
                    } catch (Exception ignored) { }
                }
                try {
                    settings.put("discounts", list);
                } catch (Exception ignored) { }
                save(false);
            }
        });
    }

    private void editAddons() {
        final JSONArray addons = settings.optJSONArray("addons") == null
                ? new JSONArray() : settings.optJSONArray("addons");
        LinearLayout form = CgUi.vbox(this);
        final EditText[] values = new EditText[addons.length()];
        for (int i = 0; i < addons.length(); i++) {
            JSONObject addon = addons.optJSONObject(i);
            if (addon == null) continue;
            values[i] = CgUi.numberField(this,
                    "percent".equals(addon.optString("type")) ? "النسبة %" : "المبلغ");
            values[i].setText(String.valueOf(Math.round(addon.optDouble("value", 0d))));
            form.addView(CgUi.labeled(this, addon.optString("label", "") + " ("
                            + ("percent".equals(addon.optString("type")) ? "نسبة" : "مبلغ ثابت") + ")",
                    values[i]), CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        }
        showForm("الإضافات", form, new Runnable() {
            @Override public void run() {
                for (int i = 0; i < addons.length(); i++) {
                    if (values[i] == null) continue;
                    try {
                        addons.optJSONObject(i).put("value", number(values[i]));
                    } catch (Exception ignored) { }
                }
                save(false);
            }
        });
    }

    private void editHero() {
        JSONObject hero = settings.optJSONObject("hero");
        if (hero == null) hero = new JSONObject();
        final JSONObject target = hero;
        LinearLayout form = CgUi.vbox(this);
        final EditText headline = CgUi.field(this, "العنوان الرئيسي", false);
        headline.setText(target.optString("headline", ""));
        final EditText sub = CgUi.field(this, "الجملة تحته", true);
        sub.setText(target.optString("sub", ""));
        final EditText users = CgUi.numberField(this, "عدد المستخدمين النشطين");
        users.setText(String.valueOf(target.optLong("active_users", 0L)));
        final EditText views = CgUi.numberField(this, "متوسط المشاهدات اليومية");
        views.setText(String.valueOf(target.optLong("daily_views", 0L)));
        final EditText note = CgUi.field(this, "ملاحظة تحت الأرقام", false);
        note.setText(target.optString("stats_note", ""));

        form.addView(CgUi.labeled(this, "العنوان", headline),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        form.addView(CgUi.labeled(this, "الجملة", sub),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "مستخدمون نشطون", users),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "مشاهدات يومية", views),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "ملاحظة", note),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.muted(this,
                        "صفر يعني «—» في التطبيق بدل رقم غير صحيح."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        showForm("أرقام الغلاف", form, new Runnable() {
            @Override public void run() {
                try {
                    target.put("headline", value(headline));
                    target.put("sub", value(sub));
                    target.put("active_users", (long) number(users));
                    target.put("daily_views", (long) number(views));
                    target.put("stats_note", value(note));
                    settings.put("hero", target);
                } catch (Exception ignored) { }
                save(false);
            }
        });
    }

    private void editContact() {
        JSONObject contact = settings.optJSONObject("contact");
        if (contact == null) contact = new JSONObject();
        final JSONObject target = contact;
        LinearLayout form = CgUi.vbox(this);
        final EditText whatsapp = CgUi.field(this, "مثال: 9665xxxxxxxx", false);
        whatsapp.setText(target.optString("whatsapp", ""));
        final EditText template = CgUi.field(this, "الرسالة الجاهزة في واتساب", true);
        template.setText(target.optString("whatsapp_template", ""));
        final EditText x = CgUi.urlField(this, "https://x.com/…");
        x.setText(target.optString("x_url", ""));
        final EditText email = CgUi.field(this, "البريد", false);
        email.setText(target.optString("email", ""));
        final CgUi.Toggle inApp = CgUi.toggle(this, "إظهار «المحادثة داخل التطبيق»",
                target.optBoolean("inapp_enabled", true));

        form.addView(CgUi.labeled(this, "رقم واتساب الإعلانات", whatsapp),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        form.addView(CgUi.labeled(this, "الرسالة الجاهزة", template),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "حساب X", x),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "البريد", email),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(inApp, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        form.addView(CgUi.muted(this, "ما تتركه فارغاً لا يظهر زره في التطبيق."),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        showForm("التواصل", form, new Runnable() {
            @Override public void run() {
                try {
                    target.put("whatsapp", value(whatsapp));
                    target.put("whatsapp_template", value(template));
                    target.put("x_url", value(x));
                    target.put("email", value(email));
                    target.put("inapp_enabled", inApp.on);
                    settings.put("contact", target);
                } catch (Exception ignored) { }
                save(false);
            }
        });
    }

    private void editHours() {
        JSONObject hours = settings.optJSONObject("hours");
        if (hours == null) hours = new JSONObject();
        final JSONObject target = hours;
        LinearLayout form = CgUi.vbox(this);
        final EditText from = CgUi.field(this, "09:00", false);
        from.setText(target.optString("open_from", "09:00"));
        final EditText to = CgUi.field(this, "23:00", false);
        to.setText(target.optString("open_to", "23:00"));
        final EditText autoReply = CgUi.field(this, "الرد التلقائي خارج الدوام", true);
        autoReply.setText(target.optString("auto_reply", ""));
        final EditText note = CgUi.field(this, "ملاحظة ساعات العمل", false);
        note.setText(target.optString("offline_note", ""));

        form.addView(CgUi.labeled(this, "من", from),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        form.addView(CgUi.labeled(this, "إلى", to),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "الرد التلقائي", autoReply),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));
        form.addView(CgUi.labeled(this, "ملاحظة", note),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 8, 0, 0));

        showForm("ساعات العمل", form, new Runnable() {
            @Override public void run() {
                try {
                    target.put("open_from", value(from));
                    target.put("open_to", value(to));
                    target.put("auto_reply", value(autoReply));
                    target.put("offline_note", value(note));
                    settings.put("hours", target);
                } catch (Exception ignored) { }
                save(false);
            }
        });
    }

    private void editRules() {
        LinearLayout form = CgUi.vbox(this);
        form.addView(CgUi.muted(this,
                        "سطر لكل بند بالشكل: العنوان | الوصف"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final EditText accepted = CgUi.field(this, "المقبول", true);
        accepted.setText(itemsToText(settings.optJSONObject("accepted")));
        form.addView(CgUi.labeled(this, "مقبول ✅", accepted),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        final EditText rejected = CgUi.field(this, "المرفوض", true);
        rejected.setText(itemsToText(settings.optJSONObject("rejected")));
        form.addView(CgUi.labeled(this, "مرفوض ❌", rejected),
                CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        showForm("المقبول والمرفوض", form, new Runnable() {
            @Override public void run() {
                try {
                    settings.put("accepted", textToItems(settings.optJSONObject("accepted"),
                            "إعلانات نقبلها", value(accepted)));
                    settings.put("rejected", textToItems(settings.optJSONObject("rejected"),
                            "إعلانات نرفضها", value(rejected)));
                } catch (Exception ignored) { }
                save(false);
            }
        });
    }

    private static String itemsToText(JSONObject group) {
        if (group == null) return "";
        JSONArray items = group.optJSONArray("items");
        if (items == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (sb.length() > 0) sb.append("\n");
            sb.append(item.optString("title", "")).append(" | ").append(item.optString("desc", ""));
        }
        return sb.toString();
    }

    private static JSONObject textToItems(JSONObject existing, String fallbackTitle, String text) {
        JSONObject group = new JSONObject();
        JSONArray items = new JSONArray();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() == 0) continue;
            String[] parts = trimmed.split("\\|", 2);
            try {
                JSONObject item = new JSONObject();
                item.put("title", parts[0].trim());
                item.put("desc", parts.length > 1 ? parts[1].trim() : "");
                items.put(item);
            } catch (Exception ignored) { }
        }
        try {
            group.put("title", existing == null ? fallbackTitle
                    : existing.optString("title", fallbackTitle));
            group.put("items", items);
        } catch (Exception ignored) { }
        return group;
    }

    private void editPolicies() {
        final JSONArray policies = settings.optJSONArray("policies") == null
                ? new JSONArray() : settings.optJSONArray("policies");
        LinearLayout form = CgUi.vbox(this);
        final EditText[] bodies = new EditText[policies.length()];
        for (int i = 0; i < policies.length(); i++) {
            JSONObject policy = policies.optJSONObject(i);
            if (policy == null) continue;
            bodies[i] = CgUi.field(this, "نص السياسة", true);
            bodies[i].setText(policy.optString("body", ""));
            form.addView(CgUi.labeled(this, policy.optString("title", ""), bodies[i]),
                    CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));
        }
        final CgUi.Toggle bump = CgUi.toggle(this,
                "رفع رقم نسخة السياسة (يطلب موافقة جديدة من كل المعلنين)", false);
        form.addView(bump, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 14, 0, 0));

        showForm("السياسات", form, new Runnable() {
            @Override public void run() {
                for (int i = 0; i < policies.length(); i++) {
                    if (bodies[i] == null) continue;
                    try {
                        policies.optJSONObject(i).put("body", value(bodies[i]));
                    } catch (Exception ignored) { }
                }
                save(bump.on);
            }
        });
    }

    private void editFaq() {
        final JSONArray faq = settings.optJSONArray("faq") == null
                ? new JSONArray() : settings.optJSONArray("faq");
        LinearLayout form = CgUi.vbox(this);
        form.addView(CgUi.muted(this, "سطر لكل سؤال بالشكل: السؤال | الجواب"),
                new LinearLayout.LayoutParams(CgUi.MATCH, CgUi.WRAP));
        final EditText text = CgUi.field(this, "السؤال | الجواب", true);
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < faq.length(); i++) {
            JSONObject item = faq.optJSONObject(i);
            if (item == null) continue;
            if (current.length() > 0) current.append("\n");
            current.append(item.optString("q", "")).append(" | ").append(item.optString("a", ""));
        }
        text.setText(current.toString());
        form.addView(text, CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 10, 0, 0));

        showForm("الأسئلة الشائعة", form, new Runnable() {
            @Override public void run() {
                JSONArray list = new JSONArray();
                for (String line : value(text).split("\n")) {
                    String trimmed = line.trim();
                    if (trimmed.length() == 0) continue;
                    String[] parts = trimmed.split("\\|", 2);
                    try {
                        JSONObject item = new JSONObject();
                        item.put("q", parts[0].trim());
                        item.put("a", parts.length > 1 ? parts[1].trim() : "");
                        list.put(item);
                    } catch (Exception ignored) { }
                }
                try {
                    settings.put("faq", list);
                } catch (Exception ignored) { }
                save(false);
            }
        });
    }

    // --------------------------------------------------------------- helpers

    private void showForm(String title, LinearLayout form, final Runnable onSave) {
        ScrollView scroll = new ScrollView(this);
        int p = CgUi.dp(this, 4);
        form.setPadding(p, p, p, p);
        scroll.addView(form);
        CgUi.dialog(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("حفظ", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        onSave.run();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    /** Always sends the whole object back, so one section cannot blank another. */
    private void save(final boolean bumpPolicy) {
        showLoading("جارٍ الحفظ...");
        CgAdsApi.async(this, new CgAdsApi.Call() {
            @Override public CgHttp.Res run() {
                return CgAdsApi.saveSettings(settings, bumpPolicy);
            }
        }, new CgHttp.Done<CgHttp.Res>() {
            @Override public void ok(CgHttp.Res res) {
                hideLoading();
                settings = CgAdsApi.data(res).optJSONObject("settings");
                if (settings == null) settings = new JSONObject();
                CgUi.toast(CgAdsSettingsActivity.this, bumpPolicy
                        ? "تم الحفظ ورفع نسخة السياسة."
                        : "تم الحفظ. التغيير يصل للتطبيق خلال دقائق.");
                build();
            }

            @Override public void fail(String message) {
                hideLoading();
                CgUi.info(CgAdsSettingsActivity.this, "تعذر الحفظ", message);
            }
        });
    }

    private static String value(EditText field) {
        if (field == null || field.getText() == null) return "";
        return field.getText().toString().trim();
    }

    private static double number(EditText field) {
        try {
            return Double.parseDouble(value(field));
        } catch (Exception ignored) {
            return 0d;
        }
    }
}
