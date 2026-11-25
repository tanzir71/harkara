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
| 7 | Reaction | NIP-25: votes `+`/`-` on posts (`e`, `p`, `k`); `yes`/`no` "still there?" on pins (`a`, `p`, `k` 33471) |
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
| 3476 | Check-in | regular, gift-wrapped | `status` = `safe` · `trip` · `overdue` · `cancelled`, `place`, optional `g` (geohash-5), `trip`, `dest`, `due`; top relay priority (§17) |
| 3477 | SOS | regular | Top relay priority; one per 30 min per key; active until an SOS update closes it |
| 3478 | SOS update | regular | `e` → SOS, `status` = `resolved` · `false_alarm` |
| 23471 | Live location | ephemeral, gift-wrapped (21059) | Session key; `share`, `seq` (replay protection), `end` (unix s); the start is a 1059 wrap from the profile key with `session` (§16) |
| 33471 | Pin | addressable | Shared/public pin: `d`, `title`, `g` 9/6/5, `color`, `visibility`, `expiration` (§15) |

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
## 11. Nearby feed

A feed post is kind 1, signed by the weekly area key (KeyRing.area(geohash5, isoWeek)), with:

| Tag | Value |
|---|---|
| g | geohash at precision 7, 6 and 5 (three tags, so relays can match any of them) |
| 	 | category: 	raffic, ood, crowd, stock, lert, chatter (untagged posts are chatter) |
| xpiration | NIP-40: created_at + 3 h / never / 4 h / 24 h / 12 h / 48 h |
| 
once | NIP-13, ≥ 16 bits (the composer tunes 16–20 bits to ~2 s on the phone) |
| stock, status, 	raffic, crowd, lert, price | optional details from loka's composer chips |

Votes are NIP-25 kind 7 (+/-, , p, k), reports NIP-56 kind 1984 (["e", id, reason], p), both signed by the voter's area key. Locally a post hides when 3 distinct keys report it, when the score (latest vote per key) reaches −5, when a moderator the user opted into (NIP-51 follow set) reports it, when the author is blocked, or when it matches the word filter or the user's mute words. Nothing here is enforced on anyone else's phone.

Composer rules (from loka): ≤ 400 characters, ≤ 1 link, no phone numbers in Chatter or Alert, Bangla and English word filters. Rate limits, enforced by peers before storing or relaying and by the composer for our own posts: 1 post per minute, 10 per hour, 30 per day per key.

## 12. Relays

The pool connects to data/default-relays.json (three required relays + four chosen weekly by 	ools/relay-check.mjs) or the user's list. Per relay: NIP-11 is fetched first (Accept: application/nostr+json) and its max_message_length / max_filters respected; reconnects use jittered exponential backoff (1 s … 5 min); subscriptions are re-sent on reconnect with since = last EOSE. Our events go through an outbox: Saved → Left this phone (written to a relay socket) → Reached the network (first OK true); unacknowledged events are retried with backoff whenever a relay connects.

The area feed subscribes to kind 1 with #g = the area's geohash-5 cell and its 8 neighbours, since = 2 days. On relays that list NIP-77, the app first runs a negentropy reconciliation (NEG-OPEN with the same filter and the (created_at, id) pairs it already holds) and then REQs only the missing ids. core/relay/LocalRelay is the same NIP-01 logic without I/O, used by the LAN room and node.

Storage budget: other people's events are capped at 20 MB (oldest received first are dropped) and media at 100 MB; expired posts are purged; the user's own events are never auto-cleared.
## 13. Tasks

A task offer is NIP-99 kind 30402 signed by the requester's profile key: d, 	itle, summary, published_at, price (["price", "120", "BDT"]), status, 	 = harkara-task and the type (pickup, uy, print, rrand — schemas in data/task-types.json), g at precision 7/6/5, ["field", key, value] for the type's fields, xpiration (24 h by default) and an optional 
ame.

Steps are kind 3471 with ["a", "30402:<requester>:<d>"], ["e", <offer id>], ["step", …] and p = the other side:

| Step | Who | From | To / effect |
|---|---|---|---|
| ccept | anyone but the requester | open | adds a pending offer to help (optional 
ame) |
| confirm | requester | open | accepted; needs ["e", <accept id>, "", "accept"] and ["handoff", <commitment>] |
| decline | runner | accepted | open again |
| picked_up | runner | accepted | picked up |
| delivered | runner | accepted, picked up | delivered; needs ["token", <signed token JSON>] or ["code", <6 digits>] |
| paid | requester | delivered | marks paid by requester; content = NIP-44 to the runner {method, amount, trxId} |
| paid_ack | runner | delivered + paid | paid |
| ated | either side, once | delivered, paid | ["rating", 1–5] |
| cancel | requester | open, accepted, picked up | cancelled |
| dispute | either side | accepted … paid | disputed; ["reason", item_issue / payment_issue / delivery_issue / safety_or_policy / other] |

Every phone computes the state with TaskStateMachine: steps in time order (lifecycle order for ties), retried until nothing changes; wrong author, wrong state, bad signature or bad proof → dropped. Open offers past xpiration are expired.

Handoff token: kind 3472 signed by the requester, ["a", <offer address>], ["p", <runner>], ["nonce", …]. It's created at confirm and stays on the requester's phone; shown as a QR (harkara:handoff:<token JSON>, checked by signature offline) or as 6 digits = SHA-256("harkara-handoff:" + sig) mod 10^6, checked against the confirm commitment scrypt(code, "harkara-handoff|<address>|<runner>", N=2^14, r=8, p=1). A token or code delivers a task once.

Reputation counts tasks done as runner and ratings received only from keys within two steps of the viewer's verified contacts.
## 14. Khata (IOUs)

IouTerms = {id, creditor {name, pubkey?}, debtor {name, pubkey?}, amount (taka), currency, note, createdAt, due?, task?}; its hash is SHA-256 of the canonical JSON (fixed field order, defaults included). A party signs with kind 3474 (["h", hash], ["iou", id]); an IOU is **agreed** only when both parties' keys signed the same hash, otherwise it is a **claim** (always the case when one side has no key). A settlement is kind 3475 with content {method, amount, trxId}, ["h", iouHash], ["s", SHA-256(iouHash | settlement JSON)]; **settled** when both sides signed the same s.

Phones exchange the whole IouBundle {terms, signatures[], settlements[]} as the content of a kind 3473 rumor, gift-wrapped (NIP-59) to the other party and to self, and merge bundles by id (only verifying signatures are kept). Face to face, the same bundle goes as a QR: harkara:iou: + base64url(JSON). Split bills create one IOU per member with shares that sum exactly. Paid tasks are shown as settled receipts but are not IOUs.

## 15. Pins

A pin is a saved place (`core/pins/Pins.kt`). Its `d` tag is the pin id; **replaceable semantics**: phones keep only the newest version per address (`<kind>:<owner>:<d>`), higher `created_at` wins, a tie goes to the lower id (NIP-01). An edit is a new version of the same `d`; a delete is a newer version with `["status", "deleted"]` (public: `expiration` one day later, so relays drop it).

| Visibility | Event | Carried as |
|---|---|---|
| Private | kind 30078 (NIP-78) signed by the profile key, tags `[["d", id]]` only; content = NIP-44 v2 to the owner's own key (conversation key of the profile key with itself) of `{"type":"harkara-pin","title","note","lat","lng","color","deleted"}` | the signed event; a restored phone fetches it with `authors = [me]` |
| Shared | kind 33471 **rumor** by the profile key with the pin tags below plus `p` per person | one NIP-59 gift wrap (1059) per person and one to the owner; the wrap carries the pin's `expiration` |
| Public | kind 33471 signed by the profile key: `d`, `title`, `g` at precision **9**, 6 and 5 (the 9-character cell is the pin's spot, ±2.4 m), `color` (a feed category tag, for the dot colour), `visibility` = `public`, NIP-40 `expiration` (1, 7 or 30 days; default 7) | the signed event; phones subscribe to `#g` = the area's geohash-5 cell and its neighbours |

Titles ≤ 80 characters, notes ≤ 400. A pin whose `visibility` tag doesn't match how it arrived (e.g. a "shared" pin published openly) is ignored.

**Still there?** NIP-25 kind 7, content `yes` or `no`, tags `["a", "33471:<owner>:<d>"]`, `["p", owner]`, `["k", "33471"]`, signed by the voter's **weekly area key** (anonymous, like feed votes). The tally counts each key's latest answer from the last 7 days.

**Files.** Pins export to GPX 1.1 waypoints, KML placemarks or a GeoJSON FeatureCollection of points; imports read only points from those formats (DTDs and external entities refused) and become private pins.

## 16. Live location

`core/location/LiveLocation.kt`. One share = one **session key** `KeyRing.session(shareId)`, an end time (15 min, 1 h or 8 h after the start, never more) and the people it goes to.

1. **Start** — rumor kind 23471 by the **profile key**: `["share", id]`, `["session", <session pubkey>, <binding>]`, `["end", unix]`, `p` per person, optional `["name", …]`. `binding` = the session key's BIP-340 signature over SHA-256(`harkara-live|<owner pubkey>|<share id>|<end>`), so nobody can claim someone else's session key. Sent as a normal gift wrap (1059) with NIP-40 `expiration` = end, so a phone that comes online later still learns the key.
2. **Points** — rumor kind 23471 by the **session key**: `["share", id]`, `["seq", n]` (1, 2, 3 …), `["end", unix]`, content `{"lat","lng","acc"}` (5 decimals, accuracy in metres). Sealed by the session key and wrapped as **ephemeral gift wraps (21059)**, one per person, with seal and wrap timestamps = the point's time (live data is delivered as it happens; backdating hides nothing). Points go straight to connected relays and are never queued or stored. At most one point per 20 s unless the phone moved 25 m.
3. **Stop** — a point with `["status", "stopped"]` and no position, sent ephemerally and as a 1059 wrap expiring at the end.

Receivers (`LiveShareTracker`) accept a point only if: a verified start bound its session key to the owner; `share` and `end` match that start; `seq` is above the last seen; the point was written before the end and it is not yet the end; the share wasn't stopped (a stop seen before its start is remembered, because relays may re-send stored wraps in any order). Everything else — replays, foreign or unknown session keys, late points — is dropped. The sender's phone reads GPS only while its share runs (foreground service of type `location`, with a Stop action in the notification) and stops by itself at the end.

## 17. Check-ins, trip plans and overdue alerts

`core/location/CheckIns.kt`. A check-in is a short rumor kind 3476 by the profile key, sealed (signed) and gift-wrapped (1059) to the chosen people **and to the sender**, queued at SOS priority. Check-ins are never public: where someone is right now is nobody else's business.

| Tag | Meaning |
|---|---|
| `status` | `safe` ("I got here safe"), `trip` (a trip plan), `overdue` (automatic alert), `cancelled` |
| `place` | label typed by the user (≤ 60 characters) |
| `g` | optional geohash-5 (~5 km), never the exact spot |
| `trip`, `dest`, `due` | trip id, destination label, expected arrival (unix s); required for `trip` and `overdue` |
| `name` | optional display name of the sender (never a phone number) |
| `p` | each person it goes to |

Content: an optional note (≤ 140 characters).

**Trip plan.** The traveller sets a destination, an expected arrival and the people who watch. The phone sends a `trip` check-in to them and schedules **one** WorkManager job at the expected arrival (nothing is scheduled while no trip is active). When it runs: if the traveller sent a `safe` check-in since the trip started (untagged, or tagged with this trip), the trip is marked arrived and nothing is sent; otherwise the phone sends an `overdue` check-in to the watchers (and tries the relays at once; the outbox keeps it until any rung opens). A run that wakes before the deadline queues itself again. A safe check-in during the trip closes it and cancels the job; Cancel sends `cancelled`. A watcher's phone that receives an `overdue` check-in (in the app or during the 30-minute background check) shows a notification.

## 18. Location cards

`core/location/LocationCard.kt`. A one-off location rides as one tag on a chat rumor (M5) or as plain text elsewhere:

```
["card", "location", "<lat>", "<lng>", "<precision>", "<label>", "<plus code>"]
```

`lat`/`lng` have 5 decimals with Latin digits. `precision`: `exact` (the fix, 10-digit Plus Code), `100m` (the centre of a ~100 m grid cell — every fix in the cell gives the same card, so repeated shares can't be averaged; 10-digit code of that centre), `area` (the geohash-5 cell centre and the area's name; 6-digit padded code like `7MMGQ800+`). Plus Codes are Open Location Code (`core/geo/OpenLocationCode.kt`, checked against the official test data) and are shown shortened against central Dhaka (`Q8V2+QX Dhaka`). Shared as text, the card is `label · code` plus an OpenStreetMap link.

## 19. Map packs

The `mappack.yml` workflow publishes `dhaka-z15.pmtiles`, `dhaka-z13.pmtiles` and `maps.json` (`{source, sourceDate, bbox, attribution, packs: [{name, maxzoom, bytes, sha256}]}`) to a `maps-YYYYMM` release. Only when the user taps it, the app lists `GET https://api.github.com/repos/tanzir71/harkara/releases`, picks the newest `maps-YYYYMM` with a `maps.json`, downloads the chosen pack (z15 by default; z13 in Lite mode or when free space is under three times the pack), and installs it only if its size and **SHA-256** match the manifest and the file is a PMTiles v3 vector archive whose bounds cover Dhaka (`core/map/PmTiles.kt` reads the header and directories). A pack passed phone to phone (`MapPackManager.import`) gets the same checks. MapLibre reads the installed file directly (`pmtiles://file://…`); the style (`core/map/MapStyle.kt`) uses the `map/*` colour tokens, Bangla labels (`name:bn`, then `name`, which is Bangla for most of Dhaka) or English (`name:en`, then `name`), glyphs bundled in the APK (Noto Sans: Latin, Bengali, punctuation) and shows "© OpenStreetMap contributors · Protomaps".
