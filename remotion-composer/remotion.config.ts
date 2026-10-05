import { Config } from "@remotion/cli/config";
import fs from "fs";

// Sandbox accommodation: use a preinstalled Chromium when Remotion's own browser download is blocked.
const candidate = "/opt/pw-browsers/chromium_headless_shell-1194/chrome-linux/headless_shell";
if (fs.existsSync(candidate)) {
  Config.setBrowserExecutable(candidate);
}
