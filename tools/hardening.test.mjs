import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, copyFileSync, rmSync, truncateSync, writeFileSync, existsSync, readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { APK_LIMIT, checkApkSize } from './check-apk-size.mjs';
import { verifyMetadata } from './verify-metadata.mjs';
import { auditContrast, contrast } from './audit-token-contrast.mjs';

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'harkara-hardening-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  return dir;
}

function apk(dir, abi, size = 1) {
  const path = join(dir, `harkara-0.11.0-${abi}.apk`);
  writeFileSync(path, 'x');
  truncateSync(path, size);
}

test('normal text contrast uses WCAG 4.5:1, not the 3:1 icon threshold', () => {
  assert.equal(contrast('#000000', '#ffffff'), 21);
  assert.equal(contrast('#ffffff', '#ffffff'), 1);
  assert.equal(contrast('#123456', '#abcdef'), contrast('#abcdef', '#123456'));
  assert.throws(() => contrast('#fff', '#000000'), /opaque RGB/);
  const tokens = JSON.parse(readFileSync('design/tokens/tokens.json', 'utf8'));
  const result = auditContrast(tokens.color);
  assert.equal(result.checks, 24);
  assert.equal(result.threshold, 4.5);
  assert.deepEqual(result.failures, []);
  assert.deepEqual(tokens.color['text/tertiary'], tokens.color['text/secondary']);
});

test('size gate rejects empty directories and universal-only artifacts', (t) => {
  const dir = fixture(t);
  assert.equal(checkApkSize(dir).errors.length, 2);
  apk(dir, 'universal');
  assert.equal(checkApkSize(dir).errors.length, 2);
});

test('30 MB boundary is inclusive, universal is exempt', (t) => {
  const dir = fixture(t);
  apk(dir, 'arm64-v8a', APK_LIMIT);
  apk(dir, 'armeabi-v7a', APK_LIMIT);
  apk(dir, 'universal', APK_LIMIT + 1);
  assert.deepEqual(checkApkSize(dir).errors, []);
  apk(dir, 'arm64-v8a', APK_LIMIT + 1);
  assert.match(checkApkSize(dir).errors.join(), /exceeds 30 MB/);
});

test('size gate accepts Gradle unsigned names, rejects duplicates, zero and misleading names', (t) => {
  const dir = fixture(t);
  for (const abi of ['arm64-v8a', 'armeabi-v7a']) writeFileSync(join(dir, `app-${abi}-release-unsigned.apk`), 'x');
  assert.deepEqual(checkApkSize(dir).errors, []);
  apk(dir, 'arm64-v8a');
  assert.match(checkApkSize(dir).errors.join(), /duplicate/);
  apk(dir, 'armeabi-v7a', 0);
  writeFileSync(join(dir, 'pretend-universal.apk'), 'x');
  assert.equal(checkApkSize(dir).errors.filter((e) => /unexpected or empty/.test(e)).length, 2);
});

test('metadata in both repository locales passes', () => assert.deepEqual(verifyMetadata(), []));

test('release metadata refuses missing or oversized splits before writing publishable files', (t) => {
  const dir = fixture(t);
  const run = () => spawnSync(process.execPath, ['tools/release-meta.mjs', '--version', '0.11.0', '--dist', dir], { encoding: 'utf8' });
  apk(dir, 'arm64-v8a');
  assert.notEqual(run().status, 0);
  assert.equal(existsSync(join(dir, 'latest.json')), false);
  apk(dir, 'armeabi-v7a', APK_LIMIT + 1);
  assert.notEqual(run().status, 0);
  assert.equal(existsSync(join(dir, 'SHA256SUMS')), false);
  apk(dir, 'armeabi-v7a');
  apk(dir, 'universal');
  const result = run();
  assert.equal(result.status, 0, result.stderr);
  const latest = JSON.parse(readFileSync(join(dir, 'latest.json'), 'utf8'));
  assert.equal(latest.versionCode, 1100);
  assert.deepEqual(latest.apks.map((a) => a.abi), ['arm64-v8a', 'armeabi-v7a', 'universal']);
  assert.equal(latest.apks[0].url, 'https://github.com/tanzir71/harkara/releases/download/v0.11.0/harkara-0.11.0-arm64-v8a.apk');
});

function metadata(t) {
  const root = fixture(t);
  for (const locale of ['en-US', 'bn-BD']) {
    const target = join(root, locale);
    mkdirSync(join(target, 'images'), { recursive: true });
    mkdirSync(join(target, 'changelogs'));
    for (const file of ['title.txt', 'short_description.txt', 'full_description.txt', 'images/icon.png', 'changelogs/100.txt', 'changelogs/400.txt']) {
      copyFileSync(join('fastlane/metadata/android', locale, file), join(target, file));
    }
  }
  return root;
}

test('metadata checks Unicode character limits, not UTF-8 byte lengths', (t) => {
  const root = metadata(t);
  writeFileSync(join(root, 'bn-BD/short_description.txt'), '\u0995'.repeat(80));
  assert.deepEqual(verifyMetadata(root), []);
  writeFileSync(join(root, 'bn-BD/short_description.txt'), '\u0995'.repeat(81));
  assert.match(verifyMetadata(root).join(), /got 81/);
});

test('metadata rejects missing locale, mismatched changelogs and invalid icons', (t) => {
  const root = metadata(t);
  rmSync(join(root, 'bn-BD/changelogs/400.txt'));
  writeFileSync(join(root, 'en-US/images/icon.png'), 'not a PNG');
  assert.equal(verifyMetadata(root).length, 2);
  rmSync(join(root, 'bn-BD/title.txt'));
  assert.throws(() => verifyMetadata(root), /ENOENT/);
});
