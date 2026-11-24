#!/usr/bin/env node
// Copies the design handover (icons, logo, tokens, copy) into design/, stripping
// embedded provenance metadata so the SVGs can be inlined and converted.
// Usage: node tools/import-handover.mjs [path-to-harkara-handover]
import { mkdirSync, readdirSync, readFileSync, writeFileSync, copyFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const src = resolve(process.argv[2] ?? join(root, '..', 'harkara-handover'));
const out = join(root, 'design');

const cleanSvg = (text) =>
  text
    .replace(/<metadata>[\s\S]*?<\/metadata>/g, '')
    .replace(/\s+xmlns:c2pa="[^"]*"/g, '')
    .replace(/\n{2,}/g, '\n')
    .trim() + '\n';

for (const dir of ['icons', 'brand']) {
  mkdirSync(join(out, dir), { recursive: true });
  for (const name of readdirSync(join(src, dir)).filter((n) => n.endsWith('.svg'))) {
    writeFileSync(join(out, dir, name), cleanSvg(readFileSync(join(src, dir, name), 'utf8')));
  }
}
for (const [dir, files] of [['tokens', ['tokens.json']], ['copy', ['landing.json']]]) {
  mkdirSync(join(out, dir), { recursive: true });
  for (const f of files) copyFileSync(join(src, dir, f), join(out, dir, f));
}
console.log(`imported handover from ${src} into ${out}`);
