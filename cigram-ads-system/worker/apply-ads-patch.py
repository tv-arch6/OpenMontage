#!/usr/bin/env python3
"""
Merges cigram-ads-module.js into cigram-admin-worker-v2.js and writes the single
file you paste into the Cloudflare dashboard. Nothing in the source worker is
removed: three lines are inserted into its router and the module is appended.

    python3 apply-ads-patch.py cigram-admin-worker-v2.js cigram-ads-module.js out.js

Re-running it on an already-patched file is refused, so it is safe to retry.
"""
import sys

HEALTH_ANCHOR = ".concat(Object.keys(MEDIA_ROUTES)),"
HEALTH_PATCH = ".concat(Object.keys(MEDIA_ROUTES))\n            .concat(Object.keys(ADS_ROUTES)),"

ROUTER_ANCHOR = """      const mediaHandler = MEDIA_ROUTES[request.method + " " + path];
      if (mediaHandler) return mediaHandler(request, env, url);
"""
ROUTER_PATCH = ROUTER_ANCHOR + """
      const adsHandler = ADS_ROUTES[request.method + " " + path];
      if (adsHandler) return adsHandler(request, env, url, ctx);
"""

CRON_ANCHOR = """    ctx.waitUntil(
      updActivateDue(env, true).catch((error) => updLog("cron_failed", { message: String(error && error.message) }))
    );
"""
CRON_PATCH = CRON_ANCHOR + """    ctx.waitUntil(
      adsCron(env).catch((error) => console.log("ads_cron_failed", String(error && error.message)))
    );
"""


def patch(worker: str, module: str) -> str:
    if "ADS_ROUTES" in worker:
        raise SystemExit("ERROR: this worker already contains the ads module. Start from a clean v2 file.")

    for name, anchor in (
        ("health route list", HEALTH_ANCHOR),
        ("router dispatch", ROUTER_ANCHOR),
        ("scheduled() cron", CRON_ANCHOR),
    ):
        if worker.count(anchor) != 1:
            raise SystemExit(
                f"ERROR: could not find exactly one '{name}' anchor "
                f"(found {worker.count(anchor)}). Apply the patch by hand — see INTEGRATION.md."
            )

    worker = worker.replace(HEALTH_ANCHOR, HEALTH_PATCH, 1)
    worker = worker.replace(ROUTER_ANCHOR, ROUTER_PATCH, 1)
    worker = worker.replace(CRON_ANCHOR, CRON_PATCH, 1)
    return worker.rstrip("\n") + "\n\n" + module.lstrip("\n")


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    worker = open(sys.argv[1], encoding="utf-8").read()
    module = open(sys.argv[2], encoding="utf-8").read()
    out = patch(worker, module)
    open(sys.argv[3], "w", encoding="utf-8").write(out)
    print(f"wrote {sys.argv[3]} ({len(out)} bytes)")


if __name__ == "__main__":
    main()
