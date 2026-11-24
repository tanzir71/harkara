# Harkara Protocol

> Inherited from Nearcast. M3 replaces these binary packets with signed NIP-01 events; this file is rewritten then.

## Packet Header

Every mesh packet starts with a 38-byte fixed header:

| Field | Size | Notes |
|---|---:|---|
| version | 1 | Protocol version, currently 1 |
| type | 1 | `ANNOUNCE=1`, `CHAT_BROADCAST=2`, `DM=3`, `POST=4`, `POST_REQUEST=5`, `SOS=6`, `ACK=7`, `FRAGMENT=8`, `SYNC_DIGEST=9`, `SYNC_PULL=10` |
| ttl | 1 | Decremented before each relay |
| flags | 1 | Bit `0x01` means a 64-byte signature follows payload |
| msgId | 8 | Random message id, big-endian |
| senderId | 16 | `NearID` bytes |
| timestamp | 8 | Epoch millis UTC, big-endian |
| payloadLen | 2 | Unsigned payload byte length, big-endian |

Signed packets append a 64-byte Ed25519 signature after payload bytes.

## Relay

Nodes keep an expiring LRU dedupe cache keyed by `msgId` with capacity 10,000 and 30-minute expiry. A packet received for the first time is stored locally and relayed to all neighbors except the source with `ttl - 1`. Packets with `ttl <= 0` are dropped.

Relay congestion primitives are deterministic in core tests:

| Primitive | Default Behavior |
|---|---|
| Token bucket | Per-peer rate limiter, default target 20 messages/second in adapters |
| Priority queue | Drops lowest priority when full |
| Priority order | `SOS > DM/ACK > CHAT/ANNOUNCE > POST/FRAGMENT > SYNC` |

## Fragments

`FRAGMENT` payloads carry enough metadata to rebuild the original packet:

| Field | Size |
|---|---:|
| original type | 1 |
| original flags | 1 |
| original msgId | 8 |
| original timestamp | 8 |
| original payload length | 4 |
| fragment index | 2 |
| fragment count | 2 |
| signature length | 1 |
| original signature | 0 or 64 |
| chunk | remaining bytes |

Reassembly is keyed by `(senderId, original msgId)` and drops incomplete state after 60 seconds.

## Identity And Crypto

`NearID` is `base32(SHA-256(ed25519_public_key)[0..15])`, encoded without padding.

Public packets are signed with Ed25519 over canonical packet bytes with the signature flag and signature bytes cleared. Verification fails if the payload, header fields, or signature are tampered.

Direct messages use X25519 ECDH followed by HKDF-SHA256 with info string `nearcast-dm-v1`. The first implementation uses the sender's X25519 identity key as the envelope public key and encrypts with AES-256-GCM using a 96-bit nonce and 128-bit tag.

## Transport Boundary

`core-protocol` defines `MeshTransport` as a packet-level interface with an incoming `Flow<MeshPacket>` and a suspending `send(packet)` function. Android modules adapt BLE and internet transports to that interface; tests use `FakeTransportHub` so app ViewModels can be exercised without radios, emulators, or servers.

## Feed Replication

Posts are cached only when their geohash starts with one of the node's subscribed prefixes. Foreign-geohash posts still return `shouldRelay=true`, so adapters can continue TTL-bounded relay without retaining them.

Partition-heal sync uses a digest/pull exchange:

1. A newly connected peer receives `SyncDigest(postIds)`.
2. It computes missing ids with `wantedFrom`.
3. It sends `SYNC_PULL` semantics for those ids.
4. The holder returns full `SocialPost` rows, which are cached only if they match local prefixes.

## Internet Relay

The FastAPI relay exposes:

| Endpoint | Purpose |
|---|---|
| `POST /items` | Bulk upsert relay items, rejecting payloads over 64 KB |
| `GET /items?geohash=&inbox=&since=` | Backfill by geohash prefix and/or inbox |
| `WS /ws?geohash=&inbox=` | Push matching uploads to connected subscribers |

`BridgeEngine` uploads local store-and-forward posts, downloads relay posts matching subscribed geohashes, and ingests them through the same feed cache rules used by mesh sync.

## Media, Reactions, Channels, ACKs

Mesh media payloads are bounded to 48 KB and tagged as `image/webp`; Android adapters are responsible for real bitmap/WebP encoding before handing bytes to core.

Reactions merge as a G-Counter by `(postId, author, kind)`, keeping each author's maximum count and summing across authors. Delivery state is monotonic: `PENDING -> MESH_DELIVERED -> ACKED`.

Channel chat payloads encode `#channel\nbody` inside `CHAT_BROADCAST` packets. `ChannelFilter` parses payloads and matches the channel hashtag.

## SOS And Off-Grid Utilities

SOS relay policy is local and abuse-resistant: muted senders are dropped, active sends are limited to one per identity per 30 minutes, and `RESOLVE` clears the active state so a genuine follow-up can be accepted.

Disaster mode derives settings rather than mutating global state: mesh duty becomes `MAXIMUM`, feed radius widens to a 4-character geohash prefix, and the UI should surface SOS. Bulletins are pinned ahead of regular feed posts; event posts expose an `eventStartTime` and are grouped by upcoming start time.

## Retention And Wipe

Retention policy purges expired posts and dedupe rows older than 30 minutes. Android wires this through a WorkManager `RetentionWorker`. Panic wipe clears all Room tables through `PanicWipeController`; Android Keystore cleanup is the next device-backed step once hardware validation begins.
