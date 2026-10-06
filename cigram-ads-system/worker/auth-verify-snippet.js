// =============================================================================
// ADD THIS ROUTE TO  cigram-auth-api.wwq-mixtv.workers.dev
// -----------------------------------------------------------------------------
// The ads Worker never sees your password hashes or your session store: it asks
// the auth Worker "who owns this bearer token?" and gets back an id only.
//
// ASSUMPTION — verify this against your real auth Worker before deploying:
// this snippet assumes sessions are looked up by token somewhere in that Worker.
// Replace `lookupSession(token, env)` with whatever that Worker already uses in
// /account/delete to resolve the caller (it must already do exactly this).
// Nothing else in the auth Worker changes.
//
// Contract the ads Worker depends on:
//   POST /verify     Authorization: Bearer <user token>   body: {"token": "..."}
//   200 -> { "ok": true,  "user": { "id": "...", "email": "...", "name": "..." } }
//   401 -> { "ok": false }
// Only `user.id` is required; email and name just prefill the advertiser file.
// =============================================================================

async function handleVerify(request, env) {
  const bearer = (request.headers.get("authorization") || "").replace(/^Bearer\s+/i, "").trim();
  let bodyToken = "";
  try {
    const body = await request.json();
    bodyToken = String((body && body.token) || "").trim();
  } catch {
    /* a body is optional; the header is enough */
  }
  const token = bearer || bodyToken;

  if (!token || token.length < 8 || token.length > 4096) {
    return new Response(JSON.stringify({ ok: false }), {
      status: 401,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
    });
  }

  // <-- the one line to adapt: reuse the session lookup /account/delete already performs.
  const session = await lookupSession(token, env);

  if (!session || !session.user_id) {
    return new Response(JSON.stringify({ ok: false }), {
      status: 401,
      headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
    });
  }

  return new Response(
    JSON.stringify({
      ok: true,
      user: {
        id: String(session.user_id),
        email: String(session.email || "").toLowerCase(),
        name: String(session.name || session.username || ""),
      },
    }),
    {
      status: 200,
      headers: {
        "content-type": "application/json; charset=utf-8",
        // Never cached: a revoked token must stop working immediately.
        "cache-control": "no-store",
      },
    }
  );
}

// In that Worker's router, next to the existing routes:
//
//   if (request.method === "POST" && path === "/verify") return handleVerify(request, env);
