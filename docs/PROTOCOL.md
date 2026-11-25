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
| 1 | Feed post | Signed by the **area key**. Tags (`EventBuilder.feedPost`): `g` at geohash precisions 6, 5 and 4 (relays filter `#g` by exact value), `t` category (`traffic`, `food`, `crowd`, `stock`, `alert`, `chatter`), `expiration` (Traffic 3 h, Crowd 4 h, Alert 12 h, Stock 24 h, Chatter 48 h, Food none), NIP-13 `["nonce", n, target]` with 16–20 bits |
| 5 | Deletion | NIP-09 |
| 7 | Reaction | NIP-25: votes `+`/`-` on posts, `yes`/`no` "still there?" on pins (`e`, `p`, `k`) |
| 13 | Seal | NIP-59, inside a gift wrap only |
| 14 | Chat message | NIP-17 rumor: `p` per member, `e` reply, `subject`; Harkara adds `n` (sender's name) and `card` (§15) |
| 15 | File message | NIP-17 rumor: `file-type`, `encryption-algorithm` `aes-gcm`, `decryption-key`, `decryption-nonce`, `x`; Harkara voice-note parts carry the audio inline (§15) |
| 1059 | Gift wrap | NIP-59: one-time key, `p` recipient |
| 1984 | Report | NIP-56: `e` + `p` with report type; 3 distinct reporting keys hide a post locally, score ≤ −5 hides |
| 10000 | Mute list | NIP-51 |
| 10050 | DM relays | NIP-17 inbox relays |
| 20000 | Area chat | Ephemeral. Area channel: `g` = geohash-5, signed by the weekly area key. Wi-Fi room: `t` = `wifi`, signed by the profile key, never sent to relays. Both: optional `n`, `expiration` = +24 h (§15) |
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
| 3479 | Chat receipt | rumor, gift-wrapped | To the message author only: `p` author, one `e` per message id (rumor id, or voice-note id), `status` = `delivered` · `read` (M5, §15) |
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
| Open Location Code encoding, validity and shortening | google/open-location-code test data, Apache-2.0 | `OpenLocationCodeTest` |
## 11. Nearby feed

A feed post is kind 1, signed by the weekly area key (KeyRing.area(geohash5, isoWeek)), with:

| Tag | Value |
|---|---|
| g | geohash at precision 7, 6 and 5 (three tags, so relays can match any of them) |
| `t` | category: `traffic`, `food`, `crowd`, `stock`, `alert`, `chatter` (untagged posts are chatter) |
| `expiration` | NIP-40: created_at + 3 h / never / 4 h / 24 h / 12 h / 48 h |
| `nonce` | NIP-13, ≥ 16 bits (the composer tunes 16–20 bits to ~2 s on the phone) |
| `stock`, `status`, `traffic`, `crowd`, `alert`, `price` | optional details from loka's composer chips |

Votes are NIP-25 kind 7 (+/-, `e`, `p`, `k`), reports NIP-56 kind 1984 (["e", id, reason], p), both signed by the voter's area key. Locally a post hides when 3 distinct keys report it, when the score (latest vote per key) reaches −5, when a moderator the user opted into (NIP-51 follow set) reports it, when the author is blocked, or when it matches the word filter or the user's mute words. Nothing here is enforced on anyone else's phone.

Composer rules (from loka): ≤ 400 characters, ≤ 1 link, no phone numbers in Chatter or Alert, Bangla and English word filters. Rate limits, enforced by peers before storing or relaying and by the composer for our own posts: 1 post per minute, 10 per hour, 30 per day per key.

## 12. Relays

The pool connects to data/default-relays.json (three required relays + four chosen weekly by `tools/relay-check.mjs`) or the user's list. Per relay: NIP-11 is fetched first (Accept: application/nostr+json) and its max_message_length / max_filters respected; reconnects use jittered exponential backoff (1 s … 5 min); subscriptions are re-sent on reconnect with since = last EOSE. Our events go through an outbox: Saved → Left this phone (written to a relay socket) → Reached the network (first OK true); unacknowledged events are retried with backoff whenever a relay connects.

The area feed subscribes to kind 1 with #g = the area's geohash-5 cell and its 8 neighbours, since = 2 days. On relays that list NIP-77, the app first runs a negentropy reconciliation (NEG-OPEN with the same filter and the (created_at, id) pairs it already holds) and then REQs only the missing ids. core/relay/LocalRelay is the same NIP-01 logic without I/O, used by the LAN room and node.

Storage budget: other people's events are capped at 20 MB (oldest received first are dropped) and media at 100 MB; expired posts are purged; the user's own events are never auto-cleared.
## 13. Tasks

A task offer is NIP-99 kind 30402 signed by the requester's profile key: d, `title`, summary, published_at, price (["price", "120", "BDT"]), status, `t` = harkara-task and the type (`pickup`, `buy`, `print`, `errand` — schemas in data/task-types.json), g at precision 7/6/5, ["field", key, value] for the type's fields, `expiration` (24 h by default) and an optional `name`.

Steps are kind 3471 with ["a", "30402:<requester>:<d>"], ["e", <offer id>], ["step", …] and p = the other side:

| Step | Who | From | To / effect |
|---|---|---|---|
| `accept` | anyone but the requester | open | adds a pending offer to help (optional `name`) |
| confirm | requester | open | accepted; needs ["e", <accept id>, "", "accept"] and ["handoff", <commitment>] |
| decline | runner | accepted | open again |
| picked_up | runner | accepted | picked up |
| delivered | runner | accepted, picked up | delivered; needs ["token", <signed token JSON>] or ["code", <6 digits>] |
| paid | requester | delivered | marks paid by requester; content = NIP-44 to the runner {method, amount, trxId} |
| paid_ack | runner | delivered + paid | paid |
| `rated` | either side, once | delivered, paid | ["rating", 1–5] |
| cancel | requester | open, accepted, picked up | cancelled |
| dispute | either side | accepted … paid | disputed; ["reason", item_issue / payment_issue / delivery_issue / safety_or_policy / other] |

Every phone computes the state with TaskStateMachine: steps in time order (lifecycle order for ties), retried until nothing changes; wrong author, wrong state, bad signature or bad proof → dropped. Open offers past `expiration` are expired.

Handoff token: kind 3472 signed by the requester, ["a", <offer address>], ["p", <runner>], ["nonce", …]. It's created at confirm and stays on the requester's phone; shown as a QR (harkara:handoff:<token JSON>, checked by signature offline) or as 6 digits = SHA-256("harkara-handoff:" + sig) mod 10^6, checked against the confirm commitment scrypt(code, "harkara-handoff|<address>|<runner>", N=2^14, r=8, p=1). A token or code delivers a task once.

Reputation counts tasks done as runner and ratings received only from keys within two steps of the viewer's verified contacts.
## 14. Khata (IOUs)

IouTerms = {id, creditor {name, pubkey?}, debtor {name, pubkey?}, amount (taka), currency, note, createdAt, due?, task?}; its hash is SHA-256 of the canonical JSON (fixed field order, defaults included). A party signs with kind 3474 (["h", hash], ["iou", id]); an IOU is **agreed** only when both parties' keys signed the same hash, otherwise it is a **claim** (always the case when one side has no key). A settlement is kind 3475 with content {method, amount, trxId}, ["h", iouHash], ["s", SHA-256(iouHash | settlement JSON)]; **settled** when both sides signed the same s.

Phones exchange the whole IouBundle {terms, signatures[], settlements[]} as the content of a kind 3473 rumor, gift-wrapped (NIP-59) to the other party and to self, and merge bundles by id (only verifying signatures are kept). Face to face, the same bundle goes as a QR: harkara:iou: + base64url(JSON). Split bills create one IOU per member with shares that sum exactly. Paid tasks are shown as settled receipts but are not IOUs.

## 15. Chats (M5)

Code: `core/chat` (`ChatProtocol`, `ChatCards`, `VoiceNotes`), `core/lan/LanRelayServer`, `core/geo/OpenLocationCode`; app `chat/ChatRepository`, `lan/LanRoom`.

### DMs and groups (NIP-17)

- A chat is a NIP-17 room: the set of the sender and the `p` tags. Its local id is `hex(SHA-256(sorted pubkeys joined by ","))[0..32]`; two members make a DM, three or more a group. A changed member list is a new room. The newest `subject` names a group; Harkara repeats the subject on every group message it sends.
- Every message is a kind 14 rumor (text, optional `card`, optional `n` name), sealed and gift-wrapped (kind 1059) **to every member and to the sender** (so a restored phone sees its own messages). Wraps and seals are back-dated up to 2 days (NIP-59). Text is at most 6 000 UTF-8 bytes, which keeps every wrap under 16 KB.
- Subscriptions: kind 1059 with `#p` = our profile key and `since` = last finished fetch − 2 days 1 hour (a week back the first time). On reconnect the relay pool resumes from the last EOSE minus the same 2 days 1 hour, so back-dated wraps are never skipped.
- Receiving: unwrap (wrap and seal signatures checked, seal signer = rumor author), drop rooms we're not in and blocked authors, dedupe by rumor id, store, then send a **delivered** receipt.
- `n` is the sender's display name, asked once when they first send and kept on their phone. Inside a wrap only the members see it. A name with 7 or more digits in a phone-like run is never sent and never shown.

### Receipts and delivery states

| State | When |
|---|---|
| Saved | stored, no wrap has left the phone |
| Left this phone | a wrap was written to a relay socket or a Wi-Fi room peer |
| Reached the network | every recipient's wrap got `OK` true from a relay or a room server |
| Delivered | every other member sent a kind 3479 receipt with `status` `delivered` (or `read`) |
| Read | every other member sent `status` `read` (sent when they open the thread) |

Receipts are kind 3479 rumors gift-wrapped only to the author: `["p", author]`, one `["e", id]` per message, `["status", "delivered"|"read"]`. They are never answered. A receipt from someone outside the room is ignored. Once a member's receipt arrives, their copies stop being retried on every carrier. The carrier glyph on our message is the rung that got furthest (relays = Online, Wi-Fi room = Node); incoming messages show the carrier they arrived by.

### Area channel

Kind 20000, `["g", geohash-5]`, optional `["n", name]`, `["expiration", created_at + 86400]`, signed by the weekly **area key** for that cell (anonymous, like Nearby posts). Subscribed on relays as `#g` = the user's cell, `since` = 24 h. Relays don't store ephemeral events; the phone keeps the last 24 h. Like Chatter posts: at most 400 characters and no phone numbers.

### "Everyone on this Wi-Fi"

- Every phone with the app open runs a **room server**: HTTP + WebSocket (RFC 6455) on plain sockets around `LocalRelay`, on a free port, advertised with Android NSD as `_harkara._tcp` under a random per-session name (`harkara-<8 hex>`, never a key). `GET /` with `Accept: application/nostr+json` answers NIP-11.
- A second relay pool connects to every other phone's server (`ws://ip:port`). Each phone subscribes there to its gift wraps and to the room (`kinds [20000]`, `#t` = `wifi`, `since` 24 h).
- Room servers accept only gift wraps (1059) and room messages (20000 with `t` = `wifi`); they keep up to 2 000 wraps in memory so a phone that joins later still gets what waited for it; room messages are ephemeral.
- Sending: our wraps go to the internet relays (outbox) **and** to the room at the same time — injected into our own server (connected peers get them live) and published to every peer's server (each server hands what it accepts to its own phone). Wraps not yet confirmed by a room server are re-sent when a peer appears (for 3 days).
- Room messages: kind 20000, `["t", "wifi"]`, optional `["n", name]`, `expiration` +24 h, signed by the **profile key** (so people can start a DM from the room). Never sent to relays. Same 400-character, no-phone-number rule.
- The room runs only while the app is in the foreground (battery rule), never as a service in M5. The connectivity pill shows "On local Wi-Fi" while the room has peers.

### Voice notes

At most 15 s (Opus/OGG 16 kbps on Android 10+, AAC/MP4 24 kbps below; mono 16 kHz). The audio is cut into parts of 4 096 bytes (at most 32) and each part is a kind 15 rumor wrapped on its own (so each wrap stays under 16 KB):

| Tag | Value |
|---|---|
| `voice` | note id, 16 random bytes in hex |
| `part` | index, count |
| `m`, `file-type` | MIME (`audio/ogg`, `audio/mp4`) |
| `duration` | milliseconds (≤ 15 000, 500 ms slack) |
| `x` | SHA-256 of the whole note |
| `n` | sender's name (optional) |

`content` is the part in Base64 (no file server — NIP-17's URL is replaced by the inline data). The receiver stores parts and builds the note when all have arrived and the hash matches; receipts name the voice id. Strangers' media never auto-downloads over the mesh (the mesh is M6; voice notes are DM/group only).

### Cards

A kind 14 rumor may carry one `card` tag; its `content` is a readable summary for other clients.

| Card | Tag |
|---|---|
| Location | `["card","location", lat, lng, precision, title, plus code, place]` — precision `exact` (5 decimals, 10-digit Plus Code), `100m` (rounded to 0.001°), `area` (geohash-5 centre, 6-digit code). Shown as a short code near Dhaka ("QJ6C+QX Dhaka") |
| Task | `["card","task", type, price, title, steps done (0–5), status, runner]` |
| IOU | `["card","iou", amount, "lent"\|"borrowed" (author's side), what, status, date, "1"\|"0" agreed]` |
| Check-in | `["card","checkin", title, detail]` |
| Pin | `["card","pin", title, detail, visibility, yes, no, category]` |

Location cards come from "Share my location" (a one-off read of the last fix; "Area" falls back to the chosen area). Task, IOU and pin cards are produced by M7–M9.

### Plus Codes

`OpenLocationCode` implements encode (2–15 digits), decode, validity and shortening from the Open Location Code specification, checked against vectors from its test data.

## 17. Off-grid carriers (M6)

`MeshEngine` runs the same verified binary events over `MeshRadio` links. BLE dual-role GATT and optional Wi-Fi Aware only move fragments; every hop reassembles and verifies before applying expiry, future-skew, dedupe, post-rate and SOS limits. Production admission also requires feed structure and at least 16 bits of PoW. Events are never re-signed by a carrier or bridge.

Saver scans 10 seconds per minute, dials at most four links and originates with TTL 5. Event scans continuously, dials at most eight links and uses TTL 7. The Disaster shortcut explicitly enables Event mesh. Photos and Wi-Fi-only room messages are excluded from mesh; SOS travels at top priority. Presence identifies a rotating daily mesh ID, node availability and distance to an internet bridge. Presence and bounded digest/pull reconciliation repeat periodically and on new links, including partition healing.

The app outbox sends waiting events when a mesh/node route opens; successful handover advances Saved to Left this phone. Only relay OK or peer receipts advance further. A phone reaching a relay publishes carried events unchanged. The connectivity pill combines real relay sockets, room/node peers, mesh links and propagated bridge distance; QR alone never pretends that another person is reachable.

Node services advertise `harkara-node-` names on `_harkara._tcp`, extending ordinary room servers with public feed/task/SOS synchronization. All off-grid inbound events pass feature-specific validation/decryption before populating Room. A node streams `/harkara.apk`, `/latest.json`, fixed-name `/maps/` packs and `/handbook/` resources over HTTP. Plain sharing exposes only its download page and installed APK while the sharing sheet remains open; private DMs/IOUs are still gift-wrapped.

Mesh and node modes require explicit opt-in and a visible connected-device notification; they use START_NOT_STICKY, no boot receiver and no background GPS. Lite suppresses mesh. Disabling modules or panic wipe tears down radios, sockets and the service. Radio interoperability, OEM power management and hotspot reachability remain unverified on hardware.

## 18. LAN tools (M11)

File drop and push-to-talk use a dedicated `LocalSendServer`/`LanToolsHttp` listener, separate from the chat relay. The screen takes a manual numeric IPv4 address and port (default 53317); there is no DNS lookup or LocalSend multicast discovery. This is plain HTTP without TLS or authenticated peer identity: aliases and fingerprints are untrusted claims, and file bytes, audio and bearer tokens are visible to LAN observers. Use only a user-selected trusted LAN; these tools do not inherit Nostr gift-wrap encryption.

LocalSend v2 endpoints under `/api/localsend/v2/` are `GET info` and `POST register`, `prepare-upload`, `upload` and `cancel`. Each offer needs explicit receiver consent (default deny, at most 25 s); the protocol can approve a subset, while the current screen accepts or rejects the whole offer. Upload grants are peer-IP-bound, single-use per file and expire after 2 min by default. Known `Content-Length` is required for uploads; chunked requests are rejected. Defaults: 25 MiB/file, 100 MiB/offer, 32 files and four sessions. Uploads stream to random private files, not sender-selected paths; size and any supplied SHA-256 are checked, and failed partial files are deleted. The screen keeps a bounded private inbox (32 files/100 MiB); Save copies via the Storage Access Framework, while Delete removes the private copy.

PTT uses `POST /api/harkara/ptt/v1/chunk`, not LocalSend audio or a Nostr event. Explicit listening issues a random bearer token bound to one peer IP for at most 15 min; the current screen exchanges tokens by copy/paste. Ordered chunks contain little-endian PCM16, 16 kHz mono, at most 6 400 bytes each, at most 75 chunks/15 s per hold. Repeated/out-of-order chunks and previously used stream IDs are rejected; replay memory is bounded to 64 streams, after which re-pairing is required. Sending is synchronous with bounded backpressure and no accumulated audio queue. A fresh permitted hold starts capture; release/cancel/focus loss or the 15-second watchdog stops it.

Construction and enabling a module do not start the listener or microphone. The user must explicitly start the screen's listener; PTT additionally requires microphone permission and a fresh hold. `ON_STOP`, screen disposal and leaving the tool close the listener, active client calls, pending consent and audio; returning to foreground does not restart them. PTT mode rejects file offers. Real-device interoperability, audio behavior and lifecycle timing remain unverified on hardware.
