#!/usr/bin/env python3
"""
Merges the ads modules into cigram-admin-worker-v2.js and writes the single file
you paste into the Cloudflare dashboard. Nothing in the source worker is removed:
three lines go into its router and the modules are appended after it.

    python3 apply-ads-patch.py cigram-admin-worker-v2.js out.js
    python3 apply-ads-patch.py cigram-admin-worker-v2.js cigram-ads-module.js out.js

With no module listed it appends every cigram-ads-*module.js next to this script,
in phase order. Re-running it on an already-patched file is refused, so retrying
is safe.
"""
import glob
import os
import re
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


def default_modules() -> list:
    """Every ads module beside this script, in phase order (base first)."""
    here = os.path.dirname(os.path.abspath(__file__))
    order = ["cigram-ads-module.js", "cigram-ads-chat-module.js"]
    found = sorted(glob.glob(os.path.join(here, "cigram-ads-*module.js")))
    ranked = sorted(found, key=lambda p: (order.index(os.path.basename(p))
                                          if os.path.basename(p) in order else 99,
                                          os.path.basename(p)))
    if not ranked:
        raise SystemExit("ERROR: no cigram-ads-*module.js found next to this script.")
    return ranked


def route_tables(modules: list) -> list:
    """Finds every `const ADS_*_ROUTES = ` table so they can be merged into ADS_ROUTES."""
    names = []
    for text in modules:
        for name in re.findall(r"^const (ADS_[A-Z_]*ROUTES) = ", text, re.M):
            if name != "ADS_ROUTES" and name not in names:
                names.append(name)
    return names


def patch(worker: str, modules: list) -> str:
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

    out = [worker.rstrip("\n")]
    out.extend(text.strip("\n") for text in modules)

    extra = route_tables(modules)
    if extra:
        merge = ["", "// Later phases add their routes to the one table the router reads.",
                 "// (appended by apply-ads-patch.py)"]
        for name in extra:
            merge.append("Object.assign(ADS_ROUTES, %s);" % name)
        out.append("\n".join(merge))
    return "\n\n".join(out) + "\n"


def main() -> None:
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    worker_path = sys.argv[1]
    out_path = sys.argv[-1]
    module_paths = sys.argv[2:-1] or default_modules()

    worker = open(worker_path, encoding="utf-8").read()
    modules = [open(p, encoding="utf-8").read() for p in module_paths]
    out = patch(worker, modules)
    open(out_path, "w", encoding="utf-8").write(out)
    print("wrote %s (%d bytes) from %s + %s"
          % (out_path, len(out), os.path.basename(worker_path),
             ", ".join(os.path.basename(p) for p in module_paths)))


if __name__ == "__main__":
    main()
