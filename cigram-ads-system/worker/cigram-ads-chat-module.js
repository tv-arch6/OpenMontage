// =============================================================================
// CIGRAM ADS v1 — PHASE (ج): CHAT, PRESENCE AND MEDIA
// -----------------------------------------------------------------------------
// APPEND THIS FILE after `cigram-ads-module.js` (it uses its helpers), then add
// the chat routes to the router — `apply-ads-patch.py` does both for you.
//
// Reuses from cigram-ads-module.js:
//   adsText adsInt adsBool adsNow adsIso adsParseTime adsId adsToken adsHash
//   adsFail adsErrorResponse adsPublicRoute adsUserRoute adsAdminRoute
//   adsReadSettings adsEffectivePresence adsArray adsSafeUrl adsSniffType
// and from the base worker: json corsHeaders cleanString readRequestData okResponse
//
// Bindings added in this phase:
//   ADS_CHAT       Durable Object -> class AdsChatDO       (one per advertiser)
//   ADS_CHAT_INDEX Durable Object -> class AdsChatIndexDO  (single inbox index)
//   ADS_MEDIA_KEY  secret, 32 bytes base64 — AES-GCM key for data at rest
//   ADS_MEDIA_SIGN secret, any long random string — signs media URLs
//
// Transport: every client gets BOTH a WebSocket (`/ws`) and a 25-second
// long-poll (`/poll`) over the same Durable Object state, so a client that
// cannot keep a socket open still receives messages within a second.
// =============================================================================

const ADS_CHAT_MEDIA_PREFIX = "ads/chat/";
const ADS_CHAT_MAX_TEXT = 4000;
const ADS_CHAT_PAGE = 40;
const ADS_CHAT_POLL_MS = 25000;
const ADS_CHAT_HEARTBEAT_MS = 25000;
/** A user counts as online this long after their last heartbeat. */
const ADS_CHAT_ONLINE_GRACE_MS = 70000;
const ADS_CHAT_MAX_IMAGE = 8 * 1024 * 1024;
const ADS_CHAT_MAX_VIDEO = 64 * 1024 * 1024;
const ADS_CHAT_MAX_AUDIO = 12 * 1024 * 1024;
const ADS_CHAT_MAX_FILE = 20 * 1024 * 1024;
const ADS_CHAT_MEDIA_URL_TTL_MS = 10 * 60 * 1000;

const ADS_CHAT_KINDS = ["text", "image", "video", "audio", "file", "ad_request", "quote", "system"];

// -----------------------------------------------------------------------------
// encryption at rest (AES-GCM, fresh IV per record)
// -----------------------------------------------------------------------------

let adsCryptoKeyPromise = null;

/**
 * Imports ADS_MEDIA_KEY once per isolate. Returns null when the secret is absent,
 * in which case records are stored as plain text — the routes still work, and
 * /admin/ads/chat/security reports that encryption is OFF rather than pretending.
 */
function adsCryptoKey(env) {
  const raw = cleanString(env.ADS_MEDIA_KEY);
  if (!raw) return Promise.resolve(null);
  if (!adsCryptoKeyPromise) {
    adsCryptoKeyPromise = (async () => {
      try {
        const bytes = Uint8Array.from(atob(raw), (ch) => ch.charCodeAt(0));
        if (bytes.length !== 32) return null;
        return await crypto.subtle.importKey("raw", bytes, { name: "AES-GCM" }, false, [
          "encrypt",
          "decrypt",
        ]);
      } catch {
        return null;
      }
    })();
  }
  return adsCryptoKeyPromise;
}

function adsB64(bytes) {
  let s = "";
  const view = new Uint8Array(bytes);
  for (let i = 0; i < view.length; i++) s += String.fromCharCode(view[i]);
  return btoa(s);
}

function adsUnB64(text) {
  const s = atob(String(text || ""));
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}

/** @returns {Promise<{e:1,iv:string,ct:string}|{e:0,v:string}>} */
async function adsSeal(env, plainText) {
  const text = String(plainText === undefined || plainText === null ? "" : plainText);
  const key = await adsCryptoKey(env);
  if (!key) return { e: 0, v: text };
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, new TextEncoder().encode(text));
  return { e: 1, iv: adsB64(iv), ct: adsB64(ct) };
}

async function adsOpen(env, record) {
  if (!record || typeof record !== "object") return "";
  if (record.e !== 1) return String(record.v === undefined ? "" : record.v);
  const key = await adsCryptoKey(env);
  if (!key) return "";
  try {
    const plain = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: adsUnB64(record.iv) },
      key,
      adsUnB64(record.ct)
    );
    return new TextDecoder().decode(plain);
  } catch {
    return "";
  }
}

/** Encrypts raw media bytes; returns the stored buffer with the IV prefixed. */
async function adsSealBytes(env, buffer) {
  const key = await adsCryptoKey(env);
  if (!key) return { body: buffer, encrypted: false };
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, buffer);
  const out = new Uint8Array(12 + ct.byteLength);
  out.set(iv, 0);
  out.set(new Uint8Array(ct), 12);
  return { body: out.buffer, encrypted: true };
}

async function adsOpenBytes(env, buffer, encrypted) {
  if (!encrypted) return buffer;
  const key = await adsCryptoKey(env);
  if (!key) throw adsFail("key_missing", "مفتاح فك التشفير غير متوفر في الخادم.", 500);
  const view = new Uint8Array(buffer);
  const iv = view.slice(0, 12);
  const ct = view.slice(12);
  return await crypto.subtle.decrypt({ name: "AES-GCM", iv }, key, ct);
}

// -----------------------------------------------------------------------------
// signed media URLs (no public R2 object is ever exposed)
// -----------------------------------------------------------------------------

async function adsSignKey(env) {
  const secret = cleanString(env.ADS_MEDIA_SIGN) || cleanString(env.ADMIN_TOKEN);
  return crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"]
  );
}

function adsUrlSafe(b64) {
  return b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** `exp` is absolute ms; the signature covers the media id and the expiry. */
async function adsSignMedia(env, mediaId, expiresAt) {
  const key = await adsSignKey(env);
  const payload = new TextEncoder().encode(mediaId + "|" + expiresAt);
  const mac = await crypto.subtle.sign("HMAC", key, payload);
  return adsUrlSafe(adsB64(mac)).slice(0, 43);
}

async function adsVerifyMedia(env, mediaId, expiresAt, signature) {
  if (!mediaId || !expiresAt || !signature) return false;
  if (adsNow() > Number(expiresAt)) return false;
  const expected = await adsSignMedia(env, mediaId, expiresAt);
  if (expected.length !== String(signature).length) return false;
  let diff = 0;
  for (let i = 0; i < expected.length; i++) {
    diff |= expected.charCodeAt(i) ^ String(signature).charCodeAt(i);
  }
  return diff === 0;
}

async function adsMediaUrl(env, mediaId) {
  if (!mediaId) return "";
  const expiresAt = adsNow() + ADS_CHAT_MEDIA_URL_TTL_MS;
  const signature = await adsSignMedia(env, mediaId, expiresAt);
  return "/ads/chat/media?id=" + encodeURIComponent(mediaId) + "&exp=" + expiresAt + "&sig=" + signature;
}

// -----------------------------------------------------------------------------
// Durable Object accessors
// -----------------------------------------------------------------------------

function adsChatStub(env, threadId) {
  if (!env.ADS_CHAT) throw adsFail("missing_binding", "ربط ADS_CHAT غير مهيأ في الـ Worker.", 500);
  return env.ADS_CHAT.get(env.ADS_CHAT.idFromName("ads-chat-v1:" + threadId));
}

function adsIndexStub(env) {
  if (!env.ADS_CHAT_INDEX) {
    throw adsFail("missing_binding", "ربط ADS_CHAT_INDEX غير مهيأ في الـ Worker.", 500);
  }
  return env.ADS_CHAT_INDEX.get(env.ADS_CHAT_INDEX.idFromName("ads-chat-index-v1"));
}

async function adsChatCall(env, threadId, path, body, request) {
  const stub = adsChatStub(env, threadId);
  const init = {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body || {}),
  };
  if (request) {
    // a WebSocket upgrade must be forwarded as the original request
    return stub.fetch(new Request("https://ads-chat.internal" + path, request));
  }
  return stub.fetch("https://ads-chat.internal" + path, init);
}

async function adsChatJson(env, threadId, path, body) {
  const res = await adsChatCall(env, threadId, path, body);
  const text = await res.text();
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch {
    parsed = { ok: false, error: "bad_reply", message: "رد غير متوقع من خدمة المحادثة." };
  }
  if (parsed && typeof parsed === "object") parsed.__status = res.status;
  return parsed;
}

/**
 * Turns a failure from the thread into a real HTTP error. Without this, wrapping
 * the reply in `{ok:true, ...result}` would hand the app a 200 whose body says
 * ok:false — so "already answered" or "blocked" would read as success.
 */
function adsChatEnsure(result) {
  if (!result || result.ok === false) {
    throw adsFail(
      (result && result.error) || "chat_failed",
      (result && result.message) || "تعذر تنفيذ الطلب.",
      (result && result.__status) || 400
    );
  }
  delete result.__status;
  return result;
}

async function adsIndexJson(env, path, body) {
  try {
    const stub = adsIndexStub(env);
    const res = await stub.fetch("https://ads-chat-index.internal" + path, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body || {}),
    });
    const text = await res.text();
    return JSON.parse(text);
  } catch {
    return { ok: false };
  }
}

// -----------------------------------------------------------------------------
// message normalisation (runs in the Worker, never trusts the client)
// -----------------------------------------------------------------------------

function adsChatNormalizeQuote(raw) {
  const o = raw && typeof raw === "object" ? raw : {};
  let status = cleanString(o.status);
  if (["open", "accepted", "negotiating", "rejected", "expired"].indexOf(status) < 0) status = "open";
  return {
    id: cleanString(o.id) || adsId("off"),
    amount: Number(adsText(String(o.amount === undefined ? 0 : o.amount), 20)) || 0,
    currency: adsText(o.currency, 10) || "SAR",
    currency_label: adsText(o.currency_label, 10),
    slot: ADS_SLOT_IDS.indexOf(cleanString(o.slot)) >= 0 ? cleanString(o.slot) : "",
    slot_name: adsText(o.slot_name, 60),
    days: adsInt(o.days, 0, 0, 3650),
    note: adsText(o.note, 1000),
    valid_until: adsParseTime(o.valid_until) ? adsIso(adsParseTime(o.valid_until)) : "",
    status,
  };
}

function adsChatNormalizeOrder(raw) {
  const o = raw && typeof raw === "object" ? raw : {};
  const quote = o.quote && typeof o.quote === "object" ? o.quote : {};
  return {
    slot: ADS_SLOT_IDS.indexOf(cleanString(o.slot)) >= 0 ? cleanString(o.slot) : "",
    slot_name: adsText(o.slot_name, 60),
    days: adsInt(o.days, 0, 0, 3650),
    cities: adsArray(o.cities).map((c) => adsText(c, 60)).filter(Boolean).slice(0, 50),
    langs: adsArray(o.langs).map((l) => adsText(l, 10)).filter(Boolean).slice(0, 10),
    hours: adsArray(o.hours).map((h) => adsInt(h, -1, 0, 23)).filter((h) => h >= 0).slice(0, 24),
    addons: adsArray(o.addons).map((a) => adsText(a, 40)).filter(Boolean).slice(0, 20),
    total: Number(quote.total) || 0,
    currency_label: adsText(quote.currency_label, 10),
    est_views: adsInt(quote.est_views, 0, 0, 1e12),
    breakdown: {
      base: Number(quote.base) || 0,
      discount_percent: Number(quote.discount_percent) || 0,
      discount_value: Number(quote.discount_value) || 0,
      targeting_value: Number(quote.targeting_value) || 0,
      addons_value: Number(quote.addons_value) || 0,
      addon_labels: adsArray(quote.addons)
        .map((a) => adsText(a && a.label, 60))
        .filter(Boolean)
        .slice(0, 20),
    },
  };
}

/**
 * Builds the record that goes into Durable Object storage. Text is sealed, media
 * is referenced by id only, and the client's own `state`/`seq`/`at` are ignored.
 */
async function adsChatBuildMessage(env, from, input, mediaRecord) {
  let kind = cleanString(input.kind);
  if (ADS_CHAT_KINDS.indexOf(kind) < 0) kind = "text";
  if (from === "user" && kind === "quote") {
    throw adsFail("not_allowed", "عرض السعر يصدر من الإدارة فقط.", 403);
  }
  if (from === "user" && kind === "system") {
    throw adsFail("not_allowed", "نوع رسالة غير مسموح.", 403);
  }

  const text = adsText(input.text || input.body, ADS_CHAT_MAX_TEXT);
  if (kind === "text" && text.length === 0) {
    throw adsFail("empty_message", "لا يمكن إرسال رسالة فارغة.", 400);
  }

  const message = {
    id: adsId("msg"),
    from,
    kind,
    at: adsNow(),
    reply_to: adsInt(input.reply_to, 0, 0, 1e9),
    // The client supplies this so a retry of the same message is not stored twice.
    client_id: adsText(input.client_id, 64),
    internal: from === "admin" && adsBool(input.internal, false),
    text: await adsSeal(env, text),
    media: mediaRecord || null,
    order: null,
    quote: null,
    deleted_for: [],
  };

  if (kind === "ad_request") {
    message.order = await adsSeal(env, JSON.stringify(adsChatNormalizeOrder(input.order)));
  }
  if (kind === "quote") {
    message.quote = await adsSeal(env, JSON.stringify(adsChatNormalizeQuote(input.quote)));
  }
  return message;
}

/** Turns a stored record into what a client may see. `viewer` is "user" or "admin". */
async function adsChatPublicMessage(env, record, viewer) {
  if (!record) return null;
  if (adsArray(record.deleted_for).indexOf(viewer) >= 0) return null;
  if (record.internal && viewer !== "admin") return null;

  const out = {
    seq: record.seq,
    id: record.id,
    client_id: record.client_id || "",
    from: record.from,
    kind: record.kind,
    at: record.at,
    reply_to: record.reply_to || 0,
    internal: Boolean(record.internal),
    text: await adsOpen(env, record.text),
    delivered_at: record.delivered_at || 0,
    read_at: viewer === "admin" ? record.read_by_user_at || 0 : record.read_by_admin_at || 0,
  };

  if (record.order) {
    try {
      out.order = JSON.parse(await adsOpen(env, record.order));
    } catch {
      out.order = null;
    }
  }
  if (record.quote) {
    try {
      out.quote = JSON.parse(await adsOpen(env, record.quote));
    } catch {
      out.quote = null;
    }
  }
  if (record.media) {
    out.media = {
      id: record.media.id,
      mime: record.media.mime,
      size: record.media.size,
      width: record.media.width || 0,
      height: record.media.height || 0,
      duration_ms: record.media.duration_ms || 0,
      name: record.media.name || "",
      url: await adsMediaUrl(env, record.media.id),
      thumb_url: record.media.thumb_id ? await adsMediaUrl(env, record.media.thumb_id) : "",
    };
  }
  return out;
}

// =============================================================================
// Durable Object — AdsChatDO : one conversation
// -----------------------------------------------------------------------------
// Storage layout (all keys inside this one object, so nothing can race):
//   meta                      -> { seq, thread_id, created_at, last_at,
//                                  unread_user, unread_admin, blocked, archived,
//                                  tags[], note, media_ids[] }
//   m:<seq padded to 12>      -> the message record
//   hb:user | hb:admin        -> last heartbeat ms (survives hibernation)
//
// Live delivery has two paths over the same state:
//   - WebSocket, using the hibernation API so an idle socket costs nothing,
//   - a 25-second long-poll, for clients that cannot hold a socket open.
// Both are fed by one broadcast() call, so they can never diverge.
// =============================================================================

export class AdsChatDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    /** @type {Array<{resolve:Function,timer:any,viewer:string,since:number}>} */
    this.waiters = [];
    this.typing = { user: 0, admin: 0 };
  }

  // ---------------------------------------------------------------- plumbing

  json(value, status) {
    return new Response(JSON.stringify(value), {
      status: status || 200,
      headers: { "content-type": "application/json; charset=utf-8" },
    });
  }

  async meta() {
    const stored = await this.state.storage.get("meta");
    return Object.assign(
      {
        seq: 0,
        thread_id: "",
        created_at: 0,
        last_at: 0,
        unread_user: 0,
        unread_admin: 0,
        blocked: false,
        archived: false,
        tags: [],
        note: "",
        media_ids: [],
      },
      stored || {}
    );
  }

  async putMeta(meta) {
    await this.state.storage.put("meta", meta);
  }

  key(seq) {
    return "m:" + String(seq).padStart(12, "0");
  }

  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === "/ws") return this.openSocket(request, url);

    let body = {};
    try {
      body = await request.json();
    } catch {
      body = {};
    }

    try {
      switch (url.pathname) {
        case "/send": return this.json(await this.send(body));
        case "/history": return this.json(await this.history(body));
        case "/poll": return this.json(await this.poll(body));
        case "/read": return this.json(await this.read(body));
        case "/typing": return this.json(await this.setTyping(body));
        case "/heartbeat": return this.json(await this.heartbeat(body));
        case "/state": return this.json(await this.threadState(body));
        case "/delete": return this.json(await this.deleteMessage(body));
        case "/quote-respond": return this.json(await this.respondToQuote(body));
        case "/flags": return this.json(await this.setFlags(body));
        case "/export": return this.json(await this.exportThread(body));
        case "/purge": return this.json(await this.purge());
        default: return this.json({ ok: false, error: "not_found" }, 404);
      }
    } catch (error) {
      if (error instanceof AdsError) {
        return this.json({ ok: false, error: error.code, message: error.message }, error.status);
      }
      return this.json({ ok: false, error: "internal_error", message: String(error && error.message) }, 500);
    }
  }

  // ----------------------------------------------------------------- sending

  async send(body) {
    const from = body.from === "admin" ? "admin" : "user";
    const meta = await this.meta();

    if (from === "user" && meta.blocked) {
      throw adsFail("blocked", "لا يمكنك إرسال رسائل في هذه المحادثة.", 403);
    }

    const record = body.message;
    if (!record || typeof record !== "object") throw adsFail("invalid", "رسالة غير صالحة.", 400);

    // A retried send carries the same client_id: return the stored one instead of
    // writing a duplicate. This is what makes the offline send queue safe.
    if (record.client_id) {
      const existing = await this.findByClientId(record.client_id, meta.seq);
      if (existing) {
        return {
          ok: true,
          duplicate: true,
          seq: existing.seq,
          message: await adsChatPublicMessage(this.env, existing, from),
        };
      }
    }

    const seq = meta.seq + 1;
    record.seq = seq;
    record.delivered_at = adsNow();
    await this.state.storage.put(this.key(seq), record);

    meta.seq = seq;
    meta.last_at = record.at;
    if (!meta.created_at) meta.created_at = record.at;
    if (!meta.thread_id) meta.thread_id = cleanString(body.thread_id);
    if (!record.internal) {
      if (from === "user") meta.unread_admin = adsInt(meta.unread_admin + 1, 1, 0, 1e6);
      else meta.unread_user = adsInt(meta.unread_user + 1, 1, 0, 1e6);
    }
    if (record.media && record.media.id) {
      meta.media_ids = adsArray(meta.media_ids).concat([record.media.id]);
      if (record.media.thumb_id) meta.media_ids.push(record.media.thumb_id);
      if (meta.media_ids.length > 5000) meta.media_ids = meta.media_ids.slice(-5000);
    }
    await this.putMeta(meta);

    const forUser = await adsChatPublicMessage(this.env, record, "user");
    const forAdmin = await adsChatPublicMessage(this.env, record, "admin");
    this.broadcast({ type: "message", user: forUser, admin: forAdmin, seq });

    return { ok: true, seq, message: from === "admin" ? forAdmin : forUser, meta: this.metaPublic(meta, from) };
  }

  /** Looks back over the recent tail only: a retry always follows its original closely. */
  async findByClientId(clientId, lastSeq) {
    const from = Math.max(1, lastSeq - 50);
    const keys = [];
    for (let s = lastSeq; s >= from; s--) keys.push(this.key(s));
    if (keys.length === 0) return null;
    const found = await this.state.storage.get(keys);
    for (const key of keys) {
      const record = found.get(key);
      if (record && record.client_id === clientId) return record;
    }
    return null;
  }

  // ---------------------------------------------------------------- reading

  async history(body) {
    const viewer = body.viewer === "admin" ? "admin" : "user";
    const meta = await this.meta();
    const limit = adsInt(body.limit, ADS_CHAT_PAGE, 1, 100);
    const since = adsInt(body.since, 0, 0, 1e9);
    const before = adsInt(body.before, 0, 0, 1e9);

    let start;
    let end;
    if (since > 0) {
      start = since + 1;
      end = Math.min(meta.seq, since + limit);
    } else if (before > 0) {
      end = before - 1;
      start = Math.max(1, end - limit + 1);
    } else {
      end = meta.seq;
      start = Math.max(1, end - limit + 1);
    }

    const messages = [];
    if (end >= start) {
      const keys = [];
      for (let s = start; s <= end; s++) keys.push(this.key(s));
      const found = await this.state.storage.get(keys);
      for (const key of keys) {
        const record = found.get(key);
        if (!record) continue;
        const shaped = await adsChatPublicMessage(this.env, record, viewer);
        if (shaped) messages.push(shaped);
      }
    }

    return {
      ok: true,
      messages,
      has_more: start > 1,
      oldest_seq: start,
      latest_seq: meta.seq,
      meta: this.metaPublic(meta, viewer),
      presence: await this.presence(),
    };
  }

  /** Long-poll: resolves as soon as a message arrives, or after 25 seconds. */
  async poll(body) {
    const viewer = body.viewer === "admin" ? "admin" : "user";
    const since = adsInt(body.since, 0, 0, 1e9);
    await this.touchHeartbeat(viewer);

    const meta = await this.meta();
    if (meta.seq > since) return this.history({ viewer, since, limit: ADS_CHAT_PAGE });

    return new Promise((resolve) => {
      const waiter = { viewer, since, resolve: null, timer: null };
      waiter.resolve = async (reason) => {
        if (waiter.done) return;
        waiter.done = true;
        clearTimeout(waiter.timer);
        this.waiters = this.waiters.filter((w) => w !== waiter);
        if (reason === "timeout") {
          resolve({ ok: true, messages: [], timeout: true, latest_seq: since, presence: await this.presence() });
        } else {
          resolve(await this.history({ viewer, since, limit: ADS_CHAT_PAGE }));
        }
      };
      waiter.timer = setTimeout(() => waiter.resolve("timeout"), ADS_CHAT_POLL_MS);
      this.waiters.push(waiter);
      if (this.waiters.length > 200) {
        const oldest = this.waiters.shift();
        if (oldest) oldest.resolve("timeout");
      }
    });
  }

  async read(body) {
    const viewer = body.viewer === "admin" ? "admin" : "user";
    const meta = await this.meta();
    const upTo = adsInt(body.up_to, meta.seq, 0, 1e9);
    const now = adsNow();

    const from = Math.max(1, upTo - 200);
    const keys = [];
    for (let s = from; s <= upTo; s++) keys.push(this.key(s));
    if (keys.length > 0) {
      const found = await this.state.storage.get(keys);
      const writes = {};
      for (const key of keys) {
        const record = found.get(key);
        if (!record) continue;
        // You mark the OTHER side's messages as read, never your own.
        if (viewer === "admin" && record.from === "user" && !record.read_by_admin_at) {
          record.read_by_admin_at = now;
          writes[key] = record;
        } else if (viewer === "user" && record.from === "admin" && !record.read_by_user_at) {
          record.read_by_user_at = now;
          writes[key] = record;
        }
      }
      if (Object.keys(writes).length > 0) await this.state.storage.put(writes);
    }

    if (viewer === "admin") meta.unread_admin = 0;
    else meta.unread_user = 0;
    await this.putMeta(meta);

    this.broadcast({ type: "read", viewer, up_to: upTo, at: now });
    return { ok: true, meta: this.metaPublic(meta, viewer) };
  }

  // --------------------------------------------------------------- presence

  async touchHeartbeat(viewer) {
    await this.state.storage.put("hb:" + viewer, adsNow());
  }

  async heartbeat(body) {
    const viewer = body.viewer === "admin" ? "admin" : "user";
    await this.touchHeartbeat(viewer);
    return { ok: true, presence: await this.presence(), latest_seq: (await this.meta()).seq };
  }

  async presence() {
    const now = adsNow();
    const userAt = Number(await this.state.storage.get("hb:user")) || 0;
    const adminAt = Number(await this.state.storage.get("hb:admin")) || 0;
    const settings = await adsReadSettings(this.env);
    const adminFlag = adsEffectivePresence(settings, now);
    return {
      user_online: now - userAt < ADS_CHAT_ONLINE_GRACE_MS,
      user_last_seen: userAt,
      // The advertiser sees the status the admin publishes, narrowed by working
      // hours — a stale socket must never read as "متصل الآن".
      admin_status: adminFlag.status,
      admin_socket_online: now - adminAt < ADS_CHAT_ONLINE_GRACE_MS,
      admin_last_seen: adminAt,
      within_hours: adminFlag.within_hours,
      auto_reply: adminFlag.auto_reply,
      offline_note: adminFlag.offline_note,
      hours_label: adminFlag.hours_label,
      typing_user: now - this.typing.user < 5000,
      typing_admin: now - this.typing.admin < 5000,
      server_time_ms: now,
    };
  }

  async setTyping(body) {
    const viewer = body.viewer === "admin" ? "admin" : "user";
    this.typing[viewer] = adsBool(body.on, true) ? adsNow() : 0;
    await this.touchHeartbeat(viewer);
    this.broadcast({ type: "typing", viewer, on: adsBool(body.on, true) });
    return { ok: true };
  }

  async threadState(body) {
    const meta = await this.meta();
    const viewer = body && body.viewer === "admin" ? "admin" : "user";
    return { ok: true, meta: this.metaPublic(meta, viewer), presence: await this.presence() };
  }

  /**
   * The thread summary. `note` and `tags` are the admin's private workspace and
   * must never reach the advertiser — they used to ride along in every history
   * reply, which handed the advertiser the admin's own notes about them.
   */
  metaPublic(meta, viewer) {
    const base = {
      seq: meta.seq,
      last_at: meta.last_at,
      unread_user: meta.unread_user,
      blocked: Boolean(meta.blocked),
    };
    if (viewer !== "admin") return base;
    return Object.assign(base, {
      unread_admin: meta.unread_admin,
      archived: Boolean(meta.archived),
      tags: adsArray(meta.tags),
      note: meta.note || "",
    });
  }

  // -------------------------------------------------------------- mutations

  async deleteMessage(body) {
    const viewer = body.viewer === "admin" ? "admin" : "user";
    const seq = adsInt(body.seq, 0, 1, 1e9);
    const key = this.key(seq);
    const record = await this.state.storage.get(key);
    if (!record) throw adsFail("not_found", "الرسالة غير موجودة.", 404);
    const hidden = adsArray(record.deleted_for);
    if (hidden.indexOf(viewer) < 0) hidden.push(viewer);
    record.deleted_for = hidden;
    await this.state.storage.put(key, record);
    // "Delete for me" only: the other side keeps their copy, and we say so in the UI.
    return { ok: true, seq };
  }

  async respondToQuote(body) {
    const seq = adsInt(body.seq, 0, 1, 1e9);
    let action = cleanString(body.action);
    if (["accepted", "negotiating", "rejected"].indexOf(action) < 0) {
      throw adsFail("invalid", "إجراء غير معروف.", 400);
    }
    const key = this.key(seq);
    const record = await this.state.storage.get(key);
    if (!record || record.kind !== "quote") throw adsFail("not_found", "عرض السعر غير موجود.", 404);

    let quote;
    try {
      quote = JSON.parse(await adsOpen(this.env, record.quote));
    } catch {
      throw adsFail("corrupt", "تعذر قراءة عرض السعر.", 500);
    }
    if (quote.status !== "open") {
      throw adsFail("already_answered", "تم الرد على هذا العرض مسبقاً.", 409, { status: quote.status });
    }
    quote.status = action;
    quote.answered_at = adsIso();
    record.quote = await adsSeal(this.env, JSON.stringify(quote));
    await this.state.storage.put(key, record);

    this.broadcast({ type: "quote", seq, status: action });
    return { ok: true, seq, status: action, quote };
  }

  async setFlags(body) {
    const meta = await this.meta();
    if (body.blocked !== undefined) meta.blocked = adsBool(body.blocked, false);
    if (body.archived !== undefined) meta.archived = adsBool(body.archived, false);
    if (body.note !== undefined) meta.note = adsText(body.note, 4000);
    if (body.tags !== undefined) {
      meta.tags = adsArray(body.tags).map((t) => adsText(t, 30)).filter(Boolean).slice(0, 20);
    }
    await this.putMeta(meta);
    // only the admin side is told about tags/notes
    this.broadcast({ type: "flags", meta: this.metaPublic(meta, "user") });
    return { ok: true, meta: this.metaPublic(meta, "admin") };
  }

  /** Plain-text transcript for the admin's "تصدير سجل محادثة". */
  async exportThread(body) {
    const meta = await this.meta();
    const includeInternal = adsBool(body.internal, false);
    const lines = [];
    const chunk = 200;
    for (let start = 1; start <= meta.seq; start += chunk) {
      const keys = [];
      for (let s = start; s < start + chunk && s <= meta.seq; s++) keys.push(this.key(s));
      const found = await this.state.storage.get(keys);
      for (const key of keys) {
        const record = found.get(key);
        if (!record) continue;
        if (record.internal && !includeInternal) continue;
        const who = record.from === "admin" ? "الإدارة" : "المعلن";
        const when = adsIso(record.at);
        let text = await adsOpen(this.env, record.text);
        if (record.kind === "image") text = "[صورة] " + text;
        else if (record.kind === "video") text = "[فيديو] " + text;
        else if (record.kind === "audio") text = "[تسجيل صوتي]";
        else if (record.kind === "file") text = "[ملف] " + (record.media ? record.media.name : "");
        else if (record.kind === "ad_request") text = "[طلب إعلان] " + (await adsOpen(this.env, record.order));
        else if (record.kind === "quote") text = "[عرض سعر] " + (await adsOpen(this.env, record.quote));
        if (record.internal) text = "(ملاحظة داخلية) " + text;
        lines.push("[" + when + "] " + who + ": " + text);
      }
    }
    return { ok: true, transcript: lines.join("\n"), count: lines.length, meta: this.metaPublic(meta, "admin") };
  }

  /** Wipes the thread and hands back every media id so R2 can be cleaned too. */
  async purge() {
    const meta = await this.meta();
    const mediaIds = adsArray(meta.media_ids).slice();
    await this.state.storage.deleteAll();
    return { ok: true, media_ids: mediaIds, removed: meta.seq };
  }

  // -------------------------------------------------------------- websocket

  openSocket(request, url) {
    if ((request.headers.get("upgrade") || "").toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const role = url.searchParams.get("role") === "admin" ? "admin" : "user";
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    // Hibernation API: an idle socket costs nothing and survives eviction.
    this.state.acceptWebSocket(server, [role]);
    this.state.storage.put("hb:" + role, adsNow());
    try {
      server.send(JSON.stringify({ type: "hello", role, server_time_ms: adsNow() }));
    } catch {
      /* the client may already be gone */
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, raw) {
    let message = {};
    try {
      message = JSON.parse(String(raw));
    } catch {
      return;
    }
    const tags = this.state.getTags(ws);
    const role = tags && tags.indexOf("admin") >= 0 ? "admin" : "user";
    try {
      if (message.type === "ping" || message.type === "heartbeat") {
        await this.touchHeartbeat(role);
        ws.send(JSON.stringify({ type: "pong", server_time_ms: adsNow() }));
        return;
      }
      if (message.type === "typing") {
        await this.setTyping({ viewer: role, on: message.on });
        return;
      }
      if (message.type === "read") {
        await this.read({ viewer: role, up_to: message.up_to });
        return;
      }
    } catch (error) {
      try {
        ws.send(JSON.stringify({ type: "error", message: String(error && error.message) }));
      } catch {
        /* ignore */
      }
    }
  }

  async webSocketClose(ws, code, reason, wasClean) {
    try {
      ws.close(code === 1006 ? 1000 : code, reason);
    } catch {
      /* already closed */
    }
  }

  async webSocketError() {
    // nothing to clean: the hibernation API drops the socket for us
  }

  /** One fan-out for both transports, so they can never show different history. */
  broadcast(event) {
    try {
      const sockets = this.state.getWebSockets ? this.state.getWebSockets() : [];
      for (const ws of sockets) {
        const tags = this.state.getTags(ws);
        const role = tags && tags.indexOf("admin") >= 0 ? "admin" : "user";
        let payload = event;
        if (event.type === "message") {
          const shaped = role === "admin" ? event.admin : event.user;
          if (!shaped) continue; // internal note: not for this side
          payload = { type: "message", message: shaped, seq: event.seq };
        }
        try {
          ws.send(JSON.stringify(payload));
        } catch {
          /* a dead socket is dropped by the runtime */
        }
      }
    } catch {
      /* sockets are best effort; the long-poll below is the guarantee */
    }
    const waiting = this.waiters.slice();
    for (const waiter of waiting) {
      if (event.type === "message" || event.type === "read" || event.type === "quote"
          || event.type === "flags" || event.type === "typing") {
        waiter.resolve("event");
      }
    }
  }
}

// =============================================================================
// Durable Object — AdsChatIndexDO : the admin inbox
// -----------------------------------------------------------------------------
// A tiny summary row per thread so the inbox renders from one read instead of
// opening every conversation. Updated by the Worker after each message.
// =============================================================================

export class AdsChatIndexDO {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  json(value, status) {
    return new Response(JSON.stringify(value), {
      status: status || 200,
      headers: { "content-type": "application/json; charset=utf-8" },
    });
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
      if (url.pathname === "/touch") return this.json(await this.touch(body));
      if (url.pathname === "/list") return this.json(await this.list(body));
      if (url.pathname === "/remove") return this.json(await this.remove(body));
      return this.json({ ok: false, error: "not_found" }, 404);
    } catch (error) {
      return this.json({ ok: false, error: "internal_error", message: String(error && error.message) }, 500);
    }
  }

  async touch(body) {
    const threadId = cleanString(body.thread_id);
    if (!threadId) return { ok: false };
    const key = "t:" + threadId;
    const row = (await this.state.storage.get(key)) || { thread_id: threadId, created_at: adsNow() };
    if (body.preview !== undefined) row.preview = adsText(body.preview, 160);
    if (body.last_at !== undefined) row.last_at = adsInt(body.last_at, adsNow(), 0, 1e15);
    if (body.unread_admin !== undefined) row.unread_admin = adsInt(body.unread_admin, 0, 0, 1e6);
    if (body.advertiser_name !== undefined) row.advertiser_name = adsText(body.advertiser_name, 120);
    if (body.advertiser_id !== undefined) row.advertiser_id = adsText(body.advertiser_id, 80);
    if (body.logo_url !== undefined) row.logo_url = adsSafeUrl(body.logo_url);
    if (body.user_online !== undefined) row.user_online = adsBool(body.user_online, false);
    if (body.blocked !== undefined) row.blocked = adsBool(body.blocked, false);
    if (body.archived !== undefined) row.archived = adsBool(body.archived, false);
    if (body.tags !== undefined) row.tags = adsArray(body.tags).slice(0, 20);
    if (body.awaiting_payment !== undefined) row.awaiting_payment = adsBool(body.awaiting_payment, false);
    row.updated_at = adsNow();
    await this.state.storage.put(key, row);
    return { ok: true, row };
  }

  async list(body) {
    const rows = await this.state.storage.list({ prefix: "t:", limit: 2000 });
    const out = [];
    for (const [, row] of rows) out.push(row);
    out.sort((a, b) => (b.last_at || 0) - (a.last_at || 0));

    const query = adsText(body.q, 60).toLowerCase();
    const filter = cleanString(body.filter);
    const filtered = out.filter((row) => {
      if (!adsBool(body.include_archived, false) && row.archived) return false;
      if (filter === "unread" && !(row.unread_admin > 0)) return false;
      if (filter === "new" && !(row.unread_admin > 0 && (row.last_at || 0) > adsNow() - 86400000)) return false;
      if (filter === "important" && adsArray(row.tags).indexOf("مهم") < 0) return false;
      if (filter === "awaiting_payment" && !row.awaiting_payment) return false;
      if (filter === "blocked" && !row.blocked) return false;
      if (query) {
        const haystack = ((row.advertiser_name || "") + " " + (row.preview || "")).toLowerCase();
        if (haystack.indexOf(query) < 0) return false;
      }
      return true;
    });

    let unreadTotal = 0;
    for (const row of out) if (!row.archived) unreadTotal += row.unread_admin || 0;

    return {
      ok: true,
      threads: filtered.slice(0, adsInt(body.limit, 200, 1, 500)),
      total: out.length,
      unread_total: unreadTotal,
    };
  }

  async remove(body) {
    const threadId = cleanString(body.thread_id);
    if (!threadId) return { ok: false };
    await this.state.storage.delete("t:" + threadId);
    return { ok: true };
  }
}

// =============================================================================
// WORKER-SIDE HANDLERS
// =============================================================================

/** The thread id is the advertiser's account id: one conversation per account. */
function adsThreadIdForUser(user) {
  return "u_" + cleanString(user.id);
}

/** Keeps the admin inbox row in step after anything that changes a thread. */
async function adsTouchIndex(env, threadId, extra) {
  try {
    // the admin view, because the inbox row needs unread_admin, tags and archived
    const state = await adsChatJson(env, threadId, "/state", { viewer: "admin" });
    const meta = state && state.meta ? state.meta : {};
    const presence = state && state.presence ? state.presence : {};
    const advertisers = await adsReadAdvertisers(env);
    const userId = threadId.indexOf("u_") === 0 ? threadId.slice(2) : threadId;
    const advertiser = advertisers.items.find((a) => a.user_id === userId) || null;

    let awaiting = false;
    if (advertiser) {
      const campaigns = await adsReadCampaigns(env);
      awaiting = campaigns.items.some(
        (c) => c.advertiser_id === advertiser.id && c.payment.status !== "paid" && c.review.approved
      );
    }

    await adsIndexJson(env, "/touch", Object.assign(
      {
        thread_id: threadId,
        last_at: meta.last_at || 0,
        unread_admin: meta.unread_admin || 0,
        blocked: meta.blocked || false,
        archived: meta.archived || false,
        tags: meta.tags || [],
        user_online: presence.user_online || false,
        advertiser_id: advertiser ? advertiser.id : "",
        advertiser_name: advertiser ? advertiser.name : "",
        logo_url: advertiser ? advertiser.logo_url : "",
        awaiting_payment: awaiting,
      },
      extra || {}
    ));
  } catch {
    /* the index is a cache of the threads; a miss only delays the inbox row */
  }
}

/** A one-line preview for the inbox, with no media content in it. */
function adsPreviewOf(kind, text) {
  if (kind === "image") return "[صورة]";
  if (kind === "video") return "[فيديو]";
  if (kind === "audio") return "[تسجيل صوتي]";
  if (kind === "file") return "[ملف]";
  if (kind === "ad_request") return "[طلب إعلان]";
  if (kind === "quote") return "[عرض سعر]";
  return adsText(text, 160);
}

// ----------------------------------------------------------------- user side

async function adsChatUserHistory(request, env, url, user) {
  const threadId = adsThreadIdForUser(user);
  const result = adsChatEnsure(await adsChatJson(env, threadId, "/history", {
    viewer: "user",
    since: url.searchParams.get("since"),
    before: url.searchParams.get("before"),
    limit: url.searchParams.get("limit"),
  }));
  return json(Object.assign({ ok: true, success: true, server_time_ms: adsNow() }, result));
}

async function adsChatUserSend(request, env, url, user) {
  const body = await readRequestData(request);
  const threadId = adsThreadIdForUser(user);

  // Make sure the company file exists before the first message lands.
  await adsAdvertiserForUser(env, user, body.company_name);

  const media = await adsResolveMedia(env, threadId, body);
  const message = await adsChatBuildMessage(env, "user", body, media);
  const result = adsChatEnsure(
    await adsChatJson(env, threadId, "/send", { from: "user", message, thread_id: threadId })
  );
  await adsTouchIndex(env, threadId, {
    preview: adsPreviewOf(message.kind, adsText(body.text || body.body, 160)),
  });
  return json(Object.assign({ ok: true, success: true }, result));
}

/** Factory for the small user routes that just forward one call to the thread. */
function adsChatUserSimple(path, viewerKey) {
  return async (request, env, url, user) => {
    const body = await readRequestData(request);
    const threadId = adsThreadIdForUser(user);
    const result = adsChatEnsure(
      await adsChatJson(env, threadId, path, Object.assign({ viewer: viewerKey }, body))
    );
    if (path === "/read" || path === "/quote-respond" || path === "/delete") {
      await adsTouchIndex(env, threadId, {});
    }
    return json(Object.assign({ ok: true, success: true }, result));
  };
}

async function adsChatUserPoll(request, env, url, user) {
  const threadId = adsThreadIdForUser(user);
  const result = adsChatEnsure(await adsChatJson(env, threadId, "/poll", {
    viewer: "user",
    since: url.searchParams.get("since"),
  }));
  return json(Object.assign({ ok: true, success: true, server_time_ms: adsNow() }, result));
}

/**
 * WebSocket upgrade. A browser cannot set an Authorization header on an upgrade,
 * so the token may also arrive as ?token= — it is verified exactly the same way.
 */
async function adsChatUserSocket(request, env, url) {
  const token = cleanString(url.searchParams.get("token"));
  const authed = token
    ? new Request(request.url, { headers: mergeAuthHeader(request, token) })
    : request;
  const user = await adsRequireUser(authed, env);
  const threadId = adsThreadIdForUser(user);
  return adsChatCall(env, threadId, "/ws?role=user", null, request);
}

function mergeAuthHeader(request, token) {
  const headers = new Headers(request.headers);
  headers.set("authorization", "Bearer " + token);
  return headers;
}

// ---------------------------------------------------------------- admin side

async function adsChatAdminThreads(request, env, url) {
  const result = await adsIndexJson(env, "/list", {
    q: url.searchParams.get("q"),
    filter: url.searchParams.get("filter"),
    include_archived: url.searchParams.get("archived") === "1",
    limit: url.searchParams.get("limit"),
  });
  const settings = await adsReadSettings(env);
  return okResponse(
    {
      threads: result && result.threads ? result.threads : [],
      unread_total: result && result.unread_total ? result.unread_total : 0,
      total: result && result.total ? result.total : 0,
      my_presence: adsEffectivePresence(settings),
    },
    ""
  );
}

function adsAdminThreadId(url, body) {
  const raw = cleanString((body && body.user) || (url && url.searchParams.get("user")));
  if (!raw) throw adsFail("missing_user", "يجب تحديد المعلن.", 400);
  return raw.indexOf("u_") === 0 ? raw : "u_" + raw;
}

async function adsChatAdminHistory(request, env, url) {
  const threadId = adsAdminThreadId(url, null);
  const result = adsChatEnsure(await adsChatJson(env, threadId, "/history", {
    viewer: "admin",
    since: url.searchParams.get("since"),
    before: url.searchParams.get("before"),
    limit: url.searchParams.get("limit"),
  }));
  const advertisers = await adsReadAdvertisers(env);
  const userId = threadId.slice(2);
  const advertiser = advertisers.items.find((a) => a.user_id === userId) || null;
  return okResponse(Object.assign({ thread_id: threadId, advertiser }, result), "");
}

async function adsChatAdminSend(request, env, url) {
  const body = await readRequestData(request);
  const threadId = adsAdminThreadId(url, body);
  const media = await adsResolveMedia(env, threadId, body);
  const message = await adsChatBuildMessage(env, "admin", body, media);
  const result = adsChatEnsure(
    await adsChatJson(env, threadId, "/send", { from: "admin", message, thread_id: threadId })
  );
  if (!message.internal) {
    await adsTouchIndex(env, threadId, {
      preview: adsPreviewOf(message.kind, adsText(body.text || body.body, 160)),
    });
    await adsNotifyUser(env, threadId, message.kind);
  } else {
    await adsTouchIndex(env, threadId, {});
  }
  return okResponse(result, "تم الإرسال.");
}

function adsChatAdminSimple(path) {
  return async (request, env, url) => {
    const body = await readRequestData(request);
    const threadId = adsAdminThreadId(url, body);
    const result = adsChatEnsure(
      await adsChatJson(env, threadId, path, Object.assign({ viewer: "admin" }, body))
    );
    await adsTouchIndex(env, threadId, {});
    return okResponse(result, "");
  };
}

async function adsChatAdminPoll(request, env, url) {
  const threadId = adsAdminThreadId(url, null);
  const result = adsChatEnsure(await adsChatJson(env, threadId, "/poll", {
    viewer: "admin",
    since: url.searchParams.get("since"),
  }));
  return okResponse(Object.assign({ server_time_ms: adsNow() }, result), "");
}

async function adsChatAdminSocket(request, env, url) {
  const threadId = adsAdminThreadId(url, null);
  return adsChatCall(env, threadId, "/ws?role=admin", null, request);
}

async function adsChatAdminExport(request, env, url) {
  const body = await readRequestData(request);
  const threadId = adsAdminThreadId(url, body);
  const result = adsChatEnsure(await adsChatJson(env, threadId, "/export", { internal: body.internal }));
  return okResponse(result, "");
}

/** Deletes the conversation AND its media from R2 — nothing is left orphaned. */
async function adsChatAdminPurge(request, env, url) {
  const body = await readRequestData(request);
  const threadId = adsAdminThreadId(url, body);
  const result = adsChatEnsure(await adsChatJson(env, threadId, "/purge", {}));
  let removedMedia = 0;
  for (const id of adsArray(result && result.media_ids)) {
    try {
      await env.MOVIES_BUCKET.delete(ADS_CHAT_MEDIA_PREFIX + "media/" + id);
      removedMedia++;
    } catch {
      /* already gone */
    }
  }
  await adsIndexJson(env, "/remove", { thread_id: threadId });
  return okResponse(
    { removed_messages: result ? result.removed : 0, removed_media: removedMedia },
    "تم حذف المحادثة ووسائطها."
  );
}

/** Turns an "ad_request" card into a real draft campaign with the fields filled. */
async function adsChatAdminToCampaign(request, env, url) {
  const body = await readRequestData(request);
  const threadId = adsAdminThreadId(url, body);
  const seq = adsInt(body.seq, 0, 1, 1e9);

  const history = adsChatEnsure(
    await adsChatJson(env, threadId, "/history", { viewer: "admin", since: seq - 1, limit: 1 })
  );
  const message = adsArray(history && history.messages)[0];
  if (!message || message.kind !== "ad_request" || !message.order) {
    throw adsFail("not_found", "لم يُعثر على طلب إعلان بهذا الرقم.", 404);
  }
  const order = message.order;

  const advertisers = await adsReadAdvertisers(env);
  const userId = threadId.slice(2);
  let advertiser = advertisers.items.find((a) => a.user_id === userId) || null;
  if (!advertiser) throw adsFail("not_found", "ملف المعلن غير موجود.", 404);

  const now = adsNow();
  const days = adsInt(order.days, 1, 1, 3650);
  const campaigns = await adsReadCampaigns(env);
  const campaign = adsNormalizeCampaign(
    {
      advertiser_id: advertiser.id,
      slot: order.slot,
      title: adsText(body.title, 120) || ("حملة " + advertiser.name),
      start_at: adsIso(now + 86400000),
      end_at: adsIso(now + 86400000 + days * 86400000),
      days,
      quote: order,
      price_final: Number(order.total) || 0,
      currency: "SAR",
      targeting: { cities: order.cities, langs: order.langs, hours: order.hours },
      status: "draft",
      exclusive: adsArray(order.addons).indexOf("exclusive") >= 0,
    },
    null
  );
  campaigns.items.push(campaign);
  await adsWriteCampaigns(env, campaigns);

  return okResponse(
    { campaign: Object.assign({}, campaign, { effective_status: adsEffectiveStatus(campaign) }) },
    "تم إنشاء الحملة من الطلب. أكمل المواد الإعلانية ثم اعتمدها."
  );
}

/** Best-effort push, reusing the update centre's relay if it is configured. */
async function adsNotifyUser(env, threadId, kind) {
  const hook = cleanString(env.PUSH_WEBHOOK_URL);
  if (!hook) return;
  try {
    await fetch(hook, {
      method: "POST",
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({
        kind: "ads_chat_message",
        thread: threadId,
        message_kind: kind,
        title: "إدارة الإعلانات",
        body: adsPreviewOf(kind, "رسالة جديدة"),
        at: adsIso(),
      }),
    });
  } catch {
    /* never block a send on a notification */
  }
}

// =============================================================================
// MEDIA
// -----------------------------------------------------------------------------
// Nothing in R2 is publicly readable: every byte is encrypted at rest (when
// ADS_MEDIA_KEY is set) and served only through GET /ads/chat/media with a
// short-lived HMAC signature. There is no public R2 URL for chat media.
//
// Large video uses R2 multipart. Each part is sealed independently with its own
// IV and the sealed part sizes are stored on the object, so the download can
// decrypt chunk by chunk as a stream instead of holding the file in memory.
// =============================================================================

const ADS_MEDIA_KINDS = {
  image: { max: ADS_CHAT_MAX_IMAGE, allow: ["image/jpeg", "image/png", "image/webp", "image/gif"] },
  video: { max: ADS_CHAT_MAX_VIDEO, allow: ["video/mp4", "video/webm"] },
  audio: { max: ADS_CHAT_MAX_AUDIO, allow: ["audio/mp4", "audio/mpeg", "audio/ogg"] },
  file: {
    max: ADS_CHAT_MAX_FILE,
    allow: ["application/pdf", "image/jpeg", "image/png", "image/webp"],
  },
};

function adsMediaKey(mediaId) {
  return ADS_CHAT_MEDIA_PREFIX + "media/" + mediaId;
}

/**
 * A media id starts with a hash of its thread, so ownership can be checked while a
 * multipart upload is still in flight (head() on an incomplete upload returns null,
 * so the custom metadata is not readable yet). A hash rather than the sanitised id
 * because stripping characters is ambiguous: "u_u-alpha" and "u_ualpha" would
 * otherwise produce the same prefix.
 */
async function adsThreadTag(threadId) {
  return (await adsHash("ads-thread|" + cleanString(threadId))).slice(0, 16);
}

async function adsNewMediaId(threadId) {
  return (await adsThreadTag(threadId)) + "-" + adsToken(10);
}

function adsCheckMediaKind(kind) {
  const rule = ADS_MEDIA_KINDS[cleanString(kind)];
  if (!rule) throw adsFail("invalid_kind", "نوع الوسائط غير معروف.", 400);
  return rule;
}

/**
 * Single-shot upload: POST .../media/upload?kind=image&name=…  with raw bytes.
 * The declared content-type is ignored; only the sniffed magic bytes decide.
 */
async function adsMediaUpload(request, env, url, threadId) {
  const kind = cleanString(url.searchParams.get("kind")) || "image";
  const rule = adsCheckMediaKind(kind);

  const buffer = await request.arrayBuffer();
  if (!buffer || buffer.byteLength === 0) throw adsFail("empty_upload", "الملف فارغ.", 400);
  if (buffer.byteLength > rule.max) {
    throw adsFail("too_large", "حجم الملف أكبر من المسموح (" + Math.round(rule.max / 1048576) + " ميجابايت).",
      413, { max_bytes: rule.max });
  }

  const sniffed = adsSniffType(buffer);
  if (!sniffed || rule.allow.indexOf(sniffed) < 0) {
    throw adsFail("unsupported_type", "نوع الملف غير مدعوم لهذا الإرسال.", 415, { allowed: rule.allow });
  }

  const mediaId = await adsNewMediaId(threadId);
  const sealed = await adsSealBytes(env, buffer);
  await env.MOVIES_BUCKET.put(adsMediaKey(mediaId), sealed.body, {
    httpMetadata: { contentType: "application/octet-stream", cacheControl: "private, no-store" },
    customMetadata: {
      kind: "ads-chat-media",
      thread: threadId,
      enc: sealed.encrypted ? "1" : "0",
      mime: sniffed,
      size: String(buffer.byteLength),
      name: adsText(url.searchParams.get("name"), 120),
      media_kind: kind,
      uploadedAt: adsIso(),
    },
  });

  return okResponse(
    {
      media_id: mediaId,
      mime: sniffed,
      size: buffer.byteLength,
      encrypted: sealed.encrypted,
      url: await adsMediaUrl(env, mediaId),
    },
    "تم الرفع."
  );
}

// ------------------------------------------------------- multipart (video)

async function adsMediaMpuCreate(request, env, url, threadId) {
  const body = await readRequestData(request);
  const kind = cleanString(body.kind) || "video";
  const rule = adsCheckMediaKind(kind);
  const total = adsInt(body.total_bytes, 0, 1, rule.max);
  if (total <= 0) throw adsFail("invalid_size", "حجم الملف غير صالح.", 400);

  const mediaId = await adsNewMediaId(threadId);
  const upload = await env.MOVIES_BUCKET.createMultipartUpload(adsMediaKey(mediaId), {
    httpMetadata: { contentType: "application/octet-stream", cacheControl: "private, no-store" },
    customMetadata: {
      kind: "ads-chat-media",
      thread: threadId,
      media_kind: kind,
      mime: adsText(body.mime, 60),
      size: String(total),
      name: adsText(body.name, 120),
      uploadedAt: adsIso(),
    },
  });
  return okResponse(
    { media_id: mediaId, upload_id: upload.uploadId, min_part_bytes: 5 * 1024 * 1024 },
    "تم بدء الرفع."
  );
}

async function adsMediaMpuPart(request, env, url, threadId) {
  const mediaId = adsText(url.searchParams.get("media_id"), 80);
  const uploadId = adsText(url.searchParams.get("upload_id"), 300);
  const partNumber = adsInt(url.searchParams.get("part"), 0, 1, 10000);
  if (!mediaId || !uploadId || !partNumber) throw adsFail("invalid", "بيانات الجزء ناقصة.", 400);
  if (mediaId.indexOf((await adsThreadTag(threadId)) + "-") !== 0) {
    throw adsFail("not_allowed", "هذا الملف لا ينتمي لمحادثتك.", 403);
  }

  const buffer = await request.arrayBuffer();
  if (!buffer || buffer.byteLength === 0) throw adsFail("empty_upload", "الجزء فارغ.", 400);

  const upload = env.MOVIES_BUCKET.resumeMultipartUpload(adsMediaKey(mediaId), uploadId);
  const sealed = await adsSealBytes(env, buffer);
  const part = await upload.uploadPart(partNumber, sealed.body);
  const sealedLength = sealed.encrypted ? buffer.byteLength + 12 + 16 : buffer.byteLength;

  return okResponse(
    { part: partNumber, etag: part.etag, sealed_bytes: sealedLength, plain_bytes: buffer.byteLength },
    ""
  );
}

async function adsMediaMpuComplete(request, env, url, threadId) {
  const body = await readRequestData(request);
  const mediaId = adsText(body.media_id, 80);
  const uploadId = adsText(body.upload_id, 300);
  if (!mediaId || !uploadId) throw adsFail("invalid", "بيانات الرفع ناقصة.", 400);

  const parts = adsArray(body.parts)
    .map((p) => ({
      partNumber: adsInt(p && p.part, 0, 1, 10000),
      etag: adsText(p && p.etag, 200),
      sealed: adsInt(p && p.sealed_bytes, 0, 1, 1e9),
    }))
    .filter((p) => p.partNumber && p.etag && p.sealed);
  if (parts.length === 0) throw adsFail("invalid", "لا توجد أجزاء مكتملة.", 400);
  parts.sort((a, b) => a.partNumber - b.partNumber);

  const upload = env.MOVIES_BUCKET.resumeMultipartUpload(adsMediaKey(mediaId), uploadId);
  await upload.complete(parts.map((p) => ({ partNumber: p.partNumber, etag: p.etag })));

  // The chunk map is what lets the download decrypt without buffering the file.
  const existing = await env.MOVIES_BUCKET.head(adsMediaKey(mediaId));
  const meta = Object.assign({}, existing ? existing.customMetadata : {});
  const key = await adsCryptoKey(env);
  meta.enc = key ? "1" : "0";
  meta.chunks = JSON.stringify(parts.map((p) => p.sealed));
  await adsRewriteMetadata(env, adsMediaKey(mediaId), meta);

  return okResponse(
    { media_id: mediaId, url: await adsMediaUrl(env, mediaId), encrypted: Boolean(key) },
    "تم إكمال الرفع."
  );
}

/**
 * R2 cannot patch custom metadata in place, so the object is re-put with the new
 * metadata. Only the multipart finish does this, once, right after the upload.
 */
async function adsRewriteMetadata(env, key, metadata) {
  const object = await env.MOVIES_BUCKET.get(key);
  if (!object) return;
  await env.MOVIES_BUCKET.put(key, object.body, {
    httpMetadata: { contentType: "application/octet-stream", cacheControl: "private, no-store" },
    customMetadata: metadata,
  });
}

async function adsMediaMpuAbort(request, env, url, threadId) {
  const body = await readRequestData(request);
  const mediaId = adsText(body.media_id, 80);
  const uploadId = adsText(body.upload_id, 300);
  if (!mediaId || !uploadId) throw adsFail("invalid", "بيانات الرفع ناقصة.", 400);
  try {
    await env.MOVIES_BUCKET.resumeMultipartUpload(adsMediaKey(mediaId), uploadId).abort();
  } catch {
    /* an already-aborted upload is fine */
  }
  return okResponse({ media_id: mediaId }, "تم إلغاء الرفع.");
}

// ------------------------------------------------------------- media fetch

/** GET /ads/chat/media?id=&exp=&sig= — signature-gated, decrypted on the way out. */
async function adsMediaFetch(request, env, url) {
  const mediaId = adsText(url.searchParams.get("id"), 120);
  const expiresAt = adsText(url.searchParams.get("exp"), 20);
  const signature = adsText(url.searchParams.get("sig"), 80);
  if (!(await adsVerifyMedia(env, mediaId, expiresAt, signature))) {
    throw adsFail("not_allowed", "الرابط غير صالح أو انتهت صلاحيته.", 403);
  }

  const object = await env.MOVIES_BUCKET.get(adsMediaKey(mediaId));
  if (!object) throw adsFail("not_found", "الملف غير موجود.", 404);

  const meta = object.customMetadata || {};
  const encrypted = meta.enc === "1";
  const mime = cleanString(meta.mime) || "application/octet-stream";
  const headers = corsHeaders({
    "content-type": mime,
    // Signed, short-lived and per-viewer: it must not be cached by any proxy.
    "cache-control": "private, max-age=60, no-transform",
    "content-disposition": meta.name
      ? 'inline; filename="' + encodeURIComponent(meta.name) + '"'
      : "inline",
    "x-content-type-options": "nosniff",
  });

  if (!encrypted) return new Response(object.body, { status: 200, headers });

  let chunks = null;
  if (meta.chunks) {
    try {
      chunks = JSON.parse(meta.chunks);
    } catch {
      chunks = null;
    }
  }

  if (!chunks || !Array.isArray(chunks) || chunks.length === 0) {
    const plain = await adsOpenBytes(env, await object.arrayBuffer(), true);
    return new Response(plain, { status: 200, headers });
  }
  return new Response(adsDecryptingStream(env, object.body, chunks), { status: 200, headers });
}

/**
 * Streams a multipart object: buffers exactly one sealed chunk, decrypts it,
 * emits the plaintext, repeats. Memory stays at one chunk, not one file.
 */
function adsDecryptingStream(env, body, chunks) {
  const reader = body.getReader();
  let pending = new Uint8Array(0);
  let index = 0;

  return new ReadableStream({
    async pull(controller) {
      try {
        while (index < chunks.length) {
          const need = chunks[index];
          while (pending.length < need) {
            const { value, done } = await reader.read();
            if (done) {
              if (pending.length === 0 && index >= chunks.length) {
                controller.close();
                return;
              }
              if (pending.length < need) {
                // truncated object: stop cleanly rather than emitting garbage
                controller.close();
                return;
              }
              break;
            }
            const merged = new Uint8Array(pending.length + value.length);
            merged.set(pending, 0);
            merged.set(value, pending.length);
            pending = merged;
          }
          const sealed = pending.slice(0, need);
          pending = pending.slice(need);
          index++;
          const plain = await adsOpenBytes(env, sealed.buffer, true);
          controller.enqueue(new Uint8Array(plain));
          return;
        }
        controller.close();
      } catch (error) {
        controller.error(error);
      }
    },
    cancel() {
      try {
        reader.cancel();
      } catch {
        /* ignore */
      }
    },
  });
}

/**
 * Attaches a previously uploaded media id to the message being sent, after
 * checking it really belongs to this thread (so one advertiser cannot reference
 * another advertiser's upload).
 */
async function adsResolveMedia(env, threadId, body) {
  const mediaId = adsText(body.media_id, 120);
  if (!mediaId) return null;
  const object = await env.MOVIES_BUCKET.head(adsMediaKey(mediaId));
  if (!object) throw adsFail("media_missing", "لم يُعثر على الملف المرفوع.", 404);
  const meta = object.customMetadata || {};
  if (cleanString(meta.thread) !== cleanString(threadId)) {
    throw adsFail("not_allowed", "هذا الملف لا ينتمي لمحادثتك.", 403);
  }

  let thumbId = adsText(body.thumb_id, 120);
  if (thumbId) {
    const thumb = await env.MOVIES_BUCKET.head(adsMediaKey(thumbId));
    if (!thumb || cleanString((thumb.customMetadata || {}).thread) !== cleanString(threadId)) {
      thumbId = "";
    }
  }

  return {
    id: mediaId,
    mime: cleanString(meta.mime) || "application/octet-stream",
    size: adsInt(meta.size, 0, 0, 1e12),
    name: adsText(meta.name, 120),
    width: adsInt(body.width, 0, 0, 20000),
    height: adsInt(body.height, 0, 0, 20000),
    duration_ms: adsInt(body.duration_ms, 0, 0, 24 * 3600 * 1000),
    thumb_id: thumbId,
  };
}

/** Honest report of what IS and is NOT encrypted, for the admin security screen. */
async function adsSecurityReport(request, env) {
  const key = await adsCryptoKey(env);
  return okResponse(
    {
      transport: "TLS (مفروض من Cloudflare على كل الطلبات)",
      messages_at_rest: key ? "مشفّرة AES-GCM بمفتاح في Secrets، IV عشوائي لكل رسالة" : "غير مشفّرة — ADS_MEDIA_KEY غير مضبوط",
      media_at_rest: key ? "مشفّرة AES-GCM، وكل جزء من الفيديو بمفتاح IV خاص" : "غير مشفّرة — ADS_MEDIA_KEY غير مضبوط",
      media_access: "روابط موقّعة HMAC تنتهي بعد " + Math.round(ADS_CHAT_MEDIA_URL_TTL_MS / 60000) + " دقيقة، ولا يوجد رابط R2 عام",
      end_to_end: false,
      end_to_end_note:
        "ليس تشفيراً طرفياً: الخادم يملك المفتاح ويستطيع فك التشفير، وهذا ما يتيح "
        + "استعادة المحادثة على جهاز جديد ودخول الإدارة من أكثر من جهاز.",
      key_configured: Boolean(key),
      sign_key_configured: Boolean(cleanString(env.ADS_MEDIA_SIGN)),
    },
    ""
  );
}

// =============================================================================
// CHAT ROUTE TABLE  — merged into ADS_ROUTES by apply-ads-patch.py
// =============================================================================

const ADS_CHAT_ROUTES = (() => {
  const A = adsAdminRoute;
  const P = adsPublicRoute;
  const U = adsUserRoute;
  return {
    // ---- advertiser (Bearer = the app account token) ------------------------
    "GET /ads/chat/history": U(adsChatUserHistory),
    "GET /ads/chat/poll": U(adsChatUserPoll),
    "GET /ads/chat/state": U(async (request, env, url, user) =>
      json(Object.assign({ ok: true, success: true },
        adsChatEnsure(await adsChatJson(env, adsThreadIdForUser(user), "/state", {}))))),
    "POST /ads/chat/send": U(adsChatUserSend),
    "POST /ads/chat/read": U(adsChatUserSimple("/read", "user")),
    "POST /ads/chat/typing": U(adsChatUserSimple("/typing", "user")),
    "POST /ads/chat/heartbeat": U(adsChatUserSimple("/heartbeat", "user")),
    "POST /ads/chat/delete": U(adsChatUserSimple("/delete", "user")),
    "POST /ads/chat/quote-respond": U(adsChatUserSimple("/quote-respond", "user")),
    "POST /ads/chat/media/upload": U(async (request, env, url, user) =>
      adsMediaUpload(request, env, url, adsThreadIdForUser(user))),
    "POST /ads/chat/media/mpu/create": U(async (request, env, url, user) =>
      adsMediaMpuCreate(request, env, url, adsThreadIdForUser(user))),
    "POST /ads/chat/media/mpu/part": U(async (request, env, url, user) =>
      adsMediaMpuPart(request, env, url, adsThreadIdForUser(user))),
    "POST /ads/chat/media/mpu/complete": U(async (request, env, url, user) =>
      adsMediaMpuComplete(request, env, url, adsThreadIdForUser(user))),
    "POST /ads/chat/media/mpu/abort": U(async (request, env, url, user) =>
      adsMediaMpuAbort(request, env, url, adsThreadIdForUser(user))),

    // signature-gated, no bearer: the signed URL IS the authorisation
    "GET /ads/chat/media": P(adsMediaFetch),
    // WebSocket upgrade: ?token= is verified exactly like the header
    "GET /ads/chat/ws": P(adsChatUserSocket),

    // ---- admin (Bearer = ADMIN_TOKEN) --------------------------------------
    "GET /admin/ads/chat/threads": A(adsChatAdminThreads),
    "GET /admin/ads/chat/history": A(adsChatAdminHistory),
    "GET /admin/ads/chat/poll": A(adsChatAdminPoll),
    "GET /admin/ads/chat/state": A(async (request, env, url) =>
      okResponse(adsChatEnsure(await adsChatJson(env, adsAdminThreadId(url, null), "/state", {})), "")),
    "POST /admin/ads/chat/send": A(adsChatAdminSend),
    "POST /admin/ads/chat/read": A(adsChatAdminSimple("/read")),
    "POST /admin/ads/chat/typing": A(adsChatAdminSimple("/typing")),
    "POST /admin/ads/chat/heartbeat": A(adsChatAdminSimple("/heartbeat")),
    "POST /admin/ads/chat/delete": A(adsChatAdminSimple("/delete")),
    "POST /admin/ads/chat/quote-respond": A(adsChatAdminSimple("/quote-respond")),
    "POST /admin/ads/chat/flags": A(adsChatAdminSimple("/flags")),
    "POST /admin/ads/chat/export": A(adsChatAdminExport),
    "POST /admin/ads/chat/purge": A(adsChatAdminPurge),
    "POST /admin/ads/chat/to-campaign": A(adsChatAdminToCampaign),
    "POST /admin/ads/chat/media/upload": A(async (request, env, url) =>
      adsMediaUpload(request, env, url, adsAdminThreadId(url, null))),
    "GET /admin/ads/chat/ws": A(adsChatAdminSocket),
    "GET /admin/ads/security": A(adsSecurityReport),
  };
})();
