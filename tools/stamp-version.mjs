// Run after changing www/index.html:   node tools/stamp-version.mjs "what changed"
// Phones running IronLog compare this version with theirs and download the new page by themselves.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const html = readFileSync(new URL('../www/index.html', import.meta.url));
const d = new Date(), p = n => String(n).padStart(2, '0');
const version = Number(`${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}${p(d.getUTCHours())}${p(d.getUTCMinutes())}`);
const out = { version, size: html.length, sha256: createHash('sha256').update(html).digest('hex'), notes: process.argv.slice(2).join(' ') };
writeFileSync(new URL('../www/version.json', import.meta.url), JSON.stringify(out, null, 2) + '\n');
console.log('www/version.json →', out.version, `(${out.size} bytes)`);
