import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const check = process.argv.includes('--check');
const screenshots = {
  'en-US': ['screen-01-nearby-feed-day', 'screen-02-nearby-map-day', 'screen-04b-chats-list-day', 'screen-06-khata-day'],
  'bn-BD': ['screen-07-me-tools-bn-day', 'screen-07-me-tools-bn-night'],
};
let stale = false;
for (const [locale, names] of Object.entries(screenshots)) {
  for (const [index, name] of names.entries()) {
    const source = readFileSync(resolve(root, `app/src/test/screenshots/${name}.png`));
    const target = resolve(root, `fastlane/metadata/android/${locale}/images/phoneScreenshots/${index + 1}.png`);
    if (check) {
      let existing;
      try { existing = readFileSync(target); } catch { /* Report missing generated images as stale. */ }
      if (!existing?.equals(source)) { console.error(`Stale store screenshot: ${target}`); stale = true; }
    } else {
      mkdirSync(dirname(target), { recursive: true });
      writeFileSync(target, source);
    }
  }
}
if (stale) process.exitCode = 1;
else console.log(`Store screenshots ${check ? 'match' : 'exported from'} verified app goldens.`);
