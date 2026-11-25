// Template for the web page for non-users (site/o/ Banglish, site/en/o/ English). The page itself is static;
// tools/site/o/main.js (bundled to assets/o.js) reads the link fragment and renders the message.
import { esc } from '../text.mjs';
import { head, nav, footer } from '../render.mjs';
import { oStrings } from './strings.mjs';

/** Icons main.js draws at run time; listed here so they end up in the page's sprite. */
export const O_ICONS = ['verified', 'report', 'send', 'drop', 'sos', 'check', 'lock', 'khata', 'tasks'];

/** Picks one language out of the bilingual string tree. */
export function pick(node, lang) {
  if (node && typeof node === 'object' && 'bn' in node && 'en' in node) return node[lang];
  if (node && typeof node === 'object') return Object.fromEntries(Object.entries(node).map(([k, v]) => [k, pick(v, lang)]));
  return node;
}

/** Content-Security-Policy for the o/ pages: own files only, WebSockets only to the listed relays. */
export function oCsp(relays) {
  return [
    "default-src 'none'",
    "script-src 'self'",
    "style-src 'self'",
    "img-src 'self' data:",
    "font-src 'self'",
    `connect-src ${relays.join(' ')}`,
    "base-uri 'none'",
    "form-action 'none'",
  ].join('; ');
}

export function oPage(ctx, { relays, siteUrl }) {
  const T = pick(oStrings, ctx.lang);
  const data = {
    lang: ctx.lang,
    relays,
    site: siteUrl,
    home: ctx.home,
    t: pick({ ...oStrings, title: undefined, description: undefined, lead: undefined, noscript: undefined, aboutTitle: undefined, aboutBody: undefined, relaysTitle: undefined }, ctx.lang),
  };
  // `<` cannot close the JSON <script> early once escaped.
  const json = JSON.stringify(data).replace(/</g, '\\u003c');
  const meta = `<meta http-equiv="Content-Security-Policy" content="${esc(oCsp(relays))}">
<meta name="referrer" content="no-referrer">
`;
  const iconSprite = `<div class="o-sprite" aria-hidden="true">${O_ICONS.map((n) => ctx.ic.icon(n)).join('')}</div>`;
  const body = [
    nav(ctx, { links: false }),
    `<main id="main" class="wrap doc o">
<h1>${esc(T.title)}</h1>
<p class="lead-s">${esc(T.lead)}</p>
<noscript><p class="o-msg">${esc(T.noscript)}</p></noscript>
<div id="o-view" class="o-view" aria-live="polite"><p class="o-msg">${esc(T.checking)}</p></div>
${iconSprite}
<section id="o-about" aria-labelledby="o-about-h">
<h2 id="o-about-h">${esc(T.aboutTitle)}</h2>
<p>${esc(T.aboutBody)}</p>
<h3>${esc(T.relaysTitle)}</h3>
<ul class="o-relays">${relays.map((r) => `<li>${esc(r)}</li>`).join('')}</ul>
</section>
</main>`,
    footer(ctx),
  ].join('\n');
  const headHtml = head(ctx, { title: `${T.title} — Harkara`, description: T.description, robots: 'noindex', meta, styles: ['assets/o.css'] });
  return `${headHtml}
<body>
${ctx.ic.sprite()}
${body}
<script type="application/json" id="o-data">${json}</script>
<script src="${ctx.root}assets/o.js" defer></script>
</body>
</html>
`;
}
