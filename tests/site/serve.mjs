#!/usr/bin/env node
// Tiny static server for site/, mounted under /harkara/ like GitHub Pages serves the project site.
// Usage: node tests/site/serve.mjs [port]   (PORT env also works; default 4391)
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { join, resolve, extname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('../../site', import.meta.url)));
const BASE = '/harkara/';
const port = Number(process.argv[2] || process.env.PORT || 4391);
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

async function file(path) {
  try {
    const s = await stat(path);
    return s.isFile() ? path : s.isDirectory() ? 'dir' : null;
  } catch {
    return null;
  }
}

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/' || url.pathname === BASE.slice(0, -1)) {
    res.writeHead(301, { Location: BASE + url.search });
    return res.end();
  }
  let found = null;
  if (url.pathname.startsWith(BASE)) {
    const rel = decodeURIComponent(url.pathname.slice(BASE.length));
    const path = resolve(root, rel);
    if (path === root || path.startsWith(root + sep)) {
      found = await file(path);
      if (found === 'dir') {
        if (!url.pathname.endsWith('/')) {
          res.writeHead(301, { Location: url.pathname + '/' + url.search });
          return res.end();
        }
        found = await file(join(path, 'index.html'));
      }
    }
  }
  const status = found ? 200 : 404;
  const target = found || join(root, '404.html');
  try {
    const body = await readFile(target);
    res.writeHead(status, { 'Content-Type': TYPES[extname(target)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    res.end(req.method === 'HEAD' ? undefined : body);
  } catch {
    res.writeHead(404, { 'Content-Type': 'text/plain' });
    res.end('not found');
  }
}).listen(port, '127.0.0.1', () => console.log(`serving ${root} at http://127.0.0.1:${port}${BASE}`));
