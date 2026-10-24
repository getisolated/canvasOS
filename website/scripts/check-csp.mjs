// Fails the build when an inline <script> in dist/ is not allowed by the CSP in dist/_headers
// (Cloudflare Pages would block it silently). Runs after `astro build`.
import { createHash } from "node:crypto";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";

const dist = new URL("../dist/", import.meta.url).pathname;
const headers = readFileSync(join(dist, "_headers"), "utf8");
const csp = headers.match(/Content-Security-Policy:\s*(.+)/)?.[1] ?? "";
const scriptSrc = csp.split(";").map((d) => d.trim()).find((d) => d.startsWith("script-src")) ?? "";

const html = [];
const walk = (dir) => {
  for (const name of readdirSync(dir)) {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) walk(path);
    else if (name.endsWith(".html")) html.push(path);
  }
};
walk(dist);

let bad = 0;
for (const file of html) {
  const src = readFileSync(file, "utf8");
  for (const m of src.matchAll(/<script(?![^>]*\bsrc=)(?![^>]*application\/ld\+json)[^>]*>([\s\S]*?)<\/script>/g)) {
    const hash = `'sha256-${createHash("sha256").update(m[1]).digest("base64")}'`;
    if (!scriptSrc.includes(hash)) {
      console.error(`CSP: ${file.slice(dist.length)} has an inline script not in public/_headers script-src: ${hash}`);
      bad++;
    }
  }
}
if (bad) process.exit(1);
console.log(`CSP: ${html.length} HTML files, every inline script is allowed.`);
