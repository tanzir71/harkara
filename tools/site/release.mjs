// Release data for the landing site: read the latest GitHub release (or a fixture) and turn
// its latest.json into the values baked into the pages. Shared by build-site and verify-site.
import { readFileSync } from 'node:fs';

export const ABIS = ['arm64-v8a', 'armeabi-v7a', 'universal'];
export const assetName = (version, abi) => `harkara-${version}-${abi}.apk`;
export const versionCode = (v) => {
  const [maj = 0, min = 0, pat = 0] = String(v).replace(/^v/, '').split(/[.-]/).map((n) => parseInt(n, 10) || 0);
  return maj * 10000 + min * 100 + pat;
};

/** Bytes -> "24" or "8.5" (MiB, whole numbers from 10 up). */
export function apkMb(bytes) {
  const v = bytes / 1048576;
  return v >= 10 ? String(Math.round(v)) : String(Math.round(v * 10) / 10);
}

/** "9f2c 41aa 0b7d e5c3 … 8a61 3e10" */
export function shortSha(hex) {
  const groups = (s) => s.match(/.{4}/g).join(' ');
  return `${groups(hex.slice(0, 16))} … ${groups(hex.slice(-8))}`;
}

const isSha = (s) => typeof s === 'string' && /^[0-9a-f]{64}$/i.test(s);

/** Values the templates need, or null when there is no release yet. */
export function releaseView(latest, repo) {
  if (!latest || !latest.version) return null;
  const version = String(latest.version).replace(/^v/, '');
  const apk = (abi) => (latest.apks || []).find((a) => a.abi === abi);
  const arm = apk('arm64-v8a') || apk('universal');
  const armAbi = apk('arm64-v8a') ? 'arm64-v8a' : 'universal';
  const download = (abi) => `https://github.com/${repo}/releases/latest/download/${assetName(version, abi)}`;
  const sha256 = arm && isSha(arm.sha256) ? arm.sha256.toLowerCase() : null;
  return {
    version,
    apkMb: arm && Number(arm.bytes) > 0 ? apkMb(Number(arm.bytes)) : null,
    sha256,
    sha256Short: sha256 ? shortSha(sha256) : null,
    armAsset: assetName(version, armAbi),
    arm64Url: download(armAbi),
    v7aUrl: apk('armeabi-v7a') ? download('armeabi-v7a') : `https://github.com/${repo}/releases/latest`,
    sumsUrl: `https://github.com/${repo}/releases/latest/download/SHA256SUMS`,
    published: latest.published || null,
  };
}

/** Placeholder values for fillText(). */
export const placeholderVars = (view) =>
  view ? { VERSION: view.version, APK_MB: view.apkMb, SHA256_SHORT: view.sha256Short } : {};

export function parseSums(text) {
  const out = {};
  for (const line of String(text || '').split(/\r?\n/)) {
    const m = line.trim().match(/^([0-9a-f]{64})\s+\*?(.+)$/i);
    if (m) out[m[2].trim()] = m[1].toLowerCase();
  }
  return out;
}

/** Build (or complete) a latest.json document from the API release + SHA256SUMS. */
export function completeLatest(release, latest, sums) {
  const version = String(latest?.version || release.tag_name || '').replace(/^v/, '');
  const fromAssets = (release.assets || [])
    .map((a) => {
      const abi = a.name.match(/-(arm64-v8a|armeabi-v7a|universal|x86_64|x86)\.apk$/)?.[1];
      return abi ? { abi, url: a.browser_download_url, bytes: a.size, sha256: sums[a.name] || null } : null;
    })
    .filter(Boolean);
  const apks = (latest?.apks?.length ? latest.apks : fromAssets).map((a) => {
    const asset = fromAssets.find((x) => x.abi === a.abi) || {};
    return {
      abi: a.abi,
      url: a.url || asset.url,
      bytes: Number(a.bytes) || asset.bytes || 0,
      sha256: (isSha(a.sha256) ? a.sha256 : asset.sha256) || null,
    };
  });
  return {
    ...(latest || {}),
    version,
    versionCode: latest?.versionCode ?? versionCode(version),
    published: latest?.published || release.published_at || null,
    apks,
    notes: latest?.notes ?? release.body ?? '',
  };
}

async function fetchText(url, headers, log) {
  try {
    const res = await fetch(url, { headers, redirect: 'follow', signal: AbortSignal.timeout(20000) });
    if (!res.ok) {
      log(`  ${url} -> HTTP ${res.status}`);
      return null;
    }
    return await res.text();
  } catch (e) {
    log(`  ${url} -> ${e.cause?.code || e.name}: ${e.message}`);
    return null;
  }
}

/**
 * Returns { source, latest } where latest is a latest.json document or null (no release).
 * Modes: offline, a fixture file ({ release, latestJson?, sha256sums? }), or the GitHub API.
 * Throws on unexpected API errors (rate limit, 5xx) so CI never deploys a downgraded page.
 */
export async function loadRelease({ repo, offline, releaseFile, token, log = console.log }) {
  if (releaseFile) {
    const fx = JSON.parse(readFileSync(releaseFile, 'utf8'));
    if (!fx.release) return { source: `fixture ${releaseFile} (no release)`, latest: null };
    return {
      source: `fixture ${releaseFile}`,
      latest: completeLatest(fx.release, fx.latestJson || null, parseSums(fx.sha256sums)),
    };
  }
  if (offline) return { source: 'offline', latest: null };

  const api = `https://api.github.com/repos/${repo}/releases/latest`;
  const headers = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'harkara-build-site',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  if (token) headers.Authorization = `Bearer ${token}`;
  let res;
  try {
    res = await fetch(api, { headers, signal: AbortSignal.timeout(20000) });
  } catch (e) {
    log(`warning: GitHub API unreachable (${e.cause?.code || e.name}); building the coming-soon page`);
    return { source: 'network error', latest: null };
  }
  if (res.status === 404) return { source: `${api} (404, no release yet)`, latest: null };
  if (!res.ok) throw new Error(`GitHub API ${api} answered HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
  const release = await res.json();

  const plain = { 'User-Agent': 'harkara-build-site' };
  const asset = (name) => (release.assets || []).find((a) => a.name === name);
  let latest = null;
  if (asset('latest.json')) {
    const txt = await fetchText(asset('latest.json').browser_download_url, plain, log);
    try {
      latest = txt ? JSON.parse(txt) : null;
    } catch {
      log('warning: latest.json in the release is not valid JSON; rebuilding it from the assets');
    }
  }
  let sums = {};
  const needSums = !latest?.apks?.length || latest.apks.some((a) => !isSha(a.sha256));
  if (needSums && asset('SHA256SUMS')) sums = parseSums(await fetchText(asset('SHA256SUMS').browser_download_url, plain, log));
  return { source: `${api} (${release.tag_name})`, latest: completeLatest(release, latest, sums) };
}
