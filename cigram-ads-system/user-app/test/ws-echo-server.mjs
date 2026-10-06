// A hand-rolled RFC6455 server, just enough to prove the Java client's framing.
// It echoes text, sends a ping, replies to pings, and can send a fragmented
// message and a >64KiB message so the 16-bit and 64-bit length paths are hit.
import net from "node:net";
import crypto from "node:crypto";

const GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
const PORT = Number(process.argv[2] || 18099);
const log = (...a) => console.log("[server]", ...a);

function frame(opcode, payload, fin = true) {
  const len = payload.length;
  let head;
  if (len < 126) head = Buffer.from([(fin ? 0x80 : 0) | opcode, len]);
  else if (len < 65536) {
    head = Buffer.alloc(4);
    head[0] = (fin ? 0x80 : 0) | opcode;
    head[1] = 126;
    head.writeUInt16BE(len, 2);
  } else {
    head = Buffer.alloc(10);
    head[0] = (fin ? 0x80 : 0) | opcode;
    head[1] = 127;
    head.writeBigUInt64BE(BigInt(len), 2);
  }
  return Buffer.concat([head, payload]);
}

const server = net.createServer((sock) => {
  let buf = Buffer.alloc(0);
  let handshaken = false;
  let assembling = Buffer.alloc(0);

  sock.on("data", (chunk) => {
    buf = Buffer.concat([buf, chunk]);

    if (!handshaken) {
      const end = buf.indexOf("\r\n\r\n");
      if (end < 0) return;
      const head = buf.slice(0, end).toString("utf8");
      buf = buf.slice(end + 4);
      const key = /sec-websocket-key:\s*(.+)/i.exec(head);
      if (!key) { sock.end("HTTP/1.1 400 Bad Request\r\n\r\n"); return; }
      const accept = crypto.createHash("sha1").update(key[1].trim() + GUID).digest("base64");
      sock.write(
        "HTTP/1.1 101 Switching Protocols\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
      );
      handshaken = true;
      log("handshake ok");
      sock.write(frame(0x1, Buffer.from(JSON.stringify({ type: "hello" }), "utf8")));
      setTimeout(() => { if (!sock.destroyed) sock.write(frame(0x9, Buffer.from("hb"))); }, 150);
    }

    for (;;) {
      if (buf.length < 2) return;
      const b0 = buf[0];
      const b1 = buf[1];
      const fin = (b0 & 0x80) !== 0;
      const opcode = b0 & 0x0f;
      const masked = (b1 & 0x80) !== 0;
      let len = b1 & 0x7f;
      let at = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); at = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); at = 10; }
      if (!masked) { log("FATAL: client frame was not masked"); process.exit(3); }
      if (buf.length < at + 4 + len) return;
      const mask = buf.slice(at, at + 4);
      const payload = Buffer.from(buf.slice(at + 4, at + 4 + len));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
      buf = buf.slice(at + 4 + len);

      if (opcode === 0x9) { sock.write(frame(0xa, payload)); continue; }
      if (opcode === 0xa) { log("pong received"); continue; }
      if (opcode === 0x8) { log("client closed"); sock.write(frame(0x8, payload)); sock.end(); return; }
      if (opcode === 0x1 || opcode === 0x0) {
        assembling = Buffer.concat([assembling, payload]);
        if (!fin) continue;
        const text = assembling.toString("utf8");
        assembling = Buffer.alloc(0);
        log("got:", text.length > 60 ? text.slice(0, 60) + "..." : text);
        if (text === "FRAGMENT") {
          sock.write(frame(0x1, Buffer.from("frag-", "utf8"), false));
          sock.write(frame(0x0, Buffer.from("part2-", "utf8"), false));
          sock.write(frame(0x0, Buffer.from("end", "utf8"), true));
        } else if (text === "BIG") {
          sock.write(frame(0x1, Buffer.from("B".repeat(70000), "utf8")));
        } else if (text === "MEDIUM") {
          sock.write(frame(0x1, Buffer.from("M".repeat(1000), "utf8")));
        } else if (text === "BYE") {
          sock.write(frame(0x8, Buffer.from([0x03, 0xe8])));
        } else {
          sock.write(frame(0x1, Buffer.from("echo:" + text, "utf8")));
        }
      }
    }
  });
  sock.on("error", () => {});
});
server.listen(PORT, "127.0.0.1", () => log("listening on " + PORT));
