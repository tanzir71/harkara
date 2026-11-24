# AGENTS.md — rules for any agent working on Harkara

Harkara is an Android app for Dhaka that keeps working when the internet doesn't, plus a GitHub Pages landing site that hands out the APK. No server, BDT 0 a month. Positioning: a neighbourhood and campus app for floods, power cuts and everyday life — never "a way around the government".

## Autonomy

1. Never ask the owner a question and never wait for one. Undecided? Use the defaults below; otherwise pick the simplest free option and add one line to `docs/DECISIONS.md`.
2. No pull requests waiting for review. Commit straight to `main` in small commits (`[Mx] message`). End of milestone: checks green → push → tag if stated → next.
3. Only automated tests. Nothing that needs a phone, SIM, Bluetooth hardware, camera, GPS or a person. Hardware-only behaviour goes into `docs/HARDWARE.md` as "unverified on hardware" and never blocks a milestone.
4. No accounts, payments or secrets from the owner. Do not sign up for third-party services or submit to any store.
5. No network in tests. Fake relay over OkHttp `MockWebServer`, `FakeTransport` for the mesh. Only the scheduled `relay-check` and `mappack` workflows touch the internet, and they never gate a release.
6. If something truly needs the owner, write it in `BLOCKERS.md`, skip it, continue.
7. Every commit on `main` builds, passes tests and keeps the site deployable.
8. Grey-area features (direct SMS, volunteer SMS gateway) ship **off by default**, behind switches, tested with fakes.

## Engineering choices (pre-decided)

| Need | Use |
|---|---|
| Schnorr/secp256k1 | `fr.acinq.secp256k1:secp256k1-kmp` (+ `-jni-android`, `-jni-jvm` for tests) |
| ChaCha20, HKDF, HMAC (NIP-44) | BouncyCastle |
| Nostr reference | Amethyst's Quartz (MIT) — read for NIP-17/44/59/49 correctness; port NIP test vectors |
| DB | Room + `net.zetetic:sqlcipher-android`; SQLite FTS for places |
| Relays | OkHttp WebSocket pool; NIP-11 limits; NIP-77 negentropy where offered |
| Mesh | Own BLE dual-role transport (bitchat-android, MIT, as reference) + Wi-Fi Aware |
| LAN | Android NSD (mDNS), small embedded HTTP server, LocalSend v2 |
| Maps | MapLibre Native + PMTiles (Protomaps), local HTTP handler if `pmtiles://` is unsupported |
| Location codes | Open Location Code + own geohash |
| QR | ZXing core |
| Prayer times | Adhan (Kotlin), Karachi method, Hanafi Asr |
| Voice | MediaRecorder Opus/OGG on API 29+, AAC below |

- Relays: start with `wss://relay.damus.io`, `wss://relay.primal.net`, `wss://relay.nostr.net` + four picked by `tools/relay-check.mjs`; `relay-check.yml` rewrites `data/default-relays.json` weekly.
- Map pack: `mappack.yml` extracts Dhaka bbox `90.30,23.65,90.55,23.92` (z15 + z13 schematic) to a `maps-YYYYMM` release.
- App language: `values/` + `values-bn/`, follow system, in-app override.
- Updates: `https://tanzir71.github.io/harkara/latest.json`, at most once a day.
- Grey areas: "Send SMS directly" (data SMS, spends credit) and "Volunteer SMS gateway" (≤ 100/day, contacts or one group) — both off by default. Default SMS rung = prefilled SMS app, no permission. SMS payload = readable Latin line + Base64url payload; Bangla only inside the payload.
- Licence: AGPL-3.0-or-later. Borrow only from MIT, BSD, Apache-2.0, MPL-2.0, LGPL, GPL-3.0, AGPL-3.0 code; never GPL-2.0-only. List every borrowed file in `NOTICE` and keep original headers.
- Design: `design/tokens/tokens.json` is the only source of colours, spacing, radii and type. Don't invent colours. Regenerate with `node tools/build-tokens.mjs` and `node tools/icons-to-vectors.mjs`.

## Testing policy

- `core/`: JUnit 5. NIP test vectors (NIP-01, BIP-340, NIP-44 v2, NIP-49), codec round trips (JSON ⇄ binary ⇄ SMS), FakeTransport gates (3-hop relay, dedupe storm, fragment reassembly, 20-node topology ≥ 95% delivery, partition heal), rate limits, PoW, moderation ladder, task state machine, IOU co-sign rules, handoff token single use, expiry, storage budget eviction.
- `app/`: Robolectric for ViewModels, Room (in-memory), WorkManager (`WorkManagerTestInitHelper`), permission gate, foreground service lifecycle, BLE adapter against fakes, SMS composer intents, deep links, QR, Compose UI tests and Roborazzi screenshot tests (Day + Night).
- Relays: OkHttp `MockWebServer` as a NIP-01 relay (REQ/EVENT/EOSE/OK/CLOSED, NIP-11).
- Never a test that needs hardware, a SIM, real relays or a person.

## Commands

```
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:verifyRoborazziDebug     # screenshot diffs (record: recordRoborazziDebug)
node tools/build-tokens.mjs --check && node tools/icons-to-vectors.mjs --check
node tools/build-site.mjs && node tools/verify-site.mjs
```
