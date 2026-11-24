#!/usr/bin/env node
// Extracts rules and data (not code) from the owner's loka repo into data/*.json.
// Usage: node tools/seed-from-loka.mjs [path-to-loka]
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const loka = resolve(process.argv[2] ?? join(root, '..', 'loka'));
const out = join(root, 'data');
mkdirSync(out, { recursive: true });

// Dhaka areas
const areasTs = readFileSync(join(loka, 'lib/dhakaAreas.ts'), 'utf8');
const areas = [...areasTs.matchAll(/\{\s*name:\s*"([^"]+)",\s*lat:\s*([\d.]+),\s*lng:\s*([\d.]+)\s*\}/g)].map((m) => ({
  name: m[1],
  lat: Number(m[2]),
  lng: Number(m[3]),
}));
const center = areasTs.match(/DHAKA_FALLBACK_CENTER\s*=\s*\{\s*lat:\s*([\d.]+),\s*lng:\s*([\d.]+)/);
writeFileSync(
  join(out, 'dhaka-areas.json'),
  JSON.stringify({ source: 'loka/lib/dhakaAreas.ts', fallbackCenter: { lat: Number(center[1]), lng: Number(center[2]) }, areas }, null, 1) + '\n',
);

// Categories and composer chips
const catTs = readFileSync(join(loka, 'lib/categories.ts'), 'utf8');
const idMap = { busy: 'crowd', general: 'chatter' };
const categories = [...catTs.matchAll(/id:\s*"(\w+)",\s*label:\s*"([^"]+)",\s*labelBn:\s*"([^"]+)",[\s\S]*?expiryHours:\s*(null|\d+),\s*placeholder:\s*"([^"]+)"/g)].map(
  (m) => ({
    id: idMap[m[1]] ?? m[1],
    lokaId: m[1],
    label: m[2],
    labelBn: m[3],
    expiryHours: m[4] === 'null' ? null : Number(m[4]),
    placeholder: m[5],
  }),
);
const chips = (name) => {
  const block = catTs.match(new RegExp(`export const ${name}[^=]*=\\s*\\[([\\s\\S]*?)\\];`))[1];
  return [...block.matchAll(/value:\s*"?([\w]+)"?,\s*label:\s*"([^"]+)"/g)].map((m) => ({
    value: /^\d+$/.test(m[1]) ? Number(m[1]) : m[1],
    // Emoji are dropped: the app uses token colours and icons instead.
    label: m[2].replace(/^[^\p{L}\p{N}৳]+/u, '').trim(),
  }));
};
writeFileSync(
  join(out, 'categories.json'),
  JSON.stringify(
    {
      source: 'loka/lib/categories.ts',
      categories,
      chips: {
        traffic: chips('TRAFFIC_CHIPS'),
        crowd: chips('BUSY_CHIPS'),
        stockItem: chips('STOCK_ITEM_CHIPS'),
        stockStatus: chips('STOCK_STATUS_CHIPS'),
        alert: chips('ALERT_CHIPS'),
        price: chips('PRICE_CHIPS'),
      },
    },
    null,
    1,
  ) + '\n',
);
console.log(`wrote ${areas.length} areas and ${categories.length} categories to ${out}`);
