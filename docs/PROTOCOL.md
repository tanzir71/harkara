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
| 3479 | SMS DM | regular, SMS rung only | NIP-44 v2 content to the single `p` recipient, signed by the sender, not gift-wrapped (§15.3) |
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

Someone without a key confirms an IOU on the web page with the one-time code from their reminder link (`confirmCodeHash`, §15.7); single signatures and settlements also travel by SMS and QR (§15.3, §15.5) and are merged by terms hash.

## 15. SMS, QR, links and the web page for non-users (M10)

### 15.1 SMS text

An SMS is a short readable **Latin** line, a space, the marker `HK1:` and a Base64url payload (`core/wire/SmsCodec.kt`), e.g. `Harkara SOS HK1:EJUbj…`. Everything is in the GSM-7 basic alphabet (Base64url's `-` and `_` included), so two joined parts carry 2 × 153 = **306 characters**; one Bangla letter anywhere would switch the SMS to UCS-2 (70 characters), so Bangla rides inside the payload only. Readable lines: `Harkara check-in` (3476), `Harkara SOS` (3477), `Harkara SOS update` (3478), `Harkara khata sign` (3474), `Harkara khata paid` (3475), `Harkara task` (3471), `Harkara msg` (3479), else `Harkara`. The receiver finds `HK1:` anywhere in the text (a forwarded SMS works). Part counting (`Gsm7.parts`): GSM-7 ≤ 160 septets = 1 part, else ⌈septets / 153⌉ (extension characters `^{}\[~]|€` and form feed cost two septets); UCS-2 ≤ 70 units = 1 part, else ⌈units / 67⌉.

### 15.2 SMS payload (version 1)

| Field | Size |
|---|---|
| header | u8: `0x10` (version 1 in the high nibble) \| flags; `0x01` = the text block is raw DEFLATE with the preset dictionary |
| kind | varint |
| author reference | 8: the first 8 bytes of the x-only pubkey; the receiver looks up keys it knows with that prefix |
| created_at | u32 big-endian |
| sig | 64 (BIP-340) |
| tags | varint count; per tag varint item count, then items (below) |
| content form | varint: `0` text (the rest of the text block), `1` base64 → varint length + raw bytes (NIP-44 payloads; used only when re-encoding gives the identical string) |
| text block | the rest: Text8 of every text item in order, then the content; DEFLATEd when that is smaller |

Item header = varint `(n << 3) | type`:

| Type | Name | Carries |
|---:|---|---|
| 0 | TEXT | n Text8 bytes, taken in order from the text block |
| 1 | HEX | n raw bytes of a lowercase-hex string (used for ≥ 16 hex characters) |
| 2 | UUID | 16 bytes of a canonical lowercase UUID (task `d`, IOU ids) |
| 3 | REF | 8-byte prefix of a 32-byte value the receiver already knows (a key, an event id, an IOU hash) |
| 4 | AUTHOR | the event's own pubkey (no bytes) |
| 5 | SAME | the n-th 32-byte value (HEX or REF) seen earlier in this payload |
| 6 | JOIN | n parts (items, not nested) joined with `:`, e.g. `30402:<AUTHOR or REF>:<UUID>` |
| 7 | DEC | n itself: a decimal string without leading zeros, ≤ 18 digits |

**Text8**: ASCII except DEL as one byte; U+0980–U+09FF (the Bengali block, Bangla digits and ৳ included) as one byte `0x80 + (cp − 0x0980)`; anything else as `0x7F` followed by its UTF-8 (so `।`, emoji and DEL still round-trip).

**Dictionary** (part of version 1, never changed; a new dictionary needs a new version): the UTF-8 of
`expirationplacenamenotestatusfalse_alarmresolvedcashrocketreasoncodedeclinedeliveredpicked_upaccept30402stepiou{"method":"nagad","amount":{"method":"bkash","amount":,"trxId":"`.

**References**: the sender first tries the full form; only if it doesn't fit two parts are the values the receiver is expected to know (`p` people, `e` events, `h` IOU hash, the key inside an `a` address — `SmsEligibility.refsFor`) sent as 8-byte REFs. **Decoding** resolves the author and every REF through the phone's known keys, ids and IOU hashes (`PrefixResolver`; at most 64 candidate combinations), rebuilds the tags and content, recomputes the NIP-01 id and keeps the combination whose BIP-340 signature verifies: the result is the exact original event, and a bridging phone re-encodes it to JSON without re-signing. Truncated input, unknown flags/types, trailing bytes after the DEFLATE stream and anything that does not verify are refused. An SMS from a key the phone has never seen cannot be verified and is dropped.

### 15.3 SMS-eligible events and their worst cases

`SmsEligibility.KINDS` = 3476 check-in, 3477 SOS, 3478 SOS update, 3474 IOU signature (also the web confirmation with `code`), 3475 settlement, 3471 task steps `accept` · `picked_up` · `decline` · `delivered` (with the 6-digit `code`; a signed token does not fit), 3479 SMS DM. Field limits builders should clamp to (`SmsLimits`): check-in place label 40 characters + note 40, SOS note 60 (+ place 40), task name 40, TrxID 20, DM plaintext 32 UTF-8 bytes, geohash 9. `SmsBudgetTest` signs each type with every field at its limit (Bangla where Bangla is allowed, amount 9 999 999, a 20-character TrxID) and asserts ≤ 2 parts, ≤ 306 characters and an identical round trip:

| Event (worst case) | Characters | Parts | Payload bytes | Form |
|---|---:|---:|---:|---|
| Check-in, 40-char Bangla place + 40-char Bangla note + expiry | 288 | 2 | 200 | full |
| SOS, 40-char Bangla place + 60-char Bangla note | 291 | 2 | 206 | full |
| SOS update, `false_alarm` + 60-char Bangla note | 279 | 2 | 192 | full |
| IOU signature | 210 | 2 | 140 | full |
| IOU web confirmation (with `code`) | 229 | 2 | 154 | full |
| Settlement, Nagad, ৳9 999 999, 20-char TrxID | 274 | 2 | 188 | full |
| Task accept + 40-char Bangla name | 269 | 2 | 189 | 8-byte refs |
| Task picked_up / decline | 269 | 2 | 189 | full |
| Task delivered + code | 283 | 2 | 199 | full |
| SMS DM, 32-byte plaintext | 274 | 2 | 193 | 8-byte refs |

**Kind 3479 (SMS DM)**: `["p", recipient]`, content = NIP-44 v2 (conversation key of sender and recipient). A NIP-59 gift wrap (seal + wrap, two NIP-44 layers, a one-time key and two signatures) is well over 600 bytes and cannot fit two SMS; on the SMS rung the carrier sees both phone numbers anyway, so the wrap's metadata hiding buys nothing there. NIP-44 pads up to 32 bytes into one 99-byte payload; 33–64 bytes would need 131 and no longer fit, hence the 32-byte limit. The app shows 3479 only to its two parties and never publishes it except through a volunteer gateway the sender chose (§15.6).

### 15.4 Data SMS ("Send SMS directly", grey area)

The same payload goes as binary data SMS to port **7471** (`SmsManager.sendDataMessage`, no SMS app). A port-addressed data SMS carries 133 bytes, so the payload is split into frames `0x48 ‖ u8 set ‖ u8 (index << 4 | count) ‖ chunk` (index and count 1–15, chunks ≤ 130 bytes); frames reassemble per sender in any order and expire after 10 minutes (`DataSmsFraming`, `DataSmsAssembler`). Every eligible event fits two frames.

### 15.5 QR transport

Any event: `harkara:e:<base64url(binary event without id)>` (§5, the reader recomputes the id and checks the signature). When that is longer than **700 characters** (about QR version 20 at error correction M, easy for budget cameras), the same Base64url is cut into `harkara:q:<set>:<i>/<n>:<chunk>` codes, `set` = first 8 hex of SHA-256(binary event), n ≤ 99; `QrAssembler` accepts parts in any order and repeats, keeps sets 30 minutes, and refuses a set whose hash or signature fails. The app draws codes with ZXing (byte mode, EC M) and reads them by paste (Settings → *Open a link, QR text or SMS*); scanning with the camera is hardware (`docs/HARDWARE.md`).

### 15.6 Links

`harkara:<rest>` and `https://tanzir71.github.io/harkara/o/#<rest>` (or `/en/o/`) carry the same `<rest>` (`core/links/HarkaraLinks.kt`). The fragment is never sent to any server.

| `<rest>` | Carries | In the app | On the web page |
|---|---|---|---|
| `e:<b64url>` | one signed event (binary, §5) | verify, store by kind, lift SOS/check-in and task steps to relays | verify, show (SOS: *Call 999*) |
| `q:<set>:<i>/<n>:<chunk>` | one multi-QR part | collect until complete | — |
| `iou:<b64url JSON>` | a whole IOU bundle (M8 QR) | Khata import | — |
| `c:<b64url>` | IOU confirm link (§15.7) | open the IOU if it is ours | verify, confirm with a throwaway key |
| `handoff:<token JSON>` | handoff token (M7) | open the task's code entry, prefilled | — |
| `t:<b64url(32-byte requester key ‖ d)>` | follow a task | subscribe to the offer and its steps | offer + steps from relays |
| `g:<geohash 4–9>` | an area's feed | opens the web page | kind 1 by `#g` from relays, last 2 days |
| `gw:<b64url JSON {name, number, pubkey?}>` | a volunteer SMS gateway card | saved on the phone; suggested as SMS recipient | — |

The app's intent filters: VIEW `harkara:*`; VIEW `https://tanzir71.github.io/harkara/o/…` and `/harkara/en/o/…` (not `autoVerify`: Digital Asset Links would have to live at the root of `tanzir71.github.io`, which this project does not control, so Android 12+ opens these in the browser and the page offers *Open in Harkara*); SEND `text/plain` (a forwarded SMS or a QR text shared to Harkara). Text with an `HK1:` payload anywhere in it is an SMS.

### 15.7 IOU confirm code (someone without a key)

When an IOU's other party has no key, its terms get `confirmCodeHash` = SHA-256(code) (hex), where code = the first 8 bytes, as hex, of HMAC-SHA256(creator's private key, `harkara/iou-confirm/<IOU id>`): the creator's phone can always rebuild it, even after a restore, and stores nothing. `confirmCodeHash` is omitted from the canonical JSON when null, so older IOU hashes do not change. The SMS reminder links to `…/o/#c:<payload>`:

| Field | Size |
|---|---|
| version | u8 (1) |
| flags | u8: `0x01` the creditor signed (else the debtor), `0x02` id is a UUID, `0x04` due, `0x08` task, `0x10` currency ≠ BDT |
| code | 8 (16 hex characters) |
| sig created_at | u32 BE |
| sig | 64 |
| signer | 32 (x-only key of the party who has one) |
| id | 16, or a string |
| amount | varint |
| terms createdAt | u32 BE |
| due / task / currency | varint / string / string, when flagged |
| creditor name, debtor name, note | strings |

Strings are varint length + Text8. The reader rebuilds the canonical terms JSON (IouTerms field order, `JSON.stringify` string escaping, which kotlinx matches for valid UTF-16, `confirmCodeHash` = SHA-256 of the code), hashes it, rebuilds the signer's kind-3474 event (`["h", hash]`, `["iou", id]`, empty content) and checks its signature; the web page does the same in the browser. A new IouTerms field needs a new link version. The page's **confirmation** is kind 3474 signed by a throwaway browser key with `["h", hash]`, `["iou", id]`, `["code", code]`, published to the default relays (the creditor's app subscribes to kind 3474 by `#h` for its waiting IOUs) and offered as an `e:` link to text back. `Iou.verifyCodeConfirmation` accepts it only when exactly one party has no key, SHA-256(code) matches, the signer is not the keyed party and the signature verifies; then the IOU is **agreed**. It proves that whoever held the link confirmed, which is weaker than a co-signature (the creditor could have made it), and the ledger says so. With a key-less party, the **creditor's** own settlement settles the IOU; a debtor's own record stays *settling*.

### 15.8 The web page for non-users (`site/o/`, `site/en/o/`)

Static pages built by `tools/build-site.mjs` (same tokens, fonts and footer as the landing site; `noindex`; `referrer: no-referrer`). `tools/site/o/main.js` is bundled with esbuild (MIT, build only) together with `@noble/curves` secp256k1 Schnorr and `@noble/hashes` SHA-256 (both MIT, licence comments kept) into `site/assets/o.js` (~50 KB minified). It verifies NIP-01 ids and BIP-340 signatures in the browser, inflates compressed `e:` content with `DecompressionStream('deflate-raw')`, and talks only to `data/default-relays.json` over WebSockets: a `Content-Security-Policy` meta (`default-src 'none'; script-src 'self'; connect-src <the relays>; …`) pins that list, with no inline scripts. `verify-site` gives the o/ pages their own budget (JS ≤ 60 KB, CSS ≤ 48 KB, page ≤ 160 KB without fonts), checks the CSP lists exactly the default relays, and fails any WebSocket URL outside the o/ pages (the landing pages stay limited to `api.country.is` and GitHub). The Playwright test opens payloads signed by core (`tools/fixtures/o-payloads.json`, written and re-checked by `WebPageFixturesTest`) with relays mocked by `routeWebSocket`: verified, tampered → *Not verified*, IOU confirmation published and its signature checked, task steps with a forged one dropped, the area feed without expired or forged posts.

### 15.9 Volunteer SMS gateway (grey area)

Off by default. When on, text SMS reaching the volunteer's phone are relayed only if the sender's number is one the volunteer added (individual contacts, or one named group; numbers compared as Bangladeshi numbers in any format) and fewer than **100** SMS were relayed that Dhaka day (in and out together, `GatewayPolicy`). A relayed SMS must decode and verify (members' keys and this phone's known keys); it is stored and published unchanged. Events from the relays with a member's key in `p` are texted to that member's number, once per event id. The volunteer's number appears only in their gateway QR (`gw:`), never on any relay. Both SMS receivers are disabled manifest components until their switch is on.
