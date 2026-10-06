// Minimal Cloudflare-shaped mocks so the merged worker can be exercised in Node.
export class MemoryBucket {
  constructor() { this.map = new Map(); }
  async get(key) {
    const v = this.map.get(key);
    if (!v) return null;
    return {
      body: v.body,
      httpEtag: '"' + key.length + "-" + v.body.length + '"',
      async text() { return typeof v.body === "string" ? v.body : new TextDecoder().decode(v.body); },
      async arrayBuffer() { return typeof v.body === "string" ? new TextEncoder().encode(v.body).buffer : v.body; },
    };
  }
  async put(key, body, opts) { this.map.set(key, { body, opts }); return { key }; }
  async delete(key) { this.map.delete(key); }
  async head(key) { return this.map.has(key) ? { key } : null; }
  async list(opts) {
    const prefix = (opts && opts.prefix) || "";
    const objects = [...this.map.keys()].filter((k) => k.startsWith(prefix)).map((k) => ({ key: k, size: 1 }));
    return { objects, truncated: false };
  }
}

class MemoryStorage {
  constructor() { this.map = new Map(); this.alarmAt = null; }
  async get(k) { return this.map.get(k); }
  async put(k, v) { this.map.set(k, v); }
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
