#!/usr/bin/env node
// Fails when a per-ABI release APK is over the 30 MB budget (handover §4). The universal APK is reported
// but not limited (it carries every ABI on purpose).
// Usage: node tools/check-apk-size.mjs [dir]
import { readdirSync, statSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const APK_LIMIT = 30_000_000;
export const REQUIRED_ABIS = ['arm64-v8a', 'armeabi-v7a'];

export function checkApkSize(dir) {
  const apks = readdirSync(dir).filter((f) => f.endsWith('.apk')).sort();
  const seen = new Set();
  const errors = [];
  const lines = [];
  for (const file of apks) {
    const abi = [...REQUIRED_ABIS, 'universal'].find((a) =>
      new RegExp(`^(?:app-${a}-release(?:-unsigned)?|harkara-\\d+\\.\\d+\\.\\d+-${a})\\.apk$`).test(file));
    const stat = statSync(join(dir, file));
    if (!abi || !stat.isFile() || stat.size === 0) {
      errors.push(`unexpected or empty APK: ${file}`);
      continue;
    }
    if (seen.has(abi)) errors.push(`duplicate APK for ${abi}`);
    seen.add(abi);
    const over = abi !== 'universal' && stat.size > APK_LIMIT;
    lines.push(`${over ? 'OVER' : 'ok  '} ${file}  ${stat.size} bytes (${(stat.size / 1e6).toFixed(2)} MB)${abi === 'universal' ? ' (universal: not limited)' : ''}`);
    if (over) errors.push(`${file} exceeds 30 MB (${APK_LIMIT} bytes) per ABI`);
  }
  for (const abi of REQUIRED_ABIS) if (!seen.has(abi)) errors.push(`missing APK for ${abi}`);
  return { lines, errors };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const { lines, errors } = checkApkSize(process.argv[2] ?? 'app/build/outputs/apk/release');
    lines.forEach((line) => console.log(line));
    errors.forEach((error) => console.error(error));
    if (errors.length) process.exitCode = 1;
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
