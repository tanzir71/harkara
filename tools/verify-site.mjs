#!/usr/bin/env node
// Static checks for the built site (handover §7.4). Run after tools/build-site.mjs; exits 1 on any failure.
// Checks: landing.json copy on each page, links/anchors, allowed hosts, lang/h1/heading order/alt/svg labels,
// focus styles, hreflang, release/coming-soon state, and budgets (JS <= 5 KB, CSS <= 40 KB, page <= 120 KB without fonts).
// The web page for non-users (o/, en/o/) has its own budget (JS <= 60 KB for the bundled Schnorr verifier, CSS <= 48 KB,
// page <= 160 KB) and is the only page allowed to talk to WebSocket relays: exactly data/default-relays.json, pinned by its CSP.
import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { join, dirname, resolve, posix } from 'node:path';
import { fileURLToPath } from 'node:url';
import { fillText, decodeEntities, normSpace } from './site/text.mjs';
import { releaseView, placeholderVars } from './site/release.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const site = join(root, 'site');
const KB = 1024;
const BUDGET = { js: 5 * KB, css: 40 * KB, page: 120 * KB, langScript: 2 * KB };
const O_BUDGET = { js: 60 * KB, css: 48 * KB, page: 160 * KB };
const RELAYS = JSON.parse(readFileSync(join(root, 'data/default-relays.json'), 'utf8')).relays;
const RELAY_HOSTS = new Set(RELAYS.map((r) => new URL(r).host));
// XML namespace identifiers in scripts are names, not requests.
const NAMESPACES = new Set(['http://www.w3.org/2000/svg']);
const ALLOWED_HOSTS = new Set(['github.com', 'api.github.com', 'objects.githubusercontent.com', 'api.country.is']);
const SKIP_KEYS = new Set(['_note', 'icon', 'color', 'card', 'works', 'online']);

const errors = [];
const fail = (where, msg) => errors.push(`${where}: ${msg}`);
const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));

const copy = readJson(join(root, 'design/copy/landing.json'));
const cfg = readJson(join(site, 'config.json'));
for (const k of ['repo', 'izzyOnDroidUrl', 'fdroidUrl', 'codebergUrl', 'signingCertSha256'])
  if (typeof cfg[k] !== 'string') fail('site/config.json', `"${k}" must be a string`);
const repo = cfg.repo;
const [owner, name] = repo.split('/');
const siteUrl = `https://${owner.toLowerCase()}.github.io/${name}/`;
const { host: siteHost, pathname: basePath } = new URL(siteUrl);

const latestPath = join(site, 'latest.json');
const latest = existsSync(latestPath) ? readJson(latestPath) : null;
if (latest) {
  for (const k of ['version', 'versionCode', 'published', 'apks', 'notes'])
    if (!(k in latest)) fail('site/latest.json', `missing "${k}"`);
  for (const a of latest.apks || [])
    for (const k of ['abi', 'url', 'bytes', 'sha256']) if (!(k in a)) fail('site/latest.json', `apk ${a.abi || '?'} missing "${k}"`);
}
const view = releaseView(latest, repo);
const vars = placeholderVars(view);
const state = view ? `release v${view.version}` : 'coming soon';

const PAGES = [
  { file: 'index.html', lang: 'bn', landing: true, alt: ['', 'en/'] },
  { file: 'en/index.html', lang: 'en', landing: true, alt: ['', 'en/'] },
  { file: 'privacy/index.html', lang: 'bn', privacy: true, alt: ['privacy/', 'en/privacy/'] },
  { file: 'en/privacy/index.html', lang: 'en', privacy: true, alt: ['privacy/', 'en/privacy/'] },
  { file: '404.html', lang: 'en' },
  { file: 'o/index.html', lang: 'bn', o: true, alt: ['o/', 'en/o/'] },
  { file: 'en/o/index.html', lang: 'en', o: true, alt: ['o/', 'en/o/'] },
];
const LANG_ATTR = { bn: 'bn-Latn', en: 'en' };

// ---------- tiny HTML helpers (the pages are generated, so regexes are enough) ----------
const stripCode = (html) => html.replace(/<script\b[\s\S]*?<\/script>/gi, ' ').replace(/<style\b[\s\S]*?<\/style>/gi, ' ');
const textOf = (html) => normSpace(decodeEntities(stripCode(html).replace(/<br\s*\/?>/gi, ' ').replace(/<[^>]+>/g, '')));
const attrsOf = (html) => normSpace([...stripCode(html).matchAll(/\s[\w:-]+="([^"]*)"/g)].map((m) => decodeEntities(m[1])).join(' \u0000 '));
const tags = (html, name) => [...html.matchAll(new RegExp(`<${name}\\b([^>]*)>`, 'gi'))].map((m) => m[1]);
const attr = (attrs, n) => {
  const m = attrs.match(new RegExp(`\\s${n}="([^"]*)"`, 'i'));
  return m ? decodeEntities(m[1]) : null;
};
const idsOf = (html) => [...html.matchAll(/\sid="([^"]+)"/g)].map((m) => m[1]);
const inlineScripts = (html) => [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].filter((m) => !/\ssrc=/.test(m[1])).map((m) => m[2]);
const inlineStyles = (html) => [...html.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/gi)].map((m) => m[1]);
const bytes = (s) => Buffer.byteLength(s, 'utf8');

const pageCache = new Map();
const readPage = (file) => {
  if (!pageCache.has(file)) pageCache.set(file, readFileSync(join(site, file), 'utf8'));
  return pageCache.get(file);
};

/** Map a URL on the site to a file under site/ (or null when it isn't the site). */
function siteFile(url) {
  if (url.host !== siteHost || !url.pathname.startsWith(basePath)) return null;
  let rel = decodeURIComponent(url.pathname.slice(basePath.length));
  if (rel === '' || rel.endsWith('/')) rel += 'index.html';
  return rel;
}

// ---------- copy ----------
function* copyStrings(node, path = '') {
  if (node && typeof node === 'object' && !Array.isArray(node) && 'bn' in node && 'en' in node) {
    yield { path, lang: node };
    return;
  }
  if (Array.isArray(node)) for (let i = 0; i < node.length; i++) yield* copyStrings(node[i], `${path}[${i}]`);
  else if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) if (!SKIP_KEYS.has(k)) yield* copyStrings(v, path ? `${path}.${k}` : k);
  } else if (typeof node === 'string' || typeof node === 'number') yield { path, plain: String(node) };
}

// Strings that may be missing on a landing page, and why.
const allowedMissing = new Map([['privacy.body', 'checked on the privacy pages']]);
if (view) allowedMissing.set('hero.ctaComingSoon', 'a release exists');
if (!view?.sha256) {
  allowedMissing.set('install.copy', 'no SHA-256 to copy yet');
  allowedMissing.set('install.copied', 'no SHA-256 to copy yet');
}
if (!cfg.izzyOnDroidUrl) allowedMissing.set('install.otherChips[1]', 'config.izzyOnDroidUrl is empty');
if (!cfg.fdroidUrl) allowedMissing.set('install.otherChips[2]', 'config.fdroidUrl is empty');
if (!cfg.codebergUrl) allowedMissing.set('footer.links[1]', 'config.codebergUrl is empty');

function checkCopy(page, html) {
  const hay = textOf(html) + ' \u0000 ' + attrsOf(html);
  let checked = 0;
  const skipped = [];
  for (const s of copyStrings(copy)) {
    const values = s.plain != null ? [[s.path, s.plain]] : [];
    if (s.lang) {
      const v = s.lang[page.lang];
      if (Array.isArray(v)) v.forEach((x, i) => values.push([`${s.path}[${i}]`, x]));
      else values.push([s.path, v]);
    }
    for (const [path, raw] of values) {
      const expected = normSpace(raw.includes('{{') ? fillText(raw, vars) : raw);
      if (!expected) {
        skipped.push(`${path} (empty without a release)`);
        continue;
      }
      checked++;
      if (hay.includes(expected)) continue;
      if (allowedMissing.has(path)) skipped.push(`${path} (${allowedMissing.get(path)})`);
      else fail(page.file, `missing landing.json string ${path}: "${expected}"`);
    }
  }
  // The other direction: things that must NOT be there in this state/config.
  const t = textOf(html);
  const L = page.lang;
  if (view && t.includes(normSpace(copy.hero.ctaComingSoon[L]))) fail(page.file, 'shows ctaComingSoon although a release exists');
  if (!view && !t.includes(normSpace(copy.hero.ctaComingSoon[L]))) fail(page.file, 'no release, but ctaComingSoon is missing');
  if (!cfg.izzyOnDroidUrl && t.includes(copy.install.otherChips[L][1])) fail(page.file, 'IzzyOnDroid chip shown without a URL in config.json');
  if (!cfg.fdroidUrl && t.includes(copy.install.otherChips[L][2])) fail(page.file, 'F-Droid chip shown without a URL in config.json');
  if (!cfg.codebergUrl && t.includes(copy.footer.links[L][1])) fail(page.file, 'Codeberg link shown without a URL in config.json');
  if (copy.meta.title[L] !== decodeEntities((html.match(/<title>([^<]*)<\/title>/) || [])[1] || '')) fail(page.file, '<title> is not meta.title');
  return { checked, skipped };
}

// ---------- per page ----------
const cssCache = new Map();
function cssOf(file) {
  if (!cssCache.has(file)) cssCache.set(file, readFileSync(join(site, file), 'utf8'));
  return cssCache.get(file);
}
const report = [];
const skippedAll = new Set();
let copyChecked = 0;

for (const page of PAGES) {
  const where = `site/${page.file}`;
  if (!existsSync(join(site, page.file))) {
    fail(where, 'missing (run node tools/build-site.mjs)');
    continue;
  }
  const html = readPage(page.file);
  const text = textOf(html);
  const baseHref = attr(tags(html, 'base')[0] || '', 'href');
  const pageUrl = new URL(baseHref || page.file.replace(/index\.html$/, ''), siteUrl);

  // lang, headings, images, svgs, ids
  const htmlLang = attr(tags(html, 'html')[0] || '', 'lang');
  if (htmlLang !== LANG_ATTR[page.lang]) fail(where, `<html lang="${htmlLang}">, expected "${LANG_ATTR[page.lang]}"`);
  const levels = [...html.matchAll(/<h([1-6])\b/gi)].map((m) => Number(m[1]));
  if (levels.filter((l) => l === 1).length !== 1) fail(where, `needs exactly one <h1>, found ${levels.filter((l) => l === 1).length}`);
  if (levels[0] !== 1) fail(where, 'first heading is not <h1>');
  levels.forEach((l, i) => {
    if (i && l > levels[i - 1] + 1) fail(where, `heading level skips from h${levels[i - 1]} to h${l}`);
  });
  for (const a of tags(html, 'img')) if (attr(a, 'alt') == null) fail(where, `<img${a}> has no alt`);
  for (const a of tags(html, 'svg'))
    if (attr(a, 'aria-hidden') !== 'true' && !(attr(a, 'role') === 'img' && attr(a, 'aria-label'))) fail(where, `<svg${a.slice(0, 40)}…> needs aria-hidden or role=img + aria-label`);
  const ids = idsOf(html);
  const dup = ids.filter((id, i) => ids.indexOf(id) !== i);
  if (dup.length) fail(where, `duplicate ids: ${[...new Set(dup)].join(', ')}`);
  if (/<iframe\b/i.test(html)) fail(where, 'contains an <iframe>');
  if (/document\.cookie/.test(html)) fail(where, 'touches document.cookie');
  if (/\{\{|\}\}/.test(html.replace(/<script type="application\/json"[^>]*>[\s\S]*?<\/script>/gi, ''))) fail(where, 'unfilled {{placeholder}} left in the HTML');
  if (/\b(undefined|NaN)\b|\bnull\b/.test(text)) fail(where, 'text contains undefined/NaN/null');
  if (/(^|\s)v\s*·|·\s*·|(?<!\d)\sMB\b/.test(text)) fail(where, 'text has a version/size fragment without its value');

  // links, anchors, resources
  const refs = [];
  for (const tag of ['a', 'link', 'script', 'img', 'use', 'source', 'base'])
    for (const a of tags(html, tag)) for (const n of ['href', 'src']) if (attr(a, n) != null && tag !== 'base') refs.push({ tag, a, v: attr(a, n) });
  for (const a of tags(html, 'meta')) if (/^https?:/.test(attr(a, 'content') || '')) refs.push({ tag: 'meta', a, v: attr(a, 'content') });
  const resources = { css: [], js: [], other: [] };
  for (const r of refs) {
    const v = r.v.trim();
    if (!v) {
      fail(where, `empty ${r.tag} href/src`);
      continue;
    }
    if (v.startsWith('#')) {
      if (r.tag !== 'use' && v === '#') fail(where, 'bare "#" link');
      else if (!ids.includes(v.slice(1))) fail(where, `anchor ${v} has no matching id`);
      continue;
    }
    if (/^obtainium:/.test(v)) {
      if (v !== `obtainium://add/https://github.com/${repo}`) fail(where, `unexpected Obtainium link ${v}`);
      continue;
    }
    let url;
    try {
      url = new URL(v, pageUrl);
    } catch {
      fail(where, `bad URL ${v}`);
      continue;
    }
    if (!/^https?:$/.test(url.protocol)) {
      fail(where, `scheme not allowed: ${v}`);
      continue;
    }
    const file = siteFile(url);
    if (file == null) {
      if (!ALLOWED_HOSTS.has(url.host)) fail(where, `link/request to a host that is not allowed: ${v}`);
      if (['link', 'script', 'img', 'source'].includes(r.tag) && !/rel="(canonical|alternate)"/.test(r.a))
        fail(where, `third-party resource ${v} (fonts, scripts and styles must be self-hosted)`);
      continue;
    }
    const abs = join(site, file);
    if (!existsSync(abs) || !statSync(abs).isFile()) {
      fail(where, `broken internal link ${v} -> site/${file}`);
      continue;
    }
    if (url.hash && file.endsWith('.html') && !idsOf(readPage(file)).includes(decodeURIComponent(url.hash.slice(1))))
      fail(where, `link ${v}: no id "${url.hash.slice(1)}" in site/${file}`);
    if (r.tag === 'link' && /rel="stylesheet"/.test(r.a)) resources.css.push(file);
    else if (r.tag === 'script') resources.js.push(file);
    else if ((r.tag === 'link' && /rel="icon"/.test(r.a)) || r.tag === 'img') resources.other.push(file);
  }

  // every absolute URL mentioned anywhere (scripts included) must be GitHub, api.country.is or the site itself;
  // WebSocket URLs only on the o/ pages and only to the default relays
  const scriptFiles = resources.js.map((f) => readFileSync(join(site, f), 'utf8'));
  for (const src of [html, ...scriptFiles]) {
    for (const m of src.matchAll(/https?:\/\/[^\s"'<>)\]`\\]+/g)) {
      if (NAMESPACES.has(m[0])) continue;
      const host = new URL(m[0]).host;
      if (host !== siteHost && !ALLOWED_HOSTS.has(host)) fail(where, `URL to a host that is not allowed: ${m[0]}`);
    }
    for (const m of src.matchAll(/wss?:\/\/[^\s"'<>)\]`\\;]+/g)) {
      if (!page.o) fail(where, `WebSocket URL outside the o/ pages: ${m[0]}`);
      else if (!m[0].startsWith('wss://') || !RELAY_HOSTS.has(new URL(m[0]).host)) fail(where, `WebSocket URL that is not a default relay: ${m[0]}`);
    }
  }

  // hreflang alternates
  if (page.alt) {
    const alts = Object.fromEntries(tags(html, 'link').filter((a) => /rel="alternate"/.test(a)).map((a) => [attr(a, 'hreflang'), attr(a, 'href')]));
    const want = { 'bn-Latn': siteUrl + page.alt[0], en: siteUrl + page.alt[1], 'x-default': siteUrl + page.alt[1] };
    for (const [hl, href] of Object.entries(want)) if (alts[hl] !== href) fail(where, `hreflang ${hl} should point to ${href} (found ${alts[hl]})`);
  }

  // language logic
  const scripts = inlineScripts(html);
  if (page.landing && page.lang === 'bn') {
    const s = scripts.find((x) => x.includes('api.country.is'));
    if (!s) fail(where, 'language script (api.country.is) missing on /');
    else if (bytes(s) >= BUDGET.langScript) fail(where, `language script is ${bytes(s)} B, must stay under ${BUDGET.langScript} B`);
    else if (!/harkara\.lang/.test(s) || !/sessionStorage/.test(s) || !/Asia\/Dhaka/.test(s) || !/1500/.test(s)) fail(where, 'language script is missing a §7.2 rule');
  }
  if (page.file.startsWith('en/') && scripts.some((x) => x.includes('api.country.is'))) fail(where, '/en/ must never look up the country');

  // landing specifics
  if (page.landing) {
    const r = checkCopy(page, html);
    copyChecked += r.checked;
    r.skipped.forEach((s) => skippedAll.add(s));
    const details = tags(html, 'details');
    if (details.length !== copy.faq.items.length) fail(where, `expected ${copy.faq.items.length} FAQ <details>, found ${details.length}`);
    if (!/\sopen\b/.test(details[0] || '') || details.slice(1).some((d) => /\sopen\b/.test(d))) fail(where, 'only the first FAQ item should be open');
    const cta = (html.match(/<a class="cta[^"]*" href="([^"]+)"/) || [])[1];
    if (view && cta !== view.arm64Url) fail(where, `CTA should download ${view.arm64Url}, links to ${cta}`);
    if (!view && cta !== `https://github.com/${repo}`) fail(where, `coming-soon CTA should link to the repo, links to ${cta}`);
    if (view?.sha256 && !html.includes(`data-copy="${view.sha256}"`)) fail(where, 'Copy button does not carry the full SHA-256');
    for (const id of ['offgrid', 'workflows', 'campus', 'install', 'faq']) if (!ids.includes(id)) fail(where, `section #${id} missing`);
  }
  if (page.privacy) {
    const t = normSpace(copy.privacy.body[page.lang]);
    if (!text.includes(t)) fail(where, 'privacy.body copy missing');
    if (!text.includes('api.country.is')) fail(where, 'privacy page must name api.country.is');
    const fp = cfg.signingCertSha256.trim();
    if (fp && !text.includes(fp)) fail(where, 'signing certificate SHA-256 from config.json not shown');
    if (!ids.includes('verify')) fail(where, 'verify section missing');
  }

  // the web page for non-users: CSP pins the relays, no inline code, not indexed
  if (page.o) {
    const csp = attr(tags(html, 'meta').find((a) => /http-equiv="Content-Security-Policy"/i.test(a)) || '', 'content') || '';
    const directive = (n) => (csp.split(';').map((d) => d.trim().split(/\s+/)).find((d) => d[0] === n) || []).slice(1);
    if (!csp) fail(where, 'o/ page has no Content-Security-Policy meta');
    if (directive('default-src').join(' ') !== "'none'") fail(where, "CSP default-src must be 'none'");
    if (directive('script-src').join(' ') !== "'self'") fail(where, "CSP script-src must be 'self' (no inline scripts)");
    if (JSON.stringify([...directive('connect-src')].sort()) !== JSON.stringify([...RELAYS].sort())) fail(where, 'CSP connect-src must list exactly data/default-relays.json');
    if (scripts.some((s, i) => !/type="application\/json"/.test([...html.matchAll(/<script\b([^>]*)>/gi)].filter((m) => !/\ssrc=/.test(m[1]))[i]?.[1] || ''))) fail(where, 'o/ page has an inline executable script');
    if (!/<meta name="robots" content="noindex">/.test(html)) fail(where, 'o/ page must be noindex');
    if (!resources.js.includes('assets/o.js')) fail(where, 'o/ page does not load assets/o.js');
    for (const r of RELAYS) if (!text.includes(r)) fail(where, `relay ${r} is not listed on the page`);
  }

  // budgets
  const css = resources.css.map(cssOf).join('') + inlineStyles(html).join('');
  const jsBytes = scripts.reduce((n, s) => n + bytes(s), 0) + resources.js.reduce((n, f) => n + statSync(join(site, f)).size, 0);
  const cssBytes = bytes(css);
  const other = resources.other.reduce((n, f) => n + statSync(join(site, f)).size, 0);
  const total = bytes(html) + cssBytes + jsBytes + other;
  const budget = page.o ? O_BUDGET : BUDGET;
  if (jsBytes > budget.js) fail(where, `JS ${jsBytes} B > ${budget.js} B`);
  if (cssBytes > budget.css) fail(where, `CSS ${cssBytes} B > ${budget.css} B`);
  if (total > budget.page) fail(where, `page weight ${total} B > ${budget.page} B (fonts excluded)`);
  if (!/:focus-visible[^{]*\{[^}]*outline\s*:\s*(?!none|0[\s;}])[^;}]+/.test(css)) fail(where, 'no visible :focus-visible outline style');
  report.push({ page: page.file, html: bytes(html), css: cssBytes, js: jsBytes, total });
}

// CSS url(...) references and font licences
for (const file of [...cssCache.keys()]) {
  for (const m of cssOf(file).matchAll(/url\(\s*["']?([^"')]+)["']?\s*\)/g)) {
    if (/^(https?:)?\/\//.test(m[1])) fail(`site/${file}`, `external url() ${m[1]}`);
    else if (!/^data:/.test(m[1]) && !existsSync(join(site, posix.dirname(file), m[1]))) fail(`site/${file}`, `url(${m[1]}) not found`);
  }
}
const fonts = readdirSync(join(site, 'assets/fonts'));
for (const [family, licence] of [['geist', 'OFL-Geist.txt'], ['anek-bangla', 'OFL-AnekBangla.txt'], ['jetbrains-mono', 'OFL-JetBrainsMono.txt']]) {
  if (!fonts.some((f) => f.startsWith(family) && f.endsWith('.woff2'))) fail('site/assets/fonts', `no ${family} woff2`);
  if (!fonts.includes(licence)) fail('site/assets/fonts', `missing licence ${licence}`);
}

// ---------- output ----------
const kb = (n) => (n / KB).toFixed(1).padStart(6) + ' KB';
console.log(`verify-site: state = ${state}; ${copyChecked} landing.json strings checked across both languages`);
console.log('page                     html        css         js          total (no fonts)');
for (const r of report) console.log(`${r.page.padEnd(24)} ${kb(r.html)}   ${kb(r.css)}   ${kb(r.js)}   ${kb(r.total)}`);
console.log(`budgets: JS <= ${BUDGET.js / KB} KB, CSS <= ${BUDGET.css / KB} KB, page <= ${BUDGET.page / KB} KB, language script < ${BUDGET.langScript / KB} KB; o/ pages: JS <= ${O_BUDGET.js / KB} KB, CSS <= ${O_BUDGET.css / KB} KB, page <= ${O_BUDGET.page / KB} KB`);
if (skippedAll.size) console.log(`allowed to be absent in this state: ${[...skippedAll].join('; ')}`);
if (errors.length) {
  console.error(`\nverify-site FAILED (${errors.length}):`);
  for (const e of errors) console.error(`  ✗ ${e}`);
  process.exit(1);
}
console.log('verify-site: all checks passed');
