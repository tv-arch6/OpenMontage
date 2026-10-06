import worker, { AdsStatsDO } from "./worker.mjs";
import { MemoryBucket, makeDoNamespace } from "./harness.mjs";

const ADMIN = "test-admin-token";
const bucket = new MemoryBucket();
const env = { MOVIES_BUCKET: bucket, ADMIN_TOKEN: ADMIN, ADS_AUTH_VERIFY_URL: "https://auth.test/verify" };
env.ADS_STATS = makeDoNamespace(AdsStatsDO, env);
const ctx = { waitUntil: (p) => { if (p && p.catch) p.catch(() => {}); } };

// Stub the user-token verification endpoint.
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, init) => {
  const u = String(url && url.url ? url.url : url);
  if (u.startsWith("https://auth.test/verify")) {
    const auth = (init && init.headers && init.headers.authorization) || "";
    if (auth === "Bearer good-user-token") {
      return new Response(JSON.stringify({ ok: true, user: { id: "u-100", email: "co@test.com", name: "شركة الاختبار" } }), {
        headers: { "content-type": "application/json" },
      });
    }
    return new Response(JSON.stringify({ ok: false }), { status: 401, headers: { "content-type": "application/json" } });
  }
  return realFetch(url, init);
};

const BASE = "https://api.test";
let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log("  PASS  " + name); }
  else { fail++; console.log("  FAIL  " + name + (extra !== undefined ? "  -> " + JSON.stringify(extra).slice(0, 400) : "")); }
}
async function call(method, path, { body, admin, user, raw, contentType } = {}) {
  const headers = {};
  if (admin) headers.authorization = "Bearer " + ADMIN;
  if (user) headers.authorization = "Bearer " + user;
  let payload;
  if (raw) { payload = raw; headers["content-type"] = contentType || "application/octet-stream"; }
  else if (body !== undefined) { payload = JSON.stringify(body); headers["content-type"] = "application/json"; }
  const res = await worker.fetch(new Request(BASE + path, { method, headers, body: payload }), env, ctx);
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not json */ }
  return { status: res.status, json, text };
}

console.log("\n=== PHASE A — existing routes still work ===");
{
  const r = await call("GET", "/health");
  check("GET /health is 200", r.status === 200, r.json && r.json.error);
  check("/health lists the new ads routes", r.json && r.json.routes.includes("GET /ads"), r.json && r.json.routes && r.json.routes.length);
  check("/health still lists the old routes", r.json && r.json.routes.includes("POST /add-movie"));
  check("/health still lists v2 routes", r.json && r.json.routes.includes("GET /admin/meta"));
}
{
  const r = await call("GET", "/movies");
  check("GET /movies untouched (empty catalogue => [])", r.status === 200 && Array.isArray(r.json));
}
{
  const r = await call("GET", "/admin/ping", { admin: true });
  check("GET /admin/ping untouched", r.status === 200 && r.json.ok === true);
}

console.log("\n=== auth ===");
{
  const r = await call("GET", "/admin/ads/settings");
  check("admin ads settings without a token is 401", r.status === 401, r.json);
  const r2 = await call("GET", "/admin/ads/settings", { admin: true });
  check("admin ads settings with the token is 200", r2.status === 200 && r2.json.ok === true, r2.json);
  const r3 = await call("GET", "/ads/my-campaigns");
  check("user route without a login is 401", r3.status === 401, r3.json);
  const r4 = await call("GET", "/ads/my-campaigns", { user: "bad-token" });
  check("user route with a bad token is 401", r4.status === 401, r4.json);
  const r5 = await call("GET", "/ads/my-campaigns", { user: "good-user-token" });
  check("user route with a good token is 200", r5.status === 200, r5.json);
}

console.log("\n=== public config ===");
let config;
{
  const r = await call("GET", "/ads/config");
  config = r.json && r.json.data;
  check("GET /ads/config is 200", r.status === 200 && r.json.ok === true);
  check("config exposes 6 slots", config && config.slots.length === 6, config && config.slots && config.slots.map((s) => s.id));
  check("config carries policies", config && config.policies.length >= 5);
  check("config carries the rejected list", config && config.rejected.items.length >= 9);
  check("config carries a policy version", config && config.policy_version === 1);
  check("config carries presence", config && typeof config.presence.status === "string");
  check("config never leaks campaigns", config && config.campaigns === undefined);
}

console.log("\n=== quote engine ===");
{
  const r = await call("POST", "/ads/quote", { body: { slot: "hero", days: 1 } });
  const q = r.json && r.json.data;
  check("1 day hero = 300", q && q.total === 300, q);
  check("1 day hero gets no discount", q && q.discount_percent === 0);
}
{
  const r = await call("POST", "/ads/quote", { body: { slot: "hero", days: 30 } });
  const q = r.json && r.json.data;
  // 300*30 = 9000, -12% = 7920
  check("30 days hero = 7920 after the 12% discount", q && q.total === 7920, q);
}
{
  const r = await call("POST", "/ads/quote", { body: { slot: "hero", days: 30, cities: ["الرياض"], addons: ["exclusive", "design"] } });
  const q = r.json && r.json.data;
  // after discount 7920 ; cities +10% = 792 ; exclusive +60% = 4752 ; design fixed 350
  check("targeting + add-ons add up exactly", q && q.total === 7920 + 792 + 4752 + 350, q && { t: q.total, ta: q.targeting_value, ad: q.addons_value });
  check("the breakdown names every add-on", q && q.addons.length === 2);
  check("estimated views shrink with city targeting", q && q.est_views < 14000 * 30, q && q.est_views);
}
{
  const r = await call("POST", "/ads/quote", { body: { slot: "nope", days: 5 } });
  check("an unknown slot is rejected with an Arabic message", r.status === 400 && /المساحة/.test(r.json.message), r.json);
}
{
  const r = await call("POST", "/ads/quote", { body: { slot: "sponsor", days: 1 } });
  check("days are clamped to the slot minimum (sponsor min 7)", r.json.data.days === 7, r.json.data);
}

console.log("\n=== advertisers ===");
let advertiserId;
{
  const r = await call("POST", "/admin/ads/advertiser/save", { admin: true, body: { name: "شركة النور", phone: "0551234567", email: "a@b.com", website: "https://example.com" } });
  advertiserId = r.json && r.json.data && r.json.data.advertiser.id;
  check("creating an advertiser works", r.status === 200 && !!advertiserId, r.json);
  check("the phone is normalised", r.json.data.advertiser.phone === "0551234567", r.json.data.advertiser.phone);
}
{
  const r = await call("POST", "/admin/ads/advertiser/save", { admin: true, body: { name: "", phone: "1" } });
  check("an advertiser without a name is rejected", r.status === 400, r.json);
}
{
  const r = await call("POST", "/admin/ads/advertiser/save", { admin: true, body: { name: "خطر <script>alert(1)</script>", website: "javascript:alert(1)" } });
  const a = r.json.data.advertiser;
  check("angle brackets are stripped from stored text", !/[<>]/.test(a.name), a.name);
  check("a non-https website is dropped", a.website === "", a.website);
}

console.log("\n=== campaigns + review gate ===");
const DAY = 86400000;
const iso = (ms) => new Date(ms).toISOString();
let activeId, scheduledId, endedId, rivalId;
async function makeCampaign(over) {
  const body = Object.assign(
    {
      advertiser_id: advertiserId,
      slot: "hero",
      title: "حملة",
      start_at: iso(Date.now() - DAY),
      end_at: iso(Date.now() + 5 * DAY),
      days: 6,
      price_final: 1000,
      creatives: [{ type: "image", url: "https://cdn.test/a.jpg", title: "عنوان", cta_label: "اطلب", action: { type: "url", value: "https://shop.test" } }],
    },
    over || {}
  );
  const r = await call("POST", "/admin/ads/campaign/save", { admin: true, body });
  return r;
}
{
  const r = await makeCampaign({ title: "الحملة النشطة", priority: 5 });
  activeId = r.json && r.json.data && r.json.data.campaign.id;
  check("a new campaign is created", r.status === 200 && !!activeId, r.json);
  check("a new campaign starts in review, never live", r.json.data.campaign.effective_status === "pending_review", r.json.data.campaign.effective_status);
}
{
  const r = await call("GET", "/ads?slot=hero&city=&lang=ar&app_version=3.5.0");
  check("an unapproved campaign is never served", r.json.ads.length === 0, r.json.ads);
  check("/ads always returns server_time", typeof r.json.server_time_ms === "number");
}
{
  const r = await call("POST", "/admin/ads/campaign/approve", { admin: true, body: { id: activeId } });
  check("approving the campaign works", r.status === 200 && r.json.data.campaign.effective_status === "active", r.json);
}
{
  const r = await call("GET", "/ads?slot=hero&lang=ar&app_version=3.5.0&did=dev1");
  check("an approved, in-window campaign is served", r.json.ads.length === 1, r.json.ads);
  const ad = r.json.ads[0];
  check("the ad carries its creative", ad && ad.creative.url === "https://cdn.test/a.jpg");
  check("the ad is labelled as an ad", ad && ad.label === "إعلان");
  check("the ad never carries a price", ad && ad.price_final === undefined && ad.quote === undefined);
  check("the ad never names the advertiser", ad && ad.advertiser_id === undefined && ad.advertiser_name === undefined);
  check("the ad never carries targeting internals", ad && ad.targeting === undefined);
}
{
  const r = await makeCampaign({ title: "مجدولة", start_at: iso(Date.now() + 3 * DAY), end_at: iso(Date.now() + 9 * DAY) });
  scheduledId = r.json.data.campaign.id;
  await call("POST", "/admin/ads/campaign/approve", { admin: true, body: { id: scheduledId } });
  const g = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("a scheduled campaign is not served before it starts", g.json.ads.every((a) => a.campaign_id !== scheduledId), g.json.ads.map((a) => a.campaign_id));
}
{
  const r = await makeCampaign({ title: "منتهية", start_at: iso(Date.now() - 20 * DAY), end_at: iso(Date.now() - DAY) });
  endedId = r.json.data.campaign.id;
  await call("POST", "/admin/ads/campaign/approve", { admin: true, body: { id: endedId } });
  const g = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("an expired campaign is never served", g.json.ads.every((a) => a.campaign_id !== endedId), g.json.ads.map((a) => a.campaign_id));
}
{
  const r = await call("POST", "/admin/ads/campaign/status", { admin: true, body: { id: activeId, status: "paused" } });
  check("pausing works", r.status === 200 && r.json.data.campaign.effective_status === "paused", r.json);
  const g = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("a paused campaign is not served", g.json.ads.length === 0, g.json.ads);
  await call("POST", "/admin/ads/campaign/status", { admin: true, body: { id: activeId, status: "active" } });
  const g2 = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("resuming serves it again", g2.json.ads.length === 1, g2.json.ads);
}
{
  const r = await call("POST", "/admin/ads/campaign/reject", { admin: true, body: { id: scheduledId, reason: "الصورة منخفضة الجودة" } });
  check("rejecting requires and stores a reason", r.status === 200 && r.json.data.campaign.review.reject_reason === "الصورة منخفضة الجودة", r.json);
  const r2 = await call("POST", "/admin/ads/campaign/reject", { admin: true, body: { id: endedId } });
  check("rejecting without a reason is refused", r2.status === 400, r2.json);
}
{
  const r = await call("POST", "/admin/ads/campaign/save", { admin: true, body: { id: activeId, advertiser_id: advertiserId, slot: "hero", title: "الحملة النشطة", start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 5 * DAY), creatives: [{ type: "image", url: "https://cdn.test/NEW.jpg" }] } });
  check("changing the creative sends it back to review", r.json.data.campaign.effective_status === "pending_review", r.json.data.campaign.effective_status);
  const g = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("the edited campaign stops serving until re-approved", g.json.ads.length === 0, g.json.ads);
  await call("POST", "/admin/ads/campaign/approve", { admin: true, body: { id: activeId } });
}

console.log("\n=== targeting ===");
{
  await call("POST", "/admin/ads/campaign/save", { admin: true, body: { id: activeId, advertiser_id: advertiserId, slot: "hero", title: "الحملة النشطة", start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 5 * DAY), creatives: [{ type: "image", url: "https://cdn.test/NEW.jpg" }], targeting: { cities: ["الرياض"] }, keep_approval: true } });
  const a = await call("GET", "/ads?slot=hero&lang=ar&city=" + encodeURIComponent("الرياض") + "&did=dev1");
  check("a Riyadh-targeted ad shows in Riyadh", a.json.ads.length === 1, a.json.ads);
  const b = await call("GET", "/ads?slot=hero&lang=ar&city=" + encodeURIComponent("جدة") + "&did=dev1");
  check("it does not show in Jeddah", b.json.ads.length === 0, b.json.ads);
  const c = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("it does not show when the city is unknown", c.json.ads.length === 0, c.json.ads);
  await call("POST", "/admin/ads/campaign/save", { admin: true, body: { id: activeId, advertiser_id: advertiserId, slot: "hero", title: "الحملة النشطة", start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 5 * DAY), creatives: [{ type: "image", url: "https://cdn.test/NEW.jpg" }], targeting: {}, keep_approval: true } });
}
{
  await call("POST", "/admin/ads/campaign/save", { admin: true, body: { id: activeId, advertiser_id: advertiserId, slot: "hero", title: "ت", start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 5 * DAY), creatives: [{ type: "image", url: "https://cdn.test/NEW.jpg" }], targeting: { min_app_version: "4.0.0" }, keep_approval: true } });
  const a = await call("GET", "/ads?slot=hero&lang=ar&app_version=3.5.0&did=dev1");
  check("an app older than min_app_version gets nothing", a.json.ads.length === 0, a.json.ads);
  const b = await call("GET", "/ads?slot=hero&lang=ar&app_version=4.1.0&did=dev1");
  check("a newer app gets the ad", b.json.ads.length === 1, b.json.ads);
  await call("POST", "/admin/ads/campaign/save", { admin: true, body: { id: activeId, advertiser_id: advertiserId, slot: "hero", title: "ت", start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 5 * DAY), creatives: [{ type: "image", url: "https://cdn.test/NEW.jpg" }], targeting: {}, keep_approval: true } });
}

console.log("\n=== rotation + exclusivity ===");
{
  const r = await makeCampaign({ title: "منافس", priority: 5 });
  rivalId = r.json.data.campaign.id;
  await call("POST", "/admin/ads/campaign/approve", { admin: true, body: { id: rivalId } });
  const g = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("both advertisers are returned in the same slot", g.json.ads.length === 2, g.json.ads.map((a) => a.campaign_id));

  const firsts = {};
  for (let i = 0; i < 60; i++) {
    const res = await call("GET", "/ads?slot=hero&lang=ar&did=dev" + i);
    const id = res.json.ads[0].campaign_id;
    firsts[id] = (firsts[id] || 0) + 1;
  }
  const keys = Object.keys(firsts);
  check("rotation gives both campaigns the lead across devices", keys.length === 2 && firsts[keys[0]] > 8 && firsts[keys[1]] > 8, firsts);

  const a = await call("GET", "/ads?slot=hero&lang=ar&did=stable");
  const b = await call("GET", "/ads?slot=hero&lang=ar&did=stable");
  check("the same device gets a stable order (no flicker)", a.json.ads[0].campaign_id === b.json.ads[0].campaign_id);
}
{
  await call("POST", "/admin/ads/campaign/save", { admin: true, body: { id: rivalId, advertiser_id: advertiserId, slot: "hero", title: "منافس", start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 5 * DAY), creatives: [{ type: "image", url: "https://cdn.test/a.jpg" }], exclusive: true, keep_approval: true } });
  const g = await call("GET", "/ads?slot=hero&lang=ar&did=dev1");
  check("an exclusive campaign locks the slot to itself", g.json.ads.length === 1 && g.json.ads[0].campaign_id === rivalId, g.json.ads.map((a) => a.campaign_id));
  await call("POST", "/admin/ads/campaign/save", { admin: true, body: { id: rivalId, advertiser_id: advertiserId, slot: "hero", title: "منافس", start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 5 * DAY), creatives: [{ type: "image", url: "https://cdn.test/a.jpg" }], exclusive: false, keep_approval: true } });
}

console.log("\n=== tracking ===");
{
  const ev = (type, nonce) => ({ type, campaign_id: activeId, creative_id: "c1", slot: "hero", nonce, ts: Date.now() });
  const r = await call("POST", "/ads/track", { body: { did: "device-A", events: [ev("impression", "n1"), ev("click", "n2")] } });
  check("tracking accepts a fresh batch", r.json.accepted === 2, r.json);
  const r2 = await call("POST", "/ads/track", { body: { did: "device-A", events: [ev("impression", "n1")] } });
  check("a replayed nonce is rejected (no double counting)", r2.json.accepted === 0 && r2.json.rejected === 1, r2.json);
  const r3 = await call("POST", "/ads/track", { body: { did: "device-A", events: [{ type: "impression", slot: "hero", nonce: "n9" }] } });
  check("an event without a campaign is rejected", r3.json.accepted === 0, r3.json);
  const r4 = await call("POST", "/ads/track", { body: { did: "device-A", events: [] } });
  check("an empty batch is a no-op, not an error", r4.status === 200 && r4.json.accepted === 0);
}
{
  // 40 impressions/day/device is the hard cap inside the DO.
  const batch = [];
  for (let i = 0; i < 50; i++) batch.push({ type: "impression", campaign_id: activeId, slot: "hero", nonce: "cap" + i, ts: Date.now() });
  const r = await call("POST", "/ads/track", { body: { did: "device-B", events: batch } });
  check("one device cannot inflate a campaign past the daily cap", r.json.accepted <= 40 && r.json.rejected >= 10, r.json);
}
{
  let limited = false;
  for (let round = 0; round < 4; round++) {
    const batch = [];
    for (let i = 0; i < 50; i++) batch.push({ type: "impression", campaign_id: activeId, slot: "hero", nonce: "rl" + round + "_" + i, ts: Date.now() });
    const r = await call("POST", "/ads/track", { body: { did: "flooder", events: batch } });
    if (r.json.rate_limited) limited = true;
  }
  check("a flooding device is rate limited", limited);
}

console.log("\n=== reports ===");
let reportToken;
{
  const r = await call("GET", "/admin/ads/report?campaign=" + activeId, { admin: true });
  const d = r.json.data;
  reportToken = d && d.report_token;
  check("the admin report totals the days", d && d.totals.impressions >= 41, d && d.totals);
  check("the admin report computes CTR", d && d.totals.ctr > 0, d && d.totals);
  check("the admin report has a public token", !!reportToken);
  check("the admin report has a daily series", d && d.days.length >= 1, d && d.days);
}
{
  const r = await call("GET", "/ads/report?token=" + reportToken);
  check("the public report opens with the token, no login", r.status === 200 && r.json.read_only === true, r.json);
  check("the public report never shows the price", r.json.campaign.price_final === undefined && r.json.campaign.payment === undefined);
  const bad = await call("GET", "/ads/report?token=" + "0".repeat(32));
  check("a wrong token is a 404", bad.status === 404, bad.json);
  const none = await call("GET", "/ads/report");
  check("a missing token is a 404", none.status === 404);
}
{
  const r = await call("GET", "/admin/ads/report", { admin: true });
  check("the overview report works", r.status === 200 && r.json.data.totals.impressions > 0, r.json.data && r.json.data.totals);
}

console.log("\n=== payments ===");
{
  const r = await call("POST", "/admin/ads/campaign/payment", { admin: true, body: { id: activeId, status: "paid", amount_paid: 1000, method: "تحويل بنكي" } });
  check("recording a payment works", r.status === 200 && r.json.data.campaign.payment.status === "paid", r.json);
  const list = await call("GET", "/admin/ads/advertisers", { admin: true });
  const a = list.json.data.items.find((x) => x.id === advertiserId);
  check("the advertiser's running total follows the payment", a && a.total_paid === 1000, a && a.total_paid);
}

console.log("\n=== consent ===");
{
  const before = await call("GET", "/ads/consent", { user: "good-user-token" });
  check("a fresh account has not accepted the policies", before.json.data.accepted === false, before.json.data);
  const r = await call("POST", "/ads/consent", { user: "good-user-token", body: { policy_version: 1, app_version: "3.5.0" } });
  check("accepting stores the version and the date", r.status === 200 && r.json.data.policy_version === 1 && !!r.json.data.accepted_at, r.json);
  const after = await call("GET", "/ads/consent", { user: "good-user-token" });
  check("the acceptance is read back", after.json.data.accepted === true, after.json.data);
  const anon = await call("POST", "/ads/consent", { body: { policy_version: 1 } });
  check("a guest cannot record a consent", anon.status === 401);
}
{
  const s = await call("GET", "/admin/ads/settings", { admin: true });
  const bumped = await call("POST", "/admin/ads/settings", { admin: true, body: { settings: s.json.data.settings, bump_policy_version: true } });
  check("bumping the policy version works", bumped.json.data.settings.policy_version === 2, bumped.json.data.settings.policy_version);
  const after = await call("GET", "/ads/consent", { user: "good-user-token" });
  check("an old acceptance stops counting after a policy bump", after.json.data.accepted === false, after.json.data);
}

console.log("\n=== my-campaigns isolation ===");
{
  const r = await call("GET", "/ads/my-campaigns", { user: "good-user-token" });
  check("an account with no linked advertiser sees nothing", r.json.data.campaigns.length === 0, r.json.data);
  await call("POST", "/admin/ads/advertiser/save", { admin: true, body: { id: advertiserId, name: "شركة النور", user_id: "u-100" } });
  const r2 = await call("GET", "/ads/my-campaigns", { user: "good-user-token" });
  check("once linked, the advertiser sees their own campaigns", r2.json.data.campaigns.length >= 1, r2.json.data.campaigns.length);
  check("my-campaigns carries the status and the numbers", r2.json.data.campaigns[0].totals !== undefined && r2.json.data.campaigns[0].status !== undefined);
  check("my-campaigns never leaks another advertiser's data", r2.json.data.campaigns.every((c) => c.advertiser_id === undefined));
}

console.log("\n=== creative upload (MIME sniffing) ===");
{
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13, 0, 0, 0, 0]);
  const r = await call("POST", "/admin/ads/creative/upload", { admin: true, raw: png, contentType: "image/png" });
  check("a real PNG uploads", r.status === 200 && r.json.data.content_type === "image/png", r.json);
  check("the upload returns an https URL", /^https:\/\//.test(r.json.data.url), r.json.data.url);
}
{
  // A script that merely CLAIMS to be a PNG must be refused on its bytes.
  const evil = new TextEncoder().encode("<?php system($_GET[0]); ?>-----------------");
  const r = await call("POST", "/admin/ads/creative/upload", { admin: true, raw: evil, contentType: "image/png" });
  check("a file lying about its type is refused", r.status === 415, r.json);
}
{
  const big = new Uint8Array(9 * 1024 * 1024);
  big.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13]);
  const r = await call("POST", "/admin/ads/creative/upload", { admin: true, raw: big, contentType: "image/png" });
  check("an oversized image is refused with 413", r.status === 413, r.json && r.json.error);
}
{
  const r = await call("POST", "/admin/ads/creative/upload", { raw: new Uint8Array([0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10, 0, 0, 0, 13]) });
  check("uploading without the admin token is 401", r.status === 401);
}

console.log("\n=== creative action validation ===");
{
  const r = await makeCampaign({ title: "فعل", creatives: [{ type: "image", url: "https://cdn.test/x.jpg", action: { type: "url", value: "javascript:alert(1)" } }] });
  check("a javascript: action is downgraded to none", r.json.data.campaign.creatives[0].action.type === "none", r.json.data.campaign.creatives[0].action);
  const r2 = await makeCampaign({ title: "فعل2", creatives: [{ type: "image", url: "http://cdn.test/x.jpg" }] });
  check("a plain http creative is refused", r2.status === 400, r2.json);
}

console.log("\n=== cron ===");
{
  await worker.scheduled({ cron: "* * * * *" }, env, ctx);
  await new Promise((r) => setTimeout(r, 50));
  const list = await call("GET", "/admin/ads/campaigns?status=ended", { admin: true });
  check("the cron marks finished campaigns as ended", list.json.data.items.length >= 1, list.json.data.items.length);
  const all = await call("GET", "/admin/ads/campaigns", { admin: true });
  const act = all.json.data.items.find((c) => c.id === activeId);
  check("the cron flips a running campaign to active", act && act.status === "active", act && act.status);
  check("campaigns about to end are flagged", all.json.data.items.some((c) => c.ends_in_days !== null));
}

console.log("\n=== delete cleans up ===");
{
  const r = await call("POST", "/admin/ads/campaign/delete", { admin: true, body: { id: endedId } });
  check("deleting a campaign works", r.status === 200, r.json);
  const list = await call("GET", "/admin/ads/campaigns", { admin: true });
  check("the deleted campaign is gone", !list.json.data.items.some((c) => c.id === endedId));
  const d = await call("POST", "/admin/ads/advertiser/delete", { admin: true, body: { id: advertiserId } });
  check("an advertiser with campaigns cannot be deleted by accident", d.status === 409, d.json);
}

console.log("\n=== slots other than hero ===");
{
  for (const slot of ["splash", "inline", "popup", "sticky", "sponsor"]) {
    const r = await call("GET", "/ads?slot=" + slot + "&lang=ar&did=dev1");
    if (r.status !== 200 || !Array.isArray(r.json.ads)) { check("slot " + slot + " answers", false, r.json); }
  }
  check("every slot answers with an empty list rather than an error", true);
  const bad = await call("GET", "/ads?slot=banana");
  check("an unknown slot is a clean 400", bad.status === 400 && bad.json.ads.length === 0, bad.json);
}

console.log("\n----------------------------------------");
console.log(`  ${pass} passed, ${fail} failed`);
console.log("----------------------------------------\n");
process.exit(fail === 0 ? 0 : 1);
