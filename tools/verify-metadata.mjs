#!/usr/bin/env node
// Offline fastlane metadata validation. Does not submit to any store.
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function verifyMetadata(root = 'fastlane/metadata/android') {
  const errors = [];
  const changelogs = [];
  for (const locale of ['en-US', 'bn-BD']) {
    const dir = join(root, locale);
    for (const [file, limit] of [['title.txt', 30], ['short_description.txt', 80], ['full_description.txt', 4000]]) {
      const text = readFileSync(join(dir, file), 'utf8').trim();
      if (!text || [...text].length > limit) errors.push(`${locale}/${file}: expected 1..${limit} Unicode characters, got ${[...text].length}`);
      if (file !== 'full_description.txt' && /[\r\n<>]/.test(text)) errors.push(`${locale}/${file}: must be one plain-text line`);
      if (locale === 'bn-BD' && !/[\u0980-\u09ff]/u.test(text)) errors.push(`${locale}/${file}: no Bangla text`);
    }
    const icon = readFileSync(join(dir, 'images/icon.png'));
    if (icon.length < 33 || icon.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' || icon.toString('ascii', 12, 16) !== 'IHDR') {
      errors.push(`${locale}: icon must be PNG`);
    } else {
      const w = icon.readUInt32BE(16), h = icon.readUInt32BE(20);
      if (w !== h || w < 48 || w > 512) errors.push(`${locale}: icon must be square, 48..512 px; got ${w}x${h}`);
    }
    const files = readdirSync(join(dir, 'changelogs')).filter((f) => f.endsWith('.txt')).sort();
    if (!files.length) errors.push(`${locale}: no changelogs`);
    for (const file of files) {
      const text = readFileSync(join(dir, 'changelogs', file), 'utf8').trim();
      if (!/^[1-9]\d*\.txt$/.test(file) || !text || [...text].length > 500) errors.push(`${locale}/changelogs/${file}: invalid versionCode or length (1..500)`);
      if (locale === 'bn-BD' && !/[\u0980-\u09ff]/u.test(text)) errors.push(`${locale}/changelogs/${file}: no Bangla text`);
    }
    changelogs.push(files);
  }
  if (JSON.stringify(changelogs[0]) !== JSON.stringify(changelogs[1])) errors.push('locale changelog versionCodes differ');
  return errors;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const errors = verifyMetadata(process.argv[2]);
    errors.forEach((error) => console.error(error));
    if (errors.length) process.exitCode = 1;
    else console.log('fastlane metadata: en-US and bn-BD valid (text limits, changelogs, PNG icons)');
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
