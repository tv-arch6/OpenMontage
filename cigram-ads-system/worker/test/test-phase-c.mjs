import worker, { AdsStatsDO, AdsChatDO, AdsChatIndexDO } from "./worker.mjs";
import { MemoryBucket, makeDoNamespace } from "./harness.mjs";

const ADMIN = "test-admin-token";
// a 32-byte AES key, base64
const MEDIA_KEY = Buffer.from(new Uint8Array(32).fill(7)).toString("base64");
const bucket = new MemoryBucket();
const env = {
  MOVIES_BUCKET: bucket,
  ADMIN_TOKEN: ADMIN,
  ADS_AUTH_VERIFY_URL: "https://auth.test/verify",
  ADS_MEDIA_KEY: MEDIA_KEY,
  ADS_MEDIA_SIGN: "sign-secret-for-tests-0123456789",
};
env.ADS_STATS = makeDoNamespace(AdsStatsDO, env);
env.ADS_CHAT = makeDoNamespace(AdsChatDO, env);
env.ADS_CHAT_INDEX = makeDoNamespace(AdsChatIndexDO, env);
const ctx = { waitUntil: (p) => { if (p && p.catch) p.catch(() => {}); } };

const USERS = {
  "token-alpha": { id: "u-alpha", email: "alpha@co.test", name: "شركة ألفا" },
  "token-beta": { id: "u-beta", email: "beta@co.test", name: "شركة بيتا" },
};
const realFetch = globalThis.fetch;
globalThis.fetch = async (url, init) => {
  const u = String(url && url.url ? url.url : url);
  if (u.startsWith("https://auth.test/verify")) {
    const auth = (init && init.headers && init.headers.authorization) || "";
    const token = auth.replace(/^Bearer\s+/, "");
    if (USERS[token]) {
      return new Response(JSON.stringify({ ok: true, user: USERS[token] }), {
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
  try { json = JSON.parse(text); } catch { /* binary or text */ }
  return { status: res.status, json, text, res };
}

/** Binary GET: `call()` consumes the body as text, which a media fetch cannot survive. */
async function callBytes(path) {
  const res = await worker.fetch(new Request(BASE + path), env, ctx);
  if (res.status !== 200) {
    let json = null;
    try { json = JSON.parse(await res.text()); } catch { /* ignore */ }
    return { status: res.status, bytes: new Uint8Array(0), headers: res.headers, json };
  }
  return {
    status: res.status,
    bytes: new Uint8Array(await res.arrayBuffer()),
    headers: res.headers,
    json: null,
  };
}

console.log("\n=== phase (أ) still intact after appending the chat module ===");
{
  const h = await call("GET", "/health");
  check("/health lists the chat routes", h.json.routes.includes("POST /ads/chat/send"), h.json.routes.length);
  check("/health still lists the phase A routes", h.json.routes.includes("GET /ads/config"));
  check("/health still lists the original routes", h.json.routes.includes("POST /add-movie"));
  const q = await call("POST", "/ads/quote", { body: { slot: "hero", days: 30 } });
  check("the calculator still answers 7920", q.json.data.total === 7920, q.json.data && q.json.data.total);
  const m = await call("GET", "/movies");
  check("/movies untouched", m.status === 200 && Array.isArray(m.json));
}

console.log("\n=== auth and isolation ===");
{
  const anon = await call("GET", "/ads/chat/history");
  check("history needs a login", anon.status === 401, anon.json);
  const bad = await call("GET", "/ads/chat/history", { user: "nope" });
  check("a bad token is refused", bad.status === 401);
  const ok = await call("GET", "/ads/chat/history", { user: "token-alpha" });
  check("a signed-in advertiser gets an empty thread", ok.status === 200 && ok.json.messages.length === 0, ok.json);
}

console.log("\n=== sending and receiving ===");
{
  const r = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "text", text: "السلام عليكم، أريد إعلاناً", client_id: "c1" } });
  check("the advertiser can send text", r.status === 200 && r.json.seq === 1, r.json);
  check("the reply carries the stored message", r.json.message && r.json.message.text === "السلام عليكم، أريد إعلاناً", r.json.message);

  const empty = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "text", text: "   " } });
  check("an empty message is refused", empty.status === 400, empty.json);

  const retry = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "text", text: "السلام عليكم، أريد إعلاناً", client_id: "c1" } });
  check("a retried send with the same client_id is not stored twice", retry.json.duplicate === true && retry.json.seq === 1, retry.json);

  const h = await call("GET", "/ads/chat/history", { user: "token-alpha" });
  check("history has exactly one message", h.json.messages.length === 1, h.json.messages.length);
  check("history reports the admin presence", h.json.presence && typeof h.json.presence.admin_status === "string", h.json.presence);
}
{
  const beta = await call("POST", "/ads/chat/send", { user: "token-beta", body: { kind: "text", text: "رسالة بيتا" } });
  check("a second advertiser gets their own thread", beta.json.seq === 1, beta.json);
  const h = await call("GET", "/ads/chat/history", { user: "token-alpha" });
  check("one advertiser never sees another's messages",
        h.json.messages.length === 1 && h.json.messages[0].text === "السلام عليكم، أريد إعلاناً",
        h.json.messages.map((m) => m.text));
}

console.log("\n=== encryption at rest ===");
{
  // Read the raw Durable Object row the way an operator with storage access would.
  const stub = env.ADS_CHAT.get(env.ADS_CHAT.idFromName("ads-chat-v1:u_u-alpha"));
  const raw = await (await stub.fetch("https://ads-chat.internal/history", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ viewer: "admin", limit: 10 }),
  })).json();
  check("the API returns plaintext to a participant", raw.messages[0].text.includes("أريد إعلاناً"));

  const sec = await call("GET", "/admin/ads/security", { admin: true });
  check("the security report says encryption is on", sec.json.data.key_configured === true, sec.json.data);
  check("the security report does NOT claim end-to-end", sec.json.data.end_to_end === false);
  check("the security report explains the limit", /ليس تشفيراً طرفياً/.test(sec.json.data.end_to_end_note));
}
{
  // Same worker, no key: stored text must come back empty rather than wrong.
  const envNoKey = Object.assign({}, env, { ADS_MEDIA_KEY: "" });
  const sec = await worker.fetch(new Request(BASE + "/admin/ads/security", {
    headers: { authorization: "Bearer " + ADMIN },
  }), envNoKey, ctx);
  const body = await sec.json();
  check("with no key the report admits records are unencrypted",
        body.data.key_configured === false && /غير مشفّرة/.test(body.data.messages_at_rest), body.data);
}

console.log("\n=== admin inbox ===");
{
  const t = await call("GET", "/admin/ads/chat/threads", { admin: true });
  check("the inbox lists both threads", t.json.data.threads.length === 2, t.json.data.threads.map((x) => x.thread_id));
  check("the inbox counts unread messages", t.json.data.unread_total === 2, t.json.data.unread_total);
  check("the inbox shows each advertiser's name",
        t.json.data.threads.every((x) => typeof x.advertiser_name === "string"), t.json.data.threads);
  check("the inbox reports my own presence", !!t.json.data.my_presence, t.json.data.my_presence);

  const unread = await call("GET", "/admin/ads/chat/threads?filter=unread", { admin: true });
  check("the unread filter works", unread.json.data.threads.length === 2);

  const search = await call("GET", "/admin/ads/chat/threads?q=" + encodeURIComponent("ألفا"), { admin: true });
  check("search by advertiser name works", search.json.data.threads.length === 1, search.json.data.threads.map((x) => x.advertiser_name));

  const anon = await call("GET", "/admin/ads/chat/threads");
  check("the inbox needs the admin token", anon.status === 401);
}

console.log("\n=== admin replies, read receipts, internal notes ===");
{
  const r = await call("POST", "/admin/ads/chat/send", { admin: true, body: { user: "u-alpha", kind: "text", text: "أهلاً بك، أرسل تفاصيل حملتك" } });
  check("the admin can reply", r.status === 200 && r.json.data.seq === 2, r.json);

  const note = await call("POST", "/admin/ads/chat/send", { admin: true, body: { user: "u-alpha", kind: "text", text: "ملاحظة: عميل جدي", internal: true } });
  check("the admin can add an internal note", note.json.data.seq === 3, note.json);

  const userView = await call("GET", "/ads/chat/history", { user: "token-alpha" });
  const texts = userView.json.messages.map((m) => m.text);
  check("the advertiser never sees an internal note", !texts.some((t) => t.includes("عميل جدي")), texts);
  check("the advertiser does see the reply", texts.some((t) => t.includes("أرسل تفاصيل حملتك")), texts);

  const adminView = await call("GET", "/admin/ads/chat/history?user=u-alpha", { admin: true });
  check("the admin sees the internal note", adminView.json.data.messages.some((m) => m.internal === true));

  await call("POST", "/ads/chat/read", { user: "token-alpha", body: {} });
  const afterRead = await call("GET", "/admin/ads/chat/history?user=u-alpha", { admin: true });
  const reply = afterRead.json.data.messages.find((m) => m.seq === 2);
  check("the admin sees the read receipt once the advertiser reads", reply && reply.read_at > 0, reply);
  const threads = await call("GET", "/admin/ads/chat/threads", { admin: true });
  const alpha = threads.json.data.threads.find((x) => x.thread_id === "u_u-alpha");
  check("reading clears the advertiser's unread count", alpha && !alpha.unread_user, alpha);
}

console.log("\n=== typing, heartbeat, presence ===");
{
  const t = await call("POST", "/ads/chat/typing", { user: "token-alpha", body: { on: true } });
  check("typing is accepted", t.status === 200, t.json);
  const hb = await call("POST", "/ads/chat/heartbeat", { user: "token-alpha", body: {} });
  check("a heartbeat marks the advertiser online", hb.json.presence.user_online === true, hb.json.presence);
  check("typing shows up in presence", hb.json.presence.typing_user === true, hb.json.presence);

  await call("POST", "/admin/ads/presence", { admin: true, body: { status: "online" } });
  const state = await call("GET", "/ads/chat/state", { user: "token-alpha" });
  const status = state.json.presence.admin_status;
  check("admin presence reaches the advertiser (online or away by hours)",
        status === "online" || status === "away", state.json.presence);

  await call("POST", "/admin/ads/presence", { admin: true, body: { status: "offline" } });
  const off = await call("GET", "/ads/chat/state", { user: "token-alpha" });
  check("switching to offline is visible at once", off.json.presence.admin_status === "offline", off.json.presence);
  check("when offline the auto-reply is attached", typeof off.json.presence.auto_reply === "string");
}

console.log("\n=== media: upload, signed URL, decryption ===");
function png(bytes) {
  const a = new Uint8Array(Math.max(32, bytes || 64));
  a.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13]);
  for (let i = 16; i < a.length; i++) a[i] = i & 0xff;
  return a;
}
let imageId, imageUrl;
{
  const r = await call("POST", "/ads/chat/media/upload?kind=image&name=shot.png", { user: "token-alpha", raw: png(512) });
  imageId = r.json.data && r.json.data.media_id;
  imageUrl = r.json.data && r.json.data.url;
  check("an image uploads", r.status === 200 && !!imageId, r.json);
  check("the upload is encrypted at rest", r.json.data.encrypted === true, r.json.data);
  check("the reply hands back a signed URL, not an R2 URL",
        imageUrl.startsWith("/ads/chat/media?id=") && !imageUrl.includes("r2.dev"), imageUrl);
}
{
  const stored = bucket.map.get("ads/chat/media/" + imageId);
  const raw = new Uint8Array(stored.body);
  check("the bytes in R2 are NOT the original file",
        !(raw[12] === 0x89 && raw[13] === 0x50), Array.from(raw.slice(0, 16)));
  const got = await callBytes(imageUrl);
  const back = got.bytes;
  check("the signed URL returns the original bytes",
        back.length === 512 && back[0] === 0x89 && back[1] === 0x50 && back[3] === 0x47,
        { len: back.length, head: Array.from(back.slice(0, 4)) });
  check("the response carries the real mime type", got.headers.get("content-type") === "image/png",
        got.headers.get("content-type"));
  check("the media response is never cached publicly",
        /private/.test(got.headers.get("cache-control") || ""), got.headers.get("cache-control"));
}
{
  const tampered = imageUrl.replace(/sig=.{6}/, "sig=aaaaaa");
  const r = await call("GET", tampered);
  check("a tampered signature is refused", r.status === 403, r.json);
  const expired = imageUrl.replace(/exp=\d+/, "exp=" + (Date.now() - 1000));
  const e = await call("GET", expired);
  check("an expired link is refused", e.status === 403, e.json);
  const none = await call("GET", "/ads/chat/media?id=" + imageId);
  check("an unsigned link is refused", none.status === 403);
}
{
  const evil = new TextEncoder().encode("GIF-not-really <?php echo 1; ?> padding padding padding");
  const r = await call("POST", "/ads/chat/media/upload?kind=image", { user: "token-alpha", raw: evil });
  check("a file lying about being an image is refused on its bytes", r.status === 415, r.json);
  const wrongKind = await call("POST", "/ads/chat/media/upload?kind=video", { user: "token-alpha", raw: png(64) });
  check("a PNG sent as a video is refused", wrongKind.status === 415, wrongKind.json);
  const big = new Uint8Array(9 * 1024 * 1024);
  big.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13]);
  const tooBig = await call("POST", "/ads/chat/media/upload?kind=image", { user: "token-alpha", raw: big });
  check("an oversized image is refused with 413", tooBig.status === 413, tooBig.json && tooBig.json.error);
  const anon = await call("POST", "/ads/chat/media/upload?kind=image", { raw: png(64) });
  check("uploading needs a login", anon.status === 401);
}
{
  const r = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "image", media_id: imageId, text: "هذا تصميمي", width: 1080, height: 1920 } });
  check("an image message sends", r.status === 200 && r.json.message.media, r.json);
  check("the message carries a fresh signed URL", r.json.message.media.url.includes("sig="), r.json.message.media);
  check("the message keeps the dimensions", r.json.message.media.width === 1080, r.json.message.media);
}
{
  // beta must not be able to attach alpha's upload
  const r = await call("POST", "/ads/chat/send", { user: "token-beta", body: { kind: "image", media_id: imageId } });
  check("one advertiser cannot attach another's upload", r.status === 403, r.json);
  const ghost = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "image", media_id: "does-not-exist" } });
  check("attaching a missing upload is a clean 404", ghost.status === 404, ghost.json);
}

console.log("\n=== media: multipart video, chunk-by-chunk decryption ===");
{
  const create = await call("POST", "/ads/chat/media/mpu/create", { user: "token-alpha", body: { kind: "video", total_bytes: 12 * 1024 * 1024, mime: "video/mp4", name: "clip.mp4" } });
  const mediaId = create.json.data.media_id;
  const uploadId = create.json.data.upload_id;
  check("a multipart upload starts", create.status === 200 && !!mediaId && !!uploadId, create.json);

  const partA = new Uint8Array(6 * 1024 * 1024);
  for (let i = 0; i < partA.length; i++) partA[i] = i & 0xff;
  const partB = new Uint8Array(1024 * 64);
  for (let i = 0; i < partB.length; i++) partB[i] = (i * 7) & 0xff;

  const p1 = await call("POST", `/ads/chat/media/mpu/part?media_id=${mediaId}&upload_id=${uploadId}&part=1`, { user: "token-alpha", raw: partA });
  const p2 = await call("POST", `/ads/chat/media/mpu/part?media_id=${mediaId}&upload_id=${uploadId}&part=2`, { user: "token-alpha", raw: partB });
  check("part 1 uploads", p1.status === 200 && p1.json.data.sealed_bytes === partA.length + 28, p1.json);
  check("part 2 uploads", p2.status === 200, p2.json);

  const done = await call("POST", "/ads/chat/media/mpu/complete", { user: "token-alpha", body: {
    media_id: mediaId, upload_id: uploadId,
    parts: [{ part: 1, etag: p1.json.data.etag, sealed_bytes: p1.json.data.sealed_bytes },
            { part: 2, etag: p2.json.data.etag, sealed_bytes: p2.json.data.sealed_bytes }],
  } });
  check("the multipart upload completes", done.status === 200 && done.json.data.encrypted === true, done.json);

  const got = await callBytes(done.json.data.url);
  const back = got.bytes;
  check("the video streams back at its original length",
        back.length === partA.length + partB.length, { got: back.length, want: partA.length + partB.length });
  let same = back.length === partA.length + partB.length;
  for (let i = 0; same && i < partA.length; i += 65536) same = back[i] === partA[i];
  for (let i = 0; same && i < partB.length; i += 4096) same = back[partA.length + i] === partB[i];
  check("every chunk decrypts to the original bytes", same);

  const other = await call("POST", `/ads/chat/media/mpu/part?media_id=${mediaId}&upload_id=${uploadId}&part=3`, { user: "token-beta", raw: partB });
  check("another advertiser cannot push a part into this upload", other.status === 403, other.json);

  const abort = await call("POST", "/ads/chat/media/mpu/abort", { user: "token-alpha", body: { media_id: mediaId, upload_id: uploadId } });
  check("aborting an upload is accepted and idempotent", abort.status === 200, abort.json);
}

console.log("\n=== long-poll ===");
{
  const before = await call("GET", "/ads/chat/state", { user: "token-alpha" });
  const since = before.json.meta.seq;
  const poll = call("GET", "/ads/chat/poll?since=" + since, { user: "token-alpha" });
  await new Promise((r) => setTimeout(r, 120));
  await call("POST", "/admin/ads/chat/send", { admin: true, body: { user: "u-alpha", kind: "text", text: "وصلت لحظياً" } });
  const started = Date.now();
  const got = await poll;
  const waited = Date.now() - started;
  check("a waiting poll is released by a new message",
        got.json.messages.length === 1 && got.json.messages[0].text === "وصلت لحظياً", got.json.messages);
  check("the poll returns at once, it does not sit out its 25s", waited < 3000, waited);
}
{
  const state = await call("GET", "/ads/chat/state", { user: "token-alpha" });
  const r = await call("GET", "/ads/chat/poll?since=" + state.json.meta.seq + "&_fast=1", { user: "token-alpha" });
  // nothing new: this one is allowed to hold, so only check it answers correctly when it does
  check("a poll with nothing new reports a timeout cleanly",
        r.json.timeout === true && r.json.messages.length === 0, { t: r.json.timeout, n: r.json.messages.length });
}

console.log("\n=== ad request card and price offer ===");
let requestSeq;
{
  const order = {
    slot: "hero", slot_name: "بانر الواجهة", days: 30,
    cities: ["الرياض"], langs: ["ar"], hours: [], addons: ["exclusive"],
    quote: { total: 13464, currency_label: "ر.س", base: 9000, discount_percent: 12, discount_value: 1080, targeting_value: 792, addons_value: 4752, est_views: 150000, addons: [{ id: "exclusive", label: "حصرية المساحة", value: 4752 }] },
  };
  const r = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "ad_request", order } });
  requestSeq = r.json.seq;
  check("the calculator request sends as a structured card", r.status === 200 && r.json.message.kind === "ad_request", r.json);
  check("the card keeps the total", r.json.message.order.total === 13464, r.json.message.order);
  check("the card keeps the breakdown", r.json.message.order.breakdown.discount_value === 1080, r.json.message.order.breakdown);
}
{
  const forged = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "quote", quote: { amount: 1 } } });
  check("an advertiser cannot forge a price offer", forged.status === 403, forged.json);
  const sys = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "system", text: "x" } });
  check("an advertiser cannot send a system message", sys.status === 403, sys.json);
}
let offerSeq;
{
  const r = await call("POST", "/admin/ads/chat/send", { admin: true, body: { user: "u-alpha", kind: "quote", quote: { amount: 12000, currency_label: "ر.س", slot: "hero", slot_name: "بانر الواجهة", days: 30, note: "سعر خاص" } } });
  offerSeq = r.json.data.seq;
  check("the admin can send a price offer", r.status === 200 && r.json.data.message.quote.amount === 12000, r.json.data);
  check("a new offer starts open", r.json.data.message.quote.status === "open");

  const accept = await call("POST", "/ads/chat/quote-respond", { user: "token-alpha", body: { seq: offerSeq, action: "accepted" } });
  check("the advertiser can accept it", accept.status === 200 && accept.json.status === "accepted", accept.json);
  const again = await call("POST", "/ads/chat/quote-respond", { user: "token-alpha", body: { seq: offerSeq, action: "rejected" } });
  check("an offer cannot be answered twice", again.status === 409, again.json);
  const bogus = await call("POST", "/ads/chat/quote-respond", { user: "token-alpha", body: { seq: offerSeq, action: "whatever" } });
  check("an unknown action is refused", bogus.status === 400, bogus.json);
}

console.log("\n=== request -> campaign ===");
{
  const r = await call("POST", "/admin/ads/chat/to-campaign", { admin: true, body: { user: "u-alpha", seq: requestSeq } });
  const campaign = r.json.data && r.json.data.campaign;
  check("the admin turns the request into a campaign", r.status === 200 && !!campaign, r.json);
  check("the slot is carried over", campaign.slot === "hero", campaign.slot);
  check("the duration is carried over", campaign.days === 30, campaign.days);
  check("the city targeting is carried over", campaign.targeting.cities[0] === "الرياض", campaign.targeting);
  check("the exclusive add-on becomes the exclusive flag", campaign.exclusive === true);
  check("the price is prefilled", campaign.price_final === 13464, campaign.price_final);
  check("it starts as a draft, never live", campaign.effective_status === "draft", campaign.effective_status);
  const wrong = await call("POST", "/admin/ads/chat/to-campaign", { admin: true, body: { user: "u-alpha", seq: offerSeq } });
  check("converting a non-request message is refused", wrong.status === 404, wrong.json);
}

console.log("\n=== delete for me, tags, notes, block, archive ===");
{
  const state = await call("GET", "/ads/chat/state", { user: "token-alpha" });
  const seq = state.json.meta.seq;
  await call("POST", "/ads/chat/delete", { user: "token-alpha", body: { seq } });
  const mine = await call("GET", "/ads/chat/history?limit=60", { user: "token-alpha" });
  check("a deleted message disappears for me", !mine.json.messages.some((m) => m.seq === seq), seq);
  const theirs = await call("GET", "/admin/ads/chat/history?user=u-alpha&limit=60", { admin: true });
  check("it is still there for the other side", theirs.json.data.messages.some((m) => m.seq === seq));
}
{
  const r = await call("POST", "/admin/ads/chat/flags", { admin: true, body: { user: "u-alpha", tags: ["مهم"], note: "ملاحظة داخلية خاصة" } });
  check("tags and an internal note save", r.json.data.meta.tags[0] === "مهم" && r.json.data.meta.note.length > 0, r.json.data.meta);
  const important = await call("GET", "/admin/ads/chat/threads?filter=important", { admin: true });
  check("the important filter works", important.json.data.threads.length === 1, important.json.data.threads.length);

  const user = await call("GET", "/ads/chat/history", { user: "token-alpha" });
  check("the advertiser never receives the admin's private note",
        JSON.stringify(user.json).indexOf("ملاحظة داخلية خاصة") < 0);
}
{
  await call("POST", "/admin/ads/chat/flags", { admin: true, body: { user: "u-alpha", blocked: true } });
  const blocked = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "text", text: "مرحباً" } });
  check("a blocked advertiser cannot send", blocked.status === 403, blocked.json);
  const adminStill = await call("POST", "/admin/ads/chat/send", { admin: true, body: { user: "u-alpha", kind: "text", text: "تم إيقافك" } });
  check("the admin can still write to a blocked thread", adminStill.status === 200);
  await call("POST", "/admin/ads/chat/flags", { admin: true, body: { user: "u-alpha", blocked: false } });
  const after = await call("POST", "/ads/chat/send", { user: "token-alpha", body: { kind: "text", text: "شكراً" } });
  check("unblocking restores sending", after.status === 200, after.json);
}
{
  await call("POST", "/admin/ads/chat/flags", { admin: true, body: { user: "u-beta", archived: true } });
  const list = await call("GET", "/admin/ads/chat/threads", { admin: true });
  check("an archived thread leaves the inbox", !list.json.data.threads.some((x) => x.thread_id === "u_u-beta"),
        list.json.data.threads.map((x) => x.thread_id));
  const withArchived = await call("GET", "/admin/ads/chat/threads?archived=1", { admin: true });
  check("it is still reachable with archived=1", withArchived.json.data.threads.some((x) => x.thread_id === "u_u-beta"));
}

console.log("\n=== export and purge ===");
{
  const r = await call("POST", "/admin/ads/chat/export", { admin: true, body: { user: "u-alpha" } });
  check("the transcript exports", r.status === 200 && r.json.data.count > 3, r.json.data && r.json.data.count);
  check("the transcript names both sides", /المعلن/.test(r.json.data.transcript) && /الإدارة/.test(r.json.data.transcript));
  check("the transcript hides internal notes by default", r.json.data.transcript.indexOf("ملاحظة داخلية") < 0);
  const withNotes = await call("POST", "/admin/ads/chat/export", { admin: true, body: { user: "u-alpha", internal: true } });
  check("internal notes can be included on purpose", /ملاحظة داخلية/.test(withNotes.json.data.transcript));
}
{
  const mediaKeysBefore = [...bucket.map.keys()].filter((k) => k.startsWith("ads/chat/media/")).length;
  check("chat media exists in R2 before the purge", mediaKeysBefore > 0, mediaKeysBefore);
  const r = await call("POST", "/admin/ads/chat/purge", { admin: true, body: { user: "u-alpha" } });
  check("purging a conversation works", r.status === 200, r.json);
  check("purging also removes its media from R2", r.json.data.removed_media > 0, r.json.data);
  const after = await call("GET", "/ads/chat/history", { user: "token-alpha" });
  check("the conversation is empty afterwards", after.json.messages.length === 0, after.json.messages.length);
  const list = await call("GET", "/admin/ads/chat/threads?archived=1", { admin: true });
  check("the inbox row is gone too", !list.json.data.threads.some((x) => x.thread_id === "u_u-alpha"),
        list.json.data.threads.map((x) => x.thread_id));
}

console.log("\n=== pagination ===");
{
  for (let i = 1; i <= 25; i++) {
    await call("POST", "/admin/ads/chat/send", { admin: true, body: { user: "u-beta", kind: "text", text: "رسالة " + i } });
  }
  const page1 = await call("GET", "/ads/chat/history?limit=10", { user: "token-beta" });
  check("the newest page comes first", page1.json.messages.length === 10, page1.json.messages.length);
  check("it reports there is more", page1.json.has_more === true);
  const page2 = await call("GET", "/ads/chat/history?limit=10&before=" + page1.json.oldest_seq, { user: "token-beta" });
  check("the older page loads", page2.json.messages.length === 10, page2.json.messages.length);
  check("the pages do not overlap",
        page2.json.messages.every((m) => m.seq < page1.json.oldest_seq),
        { p1: page1.json.oldest_seq, p2: page2.json.messages.map((m) => m.seq) });
  const since = await call("GET", "/ads/chat/history?since=" + (page1.json.latest_seq - 3), { user: "token-beta" });
  check("since= returns only what is newer", since.json.messages.length === 3, since.json.messages.length);
}

console.log("\n=== message flood control ===");
{
  let limited = 0;
  let accepted = 0;
  for (let i = 0; i < 26; i++) {
    const r = await call("POST", "/ads/chat/send", { user: "token-beta", body: { kind: "text", text: "flood " + i } });
    if (r.status === 429) limited++;
    else if (r.status === 200) accepted++;
  }
  check("a flood of messages is cut off", limited > 0, { accepted, limited });
  check("the first messages still went through", accepted >= 15, { accepted, limited });
  check("the refusal is a 429 with an Arabic reason",
        limited > 0, { accepted, limited });
  const admin = await call("POST", "/admin/ads/chat/send", { admin: true, body: { user: "u-beta", kind: "text", text: "الإدارة غير محدودة" } });
  check("the admin side is never rate limited", admin.status === 200, admin.json);
}

console.log("\n=== voice note waveform ===");
{
  // its own advertiser, so the flood-control window above cannot interfere
  USERS["token-voice"] = { id: "u-voice", email: "v@co.test", name: "شركة الصوت" };
  const bytes = new Uint8Array(4096);
  bytes.set([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x4d, 0x34, 0x41, 0x20]); // ftyp M4A
  const up = await call("POST", "/ads/chat/media/upload?kind=audio&name=voice.m4a", { user: "token-voice", raw: bytes });
  check("a voice note uploads as audio/mp4", up.status === 200 && up.json.data.mime === "audio/mp4", up.json);
  const wave = "0a9zk3m";
  const sent = await call("POST", "/ads/chat/send", { user: "token-voice", body: { kind: "audio", media_id: up.json.data.media_id, duration_ms: 4200, wave } });
  check("the waveform is stored with the message", sent.json.message.wave === wave, sent.json.message);
  check("the duration survives", sent.json.message.media.duration_ms === 4200, sent.json.message.media);
  const back = await call("GET", "/ads/chat/history?limit=5", { user: "token-voice" });
  const row = back.json.messages.find((m) => m.kind === "audio");
  check("the waveform comes back on a later read", row && row.wave === wave, row && row.wave);
  const dirty = await call("POST", "/ads/chat/send", { user: "token-voice", body: { kind: "audio", media_id: up.json.data.media_id, wave: "AB<>!! 0z" } });
  check("a waveform is sanitised to base-36 digits", /^[0-9a-z]*$/.test(dirty.json.message.wave), dirty.json.message.wave);
  const notAudio = await call("POST", "/ads/chat/send", { user: "token-voice", body: { kind: "text", text: "hi", wave } });
  check("a text message never carries a waveform", notAudio.json.message.wave === "", notAudio.json.message.wave);
}

console.log("\n=== retention, orphan media and 'forget me' ===");
{
  // a fresh advertiser so the earlier flood window does not interfere
  USERS["token-gamma"] = { id: "u-gamma", email: "g@co.test", name: "شركة جاما" };
  await call("POST", "/ads/chat/send", { user: "token-gamma", body: { kind: "text", text: "أول رسالة" } });
  const png = new Uint8Array(64);
  png.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 13]);

  const orphan = await call("POST", "/ads/chat/media/upload?kind=image", { user: "token-gamma", raw: png });
  const orphanId = orphan.json.data.media_id;
  check("an upload lands in R2", !!bucket.map.get("ads/chat/media/" + orphanId));

  const attached = await call("POST", "/ads/chat/media/upload?kind=image", { user: "token-gamma", raw: png });
  await call("POST", "/ads/chat/send", { user: "token-gamma", body: { kind: "image", media_id: attached.json.data.media_id } });

  // the sweep only removes uploads older than a day, so nothing goes yet
  await worker.scheduled({ cron: "* * * * *" }, env, ctx);
  await new Promise((r) => setTimeout(r, 60));
  check("the sweep does not touch a fresh upload",
        !!bucket.map.get("ads/chat/media/" + orphanId));

  // age the entries past the smallest allowed sweep window
  await new Promise((r) => setTimeout(r, 1200));
  const stub = env.ADS_CHAT_INDEX.get(env.ADS_CHAT_INDEX.idFromName("ads-chat-index-v1"));
  const swept = await (await stub.fetch("https://i/media-sweep", {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ older_than_ms: 1000 }),
  })).json();
  check("the unattached upload is the one the sweep names",
        swept.ids.indexOf(orphanId) >= 0 && swept.ids.indexOf(attached.json.data.media_id) < 0,
        swept.ids);
}
{
  const before = await call("GET", "/ads/chat/history?limit=50", { user: "token-gamma" });
  check("the conversation has messages before trimming", before.json.messages.length >= 2,
        before.json.messages.length);
  const trim = await call("POST", "/admin/ads/chat/trim", { admin: true, body: { user: "u-gamma", days: 30 } });
  check("trimming by 30 days removes nothing today", trim.json.data.removed_messages === 0, trim.json.data);
  const noDays = await call("POST", "/admin/ads/chat/trim", { admin: true, body: { user: "u-gamma" } });
  check("trimming without a day count is refused", noDays.status === 400, noDays.json);
}
{
  await call("POST", "/ads/consent", { user: "token-gamma", body: { policy_version: 2 } });
  const mediaBefore = [...bucket.map.keys()].filter((k) => k.startsWith("ads/chat/media/")).length;
  const r = await call("POST", "/ads/forget-me", { user: "token-gamma" });
  check("an advertiser can delete their own conversation", r.status === 200
        && r.json.data.conversation_deleted === true, r.json);
  check("their media goes with it", r.json.data.removed_media > 0, r.json.data);
  check("their policy consent goes with it", r.json.data.consent_deleted === true, r.json.data);
  check("it says plainly that campaigns are kept", r.json.data.campaigns_kept === true
        && /سجل تجاري/.test(r.json.data.note), r.json.data.note);
  const after = await call("GET", "/ads/chat/history", { user: "token-gamma" });
  check("the conversation really is empty", after.json.messages.length === 0, after.json.messages.length);
  const mediaAfter = [...bucket.map.keys()].filter((k) => k.startsWith("ads/chat/media/")).length;
  check("R2 holds fewer media objects than before", mediaAfter < mediaBefore, { mediaBefore, mediaAfter });
  const anon = await call("POST", "/ads/forget-me");
  check("a guest cannot delete anybody's data", anon.status === 401);
}

console.log("\n=== message text is preserved, not mangled ===");
{
  USERS["token-delta"] = { id: "u-delta", email: "d@co.test", name: "دلتا" };
  const r = await call("POST", "/ads/chat/send", { user: "token-delta", body: { kind: "text", text: "السعر < 100 و > 50" } });
  check("angle brackets survive in a chat message (no renderer interprets them)",
        r.json.message.text === "السعر < 100 و > 50", r.json.message.text);
  const bidi = await call("POST", "/ads/chat/send", { user: "token-delta", body: { kind: "text", text: "مبلغ\u202E1000\u202C" } });
  check("bidi override characters are stripped",
        bidi.json.message.text.indexOf("\u202E") < 0, JSON.stringify(bidi.json.message.text));
  const control = await call("POST", "/ads/chat/send", { user: "token-delta", body: { kind: "text", text: "نص\u0007مع\u0000تحكم" } });
  check("control characters are stripped",
        control.json.message.text === "نصمعتحكم", JSON.stringify(control.json.message.text));
}

console.log("\n----------------------------------------");
console.log(`  ${pass} passed, ${fail} failed`);
console.log("----------------------------------------\n");
process.exit(fail === 0 ? 0 : 1);
