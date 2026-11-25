# Harkara Tasks

Each milestone: do the work → tick items → `ci.yml` green → `CHANGELOG.md` → push → tag if stated.

## M0 - Repo, licence, CI skeleton

- [x] Copy Nearcast without history, rename ids/packages to `io.github.tanzir71.harkara`, `core-protocol` → `core`.
- [x] LICENSE (AGPL-3.0-or-later), NOTICE, README (Banglish + English), AGENTS.md, TASKS.md, DECISIONS.md, HARDWARE.md.
- [x] Generate `Tokens.kt` and `site/assets/tokens.css` from `design/tokens/tokens.json`; icons → vector drawables.
- [x] `ci.yml` green with the Nearcast tests passing under the new package names.
- [x] Gate: repo public; CI green; `./gradlew assembleDebug` produces an APK.

## M1 - Landing site live

- [x] `site/` for Banglish (`/`) and English (`/en/`), self-hosted fonts, inline icons, privacy pages, `config.json`, coming-soon state.
- [x] `build-site.mjs`, `verify-site.mjs`, Playwright checks, `pages.yml`; Pages enabled.
- [x] Gate: both pages deploy from CI; §7.4 checks pass; mocked non-BD visitor on `/` goes to `/en/`.

## M2 - App shell + first APK (v0.1.0)

- [x] Theme from tokens (Day/Night), fonts, §6 components with screenshot tests.
- [x] Five tabs, Nearby home, connectivity pill on a `ConnectivityState` flow, onboarding without signup, Settings → Modules persisted.
- [x] Panic wipe, copy crash report, update card, share-this-app placeholder.
- [x] Signing keystore + secrets, `release.yml`.
- [x] Gate: `v0.1.0` release has signed APKs + `SHA256SUMS` + `latest.json`; landing shows real version, size, hash.

## M3 - Protocol core (v0.2.0)

- [x] NIP-01 events, BIP-340 Schnorr, NIP-44 v2 + NIP-59/NIP-17, rotating mesh IDs, key derivation, NIP-49 backup (file + QR), binary codec.
- [x] Official NIP vectors; FakeTransport gates on events.
- [x] Peer rate limits (1/min, 10/h, 30/day), NIP-13 PoW, 2-of-3 recovery cards, `docs/PROTOCOL.md` rewritten.
- [x] Gate: core green; app creates, backs up and restores a key in Robolectric (`IdentityManagerTest`, `BackupFlowTest`).

## M4 - Store, relays, Nearby feed online (v0.3.0)

- [x] Room + SQLCipher schema (events, outbox, delivery states, storage budget eviction).
- [x] Relay pool (defaults, NIP-11, backoff, outbox retries, NIP-77 when offered).
- [x] Nearby feed + composer: categories, Stock chips, expiry, PoW, area key, votes, reports, moderation ladder, word filter, offline cache.
- [x] Bundled `loka` data: categories, Dhaka areas, word lists.
- [x] Gate: MockWebServer relay tests prove publish → receive → expiry → hide-after-3-reports; feed renders from Room offline.

## M5 - Chats (v0.4.0)

- [ ] DMs (NIP-17), groups, area channels, "everyone on this Wi-Fi", delivery ticks + carrier glyphs, voice notes, shared cards, "Make it a task".
- [ ] Gate: two simulated users exchange DMs and group messages via mock relay and LAN room; screen 04 screenshot.

## M6 - Off-grid carriers and the ladder (v0.5.0)

- [ ] BLE transport, Wi-Fi Aware, bridge, node mode, carrier ladder, pill + sheet (screen 08), Mesh Saver/Event, disaster mode, radar.
- [ ] Phone-to-phone APK sharing page + QR.
- [ ] Gate: 3-hop mesh delivery, bridge to mock relay, Saved → send when a rung opens.

## M7 - Tasks (v0.6.0)

- [x] NIP-99 offers, task types, signed steps + roles, countersigned accept, handoff token (QR + 6 digits, offline), proof thumbnail, disputes, ratings, reputation, Stock → task.
- [x] Gate: state machine + offline handoff tests; screen 05 screenshot.

## M8 - Khata (v0.7.0)

- [ ] IOU propose/co-sign, claimed vs agreed, settle with TrxID, task receipts, split bill, filters, CSV/JSON export, SMS reminder link.
- [ ] Gate: co-sign over relay, LAN and QR; screen 06 screenshot.

## M9 - Map, pins, location (v0.8.0)

- [ ] `mappack.yml` + maps release, MapLibre + PMTiles behind an interface, layers, pins, GPX/KML/GeoJSON, location card, live share, meet-up arrow, compass, check-ins, trip plan, no-map Lite view.
- [ ] Gate: pin sync, live-share replay protection, check-in tests; style JSON + layer builder unit tests; screen 02 screenshot.

## M10 - SMS, QR, web page for non-users (v0.9.0)

- [ ] Prefilled SMS, SMS payload codec, deep links, `site/o/` verifier page.
- [ ] Direct SMS + volunteer gateway behind default-off switches; restricted-settings guidance.
- [ ] QR transport (multi-QR).
- [ ] Gate: two-part SMS fit; `site/o/` verifies a core-signed payload in Playwright; grey switches default off.

## M11 - Tools, modules, safety (v0.10.0)

- [ ] Tools shelf, handbook (bn + en), file drop, push-to-talk, SOS flow, Lite mode, battery audit tests.
- [ ] Gate: module-off tests show no services/sensors; SOS rate-limit + resolve tests.

## M12 - Hardening and distribution (v0.11.0)

- [ ] Reproducible build settings, fastlane metadata, Obtainium naming, accessibility pass, Bangla complete, APK ≤ 30 MB/ABI, docs updated.
- [ ] Gate: two clean CI builds compared; size + accessibility checks pass.
