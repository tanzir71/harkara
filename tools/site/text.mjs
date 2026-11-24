// Text helpers shared by tools/build-site.mjs and tools/verify-site.mjs.

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' };
export const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ESC[c]);

/**
 * Fills {{KEY}} placeholders from `vars`. Placeholders without a value are removed
 * gracefully: ", {{APK_MB}} MB" disappears, and any " · "-separated segment that still
 * holds a placeholder is dropped ("Made in Dhaka · v{{VERSION}} · No ads" -> "Made in Dhaka · No ads").
 */
export function fillText(s, vars = {}) {
  let out = String(s).replace(/\{\{(\w+)\}\}/g, (m, k) => (vars[k] != null && vars[k] !== '' ? String(vars[k]) : m));
  if (!out.includes('{{')) return out;
  out = out.replace(/,\s*\{\{\w+\}\}\s*MB/g, '');
  out = out
    .split(' · ')
    .filter((part) => !part.includes('{{'))
    .join(' · ');
  return out.replace(/\{\{\w+\}\}/g, '').replace(/\s{2,}/g, ' ').trim();
}

const NAMED = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', hellip: '…', mdash: '—', ndash: '–' };
export const decodeEntities = (s) =>
  s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, e) => {
    if (e[0] === '#') return String.fromCodePoint(e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : Number(e.slice(1)));
    return NAMED[e.toLowerCase()] ?? m;
  });

export const normSpace = (s) => String(s).replace(/[\s\u00a0]+/g, ' ').trim();
