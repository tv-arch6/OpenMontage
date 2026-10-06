# نظام «إعلانات الشركات» — Cigram

بيع مساحات إعلانية داخل التطبيق مباشرة للشركات: صفحة «أعلن هنا» في قسم الحساب،
حاسبة تكلفة، محادثة مباشرة مع الإدارة، لوحة أدمن كاملة، وعرض الإعلانات داخل التطبيق.

نظام **منفصل تماماً** عن AdMob الموجود (`CigramAdConfig` / `CigramAdManager`):
لا يلمس وحداته ولا سياساته ولا عدّاداته، ويعمل بجانبه.

---

## المعمارية

```
┌─ تطبيق المستخدمين (com.Cigram.vid, Java) ──────────────┐
│  قسم الحساب ──> CigramAdsEntry   (صف «أعلن هنا»)        │
│                      │                                  │
│                 CigramAdvertiseActivity  (أعلن هنا)      │
│                      ├─ حاسبة ──> POST /ads/quote        │
│                      ├─ سياسات ─> POST /ads/consent      │
│                      └─ تواصل ──> المحادثة / واتساب / X  │
│                 CigramAdsChatActivity    (محادثة)        │
│                 CigramMyAdsActivity      (إعلاناتي)      │
│                 CigramAdSlotView         (عرض الإعلانات) │
└──────────────────────┬──────────────────────────────────┘
                       │ HTTPS
┌──────────────────────┴──────────────────────────────────┐
│  Cloudflare Worker  cigram-admin-api                     │
│  ├─ الكود القائم (v2/v3/v4) — لم يُمس                    │
│  └─ ADS_ROUTES  (cigram-ads-module.js)                   │
│       ├─ R2 MOVIES_BUCKET : ads/*.json, ads/creatives/*  │
│       ├─ DO AdsStatsDO    : العدّادات ومنع التكرار        │
│       └─ DO AdsChatDO     : المحادثة والحضور (المرحلة ج)  │
└──────────────────────┬──────────────────────────────────┘
                       │ Bearer ADMIN_TOKEN
┌──────────────────────┴──────────────────────────────────┐
│  تطبيق الأدمن (com.my.newproject, Java)                  │
│  CgAdsInboxActivity · CgAdsChatActivity                  │
│  CgAdsSettingsActivity · CgAdsCampaignsActivity ...       │
└─────────────────────────────────────────────────────────┘
```

**المصادقة**: الأدمن بـ `ADMIN_TOKEN` فقط. المعلن برمز حسابه في التطبيق، يتحقق منه
الـ Worker عبر `cigram-auth-api/verify` في كل طلب — لا يرى أحد إلا محادثته وحملاته.

**الأسعار كلها من الباك إند**: التطبيق لا يحسب شيئاً، بل يرسل الاختيارات إلى
`POST /ads/quote` ويعرض ما يعود. تعديل سعر من لوحة الأدمن ينعكس فوراً بلا تحديث تطبيق.

---

## الافتراضات (راجعها قبل النشر)

1. **الـ Worker المستهدف هو `cigram-admin-api`** لأنه صاحب `MOVIES_BUCKET` و`ADMIN_TOKEN`.
   لم يُنشأ Worker جديد. (`cigram-ads-config` الحالي يخص AdMob وتُرك كما هو.)
2. **`cigram-auth-api` يحتاج مسار `POST /verify`** — غير موجود في النسخة المرفقة ولم أر
   شيفرته. الكود الجاهز في `worker/auth-verify-snippet.js`، وسطر واحد فيه يحتاج ربطه
   بآلية الجلسات لديك. حتى تضيفه: صفحة «أعلن هنا» والحاسبة وعرض الإعلانات تعمل كلها،
   أما الموافقة و«إعلاناتي» والمحادثة فتطلب تسجيل الدخول وتفشل بـ 401.
3. **لا ملفات XML Layout.** كل واجهات Java في المشروعين مبنية برمجياً
   (`CigramUI` / `CgUi`)، وملفات `data/view` مشفّرة ولا تُعدَّل إلا من داخل Sketchware.
   فالتزمت بنفس الأسلوب: كل شاشة جديدة Java خالصة، بلا موارد XML إضافية.
   الألوان والخط تؤخذ من `CigramUI` و`CgCfg` نفسها، فالثيم موحّد تلقائياً.
4. **صف «أعلن هنا» يُحقن في `linear_account_content`** بنفس أسلوب
   `CigramAccountExtras.arrange()` القائم — لا تعديل على `account.xml`.
5. **المدن والعملة افتراضية سعودية** (`SAR` / `ر.س` وقائمة مدن) وكلها قابلة للتعديل من
   `POST /admin/ads/settings` دون لمس الكود.
6. **الأسعار الافتراضية تقديرية** (`splash 400` · `hero 300` · `inline 180` · `popup 350` ·
   `sticky 220` · `sponsor 150` ريال/يوم). ضع أسعارك الحقيقية من لوحة الأدمن.
7. **أرقام الغلاف** (المستخدمون النشطون والمشاهدات اليومية) تُدخَل يدوياً من الأدمن.
   لم أربطها بـ `cigram-analytics` لأن شكل مخرجاته غير معروف لي؛ الحقل `hero.auto_stats`
   محجوز لذلك لاحقاً.
8. **الدفع يدوي** بلا بوابة، كما طلبت. بنية `payment` جاهزة لإضافة بوابة لاحقاً.
9. **لا تشفير طرفي (E2E)** — انظر قسم الأمان في `docs/SECURITY.md` (المرحلة و).
10. **التجميع**: لا يوجد Android SDK في بيئة العمل، فالكود مكتوب على مستوى API الذي
    يستخدمه المشروعان فعلاً (minSdk 24 / Android 7) ولم يُجمَّع هنا. كود الـ Worker
    مُختبَر فعلياً على Node (انظر أدناه).

---

## المراحل

| | المرحلة | الحالة |
|---|---|---|
| أ | الباك إند الأساسي: إعدادات، معلنون، حملات، `/ads`، تتبع، تقارير | ✅ منفّذ ومُختبَر (١٠٢ فحصاً) |
| ب | قسم الحساب + صفحة «أعلن هنا» كاملة | ✅ منفّذ، مفحوص بـ javac على Android 34 |
| ج | المحادثة والحضور والوسائط (مستخدم + أدمن) | ✅ منفّذ |
| د | لوحة الأدمن للحملات والتقارير | ✅ منفّذ، مفحوص بـ javac على Android 34 |
| هـ | عرض الإعلانات `AdSlotView` | ⏳ |
| و | الأمان والتشفير والتنظيف | ⏳ |

---

## الملفات

### المرحلة (أ) — منفّذة

| الملف | جديد/معدّل | الوصف |
|---|---|---|
| `worker/cigram-ads-module.js` | جديد | كل الباك إند: المسارات، محرك الأسعار، `AdsStatsDO` |
| `worker/apply-ads-patch.py` | جديد | يدمج الوحدة مع `cigram-admin-worker-v2.js` في ملف واحد |
| `worker/auth-verify-snippet.js` | جديد | مسار `/verify` يُضاف إلى `cigram-auth-api` |
| `worker/INTEGRATION.md` | جديد | خطوات النشر والـ Bindings وهجرة الـ DO والتحقق |
| `worker/test/*` | جديد | ١٠٢ فحصاً تعمل على Node بلا Cloudflare |
| `cigram-admin-worker-v2.js` | **معدّل: ٣ إدراجات** | سطرا توجيه + سطر cron + القائمة في `/health` |

لا ملف قائم آخر يتغيّر في المرحلة (أ).

### المرحلة (ب) — منفّذة

| الملف | جديد/معدّل | الوصف |
|---|---|---|
| `user-app/java/com.Cigram.vid/CigramAdsApi.java` | جديد | كل شبكة الميزة: خيوط خلفية، كاش، أخطاء عربية |
| `user-app/java/com.Cigram.vid/CigramAdsUi.java` | جديد | الكِت البصري: بطاقات، صفوف، أيقونات Canvas، Accordion، Skeleton |
| `user-app/java/com.Cigram.vid/CigramAdsCalculator.java` | جديد | حاسبة التكلفة (كل سعر من `/ads/quote`) |
| `user-app/java/com.Cigram.vid/CigramAdvertiseActivity.java` | جديد | صفحة «أعلن هنا» بأقسامها السبعة |
| `user-app/java/com.Cigram.vid/CigramAdsEntry.java` | جديد | توحيد صفوف قسم الحساب + صف «أعلن هنا» |
| `user-app/java/com.Cigram.vid/CigramAccountExtras.java` | **معدّل** | صار يبني صف التحديثات فقط ويسلّم التنسيق لـ `CigramAdsEntry` |
| `app_components.txt` (Manifest) | **معدّل: إضافة** | تسجيل `CigramAdvertiseActivity` |
| `tools/javac-check.sh` + `tools/stubs/` | جديد | فحص أنواع على مسار Android 34 حقيقي |

لا تعديل على أي Layout، ولا موارد جديدة، ولا مكتبات جديدة، ولا أذونات جديدة.
`CigramStage1InitProvider.java` و`CigramUI.java` و`CigramAdConfig.java`
و`CigramAdManager.java` لم تُلمس.

خطوات التركيب في `user-app/INSTALL.md`.

### المرحلة (ج) — منفّذة (الباك إند + تطبيق المستخدم)

| الملف | جديد/معدّل | الوصف |
|---|---|---|
| `worker/cigram-ads-chat-module.js` | جديد | المحادثة والحضور والوسائط + `AdsChatDO` و`AdsChatIndexDO` |
| `worker/apply-ads-patch.py` | **معدّل** | يدمج أي عدد من وحدات الإعلانات ويربط جداول مساراتها |
| `user-app/java/…/CigramWs.java` | جديد | عميل WebSocket (RFC 6455) بلا مكتبات |
| `user-app/java/…/CigramAdsChat.java` | جديد | النقل والترتيب وطابور الإرسال دون اتصال |
| `user-app/java/…/CigramAdsChatAdapter.java` | جديد | قائمة الرسائل بكل أنواعها |
| `user-app/java/…/CigramAdsChatActivity.java` | جديد | شاشة المحادثة والأذونات والمسجّل |
| `user-app/java/…/CigramAdsMedia.java` | جديد | الضغط والرفع والتنزيل وكاش الصور |
| `user-app/java/…/CigramAdsVoice.java` | جديد | التسجيل والتشغيل والموجة |
| `user-app/java/…/CigramAdsDocViewer.java` | جديد | عارض PDF داخلي (`PdfRenderer`) |
| `user-app/java/…/CigramMyAdsActivity.java` | جديد | «إعلاناتي» مع رسم يومي |
| `user-app/test/*` | جديد | ١٣ فحصاً لعميل WebSocket مقابل خادم حقيقي |
| `app_components.txt` (Manifest) | **معدّل: إضافة** | شاشتان جديدتان |

إذن `CAMERA` **اختياري** (زر التصوير داخل المحادثة فقط). بقية الأذونات موجودة.

### المرحلتان (ج) و(د) — تطبيق الأدمن، منفّذتان

| الملف | جديد/معدّل | الوصف |
|---|---|---|
| `admin-app/java/…/CgAdsApi.java` | جديد | كل المسارات فوق `CgHttp` القائم |
| `admin-app/java/…/CgAdsUi.java` | جديد | Skeleton و Filters وبطاقات أرقام |
| `admin-app/java/…/CgAdsPresence.java` · `CgAdsThread.java` | جديد | زر حالتي والتحول التلقائي لغير متصل |
| `admin-app/java/…/CgWs.java` | جديد | نسخة عميل WebSocket بحزمة الأدمن |
| `admin-app/java/…/CgAdsHubActivity.java` | جديد | مركز الإعلانات (البوابة الواحدة) |
| `admin-app/java/…/CgAdsInboxActivity.java` | جديد | صندوق الوارد + البطاقة المثبتة |
| `admin-app/java/…/CgAdsChatActivity.java` | جديد | المحادثة بردود جاهزة وملاحظات وعروض أسعار |
| `admin-app/java/…/CgAdsCampaignsActivity.java` | جديد | قائمة الحملات والمراجعة السريعة |
| `admin-app/java/…/CgAdsCampaignActivity.java` | جديد | الحملة الواحدة والمواد والدفع |
| `admin-app/java/…/CgAdsSlotPreview.java` | جديد | معاينة مرسومة لشكل المساحة |
| `admin-app/java/…/CgAdsAdvertisersActivity.java` | جديد | المعلنون |
| `admin-app/java/…/CgAdsReportsActivity.java` | جديد | التقارير والرابط العام |
| `admin-app/java/…/CgAdsSettingsActivity.java` | جديد | الأسعار والسياسات والتواصل |
| `app_components.txt` (Manifest) | **معدّل: إضافة** | ٨ شاشات |
| الشاشة الرئيسية للأدمن | **معدّل: سطر واحد** | `CgAdsHubActivity.entry(this)` |

لا ملف Java قائم يتغيّر في تطبيق الأدمن، ولا مكتبة ولا إذن جديد.
خطوات التركيب في `admin-app/INSTALL.md`.

---

## الاختبار

```bash
# الباك إند
cd worker && ./test/run.sh /path/to/cigram-admin-worker-v2.js

# كود الأندرويد (فحص أنواع على مسار Android 34 حقيقي، لا تجميع APK)
./tools/javac-check.sh user

# عميل WebSocket مقابل خادم RFC 6455 حقيقي
./user-app/test/run-ws-test.sh

# كود تطبيق الأدمن
./tools/javac-check.sh admin
```

يدمج الوحدة مع نسختك الحقيقية ويشغّلها على Node مع محاكاة R2 و Durable Object.
يغطّي: بقاء المسارات القديمة، المصادقة، الحاسبة بالأرقام، بوابة المراجعة،
المجدولة والمنتهية والمتوقفة، الاستهداف بالمدينة وبنسخة التطبيق، التناوب والحصرية،
منع تكرار التتبع وحد المعدل، التقارير العامة والخاصة، الموافقة على السياسات،
عزل بيانات المعلنين، فحص MIME بالبايتات، و`scheduled()`.
