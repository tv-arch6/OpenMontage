// Phase (هـ): the serving contract AdSlotView depends on, and the report event.
import worker, { AdsStatsDO } from "./worker.mjs";
import { MemoryBucket, makeDoNamespace } from "./harness.mjs";

const ADMIN = "test-admin-token";
const bucket = new MemoryBucket();
const env = { MOVIES_BUCKET: bucket, ADMIN_TOKEN: ADMIN, ADS_AUTH_VERIFY_URL: "https://auth.test/verify" };
env.ADS_STATS = makeDoNamespace(AdsStatsDO, env);
const ctx = { waitUntil: (p) => { if (p && p.catch) p.catch(() => {}); } };

const BASE = "https://api.test";
let pass = 0, fail = 0;
function check(name, cond, extra) {
  if (cond) { pass++; console.log("  PASS  " + name); }
  else { fail++; console.log("  FAIL  " + name + (extra !== undefined ? "  -> " + JSON.stringify(extra).slice(0, 300) : "")); }
}
async function call(method, path, { body, admin } = {}) {
  const headers = {};
  if (admin) headers.authorization = "Bearer " + ADMIN;
  let payload;
  if (body !== undefined) { payload = JSON.stringify(body); headers["content-type"] = "application/json"; }
  const res = await worker.fetch(new Request(BASE + path, { method, headers, body: payload }), env, ctx);
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* ignore */ }
  return { status: res.status, json };
}

const DAY = 86400000;
const iso = (ms) => new Date(ms).toISOString();

console.log("\n=== what the app needs from GET /ads ===");
let advertiserId, campaignId;
{
  const a = await call("POST", "/admin/ads/advertiser/save", { admin: true, body: { name: "شركة العرض" } });
  advertiserId = a.json.data.advertiser.id;
  const c = await call("POST", "/admin/ads/campaign/save", { admin: true, body: {
    advertiser_id: advertiserId, slot: "inline", title: "حملة العرض",
    start_at: iso(Date.now() - DAY), end_at: iso(Date.now() + 2 * DAY),
    daily_cap_per_user: 3,
    creatives: [{ type: "image", url: "https://cdn.test/a.jpg", title: "عنوان", body: "وصف",
                  cta_label: "اطلب", action: { type: "url", value: "https://shop.test" } }],
  } });
  campaignId = c.json.data.campaign.id;
  await call("POST", "/admin/ads/campaign/approve", { admin: true, body: { id: campaignId } });
}
{
  const r = await call("GET", "/ads?slot=inline&lang=ar&did=devE");
  const ad = r.json.ads[0];
  check("the ad carries server_time so the app can correct its clock",
        typeof r.json.server_time_ms === "number" && r.json.server_time_ms > 0, r.json.server_time_ms);
  check("the ad carries ends_at so an expired one can be dropped offline",
        !!ad && typeof ad.ends_at === "string" && ad.ends_at.length > 0, ad && ad.ends_at);
  check("the ad carries daily_cap_per_user for the local cap", ad && ad.daily_cap_per_user === 3, ad);
  check("the ad carries its creative url, title, body and cta",
        ad && ad.creative.url && ad.creative.title && ad.creative.body && ad.creative.cta_label, ad && ad.creative);
  check("the ad carries the action type and value",
        ad && ad.creative.action.type === "url"
        && ad.creative.action.value.indexOf("https://shop.test") === 0,
        ad && ad.creative.action);
  check("the ad carries the 'إعلان' label the UI must show", ad && ad.label === "إعلان");
  check("the ad carries a stable id for once-per-view counting",
        ad && typeof ad.id === "string" && ad.id.indexOf(":") > 0, ad && ad.id);
  check("ttl_seconds tells the app how long to cache", r.json.ttl_seconds > 0, r.json.ttl_seconds);
}
{
  const r = await call("GET", "/ads?slot=sponsor&lang=ar&did=devE");
  check("an empty slot answers with an empty list, not an error",
        r.status === 200 && Array.isArray(r.json.ads) && r.json.ads.length === 0, r.json);
}

console.log("\n=== report is its own event, not an impression ===");
{
  const before = await call("GET", "/admin/ads/report?campaign=" + campaignId, { admin: true });
  const baseline = before.json.data.totals.impressions;

  const r = await call("POST", "/ads/track", { body: { did: "devE", events: [
    { type: "report", campaign_id: campaignId, slot: "inline", nonce: "rep1", reason: "مضلل أو كاذب" },
  ] } });
  check("a report is accepted", r.json.accepted === 1, r.json);
  check("the reply counts it as a report", r.json.reports === 1, r.json);

  const after = await call("GET", "/admin/ads/report?campaign=" + campaignId, { admin: true });
  check("a report does NOT inflate impressions",
        after.json.data.totals.impressions === baseline, { before: baseline, after: after.json.data.totals });
  check("the report is counted as a report", after.json.data.totals.reports === 1, after.json.data.totals);
  check("the admin sees the reason", after.json.data.report_reasons.length === 1
        && after.json.data.report_reasons[0].reason === "مضلل أو كاذب", after.json.data.report_reasons);
}
{
  const r = await call("POST", "/ads/track", { body: { did: "devE", events: [
    { type: "nonsense", campaign_id: campaignId, slot: "inline", nonce: "x1" },
  ] } });
  const after = await call("GET", "/admin/ads/report?campaign=" + campaignId, { admin: true });
  check("an unknown type still falls back to impression (and is counted once)",
        r.json.accepted === 1 && after.json.data.totals.impressions === 1, after.json.data.totals);
}
{
  // A device cannot spam reports to make a campaign look bad.
  let rejected = 0;
  for (let i = 0; i < 6; i++) {
    const r = await call("POST", "/ads/track", { body: { did: "spammer", events: [
      { type: "report", campaign_id: campaignId, slot: "inline", nonce: "sp" + i, reason: "x" },
    ] } });
    rejected += r.json.rejected || 0;
  }
  check("one device cannot report the same campaign more than twice a day", rejected >= 4, rejected);
}
{
  const pub = await call("GET", "/ads/report?token="
      + (await call("GET", "/admin/ads/report?campaign=" + campaignId, { admin: true })).json.data.report_token);
  check("the public report never shows the viewer complaints",
        pub.json.report_reasons === undefined, Object.keys(pub.json));
  check("the public report still shows the numbers", pub.json.totals.impressions >= 1, pub.json.totals);
}

console.log("\n=== an ended campaign is dropped by the server too ===");
{
  const c = await call("POST", "/admin/ads/campaign/save", { admin: true, body: {
    advertiser_id: advertiserId, slot: "popup", title: "منتهية",
    start_at: iso(Date.now() - 10 * DAY), end_at: iso(Date.now() - DAY),
    creatives: [{ type: "image", url: "https://cdn.test/b.jpg" }],
  } });
  await call("POST", "/admin/ads/campaign/approve", { admin: true, body: { id: c.json.data.campaign.id } });
  const g = await call("GET", "/ads?slot=popup&lang=ar&did=devE");
  check("the serving endpoint never returns an expired campaign", g.json.ads.length === 0, g.json.ads);
}

console.log("\n----------------------------------------");
console.log(`  ${pass} passed, ${fail} failed`);
console.log("----------------------------------------\n");
process.exit(fail === 0 ? 0 : 1);
