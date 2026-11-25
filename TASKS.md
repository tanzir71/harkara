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

- [x] DMs (NIP-17), groups, area channels, "everyone on this Wi-Fi", delivery ticks + carrier glyphs, voice notes, shared cards, "Make it a task".
- [x] Receipts (kind 3479), Plus Codes, location card (exact / ~100 m / area), Wi-Fi room server + NSD discovery, name asked at first send.
- [x] Gate: two simulated users exchange DMs and group messages via mock relay and LAN room (`ChatRelayTest`, `ChatLanTest`); screen 04 screenshot (`ChatScreenshotTest`).

## M6 - Off-grid carriers and the ladder (v0.5.0)

- [x] BLE transport, Wi-Fi Aware, bridge, node mode, carrier ladder, pill + sheet (screen 08), Mesh Saver/Event, disaster mode, radar.
- [x] Phone-to-phone APK sharing page + QR.
- [x] Gate: three-hop fake-radio bridge to mock relay, persisted Saved event sends when a fake radio connects, fake-GATT queue/link tests; core 213/app 147 tests and screenshot verification pass.

## M7 - Tasks (v0.6.0)

- [x] NIP-99 offers, task types, signed steps + roles, countersigned accept, handoff token (QR + 6 digits, offline), proof thumbnail, disputes, ratings, reputation, Stock → task.
- [x] Gate: state machine + offline handoff tests; screen 05 screenshot.

## M8 - Khata (v0.7.0)

- [x] IOU propose/co-sign, claimed vs agreed, settle with TrxID, task receipts, split bill, filters, CSV/JSON export, SMS reminder link.
- [x] Gate: co-sign over relay, LAN and QR; screen 06 screenshot.

## M9 - Map, pins, location (v0.8.0)

- [x] `mappack.yml` + maps-release pipeline, MapLibre + PMTiles behind an interface, layers, pins, GPX/KML/GeoJSON, location card, live share, meet-up arrow, compass, check-ins, trip plan, no-map Lite view. Data refresh remains scheduled/manual, not an implementation gate.
- [x] Map pack download (SHA-256 checked) and phone-to-phone import; PMTiles reader, Open Location Code (official vectors), APK ≤ 30 MB per ABI with MapLibre.
- [x] Gate: pin sync, live-share replay protection, check-in + overdue tests; style JSON + layer builder unit tests; map screen Compose test on a fake surface; screen 02 screenshot.
- [x] Integrated local gate: 283 core and 273 app tests, screenshot verification, lint and debug assembly; nine migration fixtures; atomic pack installs, terminal panic reset, encrypted LAN safety/live points, permission recovery and local FTS search.

## M10 - SMS, QR, web page for non-users (v0.9.0)

- [x] Prefilled SMS (`SmsComposer`, ACTION_SENDTO smsto:), SMS payload codec (`core/wire/SmsCodec`: GSM-7 line + HK1 payload, 8-byte key refs, Text8 Bangla), SMS-eligible kinds incl. SMS DM 3480, deep links (`harkara:`, `…/harkara/o/#`, shared text) with `DeepLinkRouter` + `LinkInbox`, `site/o/` verifier page (IOU confirm with a throwaway key, follow a task, area feed; CSP-pinned relays).
- [x] Direct SMS (data SMS, port 7471) + volunteer gateway (100/day, members only) behind default-off switches, disabled receivers, permission asked only on switch-on; restricted-settings guidance screen.
- [x] QR transport (`harkara:e:` single, `harkara:q:` multi-QR with reassembly), share-as-QR sheet and paste import (camera scanning is hardware).
- [x] Gate: every SMS-eligible type fits two parts (`SmsBudgetTest`); `site/o/` verifies a core-signed payload in Playwright (`tests/site/o.spec.mjs`); grey switches default off (`GreyAreaSmsTest`).
- [x] Integrated gate with M6/LAN tools: 246 core and 179 app tests, offline Robolectric runtime, screenshots/lint/debug APK; 40 browser checks in both fixture-release and missing-release states.

## M11 - Tools, modules, safety (v0.10.0)

- [x] Handbook (bn + en, 42 sections), Qibla + prayer times (Karachi/Hanafi), torch/strobe/Morse SOS/siren, SOS flow (3-s hold, Call 999 first, 30-min limit, resolve), emergency-alert sound switch, Lite mode 60-min checks.
- [x] File drop (LocalSend v2) and push-to-talk over the LAN, with explicit consent, bounded streams and lifecycle teardown; compass/QR tools land with M9/M10.
- [x] Battery audit: with default modules no service, GPS or relay runs in the background (Robolectric).
- [x] Gate: module-off tests show no services/sensors; SOS rate-limit + resolve tests.

## M12 - Hardening and distribution (v0.11.0)

- [x] Reproducible settings, fastlane metadata, Obtainium naming, Bangla parity and docs; 39 extended accessibility cases cover 78 Day/Night states at 1.3× font scale; normal-text AA contrast passes 24 checks.
- [x] Map-enabled unsigned ABI APKs: arm64 23,050,590 bytes; ARMv7 22,013,478 bytes, both below 30,000,000 bytes. Size is enforced before publication.
- [x] Historical independent clean-build comparison: v0.5.0 MATCH for both ABI splits and universal. Final-tag comparison remains to be recorded.
- [ ] Gate: two clean CI builds compared; size + accessibility checks pass.
