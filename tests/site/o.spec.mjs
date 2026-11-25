// The web page for non-users (site/o/): payloads signed by core (tools/fixtures/o-payloads.json, written by
// core's WebPageFixturesTest) are verified in the browser. Relays are mocked with routeWebSocket and every
// other request is blocked, so nothing here touches the network.
import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { schnorr } from '@noble/curves/secp256k1';
import { sha256 } from '@noble/hashes/sha2';
import { bytesToHex } from '@noble/hashes/utils';

const fx = JSON.parse(readFileSync('tools/fixtures/o-payloads.json', 'utf8'));
const relays = JSON.parse(readFileSync('data/default-relays.json', 'utf8')).relays;
const LOCAL = /^http:\/\/127\.0\.0\.1:\d+\//;

/** Blocks every non-local request and answers every relay WebSocket from [events]; records what gets published. */
async function offline(context, page, events = []) {
  const seen = { external: [], sockets: [], published: [], reqs: [], console: [] };
  await context.route(
    (url) => !LOCAL.test(url.href),
    (route) => {
      seen.external.push(route.request().url());
      return route.abort('blockedbyclient');
    },
  );
  await context.routeWebSocket(/.*/, (ws) => {
    seen.sockets.push(ws.url());
    ws.onMessage((message) => {
      const m = JSON.parse(String(message));
      if (m[0] === 'REQ') {
        seen.reqs.push(m.slice(2));
        for (const e of events) ws.send(JSON.stringify(['EVENT', m[1], e]));
        ws.send(JSON.stringify(['EOSE', m[1]]));
      } else if (m[0] === 'EVENT') {
        seen.published.push(m[1]);
        ws.send(JSON.stringify(['OK', m[1].id, true, '']));
      }
    });
  });
  page.on('console', (msg) => {
    if (msg.type() === 'error') seen.console.push(msg.text());
  });
  // Fixture events were signed at fx.now; read them an hour later.
  await page.clock.setFixedTime(new Date((fx.now + 3600) * 1000));
  return seen;
}

const idOf = (e) => bytesToHex(sha256(new TextEncoder().encode(JSON.stringify([0, e.pubkey, e.created_at, e.kind, e.tags, e.content]))));

test.describe('o/ page', () => {
  test.use({ viewport: { width: 390, height: 800 }, locale: 'en-US', timezoneId: 'Asia/Dhaka' });

  test('verifies an SOS signed by core', async ({ page, context }) => {
    const seen = await offline(context, page);
    await page.goto('en/o/#' + fx.event.fragment);
    const status = page.locator('#o-status');
    await expect(status).toHaveAttribute('data-state', 'verified');
    await expect(status).toContainText('Verified');
    await expect(page.locator('#o-content')).toHaveText(fx.event.content);
    await expect(page.locator('a[href="tel:999"]')).toBeVisible();
    await expect(page.locator('#o-open')).toHaveAttribute('href', 'harkara:' + fx.event.fragment);
    expect(await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)).toBeLessThanOrEqual(0);
    await page.screenshot({ path: 'artifacts/site-screenshots/o-en-event.png', fullPage: true });
    expect(seen.external).toEqual([]);
    expect(seen.sockets).toEqual([]);
    expect(seen.console.filter((t) => /Content Security Policy/i.test(t))).toEqual([]);
  });

  test('a tampered event is not verified', async ({ page, context }) => {
    await offline(context, page);
    await page.goto('en/o/#' + fx.eventTampered.fragment);
    const status = page.locator('#o-status');
    await expect(status).toHaveAttribute('data-state', 'failed');
    await expect(status).toContainText('Not verified');
    await expect(page.locator('a[href="tel:999"]')).toHaveCount(0);
  });

  test('an IOU confirm link is verified and confirmed with a throwaway key', async ({ page, context }) => {
    const seen = await offline(context, page);
    await page.goto('en/o/#' + fx.confirm.fragment);
    await expect(page.locator('#o-status')).toHaveAttribute('data-state', 'verified');
    await expect(page.locator('#o-amount')).toHaveText('৳1,250');
    await expect(page.locator('#o-view')).toContainText(`${fx.confirm.creditor} says you owe them ৳1,250`);
    await expect(page.locator('#o-view')).toContainText(fx.confirm.note);
    await page.locator('#o-confirm').click();
    await expect(page.locator('#o-confirmed')).toBeVisible();
    await expect(page.locator('#o-confirmed')).toHaveAttribute('data-relays', String(relays.length));
    await page.screenshot({ path: 'artifacts/site-screenshots/o-en-iou-confirmed.png', fullPage: true });

    expect(seen.published.length).toBe(relays.length);
    const e = seen.published[0];
    expect(seen.published.every((p) => p.id === e.id)).toBe(true);
    expect(e.kind).toBe(3474);
    expect(e.tags).toEqual([
      ['h', fx.confirm.hash],
      ['iou', fx.confirm.id],
      ['code', fx.confirm.code],
    ]);
    expect(e.id).toBe(idOf(e));
    expect(schnorr.verify(e.sig, e.id, e.pubkey)).toBe(true);
    expect(e.created_at).toBe(fx.now + 3600);
    // The confirmation can also travel back by SMS as an o/ link.
    await expect(page.locator('#o-copy-text')).toContainText('https://tanzir71.github.io/harkara/o/#e:');
    expect(seen.external).toEqual([]);
  });

  test('a tampered IOU link cannot be confirmed', async ({ page, context }) => {
    const seen = await offline(context, page);
    await page.goto('en/o/#' + fx.confirmTampered.fragment);
    await expect(page.locator('#o-status')).toHaveAttribute('data-state', 'failed');
    await expect(page.locator('#o-amount')).toHaveText('৳12,500');
    await expect(page.locator('#o-confirm')).toHaveCount(0);
    expect(seen.published).toEqual([]);
  });

  test('follows a task: the offer and its signed steps from relays, forgeries dropped', async ({ page, context }) => {
    const t = fx.task;
    const seen = await offline(context, page, [t.offer, ...t.steps, t.forged]);
    await page.goto('en/o/#' + t.fragment);
    await expect(page.locator('#o-task-title')).toHaveText(t.title);
    await expect(page.locator('#o-steps li')).toHaveCount(t.steps.length);
    await expect(page.locator('#o-steps')).toContainText('Offered to help');
    await expect(page.locator('#o-steps')).toContainText('Picked up');
    await expect(page.locator('#o-steps')).not.toContainText('Delivered');
    expect(seen.sockets.map((u) => u.replace(/\/$/, '')).sort()).toEqual([...relays].sort());
    expect(seen.reqs[0]).toEqual([
      { kinds: [30402], authors: [t.offer.pubkey], '#d': [t.offer.tags.find((x) => x[0] === 'd')[1]], limit: 5 },
      { kinds: [3471], '#a': [t.address], limit: 200 },
    ]);
    expect(seen.external).toEqual([]);
  });

  test('reads the area feed: signed, unexpired posts only', async ({ page, context }) => {
    const a = fx.area;
    const seen = await offline(context, page, a.events);
    await page.goto('en/o/#' + a.fragment);
    const items = page.locator('#o-feed > li');
    await expect(items).toHaveCount(a.visible.length);
    expect(await items.evaluateAll((els) => els.map((el) => el.dataset.id))).toEqual(a.visible.slice().sort((x, y) => {
      const ex = a.events.find((e) => e.id === x);
      const ey = a.events.find((e) => e.id === y);
      return ey.created_at - ex.created_at;
    }));
    const geohash = a.fragment.slice(2);
    expect(seen.reqs[0]).toEqual([{ kinds: [1], '#g': [geohash], since: fx.now + 3600 - 2 * 86400, limit: 100 }]);
    await page.screenshot({ path: 'artifacts/site-screenshots/o-en-feed.png', fullPage: true });
  });

  test('Banglish page verifies too, and the language toggle keeps the payload', async ({ page, context }) => {
    await offline(context, page);
    await page.goto('o/#' + fx.event.fragment);
    await expect(page.locator('html')).toHaveAttribute('lang', 'bn-Latn');
    await expect(page.locator('#o-status')).toHaveAttribute('data-state', 'verified');
    await page.screenshot({ path: 'artifacts/site-screenshots/o-bn-event.png', fullPage: true });
    await page.locator('.nav .seg a[data-lang="en"]').click();
    await page.waitForURL((u) => u.pathname === '/harkara/en/o/' && u.hash === '#' + fx.event.fragment);
    await expect(page.locator('#o-status')).toContainText('Verified');
  });

  test('a link without a payload explains itself', async ({ page, context }) => {
    await offline(context, page);
    await page.goto('en/o/');
    await expect(page.locator('#o-empty')).toBeVisible();
    await page.goto('en/o/#e:not-a-payload');
    await expect(page.locator('#o-broken')).toBeVisible();
  });
});
