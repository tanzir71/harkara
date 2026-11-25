// Page templates for the landing site. Every visible landing string comes from design/copy/landing.json.
// ctx: { lang, c (landing.json), view (release view or null), cfg (site/config.json), repo, theme,
//        root, alt, abs, home, privacy, ic (icon set), js }
import { esc, fillText } from './text.mjs';
import { placeholderVars } from './release.mjs';
import { extra } from './strings.mjs';

export const LANG_ATTR = { bn: 'bn-Latn', en: 'en' };
const cc = (token) => 'c-' + token.split('/').pop();
const tr = (ctx) => (o) => o[ctx.lang];
const repoUrl = (ctx) => `https://github.com/${ctx.repo}`;
const TOOL_ICONS = { Compass: 'compass', Qibla: 'qibla', Torch: 'flashlight', Handbook: 'handbook', File: 'drop' };
const join = (a) => a.join('');

export function head(ctx, { title, description, script = '', base = '', robots = '', meta = '', styles = [] }) {
  const r = base ? '' : ctx.root;
  const alternates = ctx.abs
    ? `<link rel="canonical" href="${ctx.abs[ctx.lang]}">
<link rel="alternate" hreflang="bn-Latn" href="${ctx.abs.bn}">
<link rel="alternate" hreflang="en" href="${ctx.abs.en}">
<link rel="alternate" hreflang="x-default" href="${ctx.abs.en}">
<meta property="og:url" content="${ctx.abs[ctx.lang]}">
<meta property="og:locale" content="${ctx.lang === 'bn' ? 'bn_BD' : 'en_US'}">`
    : '';
  return `<!doctype html>
<html lang="${LANG_ATTR[ctx.lang]}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
${meta}${base ? `<base href="${base}">\n` : ''}<title>${esc(title)}</title>
<meta name="description" content="${esc(description)}">
${robots ? `<meta name="robots" content="${robots}">\n` : ''}${alternates}
<meta property="og:type" content="website">
<meta property="og:title" content="${esc(title)}">
<meta property="og:description" content="${esc(description)}">
<meta name="theme-color" content="${ctx.theme.day}" media="(prefers-color-scheme: light)">
<meta name="theme-color" content="${ctx.theme.night}" media="(prefers-color-scheme: dark)">
<link rel="icon" href="${r}assets/favicon.svg" type="image/svg+xml">
<link rel="preload" href="${r}assets/fonts/geist-latin-400-normal.woff2" as="font" type="font/woff2" crossorigin>
<link rel="preload" href="${r}assets/fonts/geist-latin-600-normal.woff2" as="font" type="font/woff2" crossorigin>
<link rel="stylesheet" href="${r}assets/tokens.css">
<link rel="stylesheet" href="${r}assets/site.css">
${styles.map((s) => `<link rel="stylesheet" href="${r}${s}">\n`).join('')}${script ? `<script>${script}</script>\n` : ''}</head>`;
}

function brand(ctx) {
  return `<a class="brand" href="${ctx.home}">${ctx.ic.mark()}<span class="word">harkara</span><span class="bnmark" lang="bn">হরকরা</span></a>`;
}

function toggle(ctx) {
  const [bn, en] = tr(ctx)(ctx.c.nav.langToggle);
  const cur = (l) => (ctx.lang === l ? ' aria-current="page"' : '');
  return `<div class="seg"><a href="${ctx.alt.bn}" data-lang="bn" hreflang="bn-Latn" lang="bn-Latn"${cur('bn')}>${esc(bn)}</a><a href="${ctx.alt.en}" data-lang="en" hreflang="en" lang="en"${cur('en')}>${esc(en)}</a></div>`;
}

export function nav(ctx, { links = true } = {}) {
  const t = tr(ctx);
  const { c, ic, view } = ctx;
  const targets = ['#offgrid', '#workflows', '#install', '#faq', repoUrl(ctx)];
  const apkHref = links ? (view ? view.arm64Url : '#install') : `${ctx.home}#install`;
  return `<header class="nav wrap">${brand(ctx)}${
    links ? `<nav class="links">${join(t(c.nav.links).map((l, i) => `<a href="${targets[i]}">${esc(l)}</a>`))}</nav>` : ''
  }${toggle(ctx)}<a class="pill" href="${apkHref}">${ic.icon('drop', 'dl')}<span class="s">${esc(t(c.nav.apkShort))}</span><span class="l">${esc(
    t(c.nav.apkLong),
  )}</span></a></header>`;
}

function ctaButton(ctx, label) {
  const { c, view, ic } = ctx;
  const t = tr(ctx);
  if (!view) return `<a class="cta soon" href="${repoUrl(ctx)}">${ic.icon('queued')}<span>${esc(t(c.hero.ctaComingSoon))}</span></a>`;
  const meta = fillText(t(c.hero.ctaMeta), placeholderVars(view));
  return `<a class="cta" href="${view.arm64Url}">${ic.icon('drop', 'dl')}<span>${esc(label)}</span>${
    meta ? `<span class="cta-meta">${esc(meta)}</span>` : ''
  }</a>`;
}

function hero(ctx) {
  const t = tr(ctx);
  const { c, ic } = ctx;
  const h = c.hero;
  const accent = esc(t(h.h1Accent));
  const h1 = t(h.h1)
    .split('\n')
    .map(esc)
    .join('<br>')
    .replace(accent, `<span class="acc">${accent}</span>`);
  const r = c.route;
  const stops = r.stops.map(
    (s, i) =>
      `<li${i === r.stops.length - 1 ? ' class="done"' : ''}><span class="disc ${cc(s.color)}">${ic.icon(s.icon)}</span><div><p class="t">${esc(
        t(s.title),
      )}</p><p class="d">${esc(t(s.detail))}</p></div></li>`,
  );
  return `<section class="hero wrap" aria-labelledby="hero-h">
<div class="hero-copy">
<p class="eyebrow live">${esc(t(h.eyebrow))}</p>
<h1 id="hero-h">${h1}</h1>
<p class="lead">${esc(t(h.lead))}</p>
<div class="cta-row">${ctaButton(ctx, t(h.cta))}<a class="more" href="#install">${esc(t(h.installLink))}${ic.icon('chevron-right')}</a></div>
<p class="meta">${esc(t(h.meta))}</p>
<p class="beta">${esc(t(h.betaNote))}</p>
</div>
<aside class="route" aria-labelledby="route-h">
<div class="route-top"><p class="eyebrow" id="route-h">${esc(t(r.title))}</p><p class="net">${esc(t(r.netState))}</p></div>
<ol class="stops">${join(stops)}</ol>
<p class="note">${ic.icon('lock')}<span>${esc(t(r.footnote))}</span></p>
</aside>
</section>`;
}

function offgrid(ctx) {
  const t = tr(ctx);
  const { c, ic } = ctx;
  const o = c.offgrid;
  const rung = (r) =>
    `<li class="rung"><span class="ic ${cc(r.color)}">${ic.icon(r.icon)}</span><div><h3>${esc(t(r.title))}</h3><p>${esc(
      t(r.detail),
    )}</p></div><span class="n">${esc(r.n)}</span></li>`;
  return `<section id="offgrid" class="sec raised" aria-labelledby="offgrid-h"><div class="wrap split">
<div class="sec-head"><p class="eyebrow">${esc(t(o.eyebrow))}</p><h2 id="offgrid-h">${esc(t(o.h2))}</h2><p class="lead-s">${esc(t(o.lead))}</p></div>
<div class="ladder">
<p class="group">${esc(t(o.groupOffline))}</p>
<ul class="rungs">${join(o.rungs.filter((r) => !r.online).map(rung))}</ul>
<p class="group">${esc(t(o.groupOnline))}</p>
<ul class="rungs">${join(o.rungs.filter((r) => r.online).map(rung))}</ul>
</div>
</div></section>`;
}

// ---------- workflow stages: the real card UIs, in HTML/CSS ----------

const WAVE = [6, 10, 14, 8, 16, 12, 18, 10, 6, 12, 16, 8, 14, 10, 6, 12, 8, 14, 10, 6, 8, 12];
function wave() {
  const bar = (h, i) => `M${1.5 + i * 4} ${12 - h / 2}v${h}`;
  const played = 9;
  return `<svg class="wave" viewBox="0 0 88 24" aria-hidden="true"><path class="pl" d="${join(WAVE.slice(0, played).map(bar))}"/><path d="${join(
    WAVE.slice(played).map((h, i) => bar(h, i + played)),
  )}"/></svg>`;
}

function stageCard(ctx, it) {
  const t = tr(ctx);
  const { ic } = ctx;
  const v = it.visual;
  if (v.card === 'post')
    return `<div class="card post">
<div class="row sb"><span class="cat"><span class="dot d-${v.category.toLowerCase()}"></span><span class="b">${esc(v.category)}</span><span class="bn" lang="bn">খাবার</span></span><span class="muted">${esc(v.meta)}</span></div>
<p class="txt">${esc(t(v.text))}</p>
<p class="tags"><span><span class="dot d-${v.category.toLowerCase()}"></span>${esc(t(v.detail))}</span><span>${esc(v.expiry)}</span></p>
<div class="row acts">${ic.icon('vote-up')}<span class="b">${esc(v.score)}</span>${ic.icon('vote-down')}<span class="cm">${ic.icon('chats')}${esc(
      v.comments,
    )}</span><span class="via">${ic.icon('mesh', 'c-mesh')}${esc(v.carrier)}</span>${ic.icon('more', 'mo')}</div>
</div>`;
  if (v.sender)
    return `<div class="chat">
<p class="who">${esc(v.sender)}</p>
<div class="bub in"><p>${esc(t(v.in))}</p><p class="bm">${ic.icon('mesh', 'c-mesh')}14:05</p></div>
<div class="bub out"><p>${esc(t(v.out))}</p><p class="bm">${ic.icon('relay', 'c-online')}14:06${ic.icon('check-double', 'c-online')}</p></div>
<div class="voice"><span class="play">${ic.icon('play')}</span>${wave()}<span>${esc(v.voice)}</span></div>
</div>`;
  if (v.card === 'task')
    return `<div class="stack"><div class="card task">
<div class="row sb"><span class="muted ic-l">${ic.icon('tasks')}Task · Pickup</span><span class="amt">${esc(v.price)}</span></div>
<p class="ttl">${esc(t(v.title))}</p>
<p class="prog" aria-hidden="true"><i class="on"></i><i class="on"></i><i></i><i></i><i></i></p>
<div class="row sb muted"><span>${esc(v.status)}</span><span class="ic-l">${ic.icon('verified', 'c-online')}${esc(v.runner)}</span></div>
</div>
<p class="code-pill">${ic.icon('qr')}<span class="muted">Handoff code</span><span class="code">${esc(v.handoffCode)}</span></p></div>`;
  if (v.card === 'iou')
    return `<div class="card iou">
<div class="row sb"><span class="muted ic-l">${ic.icon('khata')}Khata · IOU</span><span class="muted">Nov</span></div>
<p class="amount">${esc(v.amount)}</p>
<p class="what">${esc(t(v.what))}</p>
<p class="muted ic-l">${ic.icon('lock')}${esc(t(v.status))}</p>
</div>`;
  if (v.card === 'pin')
    return `<div class="card pin">
<div class="row sb"><span class="pt"><span class="dot d-stock"></span>${esc(t(v.title))}</span><span class="badge">Public</span></div>
<p class="muted">${esc(t(v.detail))}</p>
<div class="row sb ask"><span><span class="q">${esc(t(v.ask))}</span> <span class="muted">${esc(t(v.tally))}</span></span><span class="yn"><span>${esc(
      t(v.yes),
    )}</span><span>${esc(t(v.no))}</span></span></div>
</div>`;
  if (v.card === 'checkin+location')
    return `<div class="stack">
<div class="card ci"><span class="ok">${ic.icon('check')}</span><span class="ci-t"><span class="ttl">${esc(t(v.checkin))}</span><span class="muted">${esc(
      v.checkinDetail,
    )}</span></span><span class="via">${ic.icon('sms', 'c-sms')}by SMS</span></div>
<div class="card loc"><div class="map" aria-hidden="true"><i class="b1"></i><i class="b2"></i><i class="b3"></i><i class="b4"></i><i class="park"></i><i class="water"></i><i class="rd h"></i><i class="rd v1"></i><i class="rd v2"></i><span class="mpin">${ic.icon(
      'pin',
    )}</span></div>
<div class="loc-b"><p class="ttl ic-l">${ic.icon('pin')}${esc(t(v.live))}</p><p class="muted">${esc(t(v.place))}</p><p class="pc">${esc(v.plusCode)}</p></div></div>
</div>`;
  if (v.tools)
    return `<div class="tools"><ul class="tool-row">${join(
      v.tools.map((n) => `<li><span class="tb">${ic.icon(TOOL_ICONS[n] || 'more')}</span>${esc(n)}</li>`),
    )}</ul>
<div class="sos">${ic.icon('sos', 'c-sos')}<div><p class="st">${esc(t(v.sosTitle))}</p><p class="muted">${esc(t(v.sosDetail))}</p></div></div></div>`;
  throw new Error(`no stage template for workflow ${it.n}`);
}

function workflows(ctx) {
  const t = tr(ctx);
  const { c, ic } = ctx;
  const w = c.workflows;
  const carrierColor = Object.fromEntries(c.offgrid.rungs.map((r) => [r.icon, r.color]));
  const item = (it, kind) => `<article class="wf ${kind}" aria-labelledby="wf-${it.n}">
<div class="wf-copy"><p class="wf-eye"><span class="num">${esc(it.n)}</span><span class="tab">${esc(it.tab)}</span><span class="bn" lang="bn">${esc(
    it.bangla,
  )}</span></p><h3 id="wf-${it.n}">${esc(t(it.title))}</h3><p class="body">${esc(t(it.body))}</p></div>
<div class="stage">${stageCard(ctx, it)}</div>
<div class="works"><span class="lbl">${esc(t(w.worksOver))}</span><ul>${join(
    it.works.map((k) => `<li>${ic.icon(k, cc(carrierColor[k] || 'text/primary'))}${esc(t(w.carriers[k]))}</li>`),
  )}</ul></div>
</article>`;
  const rows = w.items.slice(0, 3).map((it, i) => item(it, i % 2 ? 'wf-row rev' : 'wf-row'));
  const tiles = w.items.slice(3);
  const pairs = [];
  for (let i = 0; i < tiles.length; i += 2) pairs.push(`<div class="pair">${join(tiles.slice(i, i + 2).map((it) => item(it, 'tile')))}</div>`);
  return `<section id="workflows" class="sec" aria-labelledby="workflows-h"><div class="wrap">
<div class="sec-head wide"><p class="eyebrow">${esc(t(w.eyebrow))}</p><h2 id="workflows-h">${esc(t(w.h2))}</h2><p class="lead-s">${esc(t(w.lead))}</p></div>
<div class="wf-list">${join(rows)}${join(pairs)}</div>
</div></section>`;
}

function campus(ctx) {
  const t = tr(ctx);
  const { c, ic } = ctx;
  const k = c.campus;
  return `<section id="campus" class="sec band" aria-labelledby="campus-h"><div class="wrap split wide-r">
<div class="sec-head"><p class="eyebrow">${esc(t(k.eyebrow))}</p><h2 id="campus-h">${esc(t(k.h2))}</h2><p class="lead-s">${esc(t(k.lead))}</p></div>
<ul class="grow">${join(
    k.items.map((it) => `<li><span class="disc">${ic.icon(it.icon)}</span><div><h3>${esc(t(it.title))}</h3><p>${esc(t(it.detail))}</p></div></li>`),
  )}</ul>
</div></section>`;
}

function install(ctx) {
  const t = tr(ctx);
  const { c, ic, view, cfg } = ctx;
  const n = c.install;
  const vars = placeholderVars(view);
  const steps = n.steps.map(
    (s, i) =>
      `<li><span class="sn" aria-hidden="true">${String(i + 1).padStart(2, '0')}</span><span class="sm" aria-hidden="true">${i + 1}</span><div><h3>${esc(
        t(s.title),
      )}</h3><p>${esc(fillText(t(s.detail), vars))}</p></div></li>`,
  );
  const chips = t(n.otherChips);
  const ways = [
    [`obtainium://add/https://github.com/${ctx.repo}`, chips[0]],
    cfg.izzyOnDroidUrl && [cfg.izzyOnDroidUrl, chips[1]],
    cfg.fdroidUrl && [cfg.fdroidUrl, chips[2]],
    ['#campus', chips[3]],
  ].filter(Boolean);
  const sha = view?.sha256;
  return `<section id="install" class="sec" aria-labelledby="install-h"><div class="wrap">
<div class="sec-head wide"><p class="eyebrow">${esc(t(n.eyebrow))}</p><h2 id="install-h">${esc(t(n.h2))}</h2></div>
<ol class="steps">${join(steps)}</ol>
<div class="get">
<div class="get-cta">${ctaButton(ctx, t(c.nav.apkLong))}<p class="abis"><a class="tl" href="${view ? view.v7aUrl : `${repoUrl(ctx)}/releases`}">${esc(
    t(n.otherAbis),
  )}</a></p></div>
<div class="sha"><div class="row sb"><span class="ic-l">${ic.icon('lock')}${esc(t(n.verifyLabel))}</span>${
    sha
      ? `<button type="button" class="copy" data-copy="${sha}" data-copied="${esc(t(n.copied))}" aria-live="polite" hidden>${esc(t(n.copy))}</button>`
      : ''
  }</div><code class="hash"${sha ? ` title="${sha}"` : ''}>${sha ? esc(view.sha256Short) : '—'}</code><p>${esc(t(n.verifyNote))}</p></div>
<div class="ways"><p>${esc(t(n.otherWays))}</p><ul class="chips">${join(ways.map(([h, l]) => `<li><a href="${esc(h)}">${esc(l)}</a></li>`))}</ul></div>
</div>
</div></section>`;
}

function faq(ctx) {
  const t = tr(ctx);
  const { c, ic } = ctx;
  const f = c.faq;
  const lead = esc(t(f.lead)).replace('GitHub', `<a class="tl" href="${repoUrl(ctx)}/issues">GitHub</a>`);
  return `<section id="faq" class="sec raised" aria-labelledby="faq-h"><div class="wrap split">
<div class="sec-head"><p class="eyebrow">${esc(t(f.eyebrow))}</p><h2 id="faq-h">${esc(t(f.h2))}</h2><p class="lead-s">${lead}</p></div>
<div class="faq">${join(
    f.items.map(
      (q, i) => `<details${i === 0 ? ' open' : ''}><summary><span>${esc(t(q.q))}</span>${ic.icon('chevron-down', 'chev')}</summary><p>${esc(t(q.a))}</p></details>`,
    ),
  )}</div>
</div></section>`;
}

export function footer(ctx) {
  const t = tr(ctx);
  const { c, cfg, view } = ctx;
  const L = t(c.footer.links);
  const links = [
    [repoUrl(ctx), L[0]],
    cfg.codebergUrl && [cfg.codebergUrl, L[1]],
    [`${repoUrl(ctx)}/issues/new`, L[2]],
    [ctx.privacy, L[3]],
    [`${repoUrl(ctx)}/blob/main/LICENSE`, L[4]],
  ].filter(Boolean);
  return `<footer class="foot"><div class="wrap fgrid">
<div class="fbrand">${brand(ctx)}<p>${esc(t(c.footer.tagline))}</p></div>
<ul class="flinks">${join(links.map(([h, l]) => `<li><a href="${esc(h)}">${esc(l)}</a></li>`))}</ul>
<div class="fend">${toggle(ctx)}<p class="fmeta">${esc(fillText(t(c.footer.meta), placeholderVars(view)))}</p></div>
</div></footer>`;
}

function shell(ctx, headHtml, bodyHtml) {
  // The sprite is built last so it holds exactly the icons this page used.
  return `${headHtml}
<body>
${ctx.ic.sprite()}
${bodyHtml}
<script>${ctx.js.page}</script>
</body>
</html>
`;
}

export function landingPage(ctx) {
  const t = tr(ctx);
  const body = [nav(ctx), '<main id="main">', hero(ctx), offgrid(ctx), workflows(ctx), campus(ctx), install(ctx), faq(ctx), '</main>', footer(ctx)].join('\n');
  const script = ctx.lang === 'bn' ? ctx.js.langRoot : ctx.js.langEn;
  return shell(ctx, head(ctx, { title: t(ctx.c.meta.title), description: t(ctx.c.meta.description), script }), body);
}

export function privacyPage(ctx) {
  const t = tr(ctx);
  const { c, cfg, view } = ctx;
  const title = t(c.footer.links)[3];
  const fp = String(cfg.signingCertSha256 || '').trim();
  const body = [
    nav(ctx, { links: false }),
    `<main id="main" class="wrap doc">
<h1>${esc(title)}</h1>
<p class="lead-s">${esc(t(c.privacy.body))}</p>
<section id="verify" aria-labelledby="verify-h">
<h2 id="verify-h">${esc(t(extra.verifyTitle))}</h2>
<dl class="kv">
<dt>${esc(t(extra.certLabel))}</dt><dd>${fp ? `<code class="hash">${esc(fp)}</code>` : esc(t(extra.certPending))}</dd>
${view?.sha256 ? `<dt>${esc(t(extra.apkLabel))} · ${esc(view.armAsset)}</dt><dd><code class="hash">${view.sha256}</code></dd>` : ''}
</dl>
<p>${esc(t(extra.certHow))}</p>
<pre><code>apksigner verify --print-certs ${esc(view ? view.armAsset : 'harkara.apk')}</code></pre>
${view ? `<p><a class="tl" href="${view.sumsUrl}">${esc(t(extra.allSums))}</a></p>` : ''}
</section>
</main>`,
    footer(ctx),
  ].join('\n');
  return shell(ctx, head(ctx, { title: `${title} — Harkara`, description: t(c.privacy.body) }), body);
}

export function notFoundPage(ctx, basePath) {
  const body = `<header class="nav wrap">${brand(ctx)}</header>
<main id="main" class="wrap doc">
<h1>404</h1>
<p class="lead-s">${esc(extra.notFound.en)}</p>
<p class="lead-s" lang="bn-Latn">${esc(extra.notFound.bn)}</p>
<p class="back"><a class="more" href="en/" data-lang="en" lang="en">harkara · EN${ctx.ic.icon('chevron-right')}</a><a class="more" href="./" data-lang="bn" lang="bn-Latn">harkara · Banglish${ctx.ic.icon(
    'chevron-right',
  )}</a></p>
</main>`;
  return shell(ctx, head(ctx, { title: '404 — Harkara', description: extra.notFound.en, base: basePath, robots: 'noindex' }), body);
}
