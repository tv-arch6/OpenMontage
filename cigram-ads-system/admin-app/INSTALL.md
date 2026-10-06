# تركيب المرحلتين (ج) و(د) في تطبيق الأدمن

المشروع: `NewProject` — الحزمة `com.my.newproject`.
**لا تعديل على أي ملف Java أو Layout قائم، ولا مكتبات جديدة، ولا أذونات جديدة.**
سطر واحد فقط يُضاف إلى الشاشة الرئيسية.

---

## ١) ملفات Java (كلها جديدة)

Sketchware ← المشروع ← **Java Manager**:

| الملف | الوصف |
|---|---|
| `CgAdsApi.java` | كل مسارات الإعلانات فوق `CgHttp` القائم |
| `CgAdsUi.java` | Skeleton و Filters وبطاقات أرقام وتسميات الحالات |
| `CgAdsPresence.java` | زر حالتي + التحول التلقائي لـ«غير متصل» عند الإغلاق |
| `CgAdsThread.java` | المحادثة المفتوحة حالياً (للنبض) |
| `CgWs.java` | عميل WebSocket — نسخة من `CigramWs` بالحزمة الجديدة |
| `CgAdsHubActivity.java` | مركز الإعلانات: البوابة الواحدة |
| `CgAdsInboxActivity.java` | صندوق الوارد + بطاقة الوارد المثبتة |
| `CgAdsChatActivity.java` | محادثة المعلن بكل مزايا الإدارة |
| `CgAdsCampaignsActivity.java` | قائمة الحملات بفلاتر وتنبيه الانتهاء |
| `CgAdsCampaignActivity.java` | حملة واحدة: الحقول والمواد والمراجعة والدفع |
| `CgAdsSlotPreview.java` | معاينة مرسومة تحاكي شكل المساحة داخل التطبيق |
| `CgAdsAdvertisersActivity.java` | المعلنون |
| `CgAdsReportsActivity.java` | التقارير والرابط العام |
| `CgAdsSettingsActivity.java` | الأسعار والسياسات والتواصل وساعات العمل |

> `CgWs.java` نسخة حرفية من `CigramWs.java` في تطبيق المستخدمين (الحزمة والاسم
> فقط تغيّرا). المشروعان منفصلان في Sketchware ولا وحدة مشتركة بينهما، فإن
> عدّلت أحدهما عدّل الآخر. يغطّيه `user-app/test/run-ws-test.sh`.

## ٢) الـ Manifest

**AndroidManifest** ← `app_components.txt` — أضف في آخره:

```xml
<activity
    android:name="com.my.newproject.CgAdsHubActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
<activity
    android:name="com.my.newproject.CgAdsInboxActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
<activity
    android:name="com.my.newproject.CgAdsChatActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
<activity
    android:name="com.my.newproject.CgAdsCampaignsActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
<activity
    android:name="com.my.newproject.CgAdsCampaignActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
<activity
    android:name="com.my.newproject.CgAdsAdvertisersActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
<activity
    android:name="com.my.newproject.CgAdsReportsActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
<activity
    android:name="com.my.newproject.CgAdsSettingsActivity"
    android:exported="false"
    android:configChanges="orientation|screenSize|keyboardHidden"
    android:theme="@android:style/Theme.Material.NoActionBar"
    android:windowSoftInputMode="adjustResize" />
```

نفس صيغة الشاشات الموجودة (`CgHubActivity` وغيرها).

## ٣) السطر الوحيد في الشاشة الرئيسية

### الخيار الأول — بطاقة «مركز الإعلانات» (الأبسط)

في `CxHomeActivity` (أو `CgHubActivity`) حيث تُبنى البطاقات، أضف:

```java
page.addView(CgAdsHubActivity.entry(this),
        CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
```

(بدّل `page` باسم العمود الذي تضيف إليه البطاقات في تلك الشاشة.)

### الخيار الثاني — بطاقة الوارد مثبتة مع عدّاد غير المقروء

كما يطلب التصميم، بطاقة الوارد نفسها في الشاشة الرئيسية:

```java
page.addView(CgAdsInboxActivity.entryCard(this),
        CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
page.addView(CgAdsHubActivity.entry(this),
        CgUi.lp(this, CgUi.MATCH, CgUi.WRAP, 0, 0, 0, 10));
```

البطاقة تجلب عدّادها بنفسها عند بناء الشاشة، ولا تؤخّر الشاشة: الطلب في خيط
خلفي، وإن فشل تكتب «اضغط لعرض المحادثات» بلا خطأ مزعج.

## ٤) الأذونات

**لا شيء جديد.** الشاشات تستخدم `INTERNET` فقط، واختيار الصور والملفات يتم
بـ `ACTION_GET_CONTENT` الذي لا يحتاج إذناً.

---

## ما تفعله الشاشات

### صندوق الوارد
محادثات مرتبة بالأحدث، أول حرف من اسم الشركة كصورة رمزية، آخر رسالة، عدّاد غير
مقروء، نقطة اتصال المعلن. بحث فوري (٣٢٠ms) وفلاتر: **الكل · غير مقروء · جديد ·
مهم · بانتظار الدفع · محظور · المؤرشفة**. ضغط مطوّل على محادثة: أرشفة، حظر،
تصدير، حذف نهائي. يتحدّث كل ١٢ ثانية وأنت فيه.

### زر حالتي
**متصل الآن / خارج ساعات العمل / غير متصل** — يُرسل فوراً فيتغيّر رأس محادثة
المعلن في ثانية. يتحول تلقائياً إلى «غير متصل» عند إغلاق التطبيق: الشاشات تعدّ
نفسها، وعندما لا تبقى شاشة إعلانات ظاهرة ننتظر ٢٫٥ ثانية (حتى لا يبدو الانتقال
بين شاشتين خروجاً) ثم نُعلن عدم الاتصال. الحالة محفوظة محلياً فتُستعاد عند
العودة.

### محادثة المعلن
نفس النقل (WebSocket ثم long-poll) ونفس المحادثة. وزيادة على شاشة المعلن:

- **ردود جاهزة** كشرائح فوق مربع الكتابة.
- **ملاحظة داخلية**: مفتاح واضح أسفل المربع؛ الحدود تتحول للأصفر ليكون جلياً أن
  ما تكتبه لن يراه المعلن. والخادم لا يُسلّمها له في كل الحالات.
- **وسوم وإسناد**: الإسناد يُحفظ كوسم `مسند:<الاسم>` فيظهر في صندوق الوارد.
- **إنشاء عرض سعر**: مبلغ ومدة وملاحظة، ويصل للمعلن كبطاقة بأزرار قبول/تفاوض/رفض.
- **تحويل الطلب إلى حملة**: من بطاقة «طلب إعلان» بزر واحد، فتُنشأ الحملة كمسودة
  بالمساحة والمدة والمدن والسعر والحصرية مملوءة، وتُفتح شاشتها فوراً.
- أرشفة، حظر، تصدير السجل (مع الملاحظات الداخلية أو بدونها)، وحملات هذا المعلن.

### الحملات
فلاتر بالحالة وبالمساحة (من الخادم، لا تقليم محلي)، ملخص فيه عدد ما ينتظر
مراجعتك وما ينتهي خلال ٣ أيام، وتنبيه أصفر على كل حملة قاربت النهاية.
الاعتماد والرفض من القائمة مباشرة، والرفض **يرفض بدون سبب**: السبب إلزامي
ويظهر للمعلن في «إعلاناتي».

### الحملة الواحدة
الأساسيات (المعلن، العنوان، المساحة، السعر مع زر «احسب» يسأل الخادم ويمكنك
تعديل الناتج يدوياً) · المدة (منتقي تاريخ ووقت + شرائح سريعة من يوم إلى سنة +
تمديد) · الاستهداف (مدن، نسخة التطبيق، أولوية ١-١٠، حد الظهور اليومي لكل
مستخدم، حصرية) · المواد الإعلانية (رفع صورة أو فيديو، عنوان ووصف ونص زر
وإجراء: رابط/واتساب/اتصال/متجر/كوبون، ومعاينة مرسومة تحاكي المساحة وتُظهر طول
النص قبل الاعتماد) · الدفع اليدوي · المراجعة (اعتماد — ممنوع بدون مادة — ورفض
بسبب وإيقاف وتشغيل وحذف مع المواد).

### المعلنون
ملف لكل شركة، وما دفعته إجمالاً (يحدّثه الخادم عند كل تسجيل دفع فلا يتعارض مع
الحملات)، وعدد حملاتها والنشط منها، وفتح محادثتها. الحذف يحترم وجود الحملات
ويطلب تأكيداً قسرياً.

### التقارير
نظرة عامة على النظام، أو حملة واحدة مع رسم يومي (العمود مشاهدات والجزء الملوّن
نقرات) و**رابط عام بتوكن عشوائي للقراءة فقط** — لا سعر ولا حالة دفع ولا أي بيانات
مستخدمين — مع نسخ وفتح ومشاركة.

### الإعدادات
أسعار المساحات وأقل مدة والمشاهدات المتوقعة · خصومات المدد · قيم الإضافات ·
أرقام الغلاف · واتساب و X والبريد · ساعات العمل والرد التلقائي · نصوص المقبول
والمرفوض · نصوص السياسات · الأسئلة الشائعة.
كل حفظ يرسل الكائن كاملاً فلا يُفرّغ قسمٌ قسماً آخر، و**رفع رقم نسخة السياسة
مفتاح منفصل** لأنه يُلغي موافقات كل المعلنين ويطلبها من جديد.

---

## اختبار المرحلة (د)

- [ ] من الشاشة الرئيسية: بطاقة الوارد تظهر العدّاد، والضغط يفتح الصندوق.
- [ ] بدّل حالتك إلى «متصل»: رأس محادثة المعلن في تطبيق المستخدم يصبح «متصل الآن».
- [ ] أغلق تطبيق الأدمن تماماً: تصبح الحالة «غير متصل» عند المعلن.
- [ ] انتقل من الوارد إلى محادثة ورجوع: الحالة **لا** تتحول إلى غير متصل.
- [ ] أرسل رداً: يصل للمعلن بلا تحديث، ويظهر ✓✓ بعد قراءته.
- [ ] أرسل ملاحظة داخلية: لا تظهر عند المعلن إطلاقاً (تحقق من شاشته).
- [ ] أنشئ عرض سعر: يظهر عند المعلن ببطاقة وأزرار؛ اقبله عنده ← تتحدّث البطاقة لديك.
- [ ] من بطاقة «طلب إعلان» اضغط «تحويل إلى حملة»: تُفتح حملة مسودة مملوءة.
- [ ] أضف مادة إعلانية: المعاينة تتغيّر بتغيّر العنوان والزر.
- [ ] جرّب «اعتماد» بلا مادة: يُمنع برسالة واضحة.
- [ ] ارفض حملة بلا سبب: يُمنع؛ ومع سبب يظهر السبب عند المعلن في «إعلاناتي».
- [ ] غيّر سعر مساحة من الإعدادات: الحاسبة في التطبيق تعطي السعر الجديد.
- [ ] ارفع رقم نسخة السياسة: المعلن يُطلب منه الموافقة من جديد.
- [ ] احظر معلناً: لا يستطيع الإرسال، وأنت تستطيع.
- [ ] صدّر سجل محادثة: النص يحوي الطرفين، والملاحظات الداخلية حسب اختيارك.
- [ ] افتح الرابط العام للتقرير في متصفح: أرقام فقط، بلا سعر ولا بيانات.
- [ ] «تفاصيل التشفير» في المركز: يقول صراحةً إن التشفير ليس طرفياً.
