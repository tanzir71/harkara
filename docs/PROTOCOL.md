# Harkara Protocol

Every post, message, task, pin and IOU is a signed [NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md) event. Phones carry the same event over every rung of the ladder: relays and LAN as JSON, Bluetooth and SMS as a compact binary form of the same fields. A bridging phone re-encodes but never re-signs. Code: `core/src/main/kotlin/io/github/tanzir71/harkara/core/` (`nostr`, `keys`, `wire`, `mesh`, `store`, `geo`).

## 1. Envelope

```json
{"id":"<32-byte hex>","pubkey":"<32-byte x-only hex>","created_at":<unix s>,"kind":<0..65535>,"tags":[["g","wh0r0d"],…],"content":"…","sig":"<64-byte hex>"}
```

- `id` = SHA-256 of the UTF-8 canonical serialization `[0,pubkey,created_at,kind,tags,content]` with no whitespace. In strings only `\n \" \\ \r \t \b \f` use short escapes; other control characters and lone surrogates are `\u00xx` (lowercase), everything else is verbatim — byte-for-byte what `JSON.stringify` produces (`EventId.serialize`).
- `sig` = BIP-340 Schnorr over the 32-byte id, with fresh auxiliary randomness (libsecp256k1 via `fr.acinq.secp256k1:secp256k1-kmp`).
- Size: the event's JSON must be under **16 KB** (16 384 bytes).
- Checks, in order (`Event.check()`): lowercase hex shapes and kind range → size → id recomputes → signature verifies. Peers drop anything that fails before storing or relaying it.
- `expiration` (NIP-40, unix seconds): expired events are neither shown, stored nor relayed.
- Text never goes through a locale formatter (no Bangla digits in ids, weeks or tags).

## 2. Kinds

| Kind | Name | Notes |
|---:|---|---|
| 0 | Metadata | Name/photo, only when the user chooses to add one |
| 1 | Feed post | Signed by the **area key**. Tags: `g` geohash-6 (optionally coarser `g` too), `t` category (`traffic`, `food`, `crowd`, `stock`, `alert`, `chatter`), `expiration` (Traffic 3 h, Crowd 4 h, Alert 12 h, Stock 24 h, Chatter 48 h, Food none), NIP-13 `["nonce", n, target]` with 16–20 bits |
| 5 | Deletion | NIP-09 |
| 7 | Reaction | NIP-25: votes `+`/`-` on posts, `yes`/`no` "still there?" on pins (`e`, `p`, `k`) |
| 13 | Seal | NIP-59, inside a gift wrap only |
| 14 | Chat message | NIP-17 rumor: `p` per member, `e` reply, `subject` |
| 15 | File message | NIP-17 rumor: `file-type`, `encryption-algorithm` `aes-gcm`, `decryption-key`, `decryption-nonce`, `x` |
| 1059 | Gift wrap | NIP-59: one-time key, `p` recipient |
| 1984 | Report | NIP-56: `e` + `p` with report type; 3 distinct reporting keys hide a post locally, score ≤ −5 hides |
| 10000 | Mute list | NIP-51 |
| 10050 | DM relays | NIP-17 inbox relays |
| 20000 | Area chat | Ephemeral, `g`-tagged area channel message (bitchat's geohash-channel convention) |
| 21059 | Ephemeral gift wrap | NIP-59, for live data relays must not store |
| 30000 | Follow set | NIP-51, opt-in moderator lists per area |
| 30078 | App data | NIP-78: private pins encrypted to self (`d`) |
| 30402 | Task offer | NIP-99 classified listing: `d`, `title`, `price`, `g`, `expiration`, `t` task type |

**Harkara app kinds** (a block no NIP uses; reserved now, built in later milestones):

| Kind | Name | Range | Tags / rules |
|---:|---|---|---|
| 3471 | Task step | regular | `a` → `30402:<pubkey>:<d>`, `e` → offer event, `step` = `accept` · `picked_up` · `delivered` · `paid` · `rated` · `cancel` · `dispute`, `p` other party. Accept counts only after the requester countersigns (M7) |
| 3472 | Handoff token | regular | Requester-signed, single use, `a` task, `code` (6 digits), shown as QR or digits, checked offline (M7) |
| 3473 | IOU | regular, gift-wrapped | Terms as rumor to both people: `amount`, `currency`, `p` |
| 3474 | IOU signature | regular, gift-wrapped | `x` = SHA-256 of the IOU terms; agreed only when both sign the same hash (M8) |
| 3475 | Settle | regular, gift-wrapped | `e` IOU, optional `trxid` (bKash/Nagad as text), signed by both (M8) |
| 3476 | Check-in | regular | "I got here safe"; top relay priority |
| 3477 | SOS | regular | Top relay priority; one per 30 min per key; active until an SOS update closes it |
| 3478 | SOS update | regular | `e` → SOS, `status` = `resolved` · `false_alarm` |
| 23471 | Live location | ephemeral, gift-wrapped (21059) | Session key; `seq` (replay protection), `end` (unix s) |
| 33471 | Pin | addressable | Shared/public pin: `d`, `g`, `expiration`, `title` |

## 3. Private messages (NIP-17 / NIP-59 / NIP-44 v2)

- **Rumor**: an unsigned event with its `id` (kind 14/15, or any kind such as 3473). Never signed; a rumor with a `sig` is refused.
- **Seal** (kind 13): `content` = NIP-44(rumor JSON, conv(sender, recipient)); signed by the sender; tags empty; `created_at` up to 2 days in the past.
- **Gift wrap** (kind 1059, or 21059 for live data): `content` = NIP-44(seal JSON, conv(one-time key, recipient)); tag `["p", recipient]`; signed by a fresh one-time key; `created_at` up to 2 days in the past.
- **Unwrap** (`GiftWrap.unwrap`): verify the wrap → decrypt → verify the seal → decrypt → the rumor's `pubkey` must equal the seal's `pubkey` (else anyone could impersonate anyone) → the rumor id must match its fields.
- **Groups**: one wrap per member plus one to the sender (`GiftWrap.wrapForGroup`).
- **NIP-44 v2**: conversation key = HKDF-Extract(salt `nip44-v2`, unhashed ECDH x); per message HKDF-Expand(conv, nonce32, 76) → ChaCha20 key/nonce + HMAC key; padded plaintext (1..65 535 bytes); payload = base64(`0x02 ‖ nonce ‖ ciphertext ‖ HMAC(nonce ‖ ciphertext)`).
- These are the only private messages on every carrier; mesh peers carry wraps they cannot read.

## 4. Keys

One 32-byte **root seed**, made silently the first time the app needs a key (when an area is chosen) and stored only on the phone (`noBackupFilesDir/identity/root-seed`, sealed with an AndroidKeyStore AES-256-GCM key where available). Every key is derived with HKDF-SHA256 (`KeyRing`):

```
PRK  = HKDF-Extract(salt = empty, IKM = root seed)
key  = HKDF-Expand(PRK, info = label, 32)        # retried with label + "#1", "#2", … if 0 or ≥ n
```

| Key | Label | Rotates | Used for |
|---|---|---|---|
| Profile | `harkara/v1/profile` | never | chats, tasks, Khata, shared pins |
| Area | `harkara/v1/area/<geohash-5>/<ISO week>` e.g. `harkara/v1/area/wh0r0/2026-W48` | weekly per ~5 km cell | anonymous Nearby posts |
| Mesh presence id | `harkara/v1/mesh/<yyyy-mm-dd>` (16 bytes) | daily | Bluetooth presence; unlinkable without the seed |
| Session | `harkara/v1/session/<id>` | per share | one live-location share |

Weeks and days turn over on Dhaka time (`Asia/Dhaka`).

### Backup and restore (NIP-49)

- The backup is `ncryptsec1…` (NIP-49): scrypt(NFKC password, 16-byte salt, N = 2^16, r = 8, p = 1) → XChaCha20-Poly1305 with the key-security byte (`0x02`, "not tracked") as associated data. The **32-byte root seed is placed in NIP-49's "private key" field**, so one backup restores every derived key. It is not a Nostr `nsec`; other Nostr clients will decrypt it but would treat the seed as a key.
- Me → *Back up key*: password (≥ 8 characters) twice, then **Save to a file…** (Storage Access Framework, `harkara-key-backup.txt`, the ncryptsec text) or **Show as QR** (byte mode, error correction M).
- Onboarding → *Restore from backup*: paste the text or open the file, type the password. A wrong password or damaged text fails cleanly and keeps the current identity.
- Optional **recovery cards** (`RecoveryCards`): Shamir 2-of-3 over GF(2^8) on the seed; each card is bech32 `hkcard1…` of `version 1 ‖ threshold 2 ‖ x ‖ 32 share bytes`.

## 5. Binary event codec (Bluetooth, SMS)

`BinaryEventCodec` carries exactly the signed fields:

| Field | Size |
|---|---|
| version | u8 (1) |
| flags | u8: `0x01` content is raw DEFLATE, `0x02` id omitted |
| id | 32 (absent with `0x02`; the decoder recomputes it — SMS mode) |
| pubkey | 32 |
| sig | 64 |
| created_at | u32 big-endian |
| kind | varint (unsigned LEB128) |
| tags | varint count; per tag varint item count; per item varint length + UTF-8 |
| content | varint length + bytes (UTF-8, or raw DEFLATE of it when that is smaller) |

Decoded content is capped at 64 KB. JSON → binary → JSON gives the identical string, and the event still verifies.

## 6. Mesh

### Frame (`MeshFrame`)

| Field | Size |
|---|---|
| version | u8 (1) |
| type | u8: `1` EVENT, `2` FRAGMENT, `3` DIGEST, `4` PULL, `5` PRESENCE |
| ttl | u8: most hops (default 7), set by the sender |
| hops | u8: hops travelled; relays add one. Lives **outside** the signed event |
| flags | u8 (reserved, 0) |
| payload | varint length + bytes |

- EVENT: one binary event. DIGEST/PULL: varint count + 32-byte event ids (ttl 1). PRESENCE: the 16-byte daily presence id (ttl 1).
- A receiver may pass a frame on when `hops + 1 < ttl`, so a ttl of 7 reaches 7 hops.

### Fragments

Frames larger than a link's MTU are split into FRAGMENT frames (ttl 1, link-local). Payload: `8-byte set id (SHA-256 prefix of the whole frame) ‖ u16 index ‖ u16 count ‖ u32 total ‖ chunk`. Every hop reassembles, checks the event, and fragments again for its own links. Incomplete sets are dropped after **60 s**; at most 64 sets are held; a set whose hash does not match is discarded.

### Relay rules (`MeshRelay`)

For every incoming event frame, in order:

1. Decode; drop malformed.
2. Seen in the last 30 min (dedupe by **event id**, 10 000 entries)? Drop.
3. `Event.check()` (id, signature, < 16 KB); only valid events are marked seen, so a forged copy cannot block the real one.
4. Expired (NIP-40)? Drop. More than 15 min in the future? Drop.
5. **Rate limits** (`PeerRateLimiter`, before relaying): per pubkey, feed posts (kind 1) at most **1 per minute, 10 per hour, 30 per day**, counted over sliding windows of `created_at` (a backlog written over hours passes when it arrives at once; a burst written at once does not).
6. **SOS policy**: one SOS per key per 30 min; a `resolved`/`false_alarm` update re-opens it; muted keys dropped.
7. Store-and-forward (below), then relay to every neighbour except the sender with `hops + 1`, while within ttl.

Outbound queue priority: **SOS / check-in > gift wraps (DMs) > area chat & presence > posts and everything else > sync (digest/pull)**; when full, the lowest priority is dropped first. Per-peer token buckets cap link rates.

### Partition heal

When a link comes up both sides send a DIGEST of the ids they keep; each side PULLs what it lacks and receives normal EVENT frames (ttl 7, hops 0), which then flood the healed partition under the same rules.

### Gates (JVM tests on `FakeMeshNetwork`, all on signed events)

3-hop relay · TTL bound · dedupe storm (one copy per node) · fragment reassembly with a small MTU and the 60 s timeout · 20-node random topology delivers ≥ 95 % · partition heal by digest/pull · forged and expired events dropped · rate limits and SOS limits enforced before relaying · gift wraps cross opaque and open only at the recipient.

## 7. Store and bridge

`EventStore` (in-memory, store-and-forward) keeps events whose `g` tag falls under a subscribed geohash prefix, plus gift wraps carried for others; other events are relayed live but not kept. `BridgeEngine` publishes what a phone carries to a relay and pulls relay events for its areas back, unchanged (same id, same signature). The Room/SQLCipher store with the 20 MB budget and the OkHttp relay pool are M4.

## 8. Transport boundary

`MeshTransport` (`core.mesh`) moves `MeshFrame`s: `incoming: Flow<MeshFrame>`, `suspend send(frame)`. BLE, Wi-Fi Aware and LAN adapters implement it in the app; tests use `FakeTransportHub`, which pushes every frame through its byte encoding.

## 9. Wipe

Panic wipe deletes the seed file and the AndroidKeyStore key (`IdentityManager.wipe`), then the database, settings, files and caches. Keys not backed up are gone.

## 10. Test vectors

| Vector | Source | Where |
|---|---|---|
| BIP-340 `test-vectors.csv` (verify all 32-byte-message rows, sign rows 0–3) | bitcoin/bips, BSD-2-Clause | `core/src/test/resources/bip340-test-vectors.csv` |
| NIP-44 v2 `nip44.vectors.json` (SHA-256 `269ed0f6…5040`) | paulmillr/nip44, MIT | `core/src/test/resources/nip44.vectors.json` |
| NIP-49 decrypt vector (`nostr`, log_n 16) and NFKC example | NIP-49 | `Nip49Test` |
| NIP-01 ids/signatures: NIP-13 mined note, NIP-59 rumor/seal/wrap, NIP-17 wraps | NIPs | `NipExamples` |
| NIP-19 npub/nsec/nprofile | NIP-19 | `Bech32Test` |
| HChaCha20 | draft-irtf-cfrg-xchacha | `Nip49Test` |
| Key ring HKDF outputs | computed independently (Python `hmac`/`hashlib`) | `KeyRingTest` |
