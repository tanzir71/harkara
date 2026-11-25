// The web page for people without Harkara (site/o/). Bundled by tools/build-site.mjs with esbuild into
// site/assets/o.js together with @noble/curves (secp256k1 Schnorr) and @noble/hashes (SHA-256), both MIT.
// It reads the link fragment (never sent to a server), verifies BIP-340 signatures in the browser and talks
// only to the public relays baked into the page (the CSP enforces that list).
//   #e:<binary event>   one signed event (check-in, SOS, task step, IOU signature, …)
//   #c:<confirm link>   an IOU for someone without a key: verify, then confirm with a throwaway key
//   #t:<key + d>        follow a task: the offer and its steps from public relays
//   #g:<geohash>        read an area's Nearby feed from public relays
import { schnorr } from '@noble/curves/secp256k1';
import { sha256 } from '@noble/hashes/sha2';

const data = JSON.parse(document.getElementById('o-data').textContent);
const T = data.t;
const LANG = data.lang;
const RELAYS = data.relays;
const SITE = data.site; // https://tanzir71.github.io/harkara/
const view = document.getElementById('o-view');
const enc = new TextEncoder();

// ---------- bytes ----------
const hex = (b) => Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
const unhex = (h) => Uint8Array.from(h.match(/../g) || [], (x) => parseInt(x, 16));
function b64u(s) {
  if (!/^[A-Za-z0-9_-]*$/.test(s)) throw new Error('not base64url');
  s = s.replace(/-/g, '+').replace(/_/g, '/');
  while (s.length % 4) s += '=';
  const bin = atob(s);
  return Uint8Array.from(bin, (c) => c.charCodeAt(0));
}
const tob64u = (b) => btoa(String.fromCharCode(...b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const utf8 = (b) => new TextDecoder('utf-8', { fatal: true }).decode(b);

class Reader {
  constructor(b) {
    this.b = b;
    this.p = 0;
  }
  u8() {
    if (this.p >= this.b.length) throw new Error('truncated');
    return this.b[this.p++];
  }
  bytes(n) {
    if (n < 0 || this.p + n > this.b.length) throw new Error('truncated');
    const r = this.b.subarray(this.p, this.p + n);
    this.p += n;
    return r;
  }
  varint() {
    let v = 0;
    for (let s = 0; s < 53; s += 7) {
      const x = this.u8();
      v += (x & 0x7f) * 2 ** s;
      if (!(x & 0x80)) return v;
    }
    throw new Error('varint');
  }
  u32() {
    return this.u8() * 2 ** 24 + this.u8() * 2 ** 16 + this.u8() * 256 + this.u8();
  }
  lp() {
    return this.bytes(this.varint());
  }
  done() {
    return this.p === this.b.length;
  }
}

async function inflateRaw(bytes) {
  const stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return new Uint8Array(await new Response(stream).arrayBuffer());
}

/** Text8: ASCII, U+0980–U+09FF as one byte 0x80–0xFF, anything else 0x7F + UTF-8 (see core SmsCodec). */
function text8(b) {
  let out = '';
  for (let i = 0; i < b.length; ) {
    const x = b[i];
    if (x < 0x7f) (out += String.fromCharCode(x)), i++;
    else if (x >= 0x80) (out += String.fromCharCode(0x0980 + x - 0x80)), i++;
    else {
      const lead = b[i + 1];
      const n = lead < 0x80 ? 1 : lead >> 5 === 6 ? 2 : lead >> 4 === 14 ? 3 : lead >> 3 === 30 ? 4 : 0;
      if (!n || i + 1 + n > b.length) throw new Error('text8');
      out += utf8(b.subarray(i + 1, i + 1 + n));
      i += 1 + n;
    }
  }
  return out;
}

// ---------- events ----------
const eventId = (e) => hex(sha256(enc.encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]))));

/** NIP-01 checks: shapes, id recomputes, BIP-340 signature verifies. */
function verify(e) {
  try {
    if (!/^[0-9a-f]{64}$/.test(e.pubkey) || !/^[0-9a-f]{128}$/.test(e.sig)) return false;
    if (!Array.isArray(e.tags) || typeof e.content !== 'string' || !Number.isInteger(e.kind) || !Number.isInteger(e.created_at)) return false;
    const id = eventId(e);
    if (e.id && e.id !== id) return false;
    return schnorr.verify(e.sig, id, e.pubkey);
  } catch {
    return false;
  }
}

const tag = (e, name) => (e.tags.find((t) => t[0] === name) || [])[1];
const tagsOf = (e, name) => e.tags.filter((t) => t[0] === name).map((t) => t[1]);
const expired = (e, now) => {
  const x = Number(tag(e, 'expiration'));
  return Number.isFinite(x) && x <= now;
};
const nowS = () => Math.floor(Date.now() / 1000);

/** The binary event codec of core/wire/BinaryEventCodec (Bluetooth, QR, `#e:`). */
async function decodeBinary(bytes) {
  const r = new Reader(bytes);
  if (r.u8() !== 1) throw new Error('version');
  const flags = r.u8();
  if (flags & ~3) throw new Error('flags');
  const id = flags & 2 ? null : hex(r.bytes(32));
  const pubkey = hex(r.bytes(32));
  const sig = hex(r.bytes(64));
  const created_at = r.u32();
  const kind = r.varint();
  const tags = [];
  for (let n = r.varint(), i = 0; i < n; i++) {
    const t = [];
    for (let m = r.varint(), j = 0; j < m; j++) t.push(utf8(r.lp()));
    tags.push(t);
  }
  let content = r.lp();
  if (!r.done()) throw new Error('trailing');
  if (flags & 1) content = await inflateRaw(content);
  const e = { pubkey, created_at, kind, tags, content: utf8(content), sig };
  e.id = id || eventId(e);
  return e;
}

function encodeBinary(e) {
  const out = [1, 2];
  const varint = (v) => {
    while (v >= 0x80) out.push((v & 0x7f) | 0x80), (v = Math.floor(v / 128));
    out.push(v);
  };
  const bytes = (b) => {
    varint(b.length);
    out.push(...b);
  };
  out.push(...unhex(e.pubkey), ...unhex(e.sig));
  const t = e.created_at;
  out.push(Math.floor(t / 2 ** 24) & 255, (t >>> 16) & 255, (t >>> 8) & 255, t & 255);
  varint(e.kind);
  varint(e.tags.length);
  for (const tg of e.tags) {
    varint(tg.length);
    tg.forEach((x) => bytes(enc.encode(x)));
  }
  bytes(enc.encode(e.content));
  return Uint8Array.from(out);
}

/** The IOU confirm link (core/khata/IouConfirm): rebuilds the canonical terms JSON and the creator's signature. */
function decodeConfirm(bytes) {
  const r = new Reader(bytes);
  if (r.u8() !== 1) throw new Error('version');
  const flags = r.u8();
  if (flags & ~0x1f) throw new Error('flags');
  const code = hex(r.bytes(8));
  const sigAt = r.u32();
  const sig = hex(r.bytes(64));
  const signer = hex(r.bytes(32));
  const str = () => text8(r.lp());
  let id;
  if (flags & 2) {
    const u = hex(r.bytes(16));
    id = `${u.slice(0, 8)}-${u.slice(8, 12)}-${u.slice(12, 16)}-${u.slice(16, 20)}-${u.slice(20)}`;
  } else id = str();
  const amount = r.varint();
  const createdAt = r.u32();
  const due = flags & 4 ? r.varint() : null;
  const task = flags & 8 ? str() : null;
  const currency = flags & 0x10 ? str() : 'BDT';
  const creditorName = str();
  const debtorName = str();
  const note = str();
  if (!r.done()) throw new Error('trailing');
  const creditorSigned = !!(flags & 1);
  const codeHash = hex(sha256(enc.encode(code)));
  const q = JSON.stringify;
  const party = (name, pk) => `{"name":${q(name)},"pubkey":${pk ? q(pk) : 'null'}}`;
  // Field order and escaping of kotlinx.serialization for IouTerms (same as JSON.stringify for strings).
  const json =
    `{"id":${q(id)},"creditor":${party(creditorName, creditorSigned ? signer : null)},"debtor":${party(debtorName, creditorSigned ? null : signer)},` +
    `"amount":${amount},"currency":${q(currency)},"note":${q(note)},"createdAt":${createdAt},"due":${due == null ? 'null' : due},` +
    `"task":${task == null ? 'null' : q(task)},"confirmCodeHash":${q(codeHash)}}`;
  const hash = hex(sha256(enc.encode(json)));
  const signature = { pubkey: signer, created_at: sigAt, kind: 3474, tags: [['h', hash], ['iou', id]], content: '', sig };
  return { id, hash, code, amount, currency, note, creditorName, debtorName, creditorSigned, signature, verified: verify(signature) };
}

// ---------- relays ----------
/** REQ on every relay; resolves with the events and how many relays answered (EOSE or any event). */
function query(filters, timeout = 7000) {
  return new Promise((resolve) => {
    const events = new Map();
    let pending = RELAYS.length;
    let answered = 0;
    const sockets = [];
    const finish = () => {
      sockets.forEach((ws) => {
        try {
          ws.close();
        } catch {}
      });
      resolve({ events: [...events.values()], answered });
    };
    const timer = setTimeout(finish, timeout);
    const done = (ws, ok) => {
      if (ws.doneOnce) return;
      ws.doneOnce = true;
      if (ok) answered++;
      if (--pending === 0) {
        clearTimeout(timer);
        finish();
      }
    };
    for (const url of RELAYS) {
      let ws;
      try {
        ws = new WebSocket(url);
      } catch {
        pending--;
        continue;
      }
      sockets.push(ws);
      const sub = 'o' + Math.random().toString(36).slice(2, 8);
      ws.onopen = () => ws.send(JSON.stringify(['REQ', sub, ...filters]));
      ws.onmessage = (m) => {
        let msg;
        try {
          msg = JSON.parse(m.data);
        } catch {
          return;
        }
        if (msg[0] === 'EVENT' && msg[1] === sub && msg[2] && typeof msg[2] === 'object') {
          if (!events.has(msg[2].id)) events.set(msg[2].id, msg[2]);
        } else if (msg[0] === 'EOSE' && msg[1] === sub) {
          try {
            ws.send(JSON.stringify(['CLOSE', sub]));
          } catch {}
          done(ws, true);
        } else if (msg[0] === 'CLOSED' && msg[1] === sub) done(ws, false);
      };
      ws.onerror = () => done(ws, false);
      ws.onclose = () => done(ws, false);
    }
    if (pending <= 0) {
      clearTimeout(timer);
      finish();
    }
  });
}

/** Publishes [event]; resolves with the number of relays that answered OK true. */
function publish(event, timeout = 8000) {
  return new Promise((resolve) => {
    let ok = 0;
    let pending = RELAYS.length;
    const sockets = [];
    const finish = () => {
      sockets.forEach((ws) => {
        try {
          ws.close();
        } catch {}
      });
      resolve(ok);
    };
    const timer = setTimeout(finish, timeout);
    const done = (ws) => {
      if (ws.doneOnce) return;
      ws.doneOnce = true;
      if (--pending === 0) {
        clearTimeout(timer);
        finish();
      }
    };
    for (const url of RELAYS) {
      let ws;
      try {
        ws = new WebSocket(url);
      } catch {
        pending--;
        continue;
      }
      sockets.push(ws);
      ws.onopen = () => ws.send(JSON.stringify(['EVENT', event]));
      ws.onmessage = (m) => {
        try {
          const msg = JSON.parse(m.data);
          if (msg[0] === 'OK' && msg[1] === event.id) {
            if (msg[2] === true) ok++;
            done(ws);
          }
        } catch {}
      };
      ws.onerror = () => done(ws);
      ws.onclose = () => done(ws);
    }
    if (pending <= 0) {
      clearTimeout(timer);
      finish();
    }
  });
}

// ---------- DOM ----------
const SVG = 'http://www.w3.org/2000/svg';
function icon(name, cls = '') {
  const s = document.createElementNS(SVG, 'svg');
  s.setAttribute('class', 'i' + (cls ? ' ' + cls : ''));
  s.setAttribute('aria-hidden', 'true');
  const u = document.createElementNS(SVG, 'use');
  u.setAttribute('href', '#i-' + name);
  s.appendChild(u);
  return s;
}
function h(tagName, attrs, ...kids) {
  const el = document.createElement(tagName);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k === 'class') el.className = v;
    else if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else el.setAttribute(k, v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid instanceof Node ? kid : String(kid));
  return el;
}
const fill = (s, vars = {}) => s.replace(/\{(\w+)\}/g, (m, k) => (vars[k] != null ? String(vars[k]) : m));
const short = (k) => k.slice(0, 8) + '…' + k.slice(-4);
const taka = (n) => Number(n).toLocaleString('en-US');
const when = (t) =>
  new Date(t * 1000).toLocaleString('en-GB', { timeZone: 'Asia/Dhaka', day: 'numeric', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
const kindName = (k) => fill((T.kinds[k] || T.kinds.other), { kind: k });

function show(...nodes) {
  view.replaceChildren(...nodes);
}

function status(ok, key) {
  return h(
    'div',
    { class: 'o-status ' + (ok ? 'o-ok' : 'o-bad'), id: 'o-status', 'data-state': ok ? 'verified' : 'failed', role: 'status' },
    icon(ok ? 'verified' : 'report'),
    h('div', null, h('p', { class: 'o-st' }, ok ? T.verified : T.notVerified), h('p', { class: 'o-sd' }, ok ? fill(T.verifiedBody, { key: short(key) }) : T.notVerifiedBody)),
  );
}

function actions(frag, extra = []) {
  return h(
    'div',
    { class: 'o-actions' },
    ...extra,
    frag ? h('a', { class: 'o-btn', href: 'harkara:' + frag, id: 'o-open' }, icon('send'), T.openApp) : null,
    h('a', { class: 'o-btn o-ghost', href: data.home + '#install' }, icon('drop'), T.getApp),
  );
}

function kv(rows) {
  return h('dl', { class: 'o-kv' }, ...rows.filter(Boolean).flatMap(([k, v]) => [h('dt', null, k), h('dd', null, v)]));
}

function copyBox(text) {
  const btn = h('button', { type: 'button', class: 'o-btn o-ghost o-small' }, T.copy);
  btn.addEventListener('click', () => {
    navigator.clipboard?.writeText(text).then(() => (btn.textContent = T.copied));
  });
  return h('div', { class: 'o-copy' }, h('code', { class: 'o-code', id: 'o-copy-text' }, text), navigator.clipboard ? btn : null);
}

// ---------- views ----------
async function showEvent(frag, payload) {
  let e;
  try {
    e = await decodeBinary(b64u(payload));
  } catch {
    return show(h('p', { class: 'o-msg', id: 'o-broken' }, T.broken));
  }
  const ok = verify(e);
  const body = [];
  if (e.kind === 3475) {
    try {
      const s = JSON.parse(e.content);
      body.push(h('p', { class: 'o-text' }, fill(T.settlement, { method: s.method, amount: taka(s.amount), trx: s.trxId || '—' })));
    } catch {}
  } else if (e.kind === 3471) {
    const step = tag(e, 'step');
    body.push(h('p', { class: 'o-text' }, T.steps[step] ? T.steps[step] : step || ''));
  } else if (e.kind === 3478) {
    const st = tag(e, 'status');
    if (T.status[st]) body.push(h('p', { class: 'o-text' }, T.status[st]));
    if (e.content) body.push(h('p', { class: 'o-text' }, e.content));
  } else if (e.kind !== 3480 && e.kind !== 3474 && e.content) {
    body.push(h('p', { class: 'o-text', id: 'o-content' }, e.content));
  }
  const place = tag(e, 'place');
  const g = tagsOf(e, 'g').sort((a, b) => b.length - a.length)[0];
  const card = h(
    'article',
    { class: 'o-card' + (e.kind === 3477 ? ' o-sos' : '') },
    h('p', { class: 'o-kind' }, icon(e.kind === 3477 ? 'sos' : e.kind === 3476 ? 'check' : 'lock'), kindName(e.kind)),
    ...body,
    kv([[T.when, when(e.created_at)], place && [T.where, place], g && [T.area, g]]),
  );
  const extra = e.kind === 3477 && ok ? [h('a', { class: 'o-btn o-sos', href: 'tel:999' }, icon('sos'), T.call999)] : [];
  show(status(ok, e.pubkey), card, actions(frag, extra));
}

async function showConfirm(frag, payload) {
  let c;
  try {
    c = decodeConfirm(b64u(payload));
  } catch {
    return show(h('p', { class: 'o-msg', id: 'o-broken' }, T.broken));
  }
  const keyed = c.creditorSigned ? c.creditorName : c.debtorName;
  const line = fill(c.creditorSigned ? T.iouOwe : T.iouOwed, { name: keyed, amount: taka(c.amount) });
  const card = h(
    'article',
    { class: 'o-card' },
    h('p', { class: 'o-kind' }, icon('khata'), 'Khata · IOU'),
    h('p', { class: 'o-amount', id: 'o-amount' }, '৳' + taka(c.amount)),
    h('p', { class: 'o-text' }, line),
    c.note ? h('p', { class: 'o-text muted' }, fill(T.iouFor, { note: c.note })) : null,
    kv([[T.when, when(c.signature.created_at)]]),
  );
  const result = h('div', { id: 'o-result', 'aria-live': 'polite' });
  const extra = [];
  if (c.verified) {
    const btn = h('button', { type: 'button', class: 'o-btn', id: 'o-confirm' }, icon('check'), T.iouConfirm);
    btn.addEventListener('click', async () => {
      btn.disabled = true;
      btn.textContent = T.iouSending;
      const secret = schnorr.utils.randomSecretKey();
      const e = { pubkey: hex(schnorr.getPublicKey(secret)), created_at: nowS(), kind: 3474, tags: [['h', c.hash], ['iou', c.id], ['code', c.code]], content: '' };
      e.id = eventId(e);
      e.sig = hex(schnorr.sign(e.id, secret));
      const n = await publish(e);
      btn.remove();
      const back = SITE + 'o/#e:' + tob64u(encodeBinary(e));
      result.replaceChildren(
        h('p', { class: 'o-done', id: 'o-confirmed', 'data-relays': String(n) }, icon('check'), fill(T.iouConfirmed, { name: keyed })),
        h('p', { class: 'o-text muted' }, n > 0 ? fill(T.iouSentTo, { n }) : fill(T.iouNoRelay, { name: keyed })),
        h('p', { class: 'o-text muted' }, fill(T.iouCopyBack, { name: keyed })),
        copyBox(back),
      );
    });
    extra.push(btn);
  }
  const note = c.verified ? h('p', { class: 'o-text muted' }, T.iouConfirmNote) : null;
  show(status(c.verified, c.signature.pubkey), card, note, result, actions(frag, extra));
}

async function showTask(frag, payload) {
  let pubkey, d;
  try {
    const b = b64u(payload);
    if (b.length < 33) throw new Error('short');
    pubkey = hex(b.subarray(0, 32));
    d = utf8(b.subarray(32));
  } catch {
    return show(h('p', { class: 'o-msg', id: 'o-broken' }, T.broken));
  }
  const address = `30402:${pubkey}:${d}`;
  show(h('p', { class: 'o-msg', id: 'o-looking' }, T.looking));
  const { events, answered } = await query([
    { kinds: [30402], authors: [pubkey], '#d': [d], limit: 5 },
    { kinds: [3471], '#a': [address], limit: 200 },
  ]);
  const valid = events.filter(verify);
  const offer = valid.filter((e) => e.kind === 30402 && e.pubkey === pubkey && tag(e, 'd') === d).sort((a, b) => b.created_at - a.created_at)[0];
  const steps = valid.filter((e) => e.kind === 3471 && tag(e, 'a') === address).sort((a, b) => a.created_at - b.created_at);
  const foot = h('p', { class: 'o-text muted' }, fill(T.answered, { n: answered }));
  if (!offer) return show(h('p', { class: 'o-msg', id: 'o-notfound' }, T.taskNotFound), foot, actions(frag));
  const price = (offer.tags.find((t) => t[0] === 'price') || [])[1];
  const card = h(
    'article',
    { class: 'o-card' },
    h('p', { class: 'o-kind' }, icon('tasks'), kindName(30402)),
    h('h2', { class: 'o-title', id: 'o-task-title' }, tag(offer, 'title') || ''),
    offer.content ? h('p', { class: 'o-text' }, offer.content) : null,
    price ? h('p', { class: 'o-text' }, fill(T.taskPrice, { price: taka(price) })) : null,
    kv([[T.when, when(offer.created_at)]]),
  );
  const list = steps.length
    ? h(
        'ol',
        { class: 'o-steps', id: 'o-steps' },
        ...steps.map((s) =>
          h(
            'li',
            null,
            icon('verified'),
            h('span', { class: 'o-sn' }, T.steps[tag(s, 'step')] || tag(s, 'step')),
            h('span', { class: 'o-sm' }, `${s.pubkey === pubkey ? T.byRequester : T.byHelper} · ${when(s.created_at)}`),
          ),
        ),
      )
    : h('p', { class: 'o-text muted' }, T.taskNoSteps);
  show(status(true, offer.pubkey), card, h('h2', { class: 'o-h' }, T.taskSteps), list, foot, actions(frag));
}

async function showArea(frag, geohash) {
  if (!/^[0-9b-hjkmnp-z]{4,9}$/.test(geohash)) return show(h('p', { class: 'o-msg', id: 'o-broken' }, T.broken));
  show(h('p', { class: 'o-msg', id: 'o-looking' }, T.looking));
  const now = nowS();
  const { events, answered } = await query([{ kinds: [1], '#g': [geohash], since: now - 2 * 86400, limit: 100 }]);
  const posts = events
    .filter((e) => e.kind === 1 && tagsOf(e, 'g').includes(geohash) && !expired(e, now) && verify(e))
    .sort((a, b) => b.created_at - a.created_at)
    .slice(0, 50);
  const title = h('h2', { class: 'o-h' }, fill(T.feedTitle, { area: geohash }));
  const foot = [h('p', { class: 'o-text muted' }, T.feedNote), h('p', { class: 'o-text muted' }, fill(T.answered, { n: answered }))];
  if (!posts.length) return show(title, h('p', { class: 'o-msg', id: 'o-empty-feed' }, T.feedEmpty), ...foot, actions(frag));
  const list = h(
    'ul',
    { class: 'o-feed', id: 'o-feed' },
    ...posts.map((p) => {
      const cat = tag(p, 't');
      return h(
        'li',
        { class: 'o-card o-post', 'data-id': p.id },
        h('p', { class: 'o-kind' }, h('span', { class: 'dot d-' + (T.categories[cat] ? cat : 'chatter') }), T.categories[cat] ? T.categories[cat] : T.categories.chatter, h('span', { class: 'muted' }, ' · ' + when(p.created_at))),
        h('p', { class: 'o-text' }, p.content),
      );
    }),
  );
  show(title, list, ...foot, actions(frag));
}

// ---------- start ----------
function main() {
  // Keep the payload when switching language, and remember the choice like the landing pages do.
  document.querySelectorAll('[data-lang]').forEach((a) => {
    a.href = a.getAttribute('href').split('#')[0] + location.hash;
    a.addEventListener('click', () => {
      try {
        localStorage.setItem('harkara.lang', a.dataset.lang);
      } catch {}
    });
  });
  const raw = location.hash.slice(1);
  let frag = raw;
  try {
    frag = decodeURIComponent(raw);
  } catch {}
  const i = frag.indexOf(':');
  const type = i > 0 ? frag.slice(0, i) : '';
  const value = i > 0 ? frag.slice(i + 1) : '';
  view.setAttribute('data-type', type || 'none');
  if (type === 'e') return showEvent(frag, value);
  if (type === 'c') return showConfirm(frag, value);
  if (type === 't') return showTask(frag, value);
  if (type === 'g') return showArea(frag, value);
  show(h('p', { class: 'o-msg', id: 'o-empty' }, frag ? T.broken : T.empty), actions(frag || ''));
}

window.addEventListener('hashchange', main);
main();
