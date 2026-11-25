#!/usr/bin/env node
// Builds the GitHub Pages site into site/ from design/copy/landing.json, the templates in tools/site/
// and the latest GitHub release. Generated files are git-ignored; site/ after a build is the Pages artifact.
//
// Usage: node tools/build-site.mjs [--offline] [--release-file <fixture.json>]
//   --offline        no network: build the coming-soon state
//   --release-file   use a fixture { release, latestJson?, sha256sums? } instead of the GitHub API
// GITHUB_TOKEN (optional) authenticates the API call.
import { readFileSync, writeFileSync, mkdirSync, rmSync, copyFileSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseArgs } from 'node:util';
import { loadIcons, iconSet } from './site/icons.mjs';
import { loadRelease, releaseView } from './site/release.mjs';
import { landingPage, privacyPage, notFoundPage } from './site/render.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const site = join(root, 'site');
const { values: args } = parseArgs({
  options: { offline: { type: 'boolean', default: false }, 'release-file': { type: 'string' } },
});

/** Every file this script writes. Keep in sync with .gitignore. */
const GENERATED = ['index.html', '404.html', 'latest.json', 'en', 'privacy', 'assets/favicon.svg', 'assets/site.css'];

const readJson = (p) => JSON.parse(readFileSync(p, 'utf8'));
const copy = readJson(join(root, 'design/copy/landing.json'));
const tokens = readJson(join(root, 'design/tokens/tokens.json'));
const cfg = readJson(join(site, 'config.json'));
const repo = cfg.repo || 'tanzir71/harkara';
const [owner, name] = repo.split('/');
const siteUrl = `https://${owner.toLowerCase()}.github.io/${name}/`;
const basePath = new URL(siteUrl).pathname;

// Inline scripts: drop comment lines and indentation; the sources stay readable in tools/site/js/.
const script = (f) =>
  readFileSync(join(root, 'tools/site/js', f), 'utf8')
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('//'))
    .join('\n');
const js = { langRoot: script('lang-root.js'), langEn: script('lang-en.js'), page: script('page.js') };

// Stylesheet: tools/site/site.css minified into site/assets/site.css (comments and layout whitespace only).
const minCss = (css) =>
  css
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/\s+/g, ' ')
    .replace(/\s*([{};,>])\s*/g, '$1')
    .replace(/:\s+/g, ':')
    .replace(/;}/g, '}')
    .trim() + '\n';

const releaseFile = args['release-file'] ? resolve(process.cwd(), args['release-file']) : null;
const { source, latest } = await loadRelease({
  repo,
  offline: args.offline,
  releaseFile,
  token: process.env.GITHUB_TOKEN,
});
const view = releaseView(latest, repo);

for (const f of GENERATED) rmSync(join(site, f), { recursive: true, force: true });

const iconData = loadIcons(root);
const theme = { day: tokens.color['bg/canvas'].Day, night: tokens.color['bg/canvas'].Night };
const rel = (rootPrefix, p) => rootPrefix + p || './';

function context(lang, pagePath, bnPath, enPath) {
  const depth = pagePath.split('/').filter(Boolean).length;
  const r = '../'.repeat(depth);
  return {
    lang,
    c: copy,
    view,
    cfg,
    repo,
    theme,
    js,
    root: r,
    alt: { bn: rel(r, bnPath), en: rel(r, enPath) },
    abs: { bn: siteUrl + bnPath, en: siteUrl + enPath },
    home: rel(r, lang === 'en' ? 'en/' : ''),
    privacy: rel(r, lang === 'en' ? 'en/privacy/' : 'privacy/'),
    ic: iconSet(iconData),
  };
}

const pages = [
  ['index.html', landingPage(context('bn', '', '', 'en/'))],
  ['en/index.html', landingPage(context('en', 'en/', '', 'en/'))],
  ['privacy/index.html', privacyPage(context('bn', 'privacy/', 'privacy/', 'en/privacy/'))],
  ['en/privacy/index.html', privacyPage(context('en', 'en/privacy/', 'privacy/', 'en/privacy/'))],
  ['404.html', notFoundPage({ ...context('en', '', '', 'en/'), abs: null, home: './' }, basePath)],
];

for (const [file, html] of pages) {
  const out = join(site, file);
  mkdirSync(dirname(out), { recursive: true });
  writeFileSync(out, html);
}
copyFileSync(join(root, 'design/brand/logo-mark.svg'), join(site, 'assets/favicon.svg'));
writeFileSync(join(site, 'assets/site.css'), minCss(readFileSync(join(root, 'tools/site/site.css'), 'utf8')));
if (latest) writeFileSync(join(site, 'latest.json'), JSON.stringify(latest, null, 2) + '\n');
if (!existsSync(join(site, '.nojekyll'))) writeFileSync(join(site, '.nojekyll'), '');

console.log(`release: ${source}`);
console.log(
  view
    ? `state: release v${view.version} · ${view.apkMb ?? '?'} MB · sha256 ${view.sha256Short ?? 'unknown'}`
    : 'state: coming soon (no release)',
);
for (const [file] of pages) console.log(`wrote site/${file}`);
console.log('wrote site/assets/site.css, site/assets/favicon.svg');
if (latest) console.log('wrote site/latest.json');
