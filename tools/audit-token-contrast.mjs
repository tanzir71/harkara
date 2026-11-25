#!/usr/bin/env node
// WCAG AA normal-text audit. A 3:1 large-text/icon threshold does not excuse
// small caption/hint text, including text/tertiary used throughout the app.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export function contrast(a, b) {
  const luminance = (hex) => {
    if (!/^#[0-9a-f]{6}$/i.test(hex)) throw new Error(`expected opaque RGB token, got ${hex}`);
    const rgb = [1, 3, 5].map((offset) => parseInt(hex.slice(offset, offset + 2), 16) / 255)
      .map((v) => v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
    return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
  };
  const x = luminance(a), y = luminance(b);
  return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
}

export function auditContrast(colors) {
  const checks = [];
  const pairs = ['text/primary', 'text/secondary', 'text/tertiary'].flatMap((fg) =>
    ['bg/canvas', 'bg/surface', 'bg/raised'].map((bg) => [fg, bg]));
  pairs.push(['accent/text', 'accent/soft'], ['text/on-accent', 'accent/fill'], ['text/inverse', 'bg/inverse']);
  for (const mode of ['Day', 'Night']) {
    for (const [foreground, background] of pairs) {
      const ratio = contrast(colors[foreground][mode], colors[background][mode]);
      checks.push({ mode, foreground, background, ratio: Number(ratio.toFixed(3)), passes: ratio >= 4.5 });
    }
  }
  return { threshold: 4.5, checks: checks.length, failures: checks.filter((check) => !check.passes) };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const tokens = JSON.parse(readFileSync(new URL('../design/tokens/tokens.json', import.meta.url), 'utf8'));
  const result = auditContrast(tokens.color);
  console.log(JSON.stringify(result, null, 2));
  if (result.failures.length) process.exitCode = 1;
}
