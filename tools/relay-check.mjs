#!/usr/bin/env node
// Picks the default relay list: the three required relays plus the first four candidates that pass
// a NIP-11 fetch and a write-then-read round trip. Writes data/default-relays.json when it changes.
// Touches the public internet; run by the scheduled relay-check.yml, never by tests.
// Usage: node tools/relay-check.mjs [--dry-run]
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, randomBytes } from 'node:crypto';
import { schnorr } from '@noble/curves/secp256k1';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const outFile = join(root, 'data/default-relays.json');
const dryRun = process.argv.includes('--dry-run');

export const REQUIRED = ['wss://relay.damus.io', 'wss://relay.primal.net', 'wss://relay.nostr.net'];
export const CANDIDATES = [
  'wss://nos.lol',
  'wss://relay.snort.social',
  'wss://nostr.mom',
  'wss://offchain.pub',
  'wss://relay.nostr.bg',
  'wss://nostr-pub.wellorder.net',
  'wss://nostr.oxtr.dev',
  'wss://purplerelay.com',
  'wss://relay.nostr.band',
  'wss://nostr.bitcoiner.social',
  'wss://relay.mostr.pub',
  'wss://nostr.land',
];
const EXTRA = 4;
const TIMEOUT_MS = 8000;

const hex = (b) => Buffer.from(b).toString('hex');

function signEvent(sk, kind, tags, content) {
  const pubkey = hex(schnorr.getPublicKey(sk));
  const created_at = Math.floor(Date.now() / 1000);
  const id = createHash('sha256').update(JSON.stringify([0, pubkey, created_at, kind, tags, content])).digest('hex');
  const sig = hex(schnorr.sign(id, sk));
  return { id, pubkey, created_at, kind, tags, content, sig };
}

async function nip11(url) {
  const http = url.replace(/^wss:/, 'https:').replace(/^ws:/, 'http:');
  const res = await fetch(http, { headers: { Accept: 'application/nostr+json' }, signal: AbortSignal.timeout(TIMEOUT_MS) });
  if (!res.ok) throw new Error(`NIP-11 HTTP ${res.status}`);
  const info = await res.json();
  if (info?.limitation?.payment_required || info?.limitation?.auth_required) throw new Error('payment or auth required');
  return info;
}

function roundTrip(url) {
  return new Promise((resolvePromise, reject) => {
    const sk = randomBytes(32);
    const expiration = String(Math.floor(Date.now() / 1000) + 600);
    const event = signEvent(sk, 1, [['t', 'harkara-relay-check'], ['expiration', expiration]], 'harkara relay check');
    const ws = new WebSocket(url);
    const timer = setTimeout(() => {
      ws.close();
      reject(new Error('timeout'));
    }, TIMEOUT_MS);
    let gotEvent = false;
    ws.onerror = () => {
      clearTimeout(timer);
      reject(new Error('websocket error'));
    };
    ws.onopen = () => ws.send(JSON.stringify(['EVENT', event]));
    ws.onmessage = (msg) => {
      let data;
      try {
        data = JSON.parse(msg.data);
      } catch {
        return;
      }
      if (data[0] === 'OK' && data[1] === event.id) {
        if (!data[2]) {
          clearTimeout(timer);
          ws.close();
          reject(new Error(`rejected: ${data[3]}`));
          return;
        }
        ws.send(JSON.stringify(['REQ', 'check', { ids: [event.id] }]));
      } else if (data[0] === 'EVENT' && data[1] === 'check' && data[2]?.id === event.id) {
        gotEvent = true;
      } else if (data[0] === 'EOSE' && data[1] === 'check') {
        clearTimeout(timer);
        ws.close();
        if (gotEvent) resolvePromise(true);
        else reject(new Error('event not readable after write'));
      }
    };
  });
}

async function check(url) {
  const started = Date.now();
  try {
    const info = await nip11(url);
    await roundTrip(url);
    return { url, ok: true, ms: Date.now() - started, name: info?.name ?? '', maxMessageLength: info?.limitation?.max_message_length ?? null };
  } catch (e) {
    return { url, ok: false, error: String(e.message ?? e) };
  }
}

const results = await Promise.all([...REQUIRED, ...CANDIDATES].map(check));
for (const r of results) console.log(`${r.ok ? 'ok  ' : 'FAIL'} ${r.url}${r.ok ? ` (${r.ms} ms)` : ` — ${r.error}`}`);

const previous = existsSync(outFile) ? JSON.parse(readFileSync(outFile, 'utf8')) : null;
const passing = results.filter((r) => r.ok && CANDIDATES.includes(r.url)).sort((a, b) => a.ms - b.ms);
// Keep previously chosen relays that still pass, so the list doesn't churn week to week.
const keep = (previous?.relays ?? []).filter((u) => CANDIDATES.includes(u) && passing.some((p) => p.url === u));
const extra = [...keep, ...passing.map((p) => p.url).filter((u) => !keep.includes(u))].slice(0, EXTRA);
const relays = [...REQUIRED, ...extra];

if (previous && JSON.stringify(previous.relays) === JSON.stringify(relays)) {
  console.log('default relay list unchanged');
} else if (extra.length === 0) {
  console.log('no candidate passed; keeping the existing list');
} else if (dryRun) {
  console.log('would write', relays);
} else {
  const status = Object.fromEntries(results.map((r) => [r.url, r.ok ? 'ok' : r.error]));
  writeFileSync(outFile, JSON.stringify({ updated: new Date().toISOString().slice(0, 10), required: REQUIRED, relays, lastCheck: status }, null, 2) + '\n');
  console.log(`wrote ${outFile}`);
}
