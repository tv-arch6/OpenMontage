# نشر الباك إند — المرحلة (أ)

ملف واحد يُضاف إلى الـ Worker الحالي، و٣ أسطر تُدرَج في موجّه المسارات. **لا يُحذف ولا يُعدَّل أي مسار قائم.**

---

## ١) تجهيز ملف الـ Worker

### الطريقة الموصى بها (آلية، تتحقق من المراسي بنفسها)

```bash
python3 apply-ads-patch.py \
    cigram-admin-worker-v2.js \
    cigram-admin-worker-v5-ads.js
```

بدون تحديد وحدات يأخذ السكربت **كل** `cigram-ads-*module.js` بجواره بالترتيب
(`cigram-ads-module.js` ثم `cigram-ads-chat-module.js`) ويضيف سطر
`Object.assign(ADS_ROUTES, ADS_CHAT_ROUTES);` بنفسه.

الناتج `cigram-admin-worker-v5-ads.js` هو الملف الذي تلصقه في Cloudflare.
السكربت يرفض العمل على ملف مُعدَّل مسبقاً، فإعادة تشغيله آمنة.

### الطريقة اليدوية (٣ إدراجات)

**(أ)** في قائمة المسارات داخل `/health`:

```js
          .concat(Object.keys(MEDIA_ROUTES)),
```
تصبح
```js
          .concat(Object.keys(MEDIA_ROUTES))
          .concat(Object.keys(ADS_ROUTES)),
```

**(ب)** تحت سطر توجيه `MEDIA_ROUTES` مباشرة:

```js
      const mediaHandler = MEDIA_ROUTES[request.method + " " + path];
      if (mediaHandler) return mediaHandler(request, env, url);

      const adsHandler = ADS_ROUTES[request.method + " " + path];   // <-- جديد
      if (adsHandler) return adsHandler(request, env, url, ctx);    // <-- جديد
```

**(ج)** داخل `scheduled()`، بعد `updActivateDue`:

```js
    ctx.waitUntil(
      adsCron(env).catch((error) => console.log("ads_cron_failed", String(error && error.message)))
    );
```

**(د)** الصق محتوى `cigram-ads-module.js` ثم `cigram-ads-chat-module.js` في **آخر** الملف،
ثم أضف بعدهما سطراً واحداً:

```js
Object.assign(ADS_ROUTES, ADS_CHAT_ROUTES);
```

---

## ٢) الـ Bindings في Cloudflare

Workers & Pages ← الـ Worker ← **Settings → Bindings**

| النوع | الاسم | القيمة | الحالة |
|---|---|---|---|
| R2 bucket | `MOVIES_BUCKET` | الحاوية الحالية | موجود — لا تغيّره |
| Secret | `ADMIN_TOKEN` | كما هو | موجود — لا تغيّره |
| Secret | `PUSH_WEBHOOK_URL` | كما هو | اختياري — موجود |
| **Durable Object** | **`ADS_STATS`** | الصنف `AdsStatsDO` | **جديد — إلزامي** |
| **Durable Object** | **`ADS_CHAT`** | الصنف `AdsChatDO` | **جديد — إلزامي للمحادثة** |
| **Durable Object** | **`ADS_CHAT_INDEX`** | الصنف `AdsChatIndexDO` | **جديد — إلزامي للمحادثة** |
| Variable | `ADS_AUTH_VERIFY_URL` | `https://cigram-auth-api.wwq-mixtv.workers.dev/verify` | جديد — اختياري (هذه قيمته الافتراضية) |
| Secret | `ADS_MEDIA_KEY` | ٣٢ بايت Base64 — مفتاح AES-GCM لتشفير التخزين | **جديد — مطلوب للمحادثة** |
| Secret | `ADS_MEDIA_SIGN` | نص عشوائي طويل — يوقّع روابط الوسائط | جديد — موصى به (يسقط على `ADMIN_TOKEN` إن غاب) |

توليد المفتاحين:

```bash
openssl rand -base64 32    # ADS_MEDIA_KEY  (يجب أن يكون ٣٢ بايت بالضبط)
openssl rand -hex 32       # ADS_MEDIA_SIGN
```

> **احفظ `ADS_MEDIA_KEY` في مكان آمن.** فقدانه يعني أن الرسائل والوسائط المخزّنة
> لا تُفك أبداً. تغييره يُفقد كل ما خُزّن قبله (الرسائل القديمة تظهر فارغة
> والوسائط القديمة تفشل) — انظر خطة تدوير المفاتيح في `docs/SECURITY.md`.
> إن تُرك فارغاً تعمل المحادثة لكن **بدون تشفير أثناء التخزين**، و
> `GET /admin/ads/security` يقول ذلك صراحةً بدل أن يدّعي التشفير.

### هجرة الـ Durable Object (إلزامية مرة واحدة)

الـ Durable Object لا يُنشأ من واجهة الـ Bindings وحدها؛ يحتاج **migration**.

**مع Wrangler** — أضف إلى `wrangler.toml` ثم `npx wrangler deploy`:

```toml
[[durable_objects.bindings]]
name       = "ADS_STATS"
class_name = "AdsStatsDO"

[[durable_objects.bindings]]
name       = "ADS_CHAT"
class_name = "AdsChatDO"

[[durable_objects.bindings]]
name       = "ADS_CHAT_INDEX"
class_name = "AdsChatIndexDO"

[[migrations]]
tag         = "v1-ads"
new_classes = ["AdsStatsDO", "AdsChatDO", "AdsChatIndexDO"]
```

**من لوحة التحكم** (بدون Wrangler): بعد لصق الكود ونشره، افتح
**Settings → Bindings → Add → Durable Object**، اختر الاسم `ADS_STATS` والصنف `AdsStatsDO`
من القائمة (يظهر لأن الصنف صار مُصدَّراً في الكود)، ثم Deploy مرة أخرى.

> إن لم يُضبط `ADS_STATS` ستعمل كل المسارات عدا العدّادات: `/ads/track` يرد
> `accepted: 0` والتقارير تظهر أصفاراً. لا شيء ينهار، لكن لا تُحسب أرقام.
> وإن لم يُضبط `ADS_CHAT`/`ADS_CHAT_INDEX` ترد مسارات المحادثة برسالة عربية
> واضحة («ربط ADS_CHAT غير مهيأ») بدل أن تنهار، وبقية النظام يعمل كما هو.

### Cron Trigger

موجود مسبقاً لمركز التحديث (`* * * * *`). نفس الـ Trigger يشغّل الآن انتقالات حالة
الحملات وتنبيه «٣ أيام على الانتهاء». **لا حاجة لإضافة Trigger ثانٍ.**
إن لم يكن لديك Cron أصلاً: Settings → Triggers → Cron Triggers → `* * * * *`.

> الحالات تُحسب لحظياً من التواريخ عند كل قراءة أيضاً، فحتى لو تعطّل الـ Cron
> لن يظهر إعلان منتهٍ أبداً. الـ Cron فقط يثبّت الحالة المخزّنة ويرسل التنبيهات.

---

## ٣) مسار `/verify` في Worker المصادقة

مطلوب لمسارات المعلن المسجّل (`/ads/consent`، `/ads/my-campaigns`، والمحادثة في المرحلة ج).
الكود في `auth-verify-snippet.js`، وسطر واحد فيه يحتاج ربطه بآلية الجلسات لديك.

مسارات `/ads` و`/ads/config` و`/ads/quote` و`/ads/track` **لا تحتاجه** وتعمل فوراً.

---

## ٤) التحقق بعد النشر

```bash
API=https://cigram-admin-api.wwq-mixtv.workers.dev
TOKEN=<ADMIN_TOKEN>

curl -s $API/health | grep -o '"GET /ads"'                 # يجب أن يطبع "GET /ads"
curl -s "$API/ads/config" | head -c 300                     # الإعدادات الافتراضية
curl -s "$API/ads?slot=hero&lang=ar" | head -c 200          # {"ads":[]} + server_time
curl -s -X POST "$API/ads/quote" -H 'content-type: application/json' \
     -d '{"slot":"hero","days":30}'                         # total = 7920

curl -s "$API/admin/ads/settings" -H "Authorization: Bearer $TOKEN" | head -c 200
curl -s "$API/movies" | head -c 80                          # القديم ما زال يعمل
```

## ٥) اختبار محلي قبل النشر

```bash
./test/run.sh /path/to/cigram-admin-worker-v2.js        # كل المراحل
./test/run.sh /path/to/cigram-admin-worker-v2.js a      # مرحلة واحدة
```

يدمج الوحدات مع نسختك الحقيقية ويشغّل ٢١٥ فحصاً على Node (R2 والـ Durable
Objects محاكاة في الذاكرة، والتشفير والتوقيع حقيقيان). لا يلمس Cloudflare ولا
الحاوية.

---

## ٦) ملفات R2 التي ينشئها النظام

| المفتاح | المحتوى | يُنشأ عند |
|---|---|---|
| `ads/settings.json` | الأسعار والسياسات والنصوص والتواصل | أول حفظ من الأدمن |
| `ads/advertisers.json` | ملفات الشركات | أول معلن |
| `ads/campaigns.json` | الحملات والمواد الإعلانية | أول حملة |
| `ads/consents.json` | من وافق على أي نسخة سياسة ومتى | أول موافقة |
| `ads/creatives/*` | الصور والفيديو المرفوعة للحملات | أول رفع |
| `ads/chat/media/*` | وسائط المحادثات — **مشفّرة**، ولا رابط عام لها | أول إرسال وسائط |

لا شيء من هذه الملفات يُقرأ أو يُكتب من مسار قائم، وغيابها لا يكسر أي شيء:
كل قارئ يسقط على القيم الافتراضية.

---

## ٧) جدول المسارات الجديدة

### عامة (بدون أي رمز)
| المسار | الوظيفة |
|---|---|
| `GET /ads?slot=&city=&lang=&app_version=&did=` | الإعلانات الفعّالة فقط + `server_time` |
| `GET /ads/config` | كل ما تعرضه صفحة «أعلن هنا» |
| `GET /ads/presence` | حالة الإدارة وساعات العمل |
| `POST /ads/quote` | حساب السعر (المصدر الوحيد للأسعار) |
| `POST /ads/track` | impression / click مع منع التكرار وحد معدل |
| `GET /ads/report?token=` | تقرير الشركة، قراءة فقط |

### المعلن المسجّل (Bearer = رمز المستخدم)
| المسار | الوظيفة |
|---|---|
| `GET /ads/consent` · `POST /ads/consent` | الموافقة على السياسات + نسختها وتاريخها |
| `GET /ads/my-campaigns` | «إعلاناتي»: حملاته هو فقط، بأرقامها |

### الأدمن (Bearer = `ADMIN_TOKEN`)
| المسار | الوظيفة |
|---|---|
| `GET`/`POST /admin/ads/settings` | الأسعار والخصومات والإضافات والسياسات والأسئلة والتواصل |
| `POST /admin/ads/presence` | متصل / غير متصل / خارج ساعات العمل |
| `POST /admin/ads/quote` | نفس الحاسبة للمعاينة |
| `GET /admin/ads/advertisers` · `advertiser/save` · `advertiser/delete` | المعلنون |
| `GET /admin/ads/campaigns` · `campaign/save` · `approve` · `reject` · `status` · `extend` · `payment` · `delete` | الحملات |
| `POST /admin/ads/creative/upload` | رفع المواد (فحص MIME بالبايتات) |
| `GET /admin/ads/report[?campaign=]` | تقرير حملة أو نظرة عامة |


---

## ٨) مسارات المرحلة (ج) — المحادثة والوسائط

### المعلن المسجّل (Bearer = رمز المستخدم)
| المسار | الوظيفة |
|---|---|
| `GET /ads/chat/history?since=&before=&limit=` | الرسائل، بصفحات للأقدم |
| `GET /ads/chat/poll?since=` | انتظار حتى ٢٥ ثانية لرسالة جديدة |
| `GET /ads/chat/ws?token=` | WebSocket لحظي |
| `GET /ads/chat/state` | الحالة والحضور ومؤشر الكتابة |
| `POST /ads/chat/send` | نص / صورة / فيديو / صوت / ملف / طلب إعلان |
| `POST /ads/chat/read` · `typing` · `heartbeat` · `delete` | إيصال القراءة، الكتابة، النبض، حذف للنفس |
| `POST /ads/chat/quote-respond` | قبول / تفاوض / رفض عرض سعر |
| `POST /ads/chat/media/upload?kind=&name=` | رفع مباشر (صورة، صوت، ملف) |
| `POST /ads/chat/media/mpu/create` · `part` · `complete` · `abort` | رفع متعدد الأجزاء للفيديو |
| `GET /ads/chat/media?id=&exp=&sig=` | تنزيل موقّع (بلا تسجيل دخول: التوقيع هو الصلاحية) |

### الأدمن (Bearer = `ADMIN_TOKEN`)
| المسار | الوظيفة |
|---|---|
| `GET /admin/ads/chat/threads?filter=&q=&archived=1` | صندوق الوارد: `unread` · `new` · `important` · `awaiting_payment` · `blocked` |
| `GET /admin/ads/chat/history?user=` · `poll` · `state` · `GET .../ws?user=` | نفس مزايا المستخدم |
| `POST /admin/ads/chat/send` | الرد، و`internal: true` لملاحظة داخلية، و`kind: "quote"` لعرض سعر |
| `POST /admin/ads/chat/flags` | حظر / أرشفة / وسوم / ملاحظة |
| `POST /admin/ads/chat/export` | تصدير السجل نصاً (`internal: true` ليضمّ الملاحظات) |
| `POST /admin/ads/chat/purge` | حذف المحادثة **ووسائطها من R2** |
| `POST /admin/ads/chat/to-campaign` | تحويل بطاقة «طلب إعلان» إلى حملة بالحقول مملوءة |
| `GET /admin/ads/security` | تقرير صريح بما هو مشفّر وما ليس كذلك |

### ما يضمنه الاختبار في هذه المرحلة
عزل المحادثات بين المعلنين · عدم وصول الملاحظات الداخلية للمعلن (لا في الرسائل
ولا في بيانات المحادثة) · تشفير الرسائل والوسائط فعلياً في R2 (الاختبار يقرأ
البايتات الخام ويتأكد أنها ليست الملف الأصلي) · رفض الروابط المعدّلة والمنتهية ·
فحص MIME بالبايتات (ملف يبدأ بـ «GIF» وليس GIF حقيقياً مرفوض) · رفع الفيديو
متعدد الأجزاء وفك تشفيره جزءاً بجزء · منع معلن من إرفاق ملف معلن آخر · منع
المعلن من تلفيق عرض سعر · عدم الرد على عرض سعر مرتين · الحظر والأرشفة والتصدير ·
الحذف الكامل مع الوسائط · طابور الإرسال (نفس `client_id` لا يُخزَّن مرتين) ·
long-poll يُحرَّر فوراً عند وصول رسالة.
