#!/usr/bin/env node
// Writes SHA256SUMS, latest.json and notes.md for a release from the APKs in a dist folder.
// Usage: node tools/release-meta.mjs --version 0.1.0 --dist dist [--repo tanzir71/harkara]
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : fallback;
};
const version = arg('version');
const dist = resolve(arg('dist', 'dist'));
const repo = arg('repo', 'tanzir71/harkara');
if (!/^\d+\.\d+\.\d+$/.test(version ?? '')) {
  console.error('need --version x.y.z');
  process.exit(1);
}

export const versionCode = (v) => {
  const [major, minor, patch] = v.split('.').map(Number);
  return major * 10000 + minor * 100 + patch;
};

/** The CHANGELOG section for this version ("## 0.1.0" or "## v0.1.0"), without the heading. */
export function changelogSection(changelog, v) {
  const lines = changelog.split(/\r?\n/);
  const start = lines.findIndex((l) => new RegExp(`^##\\s+v?${v.replace(/\./g, '\\.')}\\b`).test(l));
  if (start < 0) return `Harkara ${v}`;
  let end = lines.findIndex((l, i) => i > start && /^##\s/.test(l));
  if (end < 0) end = lines.length;
  return lines.slice(start + 1, end).join('\n').trim();
}

const order = ['arm64-v8a', 'armeabi-v7a', 'universal'];
const apks = readdirSync(dist)
  .filter((f) => f.endsWith('.apk'))
  .map((file) => {
    const abi = order.find((a) => file === `harkara-${version}-${a}.apk`);
    if (!abi) throw new Error(`unexpected APK name ${file} (want harkara-${version}-<abi>.apk)`);
    const bytes = readFileSync(join(dist, file));
    return {
      abi,
      file,
      url: `https://github.com/${repo}/releases/download/v${version}/${file}`,
      bytes: statSync(join(dist, file)).size,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    };
  })
  .sort((a, b) => order.indexOf(a.abi) - order.indexOf(b.abi));
if (apks.length === 0) throw new Error(`no APKs in ${dist}`);

const notes = changelogSection(readFileSync(join(root, 'CHANGELOG.md'), 'utf8'), version);
writeFileSync(join(dist, 'SHA256SUMS'), apks.map((a) => `${a.sha256}  ${a.file}`).join('\n') + '\n');
writeFileSync(
  join(dist, 'latest.json'),
  JSON.stringify(
    {
      version,
      versionCode: versionCode(version),
      published: new Date().toISOString(),
      apks: apks.map(({ abi, url, bytes, sha256 }) => ({ abi, url, bytes, sha256 })),
      notes,
    },
    null,
    2,
  ) + '\n',
);
writeFileSync(join(dist, 'notes.md'), `${notes}\n\n**SHA-256**\n\n\`\`\`\n${readFileSync(join(dist, 'SHA256SUMS'), 'utf8')}\`\`\`\n`);
console.log(`release ${version}: ${apks.map((a) => `${a.abi} ${(a.bytes / 1e6).toFixed(1)} MB`).join(', ')}`);
