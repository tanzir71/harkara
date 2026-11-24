// Landing site checks in headless Chromium (handover §7.2, §7.4). State-agnostic: they pass for the
// coming-soon build (--offline) and for a build with a release (--release-file or the real API).
import { test, expect } from '@playwright/test';
import { existsSync, readFileSync } from 'node:fs';

const SHOTS = 'artifacts/site-screenshots';
const STATE = existsSync('site/latest.json') ? 'release' : 'soon';
const copy = JSON.parse(readFileSync('design/copy/landing.json', 'utf8'));
const LOCAL = /^http:\/\/127\.0\.0\.1:\d+\//;

/**
 * Keeps every test offline: only the local server answers. api.country.is answers `country`
 * ('BD', 'US', …), never answers ('timeout'), or fails ('abort').
 */
async function isolate(context, country = 'BD') {
  const calls = { country: 0, external: [] };
  await context.route(
    (url) => !LOCAL.test(url.href),
    async (route) => {
      const url = route.request().url();
      if (url.startsWith('https://api.country.is')) {
        calls.country++;
        if (country === 'timeout') return; // left hanging: the page's 1.5 s AbortController has to fire
        if (country === 'abort') return route.abort('internetdisconnected');
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          headers: { 'access-control-allow-origin': '*' },
          body: JSON.stringify({ ip: '203.0.113.7', country }),
        });
      }
      calls.external.push(url);
      return route.abort('blockedbyclient');
    },
  );
  return calls;
}

const path = (page) => new URL(page.url()).pathname;
const overflow = (page) => page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);

const LANGS = [
  { lang: 'bn', url: './', pathname: '/harkara/', htmlLang: 'bn-Latn', locale: 'bn-BD' },
  { lang: 'en', url: 'en/', pathname: '/harkara/en/', htmlLang: 'en', locale: 'en-US' },
];
const VIEWPORTS = [
  { width: 390, height: 800 },
  { width: 1440, height: 900 },
];

for (const vp of VIEWPORTS) {
  for (const L of LANGS) {
    test.describe(`${L.lang} at ${vp.width}x${vp.height}`, () => {
      test.use({ viewport: vp, locale: L.locale, timezoneId: 'Asia/Dhaka' });

      test('renders without horizontal overflow', async ({ page, context }) => {
        const calls = await isolate(context, 'BD');
        await page.goto(L.url);
        await expect(page.locator('html')).toHaveAttribute('lang', L.htmlLang);
        await expect(page.locator('h1')).toHaveCount(1);
        await page.evaluate(() => document.fonts.ready);
        expect(path(page)).toBe(L.pathname);
        expect(await overflow(page)).toBeLessThanOrEqual(0);
        for (const id of ['offgrid', 'workflows', 'campus', 'install', 'faq']) await expect(page.locator(`#${id}`)).toBeAttached();
        await page.screenshot({ path: `${SHOTS}/${STATE}-${L.lang}-${vp.width}.png`, fullPage: true });
        expect(calls.external).toEqual([]);
      });

      if (vp.width === 390) {
        test('CTA is visible above the fold', async ({ page, context }) => {
          await isolate(context, 'BD');
          await page.goto(L.url);
          const cta = page.locator('.hero .cta');
          await expect(cta).toBeVisible();
          await expect(cta).toBeInViewport({ ratio: 1 });
          const box = await cta.boundingBox();
          expect(box.y + box.height).toBeLessThanOrEqual(vp.height);
          await expect(cta).toContainText(STATE === 'release' ? copy.hero.cta[L.lang] : copy.hero.ctaComingSoon[L.lang]);
        });
      }

      test('FAQ toggles, first one open', async ({ page, context }) => {
        await isolate(context, 'BD');
        await page.goto(L.url);
        const items = page.locator('#faq details');
        await expect(items).toHaveCount(copy.faq.items.length);
        await expect(items.nth(0)).toHaveAttribute('open', '');
        await expect(items.nth(0).locator('p')).toBeVisible();
        await expect(items.nth(1)).not.toHaveAttribute('open');
        await expect(items.nth(1).locator('p')).toBeHidden();
        await items.nth(1).locator('summary').click();
        await expect(items.nth(1)).toHaveAttribute('open', '');
        await expect(items.nth(1).locator('p')).toHaveText(copy.faq.items[1].a[L.lang]);
        await items.nth(0).locator('summary').click();
        await expect(items.nth(0)).not.toHaveAttribute('open');
        await expect(items.nth(0).locator('p')).toBeHidden();
      });
    });
  }
}

test.describe('narrow budget phone (360 px)', () => {
  test.use({ viewport: { width: 360, height: 740 } });
  for (const L of LANGS)
    test(`${L.lang}: no horizontal overflow`, async ({ page, context }) => {
      await isolate(context, 'BD');
      await page.goto(L.url);
      await page.evaluate(() => document.fonts.ready);
      expect(await overflow(page)).toBeLessThanOrEqual(0);
    });
});

test.describe('language choice on /', () => {
  test.use({ locale: 'en-US', timezoneId: 'America/New_York' });

  test('a visitor in Bangladesh stays on Banglish', async ({ page, context }) => {
    const calls = await isolate(context, 'BD');
    await page.goto('./');
    await page.waitForFunction(() => sessionStorage.getItem('harkara.country') === 'BD');
    await page.waitForTimeout(300);
    expect(path(page)).toBe('/harkara/');
    expect(calls.country).toBe(1);
    await page.goto('./'); // cached for the visit: no second lookup
    await page.waitForTimeout(300);
    expect(path(page)).toBe('/harkara/');
    expect(calls.country).toBe(1);
  });

  test('a visitor outside Bangladesh goes to /en/, keeping query and hash', async ({ page, context }) => {
    await isolate(context, 'US');
    await page.goto('./?ref=hall#install');
    await page.waitForURL('**/harkara/en/?ref=hall#install');
    await expect(page.locator('html')).toHaveAttribute('lang', 'en');
  });

  test('?lang=en saves the choice and redirects, with no lookup', async ({ page, context }) => {
    const calls = await isolate(context, 'BD');
    await page.goto('./?lang=en');
    await page.waitForURL((u) => u.pathname === '/harkara/en/' && u.search === '');
    expect(await page.evaluate(() => localStorage.getItem('harkara.lang'))).toBe('en');
    await page.goto('./');
    await page.waitForURL('**/harkara/en/');
    expect(calls.country).toBe(0);
  });

  test('?lang=bn saves the choice and stays, even outside Bangladesh', async ({ page, context }) => {
    const calls = await isolate(context, 'US');
    await page.goto('./?lang=bn');
    await page.waitForTimeout(800);
    expect(page.url()).toMatch(/\/harkara\/$/);
    expect(await page.evaluate(() => localStorage.getItem('harkara.lang'))).toBe('bn');
    expect(calls.country).toBe(0);
  });

  test('crawlers are never redirected', async ({ browser }) => {
    const context = await browser.newContext({ userAgent: 'Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)' });
    const calls = await isolate(context, 'US');
    const page = await context.newPage();
    await page.goto('./');
    await page.waitForTimeout(800);
    expect(path(page)).toBe('/harkara/');
    expect(calls.country).toBe(0);
    await context.close();
  });

  test.describe('lookup times out, browser outside Dhaka', () => {
    test.use({ locale: 'en-US', timezoneId: 'America/New_York' });
    test('falls back to English after 1.5 s', async ({ page, context }) => {
      const calls = await isolate(context, 'timeout');
      const start = Date.now();
      await page.goto('./');
      await page.waitForURL('**/harkara/en/', { timeout: 6000 });
      expect(Date.now() - start).toBeGreaterThanOrEqual(1400);
      expect(calls.country).toBe(1);
    });
  });

  test.describe('lookup times out, Dhaka timezone', () => {
    test.use({ locale: 'en-US', timezoneId: 'Asia/Dhaka' });
    test('stays on Banglish', async ({ page, context }) => {
      await isolate(context, 'timeout');
      await page.goto('./');
      await page.waitForTimeout(2500);
      expect(path(page)).toBe('/harkara/');
    });
  });

  test.describe('lookup fails, Bangla browser abroad', () => {
    test.use({ locale: 'bn-BD', timezoneId: 'Europe/London' });
    test('stays on Banglish', async ({ page, context }) => {
      const calls = await isolate(context, 'abort');
      await page.goto('./');
      await page.waitForTimeout(1000);
      expect(path(page)).toBe('/harkara/');
      expect(calls.country).toBe(1);
    });
  });

  test.describe('lookup fails, English browser abroad', () => {
    test.use({ locale: 'en-GB', timezoneId: 'Europe/London' });
    test('goes to English', async ({ page, context }) => {
      await isolate(context, 'abort');
      await page.goto('./');
      await page.waitForURL('**/harkara/en/', { timeout: 4000 });
    });
  });
});

test.describe('/en/', () => {
  test.use({ locale: 'bn-BD', timezoneId: 'Asia/Dhaka' });

  test('never auto-redirects to Banglish', async ({ page, context }) => {
    const calls = await isolate(context, 'BD');
    await page.goto('en/');
    await page.waitForTimeout(2000);
    expect(path(page)).toBe('/harkara/en/');
    expect(calls.country).toBe(0);
  });

  test('follows a saved Banglish choice', async ({ page, context }) => {
    await isolate(context, 'US');
    await context.addInitScript(() => localStorage.setItem('harkara.lang', 'bn'));
    await page.goto('en/');
    await page.waitForURL((u) => u.pathname === '/harkara/');
    await page.waitForTimeout(500);
    expect(path(page)).toBe('/harkara/');
  });

  test('the toggle saves the choice and navigates', async ({ page, context }) => {
    await isolate(context, 'US');
    await page.goto('en/');
    await page.locator('.nav .seg a[data-lang="bn"]').click();
    await page.waitForURL((u) => u.pathname === '/harkara/');
    expect(await page.evaluate(() => localStorage.getItem('harkara.lang'))).toBe('bn');
    await page.waitForTimeout(800); // a US visitor who chose Banglish is not sent back
    expect(path(page)).toBe('/harkara/');
    await page.locator('footer .seg a[data-lang="en"]').click();
    await page.waitForURL('**/harkara/en/');
    expect(await page.evaluate(() => localStorage.getItem('harkara.lang'))).toBe('en');
  });
});

test.describe('without JavaScript', () => {
  test.use({ javaScriptEnabled: false });
  test('language toggle and CTA are plain links', async ({ page, context }) => {
    await isolate(context, 'US');
    await page.goto('./');
    await expect(page.locator('.hero .cta')).toHaveAttribute('href', /^https:\/\/github\.com\/tanzir71\/harkara/);
    await page.locator('.nav .seg a[data-lang="en"]').click();
    await page.waitForURL('**/harkara/en/');
    await page.locator('.nav .seg a[data-lang="bn"]').click();
    await page.waitForURL((u) => u.pathname === '/harkara/');
  });
});

test.describe('install', () => {
  test.use({ viewport: { width: 390, height: 800 }, permissions: ['clipboard-read', 'clipboard-write'] });

  test('SHA-256 box and Copy button', async ({ page, context }) => {
    await isolate(context, 'BD');
    await page.goto('en/');
    const copyBtn = page.locator('#install .copy');
    if (STATE === 'release') {
      const sha = JSON.parse(readFileSync('site/latest.json', 'utf8')).apks.find((a) => a.abi === 'arm64-v8a').sha256;
      await expect(copyBtn).toBeVisible();
      await copyBtn.click();
      await expect(copyBtn).toHaveText(copy.install.copied.en);
      expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(sha);
      await expect(copyBtn).toHaveText(copy.install.copy.en, { timeout: 4000 });
    } else {
      await expect(copyBtn).toHaveCount(0);
      await expect(page.locator('#install .hash')).toHaveText('—');
    }
  });

  test('"From a friend’s phone" scrolls to the campus band', async ({ page, context }) => {
    await isolate(context, 'BD');
    await page.goto('en/');
    await page.locator('#install .chips a[href="#campus"]').click();
    await expect(page).toHaveURL(/#campus$/);
    await expect(page.locator('#campus h2')).toBeInViewport();
  });

  test('Obtainium chip points at the repo', async ({ page, context }) => {
    await isolate(context, 'BD');
    await page.goto('./');
    await expect(page.locator('#install .chips a').first()).toHaveAttribute('href', 'obtainium://add/https://github.com/tanzir71/harkara');
  });
});

test.describe('other pages', () => {
  for (const [url, lang] of [
    ['privacy/', 'bn'],
    ['en/privacy/', 'en'],
  ])
    test(`privacy page ${url}`, async ({ page, context }) => {
      await isolate(context, 'US');
      await page.goto(url);
      await page.waitForTimeout(300);
      expect(path(page)).toBe(`/harkara/${url}`);
      await expect(page.locator('main')).toContainText(copy.privacy.body[lang]);
      await expect(page.locator('#verify')).toBeVisible();
      expect(await overflow(page)).toBeLessThanOrEqual(0);
    });

  test('404 page', async ({ page, context }) => {
    await isolate(context, 'BD');
    const res = await page.goto('no/such/page/');
    expect(res.status()).toBe(404);
    await expect(page.locator('h1')).toHaveText('404');
    await expect(page.locator('main a[href="en/"]')).toBeVisible();
    // styles load through <base href="/harkara/"> at any depth
    expect(await page.evaluate(() => getComputedStyle(document.body).fontFamily)).toContain('Geist');
  });
});

test.describe('night theme and reduced motion', () => {
  test.use({ colorScheme: 'dark', reducedMotion: 'reduce', viewport: { width: 390, height: 800 } });
  test('uses the Night tokens', async ({ page, context }) => {
    await isolate(context, 'BD');
    await page.goto('en/');
    expect(await page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(16, 17, 19)');
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).scrollBehavior)).toBe('auto');
    await page.screenshot({ path: `${SHOTS}/${STATE}-en-390-night.png`, fullPage: true });
  });
});
