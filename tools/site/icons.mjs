// Inline SVG sprite built from design/icons/*.svg and design/brand/logo-mark.svg.
// Pages reference icons with <use href="#i-name">; only icons a page uses end up in its sprite.
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

const STROKE = 'fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"';

export function loadIcons(root) {
  const dir = join(root, 'design/icons');
  const icons = {};
  for (const file of readdirSync(dir).filter((f) => f.endsWith('.svg'))) {
    const src = readFileSync(join(dir, file), 'utf8');
    const inner = src
      .replace(/^[\s\S]*?<svg[^>]*>/, '')
      .replace(/<\/svg>[\s\S]*$/, '')
      .replace(/\s(stroke-width|stroke-linecap|stroke-linejoin|stroke)="[^"]*"/g, '')
      .replace(/<g transform="([^"]*)">/, `<g transform="$1" ${STROKE}>`)
      .replace(/>\s+</g, '><')
      .trim();
    icons[file.replace(/\.svg$/, '')] = inner;
  }
  const logo = readFileSync(join(root, 'design/brand/logo-mark.svg'), 'utf8')
    .replace(/^[\s\S]*?<svg[^>]*>/, '')
    .replace(/<\/svg>[\s\S]*$/, '')
    .replace(/>\s+</g, '><')
    .trim();
  return { icons, logo };
}

/** A per-page icon registry: icon() returns markup and remembers what to put in the sprite. */
export function iconSet({ icons, logo }) {
  const used = new Set();
  let logoUsed = false;
  const icon = (name, cls = '') => {
    if (!icons[name]) throw new Error(`unknown icon "${name}"`);
    used.add(name);
    return `<svg class="i${cls ? ' ' + cls : ''}" aria-hidden="true"><use href="#i-${name}"/></svg>`;
  };
  const mark = (cls = 'mark') => {
    logoUsed = true;
    return `<svg class="${cls}" viewBox="0 0 32 32" aria-hidden="true"><use href="#logo"/></svg>`;
  };
  const sprite = () =>
    '<svg class="sprite" width="0" height="0" aria-hidden="true">' +
    (logoUsed ? `<symbol id="logo" viewBox="0 0 32 32">${logo}</symbol>` : '') +
    [...used]
      .sort()
      .map((n) => `<symbol id="i-${n}" viewBox="0 0 24 24">${icons[n]}</symbol>`)
      .join('') +
    '</svg>';
  return { icon, mark, sprite };
}
