# Site fonts

Self-hosted woff2 subsets, all SIL Open Font License 1.1 (licence texts next to them). No font CDN is used.

| File | Family / weight | Source | Subset |
|---|---|---|---|
| `geist-latin-{400,500,600}-normal.woff2` | Geist 400/500/600 (UI) | npm `geist` 1.x, `dist/fonts/geist-sans/Geist-{Regular,Medium,SemiBold}.ttf` | Latin, cut with `python -m fontTools.subset --unicodes=<fontsource latin range> --layout-features=* --flavor=woff2` |
| `anek-bangla-bengali-{500,600}-normal.woff2` | Anek Bangla 500/600 (Bangla) | npm `@fontsource/anek-bangla` 5.x, `files/` | Fontsource `bengali` subset, as shipped |
| `jetbrains-mono-latin-500-normal.woff2` | JetBrains Mono 500 (codes, eyebrows, numbers) | npm `@fontsource/jetbrains-mono` 5.x, `files/` | Fontsource `latin` subset, as shipped |

`unicode-range` in `tools/site/site.css` matches each subset, so a page only downloads what its text needs.
Licences: `OFL-Geist.txt`, `OFL-AnekBangla.txt`, `OFL-JetBrainsMono.txt`.
