// =============================================================================
// CIGRAM ADS v1 — "إعلانات الشركات" (direct-sold advertising)
// -----------------------------------------------------------------------------
// APPEND THIS WHOLE FILE to the end of `cigram-admin-worker-v2.js`, then apply
// the 3-line router patch described in worker/INTEGRATION.md. Nothing in the
// existing worker is modified or removed: every name here is prefixed `ads`/`Ads`
// so it cannot collide with the v2/v3/v4 code above it.
//
// It reuses the helpers already defined in that file:
//   json() · corsHeaders() · cleanString() · readRequestData() · adminRoute()
//   okResponse() · readJsonFile() · writeJsonFile() · r2PublicUrl()
//
// Bindings used (see INTEGRATION.md):
//   MOVIES_BUCKET  (existing R2 bucket)        — required
//   ADMIN_TOKEN    (existing secret)           — required
//   ADS_STATS      (Durable Object namespace)  — required  -> class AdsStatsDO
//   ADS_AUTH_VERIFY_URL (var, optional)        — user-token verification endpoint
//   ADS_MEDIA_KEY  (secret, optional)          — AES-GCM key, phase (و)
// =============================================================================

const ADS_SETTINGS_OBJECT = "ads/settings.json";
const ADS_ADVERTISERS_OBJECT = "ads/advertisers.json";
const ADS_CAMPAIGNS_OBJECT = "ads/campaigns.json";
const ADS_CONSENTS_OBJECT = "ads/consents.json";
const ADS_CREATIVE_PREFIX = "ads/creatives/";
const ADS_MAX_CREATIVE_IMAGE_BYTES = 8 * 1024 * 1024;
const ADS_MAX_CREATIVE_VIDEO_BYTES = 40 * 1024 * 1024;
const ADS_SERVE_CACHE_MS = 20 * 1000;
const ADS_DEFAULT_AUTH_VERIFY_URL = "https://cigram-auth-api.wwq-mixtv.workers.dev/verify";

/** Slot ids are a closed set: the app ships one renderer per id. */
const ADS_SLOT_IDS = ["splash", "hero", "inline", "popup", "sticky", "sponsor"];

/** in-memory serve cache (per isolate, tiny, always re-validated by time) */
let adsServeCache = null;
let adsServeCacheAt = 0;

// -----------------------------------------------------------------------------
// errors
// -----------------------------------------------------------------------------

class AdsError extends Error {
  constructor(code, message, status, extra) {
    super(message || code);
    this.code = code || "ads_error";
    this.status = status || 400;
    this.extra = extra || null;
  }
}

function adsFail(code, message, status, extra) {
  return new AdsError(code, message, status, extra);
}

function adsErrorResponse(error) {
  if (error instanceof AdsError) {
    return json(
      Object.assign(
        {
          ok: false,
          success: false,
          error: error.code,
          error_code: String(error.code || "").toUpperCase(),
          message: error.message,
        },
        error.extra || {}
      ),
      error.status
    );
  }
  return json(
    {
      ok: false,
      success: false,
      error: "internal_error",
      error_code: "INTERNAL_ERROR",
      message: "خطأ داخلي في خادم الإعلانات.",
    },
    500
  );
}

// -----------------------------------------------------------------------------
// small utilities
// -----------------------------------------------------------------------------

function adsNow() {
  return Date.now();
}

function adsIso(ms) {
  return new Date(typeof ms === "number" ? ms : adsNow()).toISOString();
}

function adsParseTime(value) {
  if (value === null || value === undefined || value === "") return 0;
  if (typeof value === "number" && isFinite(value)) return value > 1e12 ? value : value * 1000;
  const t = Date.parse(String(value));
  return isFinite(t) ? t : 0;
}

/**
 * A missing value yields `def`; a present one is clamped into [lo, hi].
 * null / undefined / "" all count as MISSING — Number(null) is 0, so without this
 * an absent `?limit=` would read as 0 and clamp to the minimum, quietly
 * truncating every paginated list to one row.
 */
function adsInt(value, def, lo, hi) {
  if (value === null || value === undefined || value === "") return def;
  const n = Number(value);
  if (!isFinite(n)) return def;
  let v = Math.round(n);
  if (typeof lo === "number") v = Math.max(lo, v);
  if (typeof hi === "number") v = Math.min(hi, v);
  return v;
}

/** Same missing-vs-zero rule as adsInt, for money and percentages. */
function adsNum(value, def, lo, hi) {
  if (value === null || value === undefined || value === "") return def;
  const n = Number(value);
  if (!isFinite(n)) return def;
  let v = n;
  if (typeof lo === "number") v = Math.max(lo, v);
  if (typeof hi === "number") v = Math.min(hi, v);
  return Math.round(v * 100) / 100;
}

function adsBool(value, def) {
  if (value === true || value === false) return value;
  const s = cleanString(value).toLowerCase();
  if (s === "true" || s === "1" || s === "yes") return true;
  if (s === "false" || s === "0" || s === "no") return false;
  return Boolean(def);
}

/** Strips control characters and angle brackets: nothing stored can carry markup. */
function adsText(value, max) {
  let s = cleanString(value);
  s = s.replace(/[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]/g, "");
  s = s.replace(/[<>]/g, "");
  const limit = typeof max === "number" ? max : 2000;
  if (s.length > limit) s = s.slice(0, limit);
  return s;
}

function adsArray(value) {
  return Array.isArray(value) ? value : [];
}

function adsId(prefix) {
  const rnd = crypto.getRandomValues(new Uint8Array(9));
  let s = "";
  for (let i = 0; i < rnd.length; i++) s += rnd[i].toString(36).padStart(2, "0");
  return (prefix || "id") + "_" + Date.now().toString(36) + "_" + s.slice(0, 10);
}

function adsToken(bytes) {
  const rnd = crypto.getRandomValues(new Uint8Array(bytes || 24));
  let out = "";
  for (let i = 0; i < rnd.length; i++) out += rnd[i].toString(16).padStart(2, "0");
  return out;
}

function adsSlug(value) {
  return cleanString(value)
    .toLowerCase()
    .replace(/[^a-z0-9؀-ۿ]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 64);
}

/** Only https URLs may ever be stored or served. */
function adsSafeUrl(value, allowTel) {
  const s = cleanString(value);
  if (!s) return "";
  if (allowTel && /^tel:\+?[0-9\- ]{5,20}$/.test(s)) return s;
  if (!/^https:\/\//i.test(s)) return "";
  if (s.length > 1000) return "";
  try {
    const u = new URL(s);
    if (u.protocol !== "https:") return "";
    return u.toString();
  } catch {
    return "";
  }
}

/** Keeps digits and a single leading +, for WhatsApp / phone numbers. */
function adsPhone(value) {
  const s = cleanString(value).replace(/[^\d+]/g, "");
  if (!s) return "";
  const plus = s.startsWith("+");
  const digits = s.replace(/\+/g, "");
  if (digits.length < 6 || digits.length > 20) return "";
  return (plus ? "+" : "") + digits;
}

function adsDayKey(ms, tzOffsetMinutes) {
  const off = typeof tzOffsetMinutes === "number" ? tzOffsetMinutes : 180; // Asia/Riyadh
  const d = new Date((typeof ms === "number" ? ms : adsNow()) + off * 60000);
  return d.toISOString().slice(0, 10);
}

/** Non-reversible, salted-by-campaign device bucket — never stores a raw device id. */
async function adsHash(value) {
  const data = new TextEncoder().encode(String(value || ""));
  const digest = await crypto.subtle.digest("SHA-256", data);
  const view = new Uint8Array(digest);
  let out = "";
  for (let i = 0; i < 12; i++) out += view[i].toString(16).padStart(2, "0");
  return out;
}

/** "1.2.3" -> 1002003, so version ranges compare as plain numbers. */
function adsVersionNumber(value) {
  const parts = cleanString(value).split(".").map((p) => adsInt(p, 0, 0, 999));
  while (parts.length < 3) parts.push(0);
  return parts[0] * 1000000 + parts[1] * 1000 + parts[2];
}

// -----------------------------------------------------------------------------
// settings — defaults + normalisation
// -----------------------------------------------------------------------------

function adsDefaultSettings() {
  return {
    version: 1,
    updated_at: adsIso(),
    currency: "SAR",
    currency_label: "ر.س",
    policy_version: 1,
    policy_updated_at: adsIso(),
    hero: {
      headline: "أعلن عن شركتك أمام جمهور يشاهد كل يوم",
      sub: "مساحات إعلانية داخل التطبيق، بأسعار واضحة وبدون وسيط.",
      active_users: 0,
      daily_views: 0,
      auto_stats: false,
      stats_note: "أرقام محدّثة شهرياً",
    },
    slots: [
      {
        id: "splash",
        name: "إعلان الافتتاح",
        desc: "يظهر بملء الشاشة عند فتح التطبيق مع عدّاد وزر تخطي.",
        suits: "الحملات الكبيرة وإطلاق المنتجات.",
        base_price_day: 400,
        min_days: 1,
        max_days: 365,
        daily_cap_per_user: 1,
        est_views_per_day: 9000,
        enabled: true,
      },
      {
        id: "hero",
        name: "بانر الواجهة",
        desc: "بانر متحرك أعلى الصفحة الرئيسية مع نقاط مؤشر.",
        suits: "العلامات التجارية التي تريد حضوراً دائماً.",
        base_price_day: 300,
        min_days: 1,
        max_days: 365,
        daily_cap_per_user: 6,
        est_views_per_day: 14000,
        enabled: true,
      },
      {
        id: "inline",
        name: "بطاقة داخل القوائم",
        desc: "بطاقة بين صفوف المحتوى، بنفس شكل بطاقات التطبيق.",
        suits: "العروض والخدمات والمتاجر.",
        base_price_day: 180,
        min_days: 1,
        max_days: 365,
        daily_cap_per_user: 8,
        est_views_per_day: 11000,
        enabled: true,
      },
      {
        id: "popup",
        name: "نافذة منبثقة",
        desc: "تظهر مرة واحدة يومياً لكل مستخدم مع زر إغلاق واضح.",
        suits: "الإعلانات الموسمية والعروض المحدودة.",
        base_price_day: 350,
        min_days: 1,
        max_days: 365,
        daily_cap_per_user: 1,
        est_views_per_day: 7000,
        enabled: true,
      },
      {
        id: "sticky",
        name: "شريط ثابت",
        desc: "شريط سفلي رفيع قابل للإغلاق يبقى أثناء التصفح.",
        suits: "التذكير المستمر بالعلامة التجارية.",
        base_price_day: 220,
        min_days: 1,
        max_days: 365,
        daily_cap_per_user: 10,
        est_views_per_day: 16000,
        enabled: true,
      },
      {
        id: "sponsor",
        name: "برعاية",
        desc: "شعار الراعي بجانب عنوان قسم داخل التطبيق.",
        suits: "الرعاية طويلة المدى بميزانية هادئة.",
        base_price_day: 150,
        min_days: 7,
        max_days: 365,
        daily_cap_per_user: 12,
        est_views_per_day: 12000,
        enabled: true,
      },
    ],
    durations: [
      { id: "d1", days: 1, label: "يوم" },
      { id: "d3", days: 3, label: "٣ أيام" },
      { id: "d7", days: 7, label: "أسبوع" },
      { id: "d30", days: 30, label: "شهر" },
      { id: "d90", days: 90, label: "٣ أشهر" },
      { id: "d180", days: 180, label: "٦ أشهر" },
      { id: "d365", days: 365, label: "سنة" },
    ],
    discounts: [
      { min_days: 7, percent: 5 },
      { min_days: 30, percent: 12 },
      { min_days: 90, percent: 20 },
      { min_days: 180, percent: 27 },
      { min_days: 365, percent: 35 },
    ],
    addons: [
      {
        id: "exclusive",
        label: "حصرية المساحة",
        desc: "لا يظهر أي معلن آخر في نفس المساحة طوال مدة حملتك.",
        type: "percent",
        value: 60,
      },
      {
        id: "video",
        label: "إعلان فيديو",
        desc: "استخدام مقطع فيديو بدل الصورة الثابتة.",
        type: "percent",
        value: 25,
      },
      {
        id: "design",
        label: "تصميم من فريقنا",
        desc: "نصمم لك المادة الإعلانية كاملة.",
        type: "fixed",
        value: 350,
      },
    ],
    targeting: {
      cities: ["الرياض", "جدة", "مكة المكرمة", "المدينة المنورة", "الدمام", "الخبر", "أبها", "تبوك", "بريدة", "حائل"],
      languages: [
        { id: "ar", label: "العربية" },
        { id: "en", label: "الإنجليزية" },
      ],
      city_surcharge_percent: 10,
      hours_surcharge_percent: 8,
      lang_surcharge_percent: 0,
    },
    accepted: {
      title: "إعلانات نقبلها",
      items: [
        { title: "منتجات وخدمات قانونية", desc: "متاجر، مطاعم، تطبيقات، عقار، تعليم، سياحة." },
        { title: "محتوى صادق", desc: "ما تعرضه في الإعلان هو ما يحصل عليه العميل فعلاً." },
        { title: "صور بجودة عالية", desc: "تصميم واضح، نص مقروء، شعار ظاهر." },
        { title: "عرض سعر أو خصم حقيقي", desc: "العرض ساري فعلاً خلال مدة الحملة." },
      ],
    },
    rejected: {
      title: "إعلانات نرفضها",
      items: [
        { title: "المحتوى الجنسي أو الإيحائي", desc: "مرفوض تماماً بكل أشكاله." },
        { title: "الكحول والمخدرات والتبغ", desc: "بما في ذلك البدائل والمنتجات المشابهة." },
        { title: "القمار والمراهنات", desc: "بما في ذلك تطبيقات الحظ والجوائز المشروطة بالدفع." },
        { title: "العنف والكراهية", desc: "أي تحريض ضد شخص أو فئة أو دين أو قومية." },
        { title: "المنتجات المقلدة", desc: "التقليد والنسخ غير المرخصة." },
        { title: "الادعاءات الكاذبة", desc: "وعود طبية أو مالية غير مثبتة." },
        { title: "انتحال الهوية", desc: "استخدام اسم أو شعار جهة لا تملكها." },
        { title: "المحتوى السياسي المحرّض", desc: "الدعاية الحزبية والتحريض السياسي." },
        { title: "إعلان يحاكي واجهة التطبيق", desc: "تصميم يوهم المستخدم أنه زر أو إشعار داخل التطبيق." },
      ],
    },
    policies: [
      {
        id: "ads_policy",
        title: "سياسة الإعلانات",
        body:
          "كل إعلان يمر بمراجعة يدوية قبل النشر. لنا الحق في رفض أو إيقاف أي إعلان يخالف هذه السياسة في أي وقت، " +
          "مع إبلاغك بالسبب. المواد الإعلانية مسؤولية المعلن، ويجب أن تكون مملوكة له أو مرخصة.",
      },
      {
        id: "privacy",
        title: "الخصوصية",
        body:
          "بيانات مستخدمي التطبيق لا تُشارك ولا تُباع للمعلنين أبداً، ولا نسلّم لك أي رقم أو بريد أو اسم مستخدم. " +
          "ما تحصل عليه هو أرقام مجمّعة فقط (مشاهدات، نقرات، نسبة نقر). " +
          "نحفظ من بياناتك: اسم الشركة والتواصل وسجل حملاتك، ومحتوى محادثتك معنا لتوثيق الاتفاق وحل الخلافات. " +
          "تُخزَّن الرسائل والوسائط مشفّرة أثناء التخزين، ويمكنك طلب حذف محادثتك وحسابك في أي وقت.",
      },
      {
        id: "terms",
        title: "الشروط والأحكام",
        body:
          "الحجز يصبح نهائياً بعد تأكيد الدفع. مدة الحملة تُحسب بالأيام من تاريخ بدء التشغيل الفعلي. " +
          "لا نضمن عدداً محدداً من المشاهدات أو النقرات؛ الأرقام المعروضة تقديرية مبنية على متوسطات سابقة.",
      },
      {
        id: "payment",
        title: "الدفع والاسترجاع والإلغاء",
        body:
          "الدفع يدوي حالياً عبر تحويل بنكي، ويُسجَّل في لوحة الإدارة. " +
          "الإلغاء قبل بدء الحملة: استرجاع كامل. بعد البدء: يُسترجع ما يقابل الأيام غير المستهلكة. " +
          "إيقاف الحملة بسبب مخالفة السياسة لا يستوجب استرجاعاً.",
      },
      {
        id: "ip",
        title: "حقوق الملكية والمواد الإعلانية",
        body:
          "تقر بأنك تملك حقوق الصور والفيديو والنصوص التي ترسلها، أو لديك ترخيص باستخدامها. " +
          "تمنحنا حق عرضها داخل التطبيق طوال مدة الحملة فقط. " +
          "التصاميم التي ينفذها فريقنا تبقى مرخّصة لك للاستخدام داخل التطبيق.",
      },
    ],
    faq: [
      { q: "كم يستغرق تشغيل الإعلان؟", a: "من ساعة إلى ٢٤ ساعة بعد المراجعة وتأكيد الدفع." },
      { q: "هل أستطيع تعديل الإعلان بعد النشر؟", a: "نعم، أرسل المادة الجديدة في المحادثة وتمر بمراجعة سريعة." },
      { q: "هل تظهر أرقام حملتي مباشرة؟", a: "نعم، من «إعلاناتي» ترى المشاهدات والنقرات ورسماً يومياً." },
      { q: "ماذا لو لم يكن لدي تصميم؟", a: "اختر إضافة «تصميم من فريقنا» في الحاسبة ونتولى ذلك." },
      { q: "هل تبيعون بيانات المستخدمين؟", a: "لا. لا نشارك أي بيانات شخصية مع أي معلن." },
    ],
    contact: {
      inapp_enabled: true,
      whatsapp: "",
      whatsapp_template: "السلام عليكم، أرغب بالإعلان داخل التطبيق.",
      x_url: "",
      email: "",
    },
    hours: {
      timezone_offset_minutes: 180,
      timezone_label: "بتوقيت الرياض",
      open_from: "09:00",
      open_to: "23:00",
      days: [1, 2, 3, 4, 5, 6, 0],
      auto_reply: "شكراً لتواصلك. فريق الإعلانات خارج ساعات العمل الآن وسنرد عليك فور بدء الدوام.",
      offline_note: "ساعات العمل: ٩ صباحاً حتى ١١ مساءً",
    },
    presence: {
      status: "offline",
      updated_at: adsIso(0),
      note: "",
    },
  };
}

function adsNormalizeSlot(raw, fallback) {
  const base = fallback || {};
  const o = raw && typeof raw === "object" ? raw : {};
  const id = ADS_SLOT_IDS.indexOf(cleanString(o.id)) >= 0 ? cleanString(o.id) : cleanString(base.id);
  if (!id) return null;
  return {
    id,
    name: adsText(o.name || base.name, 60),
    desc: adsText(o.desc || base.desc, 400),
    suits: adsText(o.suits || base.suits, 200),
    base_price_day: adsNum(o.base_price_day, base.base_price_day || 0, 0, 1000000),
    min_days: adsInt(o.min_days, base.min_days || 1, 1, 365),
    max_days: adsInt(o.max_days, base.max_days || 365, 1, 3650),
    daily_cap_per_user: adsInt(o.daily_cap_per_user, base.daily_cap_per_user || 0, 0, 100),
    est_views_per_day: adsInt(o.est_views_per_day, base.est_views_per_day || 0, 0, 100000000),
    enabled: adsBool(o.enabled, base.enabled !== false),
  };
}

function adsNormalizeSettings(doc) {
  const def = adsDefaultSettings();
  const s = doc && typeof doc === "object" && !Array.isArray(doc) ? doc : {};

  const slots = [];
  for (const id of ADS_SLOT_IDS) {
    const incoming = adsArray(s.slots).find((x) => x && cleanString(x.id) === id);
    const fallback = def.slots.find((x) => x.id === id);
    const slot = adsNormalizeSlot(incoming, fallback);
    if (slot) slots.push(slot);
  }

  const hero = s.hero && typeof s.hero === "object" ? s.hero : {};
  const contact = s.contact && typeof s.contact === "object" ? s.contact : {};
  const hours = s.hours && typeof s.hours === "object" ? s.hours : {};
  const presence = s.presence && typeof s.presence === "object" ? s.presence : {};
  const targeting = s.targeting && typeof s.targeting === "object" ? s.targeting : {};

  const durations = adsArray(s.durations).length
    ? adsArray(s.durations)
        .map((d) => ({
          id: adsText(d && d.id, 20) || "d" + adsInt(d && d.days, 1, 1, 3650),
          days: adsInt(d && d.days, 1, 1, 3650),
          label: adsText(d && d.label, 40),
        }))
        .filter((d) => d.days > 0)
        .slice(0, 20)
    : def.durations;

  const discounts = adsArray(s.discounts).length
    ? adsArray(s.discounts)
        .map((d) => ({
          min_days: adsInt(d && d.min_days, 1, 1, 3650),
          percent: adsNum(d && d.percent, 0, 0, 90),
        }))
        .sort((a, b) => a.min_days - b.min_days)
        .slice(0, 20)
    : def.discounts;

  const addons = adsArray(s.addons).length
    ? adsArray(s.addons)
        .map((a) => ({
          id: adsSlug(a && a.id) || adsId("addon"),
          label: adsText(a && a.label, 60),
          desc: adsText(a && a.desc, 300),
          type: cleanString(a && a.type) === "fixed" ? "fixed" : "percent",
          value: adsNum(a && a.value, 0, 0, 1000000),
        }))
        .slice(0, 20)
    : def.addons;

  const textGroup = (incoming, fallback) => {
    const g = incoming && typeof incoming === "object" ? incoming : {};
    const items = adsArray(g.items)
      .map((i) => ({ title: adsText(i && i.title, 120), desc: adsText(i && i.desc, 400) }))
      .filter((i) => i.title.length > 0)
      .slice(0, 40);
    return {
      title: adsText(g.title || fallback.title, 120),
      items: items.length ? items : fallback.items,
    };
  };

  const policies = adsArray(s.policies).length
    ? adsArray(s.policies)
        .map((p) => ({
          id: adsSlug(p && p.id) || adsId("pol"),
          title: adsText(p && p.title, 120),
          body: adsText(p && p.body, 8000),
        }))
        .filter((p) => p.title.length > 0)
        .slice(0, 30)
    : def.policies;

  const faq = adsArray(s.faq).length
    ? adsArray(s.faq)
        .map((f) => ({ q: adsText(f && f.q, 200), a: adsText(f && f.a, 2000) }))
        .filter((f) => f.q.length > 0)
        .slice(0, 50)
    : def.faq;

  const presenceStatus = ["online", "offline", "away"].indexOf(cleanString(presence.status)) >= 0
    ? cleanString(presence.status)
    : "offline";

  return {
    version: 1,
    updated_at: adsText(s.updated_at, 40) || adsIso(),
    currency: adsText(s.currency, 10) || def.currency,
    currency_label: adsText(s.currency_label, 10) || def.currency_label,
    policy_version: adsInt(s.policy_version, def.policy_version, 1, 100000),
    policy_updated_at: adsText(s.policy_updated_at, 40) || def.policy_updated_at,
    hero: {
      headline: adsText(hero.headline || def.hero.headline, 160),
      sub: adsText(hero.sub || def.hero.sub, 300),
      active_users: adsInt(hero.active_users, def.hero.active_users, 0, 1000000000),
      daily_views: adsInt(hero.daily_views, def.hero.daily_views, 0, 1000000000),
      auto_stats: adsBool(hero.auto_stats, def.hero.auto_stats),
      stats_note: adsText(hero.stats_note || def.hero.stats_note, 120),
    },
    slots,
    durations,
    discounts,
    addons,
    targeting: {
      cities: adsArray(targeting.cities).length
        ? adsArray(targeting.cities).map((c) => adsText(c, 60)).filter(Boolean).slice(0, 200)
        : def.targeting.cities,
      languages: adsArray(targeting.languages).length
        ? adsArray(targeting.languages)
            .map((l) => ({ id: adsSlug(l && l.id) || "ar", label: adsText(l && l.label, 40) }))
            .slice(0, 20)
        : def.targeting.languages,
      city_surcharge_percent: adsNum(targeting.city_surcharge_percent, def.targeting.city_surcharge_percent, 0, 200),
      hours_surcharge_percent: adsNum(targeting.hours_surcharge_percent, def.targeting.hours_surcharge_percent, 0, 200),
      lang_surcharge_percent: adsNum(targeting.lang_surcharge_percent, def.targeting.lang_surcharge_percent, 0, 200),
    },
    accepted: textGroup(s.accepted, def.accepted),
    rejected: textGroup(s.rejected, def.rejected),
    policies,
    faq,
    contact: {
      inapp_enabled: adsBool(contact.inapp_enabled, true),
      whatsapp: adsPhone(contact.whatsapp),
      whatsapp_template: adsText(contact.whatsapp_template || def.contact.whatsapp_template, 500),
      x_url: adsSafeUrl(contact.x_url),
      email: adsText(contact.email, 160).replace(/\s/g, ""),
    },
    hours: {
      timezone_offset_minutes: adsInt(hours.timezone_offset_minutes, def.hours.timezone_offset_minutes, -720, 840),
      timezone_label: adsText(hours.timezone_label || def.hours.timezone_label, 60),
      open_from: /^\d{2}:\d{2}$/.test(cleanString(hours.open_from)) ? cleanString(hours.open_from) : def.hours.open_from,
      open_to: /^\d{2}:\d{2}$/.test(cleanString(hours.open_to)) ? cleanString(hours.open_to) : def.hours.open_to,
      days: adsArray(hours.days).length
        ? adsArray(hours.days).map((d) => adsInt(d, 0, 0, 6)).slice(0, 7)
        : def.hours.days,
      auto_reply: adsText(hours.auto_reply || def.hours.auto_reply, 1000),
      offline_note: adsText(hours.offline_note || def.hours.offline_note, 200),
    },
    presence: {
      status: presenceStatus,
      updated_at: adsText(presence.updated_at, 40) || adsIso(0),
      note: adsText(presence.note, 200),
    },
  };
}

async function adsReadSettings(env) {
  const raw = await readJsonFile(env, ADS_SETTINGS_OBJECT, null);
  return adsNormalizeSettings(raw);
}

async function adsWriteSettings(env, settings) {
  const doc = adsNormalizeSettings(settings);
  doc.updated_at = adsIso();
  await writeJsonFile(env, ADS_SETTINGS_OBJECT, doc);
  return doc;
}

/** True when "now" falls inside the configured working hours. */
function adsWithinHours(settings, atMs) {
  try {
    const h = settings.hours;
    const local = new Date((typeof atMs === "number" ? atMs : adsNow()) + h.timezone_offset_minutes * 60000);
    const day = local.getUTCDay();
    if (h.days.indexOf(day) < 0) return false;
    const minutes = local.getUTCHours() * 60 + local.getUTCMinutes();
    const [fh, fm] = h.open_from.split(":").map((x) => adsInt(x, 0, 0, 59));
    const [th, tm] = h.open_to.split(":").map((x) => adsInt(x, 0, 0, 59));
    const from = fh * 60 + fm;
    const to = th * 60 + tm;
    if (from === to) return true;
    if (from < to) return minutes >= from && minutes < to;
    return minutes >= from || minutes < to; // span over midnight
  } catch {
    return false;
  }
}

/** The presence the advertiser actually sees: admin flag narrowed by working hours. */
function adsEffectivePresence(settings, atMs) {
  const open = adsWithinHours(settings, atMs);
  const raw = settings.presence.status;
  let status = raw;
  if (raw === "online" && !open) status = "away";
  if (raw !== "online" && !open) status = "offline";
  return {
    status,
    raw_status: raw,
    within_hours: open,
    updated_at: settings.presence.updated_at,
    note: settings.presence.note,
    offline_note: settings.hours.offline_note,
    auto_reply: open ? "" : settings.hours.auto_reply,
    hours_label: settings.hours.timezone_label,
    open_from: settings.hours.open_from,
    open_to: settings.hours.open_to,
  };
}

// -----------------------------------------------------------------------------
// pricing engine — the ONLY place a price is computed (the app never does maths)
// -----------------------------------------------------------------------------

/**
 * @param {object} settings normalised settings
 * @param {object} req { slot, days, cities[], langs[], hours[], addons[] }
 * @returns {object} full breakdown, always in settings.currency
 */
function adsQuote(settings, req) {
  const slotId = cleanString(req && req.slot);
  const slot = settings.slots.find((s) => s.id === slotId && s.enabled);
  if (!slot) throw adsFail("invalid_slot", "المساحة الإعلانية غير متاحة.", 400);

  const days = adsInt(req && req.days, slot.min_days, slot.min_days, slot.max_days);
  const cities = adsArray(req && req.cities).map((c) => adsText(c, 60)).filter(Boolean).slice(0, 50);
  const langs = adsArray(req && req.langs).map((l) => adsSlug(l)).filter(Boolean).slice(0, 10);
  const hours = adsArray(req && req.hours).map((h) => adsInt(h, -1, 0, 23)).filter((h) => h >= 0).slice(0, 24);
  const wantedAddons = adsArray(req && req.addons).map((a) => adsSlug(a)).filter(Boolean).slice(0, 20);

  const base = Math.round(slot.base_price_day * days * 100) / 100;

  let discountPercent = 0;
  for (const d of settings.discounts) {
    if (days >= d.min_days && d.percent > discountPercent) discountPercent = d.percent;
  }
  const discountValue = Math.round(base * (discountPercent / 100) * 100) / 100;
  const afterDiscount = Math.round((base - discountValue) * 100) / 100;

  const targetingLines = [];
  let targetingValue = 0;
  if (cities.length > 0 && settings.targeting.city_surcharge_percent > 0) {
    const v = Math.round(afterDiscount * (settings.targeting.city_surcharge_percent / 100) * 100) / 100;
    targetingValue += v;
    targetingLines.push({ id: "cities", label: "استهداف مدن محددة", value: v });
  }
  if (hours.length > 0 && hours.length < 24 && settings.targeting.hours_surcharge_percent > 0) {
    const v = Math.round(afterDiscount * (settings.targeting.hours_surcharge_percent / 100) * 100) / 100;
    targetingValue += v;
    targetingLines.push({ id: "hours", label: "استهداف ساعات محددة", value: v });
  }
  if (langs.length > 0 && settings.targeting.lang_surcharge_percent > 0) {
    const v = Math.round(afterDiscount * (settings.targeting.lang_surcharge_percent / 100) * 100) / 100;
    targetingValue += v;
    targetingLines.push({ id: "langs", label: "استهداف لغة محددة", value: v });
  }
  targetingValue = Math.round(targetingValue * 100) / 100;

  const addonLines = [];
  let addonsValue = 0;
  for (const id of wantedAddons) {
    const addon = settings.addons.find((a) => a.id === id);
    if (!addon) continue;
    const v =
      addon.type === "fixed"
        ? Math.round(addon.value * 100) / 100
        : Math.round(afterDiscount * (addon.value / 100) * 100) / 100;
    addonsValue += v;
    addonLines.push({ id: addon.id, label: addon.label, type: addon.type, value: v });
  }
  addonsValue = Math.round(addonsValue * 100) / 100;

  const total = Math.round((afterDiscount + targetingValue + addonsValue) * 100) / 100;

  // Estimated views: slot average, narrowed when the campaign targets a subset.
  let reach = 1;
  if (cities.length > 0) reach *= Math.min(1, 0.22 + 0.11 * cities.length);
  if (hours.length > 0 && hours.length < 24) reach *= Math.max(0.15, hours.length / 24);
  const estViews = Math.max(0, Math.round(slot.est_views_per_day * days * reach));

  return {
    currency: settings.currency,
    currency_label: settings.currency_label,
    slot: slot.id,
    slot_name: slot.name,
    days,
    cities,
    langs,
    hours,
    addons: addonLines,
    base_price_day: slot.base_price_day,
    base,
    discount_percent: discountPercent,
    discount_value: discountValue,
    after_discount: afterDiscount,
    targeting_lines: targetingLines,
    targeting_value: targetingValue,
    addons_value: addonsValue,
    total,
    est_views: estViews,
    est_views_note: "تقدير مبني على متوسط مشاهدات هذه المساحة، وليس ضماناً.",
    quoted_at: adsIso(),
  };
}

// -----------------------------------------------------------------------------
// advertisers
// -----------------------------------------------------------------------------

function adsEmptyAdvertisers() {
  return { version: 1, updated_at: adsIso(), items: [] };
}

function adsNormalizeAdvertiser(raw, previous) {
  const o = raw && typeof raw === "object" ? raw : {};
  const prev = previous || {};
  const name = adsText(o.name !== undefined ? o.name : prev.name, 120);
  if (!name) throw adsFail("invalid_advertiser", "اسم الشركة مطلوب.", 400);
  return {
    id: cleanString(prev.id) || cleanString(o.id) || adsId("adv"),
    name,
    logo_url: adsSafeUrl(o.logo_url !== undefined ? o.logo_url : prev.logo_url),
    contact_name: adsText(o.contact_name !== undefined ? o.contact_name : prev.contact_name, 120),
    phone: adsPhone(o.phone !== undefined ? o.phone : prev.phone),
    email: adsText(o.email !== undefined ? o.email : prev.email, 160).replace(/\s/g, ""),
    website: adsSafeUrl(o.website !== undefined ? o.website : prev.website),
    /** Links this company file to a logged-in app account (so the chat/"إعلاناتي" can find it). */
    user_id: adsText(o.user_id !== undefined ? o.user_id : prev.user_id, 80),
    note: adsText(o.note !== undefined ? o.note : prev.note, 2000),
    blocked: adsBool(o.blocked !== undefined ? o.blocked : prev.blocked, false),
    total_paid: adsNum(o.total_paid !== undefined ? o.total_paid : prev.total_paid, 0, 0, 1000000000),
    created_at: cleanString(prev.created_at) || adsIso(),
    updated_at: adsIso(),
  };
}

async function adsReadAdvertisers(env) {
  const raw = await readJsonFile(env, ADS_ADVERTISERS_OBJECT, null);
  if (!raw || typeof raw !== "object") return adsEmptyAdvertisers();
  const items = adsArray(raw.items)
    .map((a) => {
      try {
        return adsNormalizeAdvertiser(a, a);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
  return { version: 1, updated_at: cleanString(raw.updated_at) || adsIso(), items };
}

async function adsWriteAdvertisers(env, doc) {
  const out = { version: 1, updated_at: adsIso(), items: adsArray(doc.items).slice(0, 5000) };
  await writeJsonFile(env, ADS_ADVERTISERS_OBJECT, out);
  return out;
}

/** Finds (or creates) the advertiser file attached to a logged-in app account. */
async function adsAdvertiserForUser(env, user, companyName) {
  const doc = await adsReadAdvertisers(env);
  const uid = cleanString(user && user.id);
  let found = uid ? doc.items.find((a) => a.user_id === uid) : null;
  if (!found && user && user.email) {
    const email = cleanString(user.email).toLowerCase();
    found = doc.items.find((a) => cleanString(a.email).toLowerCase() === email);
    if (found && uid && !found.user_id) {
      found.user_id = uid;
      await adsWriteAdvertisers(env, doc);
    }
  }
  if (found) return found;
  const created = adsNormalizeAdvertiser(
    {
      name: adsText(companyName, 120) || adsText(user && (user.name || user.email), 120) || "معلن جديد",
      email: cleanString(user && user.email),
      user_id: uid,
    },
    null
  );
  doc.items.push(created);
  await adsWriteAdvertisers(env, doc);
  return created;
}

// -----------------------------------------------------------------------------
// campaigns + creatives
// -----------------------------------------------------------------------------

const ADS_STATUSES = [
  "draft",
  "pending_review",
  "approved",
  "scheduled",
  "active",
  "paused",
  "ended",
  "rejected",
];

const ADS_ACTION_TYPES = ["none", "url", "whatsapp", "call", "store", "coupon"];

function adsNormalizeCreative(raw, previous) {
  const o = raw && typeof raw === "object" ? raw : {};
  const prev = previous || {};
  const type = cleanString(o.type || prev.type) === "video" ? "video" : "image";
  const url = adsSafeUrl(o.url !== undefined ? o.url : prev.url);
  if (!url) throw adsFail("invalid_creative", "رابط المادة الإعلانية غير صالح (يجب أن يبدأ بـ https).", 400);

  const actionRaw = o.action && typeof o.action === "object" ? o.action : prev.action || {};
  let actionType = cleanString(actionRaw.type);
  if (ADS_ACTION_TYPES.indexOf(actionType) < 0) actionType = "none";
  let actionValue = "";
  if (actionType === "url" || actionType === "store") actionValue = adsSafeUrl(actionRaw.value);
  else if (actionType === "whatsapp" || actionType === "call") actionValue = adsPhone(actionRaw.value);
  else if (actionType === "coupon") actionValue = adsText(actionRaw.value, 40);
  if (actionType !== "none" && !actionValue) actionType = "none";

  return {
    id: cleanString(prev.id) || cleanString(o.id) || adsId("crv"),
    type,
    url,
    thumb_url: adsSafeUrl(o.thumb_url !== undefined ? o.thumb_url : prev.thumb_url),
    width: adsInt(o.width !== undefined ? o.width : prev.width, 0, 0, 10000),
    height: adsInt(o.height !== undefined ? o.height : prev.height, 0, 0, 10000),
    duration_ms: adsInt(o.duration_ms !== undefined ? o.duration_ms : prev.duration_ms, 0, 0, 600000),
    title: adsText(o.title !== undefined ? o.title : prev.title, 80),
    body: adsText(o.body !== undefined ? o.body : prev.body, 240),
    cta_label: adsText(o.cta_label !== undefined ? o.cta_label : prev.cta_label, 30),
    action: { type: actionType, value: actionValue },
  };
}

function adsNormalizeCampaign(raw, previous) {
  const o = raw && typeof raw === "object" ? raw : {};
  const prev = previous || {};

  const slot = ADS_SLOT_IDS.indexOf(cleanString(o.slot || prev.slot)) >= 0 ? cleanString(o.slot || prev.slot) : "";
  if (!slot) throw adsFail("invalid_slot", "المساحة الإعلانية غير صحيحة.", 400);

  const advertiserId = cleanString(o.advertiser_id !== undefined ? o.advertiser_id : prev.advertiser_id);
  if (!advertiserId) throw adsFail("invalid_advertiser", "يجب ربط الحملة بمعلن.", 400);

  const startAt = adsParseTime(o.start_at !== undefined ? o.start_at : prev.start_at);
  const endAt = adsParseTime(o.end_at !== undefined ? o.end_at : prev.end_at);
  if (startAt && endAt && endAt <= startAt) {
    throw adsFail("invalid_range", "تاريخ النهاية يجب أن يكون بعد تاريخ البداية.", 400);
  }

  const creativesRaw = o.creatives !== undefined ? adsArray(o.creatives) : adsArray(prev.creatives);
  const prevCreatives = adsArray(prev.creatives);
  const creatives = creativesRaw
    .slice(0, 10)
    .map((c) => adsNormalizeCreative(c, prevCreatives.find((p) => p.id === cleanString(c && c.id)) || null));

  let status = cleanString(o.status !== undefined ? o.status : prev.status);
  if (ADS_STATUSES.indexOf(status) < 0) status = "draft";

  const reviewRaw = o.review && typeof o.review === "object" ? o.review : prev.review || {};
  const paymentRaw = o.payment && typeof o.payment === "object" ? o.payment : prev.payment || {};
  const targetingRaw = o.targeting && typeof o.targeting === "object" ? o.targeting : prev.targeting || {};

  let paymentStatus = cleanString(paymentRaw.status);
  if (["unpaid", "partial", "paid", "refunded"].indexOf(paymentStatus) < 0) paymentStatus = "unpaid";

  return {
    id: cleanString(prev.id) || cleanString(o.id) || adsId("cmp"),
    advertiser_id: advertiserId,
    slot,
    title: adsText(o.title !== undefined ? o.title : prev.title, 120) || "حملة إعلانية",
    start_at: startAt ? adsIso(startAt) : "",
    end_at: endAt ? adsIso(endAt) : "",
    days: adsInt(o.days !== undefined ? o.days : prev.days, 0, 0, 3650),
    quote: o.quote && typeof o.quote === "object" ? o.quote : prev.quote || null,
    price_final: adsNum(o.price_final !== undefined ? o.price_final : prev.price_final, 0, 0, 100000000),
    currency: adsText(o.currency !== undefined ? o.currency : prev.currency, 10) || "SAR",
    payment: {
      status: paymentStatus,
      amount_paid: adsNum(paymentRaw.amount_paid, 0, 0, 100000000),
      method: adsText(paymentRaw.method, 60),
      note: adsText(paymentRaw.note, 1000),
      updated_at: adsIso(),
    },
    priority: adsInt(o.priority !== undefined ? o.priority : prev.priority, 5, 1, 10),
    exclusive: adsBool(o.exclusive !== undefined ? o.exclusive : prev.exclusive, false),
    daily_cap_per_user: adsInt(
      o.daily_cap_per_user !== undefined ? o.daily_cap_per_user : prev.daily_cap_per_user,
      0,
      0,
      100
    ),
    targeting: {
      cities: adsArray(targetingRaw.cities).map((c) => adsText(c, 60)).filter(Boolean).slice(0, 100),
      langs: adsArray(targetingRaw.langs).map((l) => adsSlug(l)).filter(Boolean).slice(0, 10),
      hours: adsArray(targetingRaw.hours).map((h) => adsInt(h, -1, 0, 23)).filter((h) => h >= 0).slice(0, 24),
      min_app_version: adsText(targetingRaw.min_app_version, 20),
      max_app_version: adsText(targetingRaw.max_app_version, 20),
    },
    creatives,
    status,
    review: {
      approved: adsBool(reviewRaw.approved, false),
      by: adsText(reviewRaw.by, 80),
      at: adsText(reviewRaw.at, 40),
      reject_reason: adsText(reviewRaw.reject_reason, 1000),
    },
    report_token: cleanString(prev.report_token) || adsToken(16),
    expiry_notified: adsBool(o.expiry_notified !== undefined ? o.expiry_notified : prev.expiry_notified, false),
    created_at: cleanString(prev.created_at) || adsIso(),
    updated_at: adsIso(),
  };
}

async function adsReadCampaigns(env) {
  const raw = await readJsonFile(env, ADS_CAMPAIGNS_OBJECT, null);
  if (!raw || typeof raw !== "object") return { version: 1, updated_at: adsIso(), items: [] };
  const items = adsArray(raw.items)
    .map((c) => {
      try {
        return adsNormalizeCampaign(c, c);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
  return { version: 1, updated_at: cleanString(raw.updated_at) || adsIso(), items };
}

async function adsWriteCampaigns(env, doc) {
  const out = { version: 1, updated_at: adsIso(), items: adsArray(doc.items).slice(0, 5000) };
  await writeJsonFile(env, ADS_CAMPAIGNS_OBJECT, out);
  adsServeCache = null;
  return out;
}

/**
 * The status the rest of the system must obey. Dates win over the stored label,
 * so an expired campaign is "ended" even if nobody ran the cron yet.
 */
function adsEffectiveStatus(campaign, atMs) {
  const now = typeof atMs === "number" ? atMs : adsNow();
  if (campaign.status === "rejected") return "rejected";
  if (campaign.status === "draft") return "draft";
  if (campaign.status === "paused") return "paused";
  // draft / paused / rejected already returned above: anything else unapproved is waiting on review.
  if (!campaign.review.approved) return "pending_review";
  const start = adsParseTime(campaign.start_at);
  const end = adsParseTime(campaign.end_at);
  if (end && now >= end) return "ended";
  if (start && now < start) return "scheduled";
  if (!start) return "approved";
  return "active";
}

/** Serving gate: approved + inside its window + not paused/rejected + has a creative. */
function adsIsServable(campaign, atMs) {
  if (adsEffectiveStatus(campaign, atMs) !== "active") return false;
  if (!adsArray(campaign.creatives).length) return false;
  return true;
}

function adsMatchesTargeting(campaign, ctx) {
  const t = campaign.targeting || {};
  if (adsArray(t.cities).length > 0) {
    const city = cleanString(ctx.city);
    if (!city) return false;
    if (!t.cities.some((c) => cleanString(c) === city)) return false;
  }
  if (adsArray(t.langs).length > 0) {
    const lang = adsSlug(ctx.lang);
    if (!lang || t.langs.indexOf(lang) < 0) return false;
  }
  if (adsArray(t.hours).length > 0 && t.hours.length < 24) {
    const offset = typeof ctx.tzOffsetMinutes === "number" ? ctx.tzOffsetMinutes : 180;
    const local = new Date(ctx.now + offset * 60000);
    if (t.hours.indexOf(local.getUTCHours()) < 0) return false;
  }
  const v = adsVersionNumber(ctx.appVersion);
  if (t.min_app_version && v > 0 && v < adsVersionNumber(t.min_app_version)) return false;
  if (t.max_app_version && v > 0 && v > adsVersionNumber(t.max_app_version)) return false;
  return true;
}

// -----------------------------------------------------------------------------
// user authentication (app accounts live in cigram-auth-api)
// -----------------------------------------------------------------------------

/**
 * Verifies the app user's bearer token against the auth Worker.
 * ADS_AUTH_VERIFY_URL must answer POST {token} with {ok:true,user:{id,email,name}}.
 * See worker/auth-verify-snippet.js for the route to add there.
 * @returns {Promise<{id:string,email:string,name:string}>}
 */
async function adsRequireUser(request, env) {
  const bearer = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
  if (!bearer || bearer.length < 8 || bearer.length > 4096) {
    throw adsFail("unauthorized", "يلزم تسجيل الدخول للمتابعة.", 401);
  }
  const url = cleanString(env.ADS_AUTH_VERIFY_URL) || ADS_DEFAULT_AUTH_VERIFY_URL;
  let res;
  try {
    res = await fetch(url, {
      method: "POST",
      headers: {
        "content-type": "application/json; charset=utf-8",
        authorization: "Bearer " + bearer,
      },
      body: JSON.stringify({ token: bearer }),
    });
  } catch {
    throw adsFail("auth_unavailable", "تعذر التحقق من حسابك الآن. أعد المحاولة بعد قليل.", 503);
  }
  if (!res.ok) throw adsFail("unauthorized", "انتهت جلستك. سجّل الدخول من جديد.", 401);
  let body;
  try {
    body = await res.json();
  } catch {
    throw adsFail("auth_unavailable", "تعذر التحقق من حسابك الآن.", 503);
  }
  const user = body && (body.user || body.data || body);
  const id = cleanString(user && (user.id || user.user_id));
  if (!body || body.ok === false || !id) throw adsFail("unauthorized", "انتهت جلستك. سجّل الدخول من جديد.", 401);
  return {
    id,
    email: cleanString(user.email).toLowerCase(),
    name: adsText(user.name || user.username, 120),
  };
}

/**
 * Admin wrapper for the ads routes. `adminRoute()` above only knows how to render
 * AdminError, so an AdsError thrown in here would surface as a bare 500 with the
 * Arabic text buried in it. This keeps the same auth but renders our own errors.
 */
function adsAdminRoute(handler) {
  return async (request, env, url, ctx) => {
    if (!adminAuthorized(request, env)) {
      return json(
        { ok: false, success: false, error: "unauthorized", error_code: "UNAUTHORIZED", message: "رمز الإدارة غير صحيح أو مفقود." },
        401
      );
    }
    try {
      return await handler(request, env, url, ctx);
    } catch (error) {
      if (error instanceof AdsError) return adsErrorResponse(error);
      return adminErrorResponse(error);
    }
  };
}

function adsUserRoute(handler) {
  return async (request, env, url, ctx) => {
    try {
      const user = await adsRequireUser(request, env);
      return await handler(request, env, url, user, ctx);
    } catch (error) {
      return adsErrorResponse(error);
    }
  };
}

function adsPublicRoute(handler) {
  return async (request, env, url, ctx) => {
    try {
      return await handler(request, env, url, ctx);
    } catch (error) {
      return adsErrorResponse(error);
    }
  };
}

// -----------------------------------------------------------------------------
// stats Durable Object accessor
// -----------------------------------------------------------------------------

function adsStatsStub(env) {
  if (!env.ADS_STATS) throw adsFail("missing_binding", "ربط ADS_STATS غير مهيأ في الـ Worker.", 500);
  return env.ADS_STATS.get(env.ADS_STATS.idFromName("ads-stats-v1"));
}

/** Calls the stats DO with a JSON body; never throws on a transport error. */
async function adsStatsCall(env, path, body) {
  try {
    const stub = adsStatsStub(env);
    const res = await stub.fetch("https://ads-stats.internal" + path, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body || {}),
    });
    const text = await res.text();
    try {
      return JSON.parse(text);
    } catch {
      return { ok: false };
    }
  } catch {
    return { ok: false };
  }
}

// -----------------------------------------------------------------------------
// GET /ads — the serving endpoint the app calls
// -----------------------------------------------------------------------------

/** Deterministic 0..1 from a string: equal inputs rotate the same way for a whole hour. */
function adsSeedFraction(seed) {
  let h = 2166136261;
  const s = String(seed || "");
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return ((h >>> 0) % 100000) / 100000;
}

/**
 * Priority-weighted fair rotation: each campaign gets a sort key of
 * seedFraction^(1/priority), the classic weighted-random-sample trick. Over many
 * devices/hours a priority-10 campaign is served ~10x as often as a priority-1 one,
 * and every device sees a stable order for the whole hour (no flicker on re-fetch).
 */
function adsRotate(campaigns, seed) {
  return campaigns
    .map((c) => {
      const f = adsSeedFraction(seed + "|" + c.id);
      const weight = Math.max(1, c.priority);
      return { campaign: c, key: Math.pow(f === 0 ? 0.000001 : f, 1 / weight) };
    })
    .sort((a, b) => b.key - a.key)
    .map((x) => x.campaign);
}

function adsPublicCreative(creative) {
  return {
    id: creative.id,
    type: creative.type,
    url: creative.url,
    thumb_url: creative.thumb_url,
    width: creative.width,
    height: creative.height,
    duration_ms: creative.duration_ms,
    title: creative.title,
    body: creative.body,
    cta_label: creative.cta_label,
    action: { type: creative.action.type, value: creative.action.value },
  };
}

/** Everything the app needs and NOTHING about price, advertiser or targeting internals. */
function adsPublicAd(campaign, seed) {
  const creatives = adsArray(campaign.creatives);
  const pick = creatives[Math.floor(adsSeedFraction(seed + "|c|" + campaign.id) * creatives.length)] || creatives[0];
  return {
    id: campaign.id + ":" + pick.id,
    campaign_id: campaign.id,
    creative_id: pick.id,
    slot: campaign.slot,
    priority: campaign.priority,
    exclusive: campaign.exclusive,
    daily_cap_per_user: campaign.daily_cap_per_user,
    ends_at: campaign.end_at,
    label: "إعلان",
    creative: adsPublicCreative(pick),
  };
}

async function adsLoadServeSnapshot(env) {
  const now = adsNow();
  if (adsServeCache && now - adsServeCacheAt < ADS_SERVE_CACHE_MS) return adsServeCache;
  const [settings, campaigns] = await Promise.all([adsReadSettings(env), adsReadCampaigns(env)]);
  adsServeCache = { settings, campaigns: campaigns.items };
  adsServeCacheAt = now;
  return adsServeCache;
}

async function adsHandleServe(request, env, url) {
  const now = adsNow();
  const slot = cleanString(url.searchParams.get("slot"));
  const snapshot = await adsLoadServeSnapshot(env);
  const settings = snapshot.settings;

  const base = {
    ok: true,
    success: true,
    server_time: adsIso(now),
    server_time_ms: now,
    ttl_seconds: 300,
    slot,
    ads: [],
  };

  if (!slot || ADS_SLOT_IDS.indexOf(slot) < 0) {
    return json(Object.assign({}, base, { ok: false, success: false, error: "invalid_slot", message: "مساحة غير معروفة." }), 400);
  }

  const ctx = {
    now,
    city: adsText(url.searchParams.get("city"), 60),
    lang: adsSlug(url.searchParams.get("lang")),
    appVersion: adsText(url.searchParams.get("app_version"), 20),
    tzOffsetMinutes: settings.hours.timezone_offset_minutes,
  };

  let eligible = snapshot.campaigns.filter(
    (c) => c.slot === slot && adsIsServable(c, now) && adsMatchesTargeting(c, ctx)
  );

  // Exclusivity wins over everything else in the same slot.
  const exclusives = eligible.filter((c) => c.exclusive);
  if (exclusives.length > 0) {
    exclusives.sort((a, b) => b.priority - a.priority || adsParseTime(a.start_at) - adsParseTime(b.start_at));
    eligible = [exclusives[0]];
  }

  // Rotation seed: stable per device per hour, so one device keeps one order.
  const device = adsText(url.searchParams.get("did"), 80) || request.headers.get("cf-connecting-ip") || "anon";
  const seed = device + "|" + slot + "|" + Math.floor(now / 3600000);
  const ordered = adsRotate(eligible, seed).slice(0, 8);

  base.ads = ordered.map((c) => adsPublicAd(c, seed));
  return json(base);
}

// -----------------------------------------------------------------------------
// POST /ads/track — impressions and clicks
// -----------------------------------------------------------------------------

async function adsHandleTrack(request, env) {
  const body = await readRequestData(request);
  const now = adsNow();
  const rawDevice = adsText(body.did, 120) || request.headers.get("cf-connecting-ip") || "anon";
  const device = await adsHash("cigram-ads|" + rawDevice);

  const events = adsArray(body.events)
    .slice(0, 50)
    .map((e) => {
      // Three kinds, and nothing else: an unknown type must not be silently
      // counted as an impression (a viewer's "إبلاغ" used to inflate the count).
      const raw = cleanString(e && e.type);
      const type = raw === "click" || raw === "report" ? raw : "impression";
      return {
        type,
        reason: raw === "report" ? adsText(e && e.reason, 120) : "",
        campaign_id: adsText(e && e.campaign_id, 80),
        creative_id: adsText(e && e.creative_id, 80),
        slot: ADS_SLOT_IDS.indexOf(cleanString(e && e.slot)) >= 0 ? cleanString(e.slot) : "",
        nonce: adsText(e && e.nonce, 80),
        ts: adsParseTime(e && e.ts) || now,
      };
    })
    .filter((e) => e.campaign_id && e.slot);

  if (events.length === 0) return json({ ok: true, success: true, accepted: 0, server_time_ms: now });

  const result = await adsStatsCall(env, "/track", { device, events, now });
  return json({
    ok: true,
    success: true,
    accepted: result && typeof result.accepted === "number" ? result.accepted : 0,
    reports: result && typeof result.reports === "number" ? result.reports : 0,
    rejected: result && typeof result.rejected === "number" ? result.rejected : 0,
    // The client backs off for a minute when it sees this instead of retrying the batch.
    rate_limited: Boolean(result && result.rate_limited),
    server_time_ms: now,
    server_time: adsIso(now),
  });
}

// -----------------------------------------------------------------------------
// reports
// -----------------------------------------------------------------------------

function adsReportShape(campaign, advertiser, stats, settings) {
  const days = adsArray(stats && stats.days);
  let impressions = 0;
  let clicks = 0;
  let reports = 0;
  for (const d of days) {
    impressions += adsInt(d.impressions, 0, 0, 1e12);
    clicks += adsInt(d.clicks, 0, 0, 1e12);
    reports += adsInt(d.reports, 0, 0, 1e12);
  }
  const slot = settings.slots.find((s) => s.id === campaign.slot);
  return {
    campaign: {
      id: campaign.id,
      title: campaign.title,
      slot: campaign.slot,
      slot_name: slot ? slot.name : campaign.slot,
      status: adsEffectiveStatus(campaign),
      start_at: campaign.start_at,
      end_at: campaign.end_at,
      exclusive: campaign.exclusive,
    },
    advertiser: advertiser ? { id: advertiser.id, name: advertiser.name, logo_url: advertiser.logo_url } : null,
    totals: {
      impressions,
      clicks,
      reports,
      ctr: impressions > 0 ? Math.round((clicks / impressions) * 10000) / 100 : 0,
    },
    days,
    generated_at: adsIso(),
  };
}

async function adsHandlePublicReport(request, env, url) {
  const token = adsText(url.searchParams.get("token"), 80);
  if (!token || token.length < 16) throw adsFail("not_found", "رابط التقرير غير صالح.", 404);
  const [settings, campaigns, advertisers] = await Promise.all([
    adsReadSettings(env),
    adsReadCampaigns(env),
    adsReadAdvertisers(env),
  ]);
  const campaign = campaigns.items.find((c) => c.report_token === token);
  if (!campaign) throw adsFail("not_found", "رابط التقرير غير صالح أو منتهي.", 404);
  const stats = await adsStatsCall(env, "/report", { campaign_id: campaign.id });
  const advertiser = advertisers.items.find((a) => a.id === campaign.advertiser_id) || null;
  return json(Object.assign({ ok: true, success: true, read_only: true }, adsReportShape(campaign, advertiser, stats, settings)));
}

// -----------------------------------------------------------------------------
// public config + quote + consent (what the "أعلن هنا" page reads)
// -----------------------------------------------------------------------------

function adsPublicConfig(settings) {
  return {
    version: settings.version,
    updated_at: settings.updated_at,
    currency: settings.currency,
    currency_label: settings.currency_label,
    policy_version: settings.policy_version,
    policy_updated_at: settings.policy_updated_at,
    hero: settings.hero,
    slots: settings.slots.filter((s) => s.enabled),
    durations: settings.durations,
    discounts: settings.discounts,
    addons: settings.addons,
    targeting: settings.targeting,
    accepted: settings.accepted,
    rejected: settings.rejected,
    policies: settings.policies,
    faq: settings.faq,
    contact: settings.contact,
    presence: adsEffectivePresence(settings),
  };
}

async function adsHandlePublicConfig(request, env) {
  const settings = await adsReadSettings(env);
  return json({
    ok: true,
    success: true,
    server_time: adsIso(),
    server_time_ms: adsNow(),
    data: adsPublicConfig(settings),
  });
}

async function adsHandlePublicPresence(request, env) {
  const settings = await adsReadSettings(env);
  return json({ ok: true, success: true, data: adsEffectivePresence(settings), server_time_ms: adsNow() });
}

async function adsHandleQuote(request, env) {
  const body = await readRequestData(request);
  const settings = await adsReadSettings(env);
  const quote = adsQuote(settings, {
    slot: body.slot,
    days: body.days,
    cities: body.cities,
    langs: body.langs,
    hours: body.hours,
    addons: body.addons,
  });
  return json({ ok: true, success: true, data: quote });
}

async function adsReadConsents(env) {
  const raw = await readJsonFile(env, ADS_CONSENTS_OBJECT, null);
  if (!raw || typeof raw !== "object") return { version: 1, items: {} };
  return { version: 1, items: raw.items && typeof raw.items === "object" ? raw.items : {} };
}

/** Records that this account accepted policy version N (date + version are the audit trail). */
async function adsHandleConsent(request, env, url, user) {
  const body = await readRequestData(request);
  const settings = await adsReadSettings(env);
  const version = adsInt(body.policy_version, settings.policy_version, 1, 100000);
  const doc = await adsReadConsents(env);
  doc.items[user.id] = {
    user_id: user.id,
    policy_version: version,
    accepted_at: adsIso(),
    app_version: adsText(body.app_version, 20),
  };
  const keys = Object.keys(doc.items);
  if (keys.length > 20000) {
    const trimmed = {};
    for (const k of keys.slice(-20000)) trimmed[k] = doc.items[k];
    doc.items = trimmed;
  }
  await writeJsonFile(env, ADS_CONSENTS_OBJECT, doc);
  return json({ ok: true, success: true, data: doc.items[user.id], current_policy_version: settings.policy_version });
}

async function adsHandleConsentGet(request, env, url, user) {
  const [settings, doc] = await Promise.all([adsReadSettings(env), adsReadConsents(env)]);
  const mine = doc.items[user.id] || null;
  return json({
    ok: true,
    success: true,
    data: {
      accepted: Boolean(mine && mine.policy_version >= settings.policy_version),
      consent: mine,
      current_policy_version: settings.policy_version,
    },
  });
}

// -----------------------------------------------------------------------------
// "إعلاناتي" — the advertiser's own campaigns (never anybody else's)
// -----------------------------------------------------------------------------

async function adsHandleMyCampaigns(request, env, url, user) {
  const [settings, campaigns, advertisers] = await Promise.all([
    adsReadSettings(env),
    adsReadCampaigns(env),
    adsReadAdvertisers(env),
  ]);
  const mine = advertisers.items.filter((a) => a.user_id && a.user_id === user.id).map((a) => a.id);
  if (mine.length === 0) return json({ ok: true, success: true, data: { campaigns: [] } });

  const list = campaigns.items.filter((c) => mine.indexOf(c.advertiser_id) >= 0);
  const out = [];
  for (const c of list) {
    const stats = await adsStatsCall(env, "/report", { campaign_id: c.id });
    const slot = settings.slots.find((s) => s.id === c.slot);
    const days = adsArray(stats && stats.days);
    let impressions = 0;
    let clicks = 0;
    for (const d of days) {
      impressions += adsInt(d.impressions, 0, 0, 1e12);
      clicks += adsInt(d.clicks, 0, 0, 1e12);
    }
    out.push({
      id: c.id,
      title: c.title,
      slot: c.slot,
      slot_name: slot ? slot.name : c.slot,
      status: adsEffectiveStatus(c),
      reject_reason: c.review.reject_reason,
      start_at: c.start_at,
      end_at: c.end_at,
      price_final: c.price_final,
      currency: c.currency,
      payment_status: c.payment.status,
      report_url: "/ads/report?token=" + c.report_token,
      totals: { impressions, clicks, ctr: impressions > 0 ? Math.round((clicks / impressions) * 10000) / 100 : 0 },
      days,
    });
  }
  out.sort((a, b) => adsParseTime(b.start_at) - adsParseTime(a.start_at));
  return json({ ok: true, success: true, data: { campaigns: out } });
}

// -----------------------------------------------------------------------------
// media: real MIME sniffing (magic bytes), never the declared header alone
// -----------------------------------------------------------------------------

const ADS_MEDIA_TYPES = {
  "image/jpeg": "jpg",
  "image/png": "png",
  "image/webp": "webp",
  "image/gif": "gif",
  "video/mp4": "mp4",
  "video/webm": "webm",
  "audio/mp4": "m4a",
  "audio/mpeg": "mp3",
  "audio/ogg": "ogg",
  "application/pdf": "pdf",
};

/** Reads the file's own magic bytes. Returns "" when the bytes match nothing we allow. */
function adsSniffType(buffer) {
  const b = new Uint8Array(buffer);
  if (b.length < 12) return "";
  const ascii = (start, len) => {
    let s = "";
    for (let i = start; i < start + len && i < b.length; i++) s += String.fromCharCode(b[i]);
    return s;
  };
  if (b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return "image/jpeg";
  if (b[0] === 0x89 && ascii(1, 3) === "PNG") return "image/png";
  if (ascii(0, 4) === "RIFF" && ascii(8, 4) === "WEBP") return "image/webp";
  // the full signature, not just "GIF": a text file starting with those three
  // letters is not an image.
  if (ascii(0, 6) === "GIF87a" || ascii(0, 6) === "GIF89a") return "image/gif";
  if (ascii(0, 4) === "%PDF") return "application/pdf";
  if (b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3) return "video/webm";
  if (ascii(4, 4) === "ftyp") {
    const brand = ascii(8, 4);
    if (brand === "M4A " || brand === "M4B ") return "audio/mp4";
    return "video/mp4";
  }
  if (ascii(0, 4) === "OggS") return "audio/ogg";
  if (ascii(0, 3) === "ID3" || (b[0] === 0xff && (b[1] & 0xe0) === 0xe0)) return "audio/mpeg";
  return "";
}

/**
 * POST /admin/ads/creative/upload?kind=image|video — raw bytes in the body.
 * The declared content-type is ignored: only the sniffed type decides.
 */
async function adsHandleCreativeUpload(request, env, url) {
  const buffer = await request.arrayBuffer();
  if (!buffer || buffer.byteLength === 0) throw adsFail("empty_upload", "الملف فارغ.", 400);

  const sniffed = adsSniffType(buffer);
  if (!sniffed || !ADS_MEDIA_TYPES[sniffed]) {
    throw adsFail("unsupported_type", "نوع الملف غير مدعوم. المسموح: JPG, PNG, WEBP, GIF, MP4, WEBM, PDF.", 415);
  }
  const isVideo = sniffed.startsWith("video/");
  const limit = isVideo ? ADS_MAX_CREATIVE_VIDEO_BYTES : ADS_MAX_CREATIVE_IMAGE_BYTES;
  if (buffer.byteLength > limit) {
    throw adsFail("too_large", "حجم الملف أكبر من المسموح (" + Math.round(limit / 1048576) + " ميجابايت).", 413, {
      max_bytes: limit,
    });
  }

  const ext = ADS_MEDIA_TYPES[sniffed];
  const key = ADS_CREATIVE_PREFIX + Date.now() + "_" + adsToken(8) + "." + ext;
  await env.MOVIES_BUCKET.put(key, buffer, {
    httpMetadata: { contentType: sniffed, cacheControl: "public, max-age=31536000, immutable" },
    customMetadata: { kind: "ads-creative", uploadedAt: adsIso() },
  });

  return okResponse(
    { key, url: r2PublicUrl(key), content_type: sniffed, size: buffer.byteLength, is_video: isVideo },
    "تم رفع المادة الإعلانية."
  );
}

// -----------------------------------------------------------------------------
// admin handlers
// -----------------------------------------------------------------------------

async function adsAdminSettingsGet(request, env) {
  const settings = await adsReadSettings(env);
  return okResponse({ settings, presence: adsEffectivePresence(settings) }, "");
}

async function adsAdminSettingsSave(request, env) {
  const body = await readRequestData(request);
  const incoming = body.settings && typeof body.settings === "object" ? body.settings : body;
  const current = await adsReadSettings(env);

  const merged = Object.assign({}, current, incoming);
  // Bumping the policy version is explicit: it re-asks every advertiser to accept.
  if (adsBool(body.bump_policy_version, false)) {
    merged.policy_version = current.policy_version + 1;
    merged.policy_updated_at = adsIso();
  } else {
    merged.policy_version = current.policy_version;
    merged.policy_updated_at = current.policy_updated_at;
  }
  // Presence has its own route; a settings save must never silently flip it.
  merged.presence = current.presence;

  const saved = await adsWriteSettings(env, merged);
  adsServeCache = null;
  return okResponse({ settings: saved }, "تم حفظ إعدادات الإعلانات.");
}

async function adsAdminPresenceSet(request, env) {
  const body = await readRequestData(request);
  let status = cleanString(body.status);
  if (["online", "offline", "away"].indexOf(status) < 0) status = "offline";
  const settings = await adsReadSettings(env);
  settings.presence = { status, updated_at: adsIso(), note: adsText(body.note, 200) };
  const saved = await adsWriteSettings(env, settings);
  adsServeCache = null;
  return okResponse({ presence: adsEffectivePresence(saved) }, "تم تحديث حالتك.");
}

async function adsAdminAdvertisersList(request, env) {
  const doc = await adsReadAdvertisers(env);
  const campaigns = await adsReadCampaigns(env);
  const items = doc.items.map((a) => {
    const mine = campaigns.items.filter((c) => c.advertiser_id === a.id);
    return Object.assign({}, a, {
      campaign_count: mine.length,
      active_count: mine.filter((c) => adsEffectiveStatus(c) === "active").length,
    });
  });
  return okResponse({ items }, "");
}

async function adsAdminAdvertiserSave(request, env) {
  const body = await readRequestData(request);
  const doc = await adsReadAdvertisers(env);
  const id = cleanString(body.id);
  const index = id ? doc.items.findIndex((a) => a.id === id) : -1;
  if (id && index < 0) throw adsFail("not_found", "المعلن غير موجود.", 404);
  const saved = adsNormalizeAdvertiser(body, index >= 0 ? doc.items[index] : null);
  if (index >= 0) doc.items[index] = saved;
  else doc.items.push(saved);
  await adsWriteAdvertisers(env, doc);
  return okResponse({ advertiser: saved }, index >= 0 ? "تم تحديث بيانات المعلن." : "تمت إضافة المعلن.");
}

async function adsAdminAdvertiserDelete(request, env) {
  const body = await readRequestData(request);
  const id = cleanString(body.id);
  const doc = await adsReadAdvertisers(env);
  const index = doc.items.findIndex((a) => a.id === id);
  if (index < 0) throw adsFail("not_found", "المعلن غير موجود.", 404);
  const campaigns = await adsReadCampaigns(env);
  const linked = campaigns.items.filter((c) => c.advertiser_id === id).length;
  if (linked > 0 && !adsBool(body.force, false)) {
    throw adsFail("has_campaigns", "لهذا المعلن " + linked + " حملة. احذف حملاته أولاً أو أكد الحذف القسري.", 409, {
      campaigns: linked,
    });
  }
  const removed = doc.items.splice(index, 1)[0];
  await adsWriteAdvertisers(env, doc);
  return okResponse({ removed_id: removed.id }, "تم حذف المعلن.");
}

async function adsAdminCampaignsList(request, env, url) {
  const [settings, campaigns, advertisers] = await Promise.all([
    adsReadSettings(env),
    adsReadCampaigns(env),
    adsReadAdvertisers(env),
  ]);
  const filterStatus = cleanString(url && url.searchParams.get("status"));
  const filterSlot = cleanString(url && url.searchParams.get("slot"));
  const now = adsNow();
  const items = campaigns.items
    .map((c) => {
      const advertiser = advertisers.items.find((a) => a.id === c.advertiser_id);
      const slot = settings.slots.find((s) => s.id === c.slot);
      return Object.assign({}, c, {
        effective_status: adsEffectiveStatus(c, now),
        advertiser_name: advertiser ? advertiser.name : "—",
        slot_name: slot ? slot.name : c.slot,
        ends_in_days: c.end_at ? Math.ceil((adsParseTime(c.end_at) - now) / 86400000) : null,
      });
    })
    .filter((c) => (!filterStatus || c.effective_status === filterStatus) && (!filterSlot || c.slot === filterSlot))
    .sort((a, b) => adsParseTime(b.updated_at) - adsParseTime(a.updated_at));
  return okResponse({ items, slots: settings.slots }, "");
}

async function adsAdminCampaignSave(request, env) {
  const body = await readRequestData(request);
  const doc = await adsReadCampaigns(env);
  const id = cleanString(body.id);
  const index = id ? doc.items.findIndex((c) => c.id === id) : -1;
  if (id && index < 0) throw adsFail("not_found", "الحملة غير موجودة.", 404);

  const advertisers = await adsReadAdvertisers(env);
  const advertiserId = cleanString(body.advertiser_id) || (index >= 0 ? doc.items[index].advertiser_id : "");
  if (!advertisers.items.some((a) => a.id === advertiserId)) {
    throw adsFail("invalid_advertiser", "المعلن المحدد غير موجود.", 400);
  }

  const previous = index >= 0 ? doc.items[index] : null;
  const saved = adsNormalizeCampaign(body, previous);

  // Any content change sends the campaign back through review.
  if (previous) {
    const contentChanged =
      JSON.stringify(previous.creatives) !== JSON.stringify(saved.creatives) ||
      previous.slot !== saved.slot ||
      previous.title !== saved.title;
    if (contentChanged && !adsBool(body.keep_approval, false)) {
      saved.review = { approved: false, by: "", at: "", reject_reason: "" };
      if (saved.status !== "draft") saved.status = "pending_review";
    }
  } else {
    // A brand-new campaign is never born approved: unless it is explicitly saved
    // as a draft it goes straight into the review queue.
    saved.review = { approved: false, by: "", at: "", reject_reason: "" };
    saved.status = cleanString(body.status) === "draft" ? "draft" : "pending_review";
  }

  if (index >= 0) doc.items[index] = saved;
  else doc.items.push(saved);
  await adsWriteCampaigns(env, doc);
  return okResponse(
    { campaign: Object.assign({}, saved, { effective_status: adsEffectiveStatus(saved) }) },
    index >= 0 ? "تم تحديث الحملة." : "تم إنشاء الحملة."
  );
}

async function adsLoadCampaignForWrite(env, id) {
  const doc = await adsReadCampaigns(env);
  const index = doc.items.findIndex((c) => c.id === cleanString(id));
  if (index < 0) throw adsFail("not_found", "الحملة غير موجودة.", 404);
  return { doc, index, campaign: doc.items[index] };
}

async function adsAdminCampaignApprove(request, env) {
  const body = await readRequestData(request);
  const { doc, index, campaign } = await adsLoadCampaignForWrite(env, body.id);
  if (!adsArray(campaign.creatives).length) {
    throw adsFail("no_creative", "لا يمكن اعتماد حملة بدون مادة إعلانية.", 400);
  }
  campaign.review = { approved: true, by: adsText(body.by, 80) || "admin", at: adsIso(), reject_reason: "" };
  campaign.status = "approved";
  campaign.updated_at = adsIso();
  doc.items[index] = campaign;
  await adsWriteCampaigns(env, doc);
  return okResponse(
    { campaign: Object.assign({}, campaign, { effective_status: adsEffectiveStatus(campaign) }) },
    "تم اعتماد الحملة."
  );
}

async function adsAdminCampaignReject(request, env) {
  const body = await readRequestData(request);
  const reason = adsText(body.reason, 1000);
  if (!reason) throw adsFail("reason_required", "سبب الرفض مطلوب ليصل للمعلن.", 400);
  const { doc, index, campaign } = await adsLoadCampaignForWrite(env, body.id);
  campaign.review = { approved: false, by: adsText(body.by, 80) || "admin", at: adsIso(), reject_reason: reason };
  campaign.status = "rejected";
  campaign.updated_at = adsIso();
  doc.items[index] = campaign;
  await adsWriteCampaigns(env, doc);
  return okResponse({ campaign }, "تم رفض الحملة وإبلاغ المعلن.");
}

async function adsAdminCampaignStatus(request, env) {
  const body = await readRequestData(request);
  let status = cleanString(body.status);
  if (["paused", "active", "ended", "draft"].indexOf(status) < 0) {
    throw adsFail("invalid_status", "حالة غير مسموحة.", 400);
  }
  const { doc, index, campaign } = await adsLoadCampaignForWrite(env, body.id);
  if (status === "active" && !campaign.review.approved) {
    throw adsFail("not_approved", "لا يمكن تشغيل حملة غير معتمدة.", 400);
  }
  campaign.status = status === "active" ? "approved" : status;
  campaign.updated_at = adsIso();
  doc.items[index] = campaign;
  await adsWriteCampaigns(env, doc);
  return okResponse(
    { campaign: Object.assign({}, campaign, { effective_status: adsEffectiveStatus(campaign) }) },
    "تم تحديث حالة الحملة."
  );
}

async function adsAdminCampaignExtend(request, env) {
  const body = await readRequestData(request);
  const addDays = adsInt(body.days, 0, 1, 3650);
  const { doc, index, campaign } = await adsLoadCampaignForWrite(env, body.id);
  const base = Math.max(adsParseTime(campaign.end_at) || 0, adsNow());
  campaign.end_at = adsIso(base + addDays * 86400000);
  campaign.days = adsInt(campaign.days + addDays, addDays, 1, 3650);
  campaign.expiry_notified = false;
  if (campaign.status === "ended") campaign.status = "approved";
  campaign.updated_at = adsIso();
  doc.items[index] = campaign;
  await adsWriteCampaigns(env, doc);
  return okResponse(
    { campaign: Object.assign({}, campaign, { effective_status: adsEffectiveStatus(campaign) }) },
    "تم تمديد الحملة " + addDays + " يوماً."
  );
}

async function adsAdminCampaignPayment(request, env) {
  const body = await readRequestData(request);
  let status = cleanString(body.status);
  if (["unpaid", "partial", "paid", "refunded"].indexOf(status) < 0) status = "unpaid";
  const { doc, index, campaign } = await adsLoadCampaignForWrite(env, body.id);
  const amount = adsNum(body.amount_paid, campaign.payment.amount_paid, 0, 100000000);
  const delta = amount - campaign.payment.amount_paid;
  campaign.payment = {
    status,
    amount_paid: amount,
    method: adsText(body.method, 60) || campaign.payment.method,
    note: adsText(body.note, 1000),
    updated_at: adsIso(),
  };
  campaign.updated_at = adsIso();
  doc.items[index] = campaign;
  await adsWriteCampaigns(env, doc);

  if (delta !== 0) {
    const advertisers = await adsReadAdvertisers(env);
    const a = advertisers.items.find((x) => x.id === campaign.advertiser_id);
    if (a) {
      a.total_paid = adsNum(a.total_paid + delta, 0, 0, 1000000000);
      await adsWriteAdvertisers(env, advertisers);
    }
  }
  return okResponse({ campaign }, "تم تسجيل حالة الدفع.");
}

async function adsAdminCampaignDelete(request, env) {
  const body = await readRequestData(request);
  const { doc, index, campaign } = await adsLoadCampaignForWrite(env, body.id);
  // Creatives uploaded to R2 go with the campaign: no orphan media is left behind.
  for (const c of adsArray(campaign.creatives)) {
    const key = adsCreativeKeyFromUrl(c.url);
    if (key) {
      try {
        await env.MOVIES_BUCKET.delete(key);
      } catch {
        /* a missing object is not a failure */
      }
    }
  }
  doc.items.splice(index, 1);
  await adsWriteCampaigns(env, doc);
  await adsStatsCall(env, "/purge", { campaign_id: campaign.id });
  return okResponse({ removed_id: campaign.id }, "تم حذف الحملة وموادها.");
}

function adsCreativeKeyFromUrl(value) {
  const url = cleanString(value);
  if (!url.startsWith(R2_PUBLIC_BASE + "/" + ADS_CREATIVE_PREFIX)) return "";
  try {
    const u = new URL(url);
    const path = decodeURIComponent(u.pathname.replace(/^\/+/, ""));
    return path.startsWith(ADS_CREATIVE_PREFIX) ? path : "";
  } catch {
    return "";
  }
}

async function adsAdminReport(request, env, url) {
  const campaignId = adsText(url.searchParams.get("campaign"), 80);
  const [settings, campaigns, advertisers] = await Promise.all([
    adsReadSettings(env),
    adsReadCampaigns(env),
    adsReadAdvertisers(env),
  ]);
  if (campaignId) {
    const campaign = campaigns.items.find((c) => c.id === campaignId);
    if (!campaign) throw adsFail("not_found", "الحملة غير موجودة.", 404);
    const stats = await adsStatsCall(env, "/report", { campaign_id: campaign.id });
    const advertiser = advertisers.items.find((a) => a.id === campaign.advertiser_id) || null;
    const report = adsReportShape(campaign, advertiser, stats, settings);
    report.public_url = "/ads/report?token=" + campaign.report_token;
    report.report_token = campaign.report_token;
    // Viewer complaints are for the admin only; the public report never shows them.
    report.report_reasons = adsArray(stats && stats.report_reasons);
    return okResponse(report, "");
  }
  const overview = await adsStatsCall(env, "/overview", {});
  return okResponse(
    {
      totals: overview && overview.totals ? overview.totals : { impressions: 0, clicks: 0 },
      by_campaign: overview && overview.by_campaign ? overview.by_campaign : [],
      campaigns: campaigns.items.length,
      advertisers: advertisers.items.length,
    },
    ""
  );
}

async function adsAdminQuote(request, env) {
  return adsHandleQuote(request, env);
}

// -----------------------------------------------------------------------------
// cron — status transitions + 3-day expiry alerts
// -----------------------------------------------------------------------------

/** Called from scheduled(). Writes only when something actually changed. */
async function adsCron(env) {
  const now = adsNow();
  const doc = await adsReadCampaigns(env);
  let changed = false;
  const expiring = [];

  for (const c of doc.items) {
    const effective = adsEffectiveStatus(c, now);
    if (effective === "ended" && c.status !== "ended") {
      c.status = "ended";
      c.updated_at = adsIso(now);
      changed = true;
    }
    if (effective === "active" && c.status === "approved") {
      c.status = "active";
      c.updated_at = adsIso(now);
      changed = true;
    }
    if (effective === "scheduled" && c.status === "approved") {
      c.status = "scheduled";
      c.updated_at = adsIso(now);
      changed = true;
    }
    const end = adsParseTime(c.end_at);
    if (end && effective === "active" && !c.expiry_notified && end - now <= 3 * 86400000) {
      c.expiry_notified = true;
      changed = true;
      expiring.push({ id: c.id, title: c.title, end_at: c.end_at });
    }
  }

  if (changed) await adsWriteCampaigns(env, doc);
  if (expiring.length > 0) await adsNotifyExpiring(env, expiring);
  return { changed, expiring: expiring.length };
}

/** Reuses the update centre's push relay when it is configured; silent otherwise. */
async function adsNotifyExpiring(env, expiring) {
  const hook = cleanString(env.PUSH_WEBHOOK_URL);
  if (!hook) return;
  try {
    await fetch(hook, {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({
        kind: "ads_campaign_expiring",
        count: expiring.length,
        items: expiring,
        at: adsIso(),
      }),
    });
  } catch {
    /* notifications are best-effort and never block the cron */
  }
}

// =============================================================================
// Durable Object — AdsStatsDO
// -----------------------------------------------------------------------------
// One instance ("ads-stats-v1") owns every counter, so impressions never race
// against each other the way an R2 read-modify-write would.
//
// Storage layout:
//   c:<campaign>:<YYYY-MM-DD>          -> { i, c }   campaign day totals
//   u:<device>:<campaign>:<YYYY-MM-DD> -> { i, c }   per-device day totals (caps)
//   meta:campaigns                     -> [ campaign ids ]  (overview index)
// Nonces and the rate limiter live in memory only: a DO eviction can at worst
// let one retried batch through, and the per-device day caps still bound it.
// =============================================================================

export class AdsStatsDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.nonces = new Set();
    this.nonceOrder = [];
    this.rate = new Map();
    this.DAY_IMPRESSION_CAP = 40;
    this.DAY_CLICK_CAP = 15;
    /** One device reporting the same campaign twice a day adds nothing. */
    this.DAY_REPORT_CAP = 2;
    this.RATE_LIMIT = 120; // events per device per minute
  }

  async fetch(request) {
    const url = new URL(request.url);
    let body = {};
    try {
      body = await request.json();
    } catch {
      body = {};
    }
    try {
      if (url.pathname === "/track") return this.json(await this.track(body));
      if (url.pathname === "/report") return this.json(await this.report(body));
      if (url.pathname === "/overview") return this.json(await this.overview());
      if (url.pathname === "/purge") return this.json(await this.purge(body));
      return this.json({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      return this.json({ ok: false, error: "internal_error", message: String(error && error.message) }, 500);
    }
  }

  json(value, status) {
    return new Response(JSON.stringify(value), {
      status: status || 200,
      headers: { "content-type": "application/json; charset=utf-8" },
    });
  }

  dayKey(ms) {
    return new Date((typeof ms === "number" ? ms : Date.now()) + 180 * 60000).toISOString().slice(0, 10);
  }

  rememberNonce(nonce) {
    if (!nonce) return false; // no nonce => cannot dedupe, let the day caps decide
    if (this.nonces.has(nonce)) return true;
    this.nonces.add(nonce);
    this.nonceOrder.push(nonce);
    if (this.nonceOrder.length > 20000) {
      const old = this.nonceOrder.splice(0, 5000);
      for (const n of old) this.nonces.delete(n);
    }
    return false;
  }

  allowRate(device, count) {
    const now = Date.now();
    const slot = Math.floor(now / 60000);
    const entry = this.rate.get(device);
    if (!entry || entry.slot !== slot) {
      this.rate.set(device, { slot, used: count });
      if (this.rate.size > 50000) this.rate.clear();
      return count <= this.RATE_LIMIT;
    }
    entry.used += count;
    return entry.used <= this.RATE_LIMIT;
  }

  async track(body) {
    const device = String(body.device || "anon").slice(0, 64);
    const events = Array.isArray(body.events) ? body.events.slice(0, 50) : [];
    if (events.length === 0) return { ok: true, accepted: 0, rejected: 0 };
    if (!this.allowRate(device, events.length)) return { ok: true, accepted: 0, rejected: events.length, rate_limited: true };

    let accepted = 0;
    let rejected = 0;
    let reports = 0;
    const campaignTouched = new Set();

    await this.state.blockConcurrencyWhile(async () => {
      for (const e of events) {
        const raw = e && e.type;
        const type = raw === "click" || raw === "report" ? raw : "impression";
        const campaign = String((e && e.campaign_id) || "").slice(0, 80);
        if (!campaign) {
          rejected++;
          continue;
        }
        if (this.rememberNonce(String((e && e.nonce) || "").slice(0, 80))) {
          rejected++;
          continue;
        }
        const day = this.dayKey(Number(e && e.ts) || Date.now());
        const userKey = "u:" + device + ":" + campaign + ":" + day;
        const user = (await this.state.storage.get(userKey)) || { i: 0, c: 0, r: 0 };
        if (type === "impression" && user.i >= this.DAY_IMPRESSION_CAP) {
          rejected++;
          continue;
        }
        if (type === "click" && user.c >= this.DAY_CLICK_CAP) {
          rejected++;
          continue;
        }
        if (type === "report" && (user.r || 0) >= this.DAY_REPORT_CAP) {
          rejected++;
          continue;
        }
        if (type === "impression") user.i++;
        else if (type === "click") user.c++;
        else user.r = (user.r || 0) + 1;
        await this.state.storage.put(userKey, user);

        const dayKey = "c:" + campaign + ":" + day;
        const totals = (await this.state.storage.get(dayKey)) || { i: 0, c: 0, r: 0 };
        if (type === "impression") totals.i++;
        else if (type === "click") totals.c++;
        else totals.r = (totals.r || 0) + 1;
        await this.state.storage.put(dayKey, totals);
        if (type === "report") {
          reports++;
          // Keep only the newest reasons: enough to act on, not a log of everyone.
          const reasonKey = "rr:" + campaign;
          const stored = (await this.state.storage.get(reasonKey)) || [];
          stored.push({ reason: String((e && e.reason) || "").slice(0, 120), at: Date.now() });
          await this.state.storage.put(reasonKey, stored.slice(-50));
        }

        campaignTouched.add(campaign);
        accepted++;
      }

      if (campaignTouched.size > 0) {
        const index = (await this.state.storage.get("meta:campaigns")) || [];
        let dirty = false;
        for (const id of campaignTouched) {
          if (index.indexOf(id) < 0) {
            index.push(id);
            dirty = true;
          }
        }
        if (dirty) await this.state.storage.put("meta:campaigns", index.slice(-5000));
      }
    });

    await this.scheduleCleanup();
    return { ok: true, accepted, rejected, reports };
  }

  async scheduleCleanup() {
    try {
      const current = await this.state.storage.getAlarm();
      if (current === null || current === undefined) {
        await this.state.storage.setAlarm(Date.now() + 6 * 3600 * 1000);
      }
    } catch {
      /* alarms unavailable: per-device rows simply live longer */
    }
  }

  /** Drops per-device rows older than 3 days; campaign totals are kept forever. */
  async alarm() {
    try {
      const cutoff = this.dayKey(Date.now() - 3 * 86400000);
      const rows = await this.state.storage.list({ prefix: "u:", limit: 10000 });
      const doomed = [];
      for (const key of rows.keys()) {
        const day = key.slice(key.lastIndexOf(":") + 1);
        if (day < cutoff) doomed.push(key);
      }
      for (let i = 0; i < doomed.length; i += 128) {
        await this.state.storage.delete(doomed.slice(i, i + 128));
      }
    } catch {
      /* best effort */
    }
    try {
      await this.state.storage.setAlarm(Date.now() + 6 * 3600 * 1000);
    } catch {
      /* ignore */
    }
  }

  async report(body) {
    const campaign = String(body.campaign_id || "").slice(0, 80);
    if (!campaign) return { ok: false, days: [] };
    const rows = await this.state.storage.list({ prefix: "c:" + campaign + ":", limit: 1000 });
    const days = [];
    for (const [key, value] of rows) {
      days.push({
        day: key.slice(key.lastIndexOf(":") + 1),
        impressions: Number(value && value.i) || 0,
        clicks: Number(value && value.c) || 0,
        reports: Number(value && value.r) || 0,
      });
    }
    days.sort((a, b) => (a.day < b.day ? -1 : a.day > b.day ? 1 : 0));
    const reasons = (await this.state.storage.get("rr:" + campaign)) || [];
    return { ok: true, campaign_id: campaign, days, report_reasons: reasons.slice(-20) };
  }

  async overview() {
    const index = (await this.state.storage.get("meta:campaigns")) || [];
    const byCampaign = [];
    let impressions = 0;
    let clicks = 0;
    for (const id of index.slice(-500)) {
      const rows = await this.state.storage.list({ prefix: "c:" + id + ":", limit: 1000 });
      let i = 0;
      let c = 0;
      for (const [, value] of rows) {
        i += Number(value && value.i) || 0;
        c += Number(value && value.c) || 0;
      }
      impressions += i;
      clicks += c;
      byCampaign.push({ campaign_id: id, impressions: i, clicks: c });
    }
    byCampaign.sort((a, b) => b.impressions - a.impressions);
    return { ok: true, totals: { impressions, clicks }, by_campaign: byCampaign.slice(0, 200) };
  }

  async purge(body) {
    const campaign = String(body.campaign_id || "").slice(0, 80);
    if (!campaign) return { ok: false };
    const rows = await this.state.storage.list({ prefix: "c:" + campaign + ":", limit: 1000 });
    const keys = Array.from(rows.keys());
    for (let i = 0; i < keys.length; i += 128) {
      await this.state.storage.delete(keys.slice(i, i + 128));
    }
    const index = (await this.state.storage.get("meta:campaigns")) || [];
    const next = index.filter((id) => id !== campaign);
    await this.state.storage.put("meta:campaigns", next);
    return { ok: true, removed: keys.length };
  }
}

// =============================================================================
// ROUTE TABLE  —  add `.concat(Object.keys(ADS_ROUTES))` to the health list and
// `const adsHandler = ADS_ROUTES[request.method + " " + path];` to the router.
// =============================================================================

const ADS_ROUTES = (() => {
  const A = adsAdminRoute;
  const P = adsPublicRoute;
  const U = adsUserRoute;
  return {
    // ---- public (the user app reads these without a login) -------------------
    "GET /ads": P(adsHandleServe),
    "GET /ads/config": P(adsHandlePublicConfig),
    "GET /ads/presence": P(adsHandlePublicPresence),
    "POST /ads/quote": P(adsHandleQuote),
    "POST /ads/track": P(adsHandleTrack),
    "GET /ads/report": P(adsHandlePublicReport),

    // ---- logged-in advertiser ------------------------------------------------
    "GET /ads/consent": U(adsHandleConsentGet),
    "POST /ads/consent": U(adsHandleConsent),
    "GET /ads/my-campaigns": U(adsHandleMyCampaigns),

    // ---- admin (ADMIN_TOKEN) -------------------------------------------------
    "GET /admin/ads/settings": A(adsAdminSettingsGet),
    "POST /admin/ads/settings": A(adsAdminSettingsSave),
    "POST /admin/ads/presence": A(adsAdminPresenceSet),
    "POST /admin/ads/quote": A(adsAdminQuote),

    "GET /admin/ads/advertisers": A(adsAdminAdvertisersList),
    "POST /admin/ads/advertiser/save": A(adsAdminAdvertiserSave),
    "POST /admin/ads/advertiser/delete": A(adsAdminAdvertiserDelete),

    "GET /admin/ads/campaigns": A(adsAdminCampaignsList),
    "POST /admin/ads/campaign/save": A(adsAdminCampaignSave),
    "POST /admin/ads/campaign/approve": A(adsAdminCampaignApprove),
    "POST /admin/ads/campaign/reject": A(adsAdminCampaignReject),
    "POST /admin/ads/campaign/status": A(adsAdminCampaignStatus),
    "POST /admin/ads/campaign/extend": A(adsAdminCampaignExtend),
    "POST /admin/ads/campaign/payment": A(adsAdminCampaignPayment),
    "POST /admin/ads/campaign/delete": A(adsAdminCampaignDelete),
    "POST /admin/ads/creative/upload": A(adsHandleCreativeUpload),

    "GET /admin/ads/report": A(adsAdminReport),
  };
})();
