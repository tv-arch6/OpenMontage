// Minimal Cloudflare-shaped mocks so the merged worker can be exercised in Node.
function asBytes(body) {
  if (typeof body === "string") return new TextEncoder().encode(body);
  if (body instanceof Uint8Array) return body;
  if (body instanceof ArrayBuffer) return new Uint8Array(body);
  if (body && body.buffer) return new Uint8Array(body.buffer);
  return new Uint8Array(0);
}

export class MemoryBucket {
  constructor() { this.map = new Map(); this.mpu = new Map(); }

  shape(key, v) {
    const bytes = asBytes(v.body);
    const opts = v.opts || {};
    return {
      key,
      size: bytes.length,
      body: new Blob([bytes]).stream(),
      httpEtag: '"' + key.length + "-" + bytes.length + '"',
      // R2 surfaces these on get() AND head(); the worker reads mime/enc/chunks here.
      customMetadata: opts.customMetadata || {},
      httpMetadata: opts.httpMetadata || {},
      async text() { return new TextDecoder().decode(bytes); },
      async arrayBuffer() { return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length); },
    };
  }

  async get(key) {
    const v = this.map.get(key);
    return v ? this.shape(key, v) : null;
  }
  async put(key, body, opts) {
    // A stream body (as the metadata rewrite passes) is drained first.
    if (body && typeof body.getReader === "function") {
      const reader = body.getReader();
      const parts = [];
      let total = 0;
      for (;;) {
        const { value, done } = await reader.read();
        if (done) break;
        parts.push(value);
        total += value.length;
      }
      const merged = new Uint8Array(total);
      let at = 0;
      for (const part of parts) { merged.set(part, at); at += part.length; }
      body = merged;
    }
    this.map.set(key, { body: asBytes(body), opts });
    return { key };
  }
  async delete(key) { this.map.delete(key); }
  async head(key) {
    const v = this.map.get(key);
    return v ? this.shape(key, v) : null;
  }

  // ---- multipart, enough of it to exercise the sealed-chunk path ----
  async createMultipartUpload(key, opts) {
    const uploadId = "mpu-" + Math.random().toString(36).slice(2);
    this.mpu.set(uploadId, { key, opts, parts: new Map() });
    return { uploadId, key };
  }
  resumeMultipartUpload(key, uploadId) {
    const self = this;
    return {
      async uploadPart(partNumber, body) {
        const session = self.mpu.get(uploadId);
        if (!session) throw new Error("no such upload");
        const bytes = asBytes(body);
        session.parts.set(partNumber, bytes);
        return { partNumber, etag: "etag-" + partNumber + "-" + bytes.length };
      },
      async complete(parts) {
        const session = self.mpu.get(uploadId);
        if (!session) throw new Error("no such upload");
        const ordered = parts.slice().sort((a, b) => a.partNumber - b.partNumber);
        let total = 0;
        for (const p of ordered) total += (session.parts.get(p.partNumber) || new Uint8Array(0)).length;
        const merged = new Uint8Array(total);
        let at = 0;
        for (const p of ordered) {
          const bytes = session.parts.get(p.partNumber) || new Uint8Array(0);
          merged.set(bytes, at);
          at += bytes.length;
        }
        self.map.set(key, { body: merged, opts: session.opts });
        self.mpu.delete(uploadId);
        return { key };
      },
      async abort() { self.mpu.delete(uploadId); },
    };
  }
  async list(opts) {
    const prefix = (opts && opts.prefix) || "";
    const objects = [...this.map.keys()].filter((k) => k.startsWith(prefix)).map((k) => ({ key: k, size: 1 }));
    return { objects, truncated: false };
  }
}

class MemoryStorage {
  constructor() { this.map = new Map(); this.alarmAt = null; }
  // Real DO storage: get(key) -> value, get(keys[]) -> Map, put(key,v), put({k:v}).
  async get(k) {
    if (Array.isArray(k)) {
      const out = new Map();
      for (const key of k) { if (this.map.has(key)) out.set(key, this.map.get(key)); }
      return out;
    }
    return this.map.get(k);
  }
  async put(k, v) {
    if (k && typeof k === "object" && !Array.isArray(k)) {
      for (const [key, value] of Object.entries(k)) this.map.set(key, value);
      return;
    }
    this.map.set(k, v);
  }
  async deleteAll() { this.map.clear(); }
  async delete(k) {
    if (Array.isArray(k)) { for (const x of k) this.map.delete(x); return k.length; }
    return this.map.delete(k);
  }
  async list(opts) {
    const prefix = (opts && opts.prefix) || "";
    const limit = (opts && opts.limit) || 1000;
    const out = new Map();
    for (const [k, v] of this.map) { if (k.startsWith(prefix) && out.size < limit) out.set(k, v); }
    return out;
  }
  async getAlarm() { return this.alarmAt; }
  async setAlarm(t) { this.alarmAt = t; }
}

export function makeDoNamespace(Klass, env) {
  const instances = new Map();
  return {
    idFromName(name) { return { name, toString: () => name }; },
    get(id) {
      const key = id.name || String(id);
      if (!instances.has(key)) {
        const state = {
          storage: new MemoryStorage(),
          async blockConcurrencyWhile(fn) { return fn(); },
        };
        instances.set(key, new Klass(state, env));
      }
      const obj = instances.get(key);
      return { fetch: (url, init) => obj.fetch(new Request(url, init)) };
    },
  };
}
