#!/usr/bin/env node
// Fails when a per-ABI release APK is over the 30 MB budget (handover §4). The universal APK is reported
// but not limited (it carries every ABI on purpose).
// Usage: node tools/check-apk-size.mjs [dir]
import { readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2] ?? 'app/build/outputs/apk/release';
const LIMIT = 30 * 1024 * 1024;
const apks = readdirSync(dir).filter((f) => f.endsWith('.apk'));
if (apks.length === 0) {
  console.error(`no APKs in ${dir}`);
  process.exit(1);
}
let failed = false;
for (const f of apks) {
  const bytes = statSync(join(dir, f)).size;
  const universal = f.includes('universal');
  const over = !universal && bytes > LIMIT;
  console.log(`${over ? 'OVER' : 'ok  '} ${f}  ${(bytes / 1024 / 1024).toFixed(1)} MB${universal ? '  (universal: not limited)' : ''}`);
  failed ||= over;
}
if (failed) {
  console.error('APK size budget is 30 MB per ABI');
  process.exit(1);
}
