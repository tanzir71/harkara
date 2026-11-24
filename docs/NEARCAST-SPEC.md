# NEARCAST — Hybrid Mesh Social App (Android, Kotlin Native)

> **Spec for autonomous agent (Codex) execution.** Build a complete, working Android app from this document. Work in a loop: pick the next unchecked task, implement, test, commit, repeat. Never stop to ask questions — every ambiguity has a default decision in this spec or in §12 (Autonomy Rules).

---

## 1. Vision

Nearcast is an offline-first social + messaging app. Think **BitChat's Bluetooth mesh chat** fused with a **location-based social feed** (Snapchat/Instagram-style posts pinned to places), with **hybrid sync**: everything works with zero internet over BLE mesh, and opportunistically syncs through the internet when any peer has connectivity. The name is the product: you broadcast to people **near** you.

Core promises:

1. **No accounts, no servers required.** Cryptographic identity generated on-device. Internet sync is optional and additive.
2. **Messages and posts flow peer-to-peer** over BLE mesh (multi-hop, store-and-forward), and over the internet when available.
3. **Location-scoped social feed.** Posts are tagged to a geohash; you see a feed of posts near you, propagated by mesh and/or internet relay.
4. **Off-grid utility**: SOS beacons (guard-railed), local bulletin boards, event announcements, offline peer map, disaster mode.

### Reference codebase

`../bitchat-android` (sibling folder) is a working, production BLE mesh messenger (Kotlin + Jetpack Compose, X25519/AES-256-GCM, TTL relay, store & forward, fragmentation). **Study it before writing transport code.** Do not copy files wholesale (it's public domain so reuse is legal, but our architecture differs); use it to answer "how do I do X on Android BLE" questions: GATT server+client dual role, MTU-sized chunking, adaptive duty-cycle scanning, background service, permission flows, Compose chat UI patterns.

Key reference paths:
- `app/src/main/java/com/bitchat/android/mesh/` — BLE connection manager, peer manager, fragmentation, relay logic
- `app/src/main/java/com/bitchat/android/protocol/` + `model/` — binary packet encoding, TTL, dedupe
- `app/src/main/java/com/bitchat/android/crypto` / `noise/` — encryption/session patterns
- `docs/` — protocol whitepaper

---

## 2. Stack (fixed — do not substitute)

| Layer | Choice |
|---|---|
| Language | Kotlin 2.x, JDK 17 |
| UI | Jetpack Compose + Material 3 |
| Min/Target SDK | minSdk 26, targetSdk 35 |
| DI | Manual (constructor injection); no Hilt/Dagger (keeps builds simple for agent loop) |
| Local DB | Room + SQLite |
| Serialization | Custom binary for mesh packets; kotlinx.serialization JSON for internet sync |
| Crypto | X25519 + Ed25519 + AES-256-GCM via `org.bouncycastle` (or libsodium bindings if BC blocks) |
| Concurrency | Kotlin coroutines + Flow |
| Networking (internet) | OkHttp + WebSocket |
| Location | FusedLocationProvider (play-services-location); fall back to LocationManager |
| Maps (offline peer map) | osmdroid (OSM tiles, offline-cacheable). No Google Maps SDK. |
| Sync backend (optional component) | Python 3.11+, FastAPI + uvicorn, SQLite (dev) / Postgres (prod), single `server/` folder in repo |
| Build | Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`) |
| Tests | JUnit5 + kotlinx-coroutines-test for pure Kotlin; Robolectric for Android-touching units; NO instrumentation tests (no emulator in sandbox) |

**Repo location:** create as a new top-level folder `nearcast/` (sibling of `bitchat-android/`). Structure:

```
nearcast/
  app/                 # Android app module
  core-protocol/       # PURE KOTLIN/JVM module: packets, routing, crypto, sync logic (no Android imports)
  server/              # FastAPI relay/sync server (optional runtime component)
  docs/PROTOCOL.md     # generated as you build
  TASKS.md             # living checklist (see §13)
  BLOCKERS.md          # blocker log (see §12)
```

**`core-protocol` as a pure JVM module is mandatory.** All mesh logic (packet encode/decode, TTL/dedupe, store-and-forward queues, feed replication rules, CRDT merge, crypto envelopes) lives there and is fully unit-testable without Android, an emulator, or Bluetooth hardware. The `app` module only implements thin adapters: `BleTransport`, `InternetTransport`, `LocationProvider` — each behind an interface defined in `core-protocol`.

---

## 3. Identity & Crypto

- On first launch generate: Ed25519 signing keypair (long-term identity), X25519 keypair (key agreement). Store in Android Keystore-encrypted prefs.
- Public identity = `NearID` = base32(SHA-256(ed25519_pub)[0..15]). Display name is a free-text label attached to identity announcements (not unique, not verified).
- Private DMs: X25519 ECDH → HKDF → AES-256-GCM per-message, with ephemeral keys per session (simplified Noise XX is acceptable; mirror bitchat's approach).
- Public posts/broadcasts: signed (Ed25519) but not encrypted.
- Panic wipe: settings action (and triple-tap logo, like bitchat) clears DB + keys.

---

## 4. Mesh Protocol (core-protocol module)

Binary packet, fixed header:

```
version(1) | type(1) | ttl(1) | flags(1) | msgId(8: random64) |
senderId(16) | timestamp(8) | payloadLen(2) | payload(...) | signature(64, if flags.SIGNED)
```

- **Types:** ANNOUNCE(1), CHAT_BROADCAST(2), DM(3), POST(4), POST_REQUEST(5), SOS(6), ACK(7), FRAGMENT(8), SYNC_DIGEST(9), SYNC_PULL(10).
- **Relay:** decrement TTL (default 7), forward to all connected peers except source; dedupe via LRU set of msgIds (capacity 10k, 30-min expiry).
- **Congestion control:** per-peer token bucket (default 20 msg/s), jittered relay delay 20–120 ms, drop-lowest-priority-first queue (SOS > DM > CHAT > POST > SYNC).
- **Fragmentation:** payloads > (negotiated MTU − header) split into FRAGMENT packets `{origMsgId, index, count, chunk}`; reassembly with 60 s timeout.
- **Store & forward:** undelivered DMs and fresh posts cached (Room) and offered to newly connected peers via SYNC_DIGEST (list of msgIds + geohash prefixes) → peer responds SYNC_PULL with wanted ids.

All of the above gets exhaustive JVM unit tests, including a **`FakeTransport`** that wires N in-memory nodes into arbitrary topologies to test multi-hop relay, TTL expiry, dedupe, fragmentation, partition-heal sync. This simulated-mesh test harness is a first-class deliverable — it is how correctness is proven without hardware.

---

## 5. Transports

### 5.1 BLE mesh (primary, offline)
- Dual role: GATT server (advertise service UUID, notify characteristic) + client (scan, connect, subscribe). Follow bitchat-android's `BluetoothConnectionManager` patterns, including MTU negotiation (request 512, handle 23-byte floor) and adaptive scan duty cycle by battery level.
- Foreground service with persistent notification keeps mesh alive; respect Doze via `PARTIAL_WAKE_LOCK` sparingly.
- Runtime permissions flow: BLUETOOTH_SCAN/CONNECT/ADVERTISE (API 31+), ACCESS_FINE_LOCATION (≤30 and for geo features), POST_NOTIFICATIONS (33+). Build a single `PermissionGate` composable that explains + requests; app must degrade gracefully when denied (internet-only mode).

### 5.2 Internet sync (opportunistic)
- WebSocket client to the `server/` relay. Auth-less: clients subscribe to geohash prefixes + their own NearID inbox.
- Any node with internet becomes a **bridge**: it uploads its store-and-forward cache (posts, public msgs, queued DMs addressed via relay inbox) and downloads items matching its subscribed geohashes, then re-broadcasts novel items onto local mesh. This is the "hybrid sync" heart — implement as `BridgeEngine` in core-protocol with transports injected.
- Server (FastAPI): endpoints `WS /ws` (pub/sub by geohash prefix + inbox), `POST /items` bulk upload, `GET /items?geohash=&since=` backfill; SQLite storage, item TTL 7 days, size cap per item 64 KB. ~500 lines; keep it boring. Include `server/tests/` with pytest + `httpx` websocket tests.

### 5.3 Wi-Fi Direct (stretch, Phase 8 only)
Higher-throughput local transport for media. Do not start until all prior phases are green.

---

## 6. Social Layer

### 6.1 Posts & feed
- Post = `{postId, author, displayName, text ≤ 512 chars, optional image ref, geohash(6 chars ≈ 1.2 km), createdAt, expiresAt (default 72 h), signature}`.
- **Feed query:** posts whose geohash shares a prefix with the user's current geohash (configurable radius: 4/5/6-char prefix ⇒ city/neighborhood/block), newest first, expired hidden.
- Replication rule: nodes relay and cache posts within their subscribed geohash prefixes; foreign-geohash posts relay (TTL-bounded) but aren't cached.
- Reactions & replies: lightweight `POST` subtype referencing parent postId; counts merge via G-Counter-style CRDT (per-author max, since each reaction is signed and idempotent by (postId, author, kind)).
- Media: images only in v1. Compress to WebP ≤ 48 KB for mesh transport (fragmented); full-res (≤ 512 KB) only via internet path. Show blur-hash placeholder until media arrives.

### 6.2 Channels & chat
- Public local broadcast chat (like bitchat's mesh channel) + named channels (`#market`, `#event-xyz`) joined by hashtag; channel = filter over CHAT_BROADCAST with channel field in payload.
- 1:1 encrypted DMs with delivery ACKs and store-and-forward.

### 6.3 Off-grid utilities (each small, additive)
- **Bulletin board:** pinned post type (flag) that stays at top of local feed until expiry — e.g., "water available at school", "road closed".
- **Events:** post subtype with startTime/place; feed tab groups upcoming events nearby.
- **Peer map:** osmdroid map showing approximate positions (geohash-truncated, never exact GPS) of peers who opted in to location sharing; offline tile cache.
- **Disaster mode:** one toggle that (a) maxes mesh duty cycle, (b) auto-subscribes to wider geohash, (c) surfaces SOS feed tab, (d) shows battery-saver darker UI.

### 6.4 SOS (build LAST among features — Phase 7)
Deliberately low priority and abuse-resistant:
- Sending requires a 3-second press-and-hold + confirm dialog; message auto-includes coarse geohash + optional note; highest relay priority; distinctive UI + notification on receivers.
- Abuse guards: max 1 active SOS per identity per 30 min (enforced locally at relay time — nodes drop violating SOS from same sender); "resolve/false alarm" follow-up packet; receivers can mute a sender's SOS; SOS never triggers sounds unless user enabled "emergency alerts".
- Cancel/resolve flow is mandatory in the same phase as send.

---

## 7. UI (Compose, Material 3)

Bottom nav: **Feed · Chat · Map · Compose(+) · Settings**. Dark theme default; clean, modern, not terminal-styled (differentiate from bitchat).

- Feed: card list (author, time-ago, distance bucket like "~500 m", text, image, reactions); pull-to-refresh triggers bridge sync; radius selector chip row.
- Chat: channel list + conversation screens; delivery states (pending/mesh-delivered/acked); peer list sheet showing hop count + signal.
- Compose: text, attach image (Photo Picker), channel or feed target, geohash preview ("casting to ~1 km area"), expiry picker.
- Map: peers + pinned bulletins; offline tile notice.
- Settings: identity card (NearID QR), display name, location sharing opt-in, mesh duty controls, disaster mode, data/retention, panic wipe.
- Onboarding: 3 screens (what it is → permissions → display name). Must be skippable except permissions gate.

State: one ViewModel per screen backed by Room Flows; no global god-object. Previews for every composable (previews compile-time-verify UI without emulator).

---

## 8. Data (Room)

Tables: `identities(peers seen)`, `messages`, `posts`, `reactions`, `channels`, `outbox`(store&forward queue with attempt/backoff columns), `dedupe_seen`, `settings`. All timestamps epoch-millis UTC. Retention job (WorkManager): purge expired posts, seen-set older than 30 min, delivered outbox rows.

---

## 9. Phases & Acceptance Gates

Execute strictly in order. A phase is DONE only when its gate passes; then commit with message `phase(N): <name> green` and update TASKS.md.

| # | Phase | Gate (must be machine-checkable) |
|---|---|---|
| 0 | Scaffold: modules, version catalog, CI-less local build, empty Compose shell, Room setup | `./gradlew :app:assembleDebug :core-protocol:test` passes |
| 1 | core-protocol: packets, TTL/dedupe, fragmentation, FakeTransport mesh simulator | JVM tests: 3-hop relay, dedupe storm, fragment reassembly, 20-node random topology delivers broadcast ≥ 95% |
| 2 | Identity + crypto envelopes (sign/verify, DM encrypt/decrypt) | Round-trip + tamper tests green |
| 3 | BLE transport adapter + foreground service + permission gate + basic broadcast chat UI | assembleDebug green; Robolectric tests for service lifecycle + permission gate logic; chat works between two FakeTransport nodes end-to-end through the real ViewModel layer |
| 4 | Posts, feed data model, geohash lib, store & forward, feed UI | Simulator test: node A posts in geohash X, node B (same prefix) receives after partition heal; feed UI renders from Room in Robolectric |
| 5 | Internet sync: server/ + BridgeEngine + hybrid re-broadcast | pytest server suite green; JVM test: mesh-only node's post reaches internet-only node via a bridge node (all FakeTransports + in-memory server stub) |
| 6 | Media (WebP compress, fragmentation transport, blur-hash), reactions/replies, channels, DMs with ACK | Unit tests for compression bounds, CRDT reaction merge, ACK state machine |
| 7 | SOS + disaster mode + bulletin/events + peer map | Rate-limit and resolve-flow unit tests; map screen Robolectric smoke test |
| 8 | Polish: battery adaptive duty, retention jobs, onboarding, settings, panic wipe; optional Wi-Fi Direct | Full test suite green; `./gradlew lint` no errors; README with screenshots-TODO markers |

---

## 10. Sandbox & Blocker Playbook (READ FIRST, REVISIT WHEN STUCK)

These are the known ways an agent loop dies on this project. Pre-committed solutions:

1. **No emulator / no Bluetooth hardware.** Never write instrumentation (`androidTest`) tests. All logic in `core-protocol` (JVM). Android-side classes get Robolectric tests only for lifecycle/DB/permission logic. BLE code is verified by interface contract + FakeTransport; note untested-on-hardware areas in `docs/HARDWARE_TEST_PLAN.md` instead of blocking.
2. **Gradle needs network on first sync.** Pin ALL versions in `libs.versions.toml`. If dependency resolution fails: retry once, then check for a locally cached alternative version, then (last resort) remove the dependency and hand-roll (e.g., write own geohash encoder ~60 lines instead of pulling a lib — actually: **hand-roll geohash from the start**, it's trivial). Prefer stdlib/AndroidX over third-party everywhere.
3. **Android SDK missing in sandbox.** If `assembleDebug` cannot run because SDK packages won't download: set `sdk.dir` via `local.properties` to whatever SDK exists (`sdkmanager --list_installed`); accept licenses non-interactively (`yes | sdkmanager --licenses`); if truly no SDK, keep developing `core-protocol` + `server/` (both JVM/Python, fully buildable) and log the app-build blocker in BLOCKERS.md — do not idle.
4. **play-services-location unavailable offline** → fall back to `android.location.LocationManager` behind the `LocationProvider` interface; the interface exists precisely so this swap is one file.
5. **BouncyCastle classloading conflicts** (duplicate provider on Android): register provider defensively (`removeProvider` then `addProvider`), or switch to `com.google.crypto.tink` if BC fails at runtime tests — the crypto API is wrapped in `CryptoBox` interface so the swap is contained.
6. **Robolectric downloads android-all jars on first run.** If blocked, mark those tests `@Ignore` with reason string `SANDBOX-NET`, keep JVM tests as the gate, note in BLOCKERS.md.
7. **Compose preview/lint OOM** → add `org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g` to `gradle.properties` at scaffold time (do it preemptively).
8. **Long Gradle runs timing out the loop** → always run scoped tasks (`:core-protocol:test` not `test`), keep `org.gradle.caching=true`, `org.gradle.configuration-cache=true` from day one.
9. **Server port conflicts in tests** → bind port 0 (ephemeral) in all pytest fixtures.
10. **Git**: commit after every green gate and every ~30 min of work regardless. Never rebase/force-push. If the working tree gets corrupted, `git stash` and re-apply incrementally.

## 11. Quality Bar

- Every public function in `core-protocol` has KDoc.
- No TODO left without a TASKS.md entry.
- Protocol documented in `docs/PROTOCOL.md` as it's built (packet tables, state machines) — update in the same commit as the code.
- Deterministic tests only: inject `Clock` and `Random` everywhere; no `Thread.sleep` in tests (use virtual time from `runTest`).

## 12. Autonomy Rules

1. **Never ask the user anything.** If a decision is needed, choose the option that (a) matches this spec's spirit, (b) is simpler, (c) is more testable — in that order. Record the decision in `docs/DECISIONS.md` (one line: date, decision, why).
2. **Never idle on a blocker.** Apply §10; if still blocked after 3 distinct attempts, write a BLOCKERS.md entry (symptom, attempts, proposed human fix) and move to the next unblocked task — there is always parallel work (tests, docs, server, another module).
3. **Keep the build green.** If a change breaks the build and a fix isn't found in 2 attempts, revert the change and re-approach differently.
4. **Scope discipline.** No features beyond this spec. No stack substitutions except the pre-authorized fallbacks in §10.

## 13. TASKS.md Protocol

On first run, generate `TASKS.md` by expanding §9 phases into fine-grained checkboxes (each ≤ ~1 h of work). Loop: pick topmost unchecked task in the current phase → implement → test → check it off with commit hash → next. Phase gates are explicit tasks. This file is the single source of truth for progress; re-read it at the start of every session.
