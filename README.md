# Harkara · হরকরা

**Net nai? Harkara ache.** Dhaka-r off-grid messenger. Internet thakle public relay, na thakle Bluetooth mesh, hall-er Wi-Fi, SMS ba QR diye message, kaaj, khata ar map. Free, open source, signup nai.

**No signal? Harkara gets through.** An off-grid app for Dhaka: messages, errands, IOUs and maps that travel phone to phone — over internet relays when there is a connection, and over Bluetooth, campus Wi-Fi, SMS or a QR code when there isn't. No server, no sign-up, no ads.

A neighbourhood and campus app for floods, power cuts and everyday life.

## Install · APK namao

- Website: https://tanzir71.github.io/harkara/ (English: https://tanzir71.github.io/harkara/en/)
- Latest APK: https://github.com/tanzir71/harkara/releases/latest — `arm64-v8a` for most phones, `armeabi-v7a` for older ones.
- Obtainium: `obtainium://add/https://github.com/tanzir71/harkara`

Android 8+ · Check the SHA-256 from the release's `SHA256SUMS`; every update is signed with the same key.

## Five tabs · Panch ta tab

| Tab | What it does |
|---|---|
| Nearby · কাছে | Anonymous local feed (Traffic, Food, Crowd, Stock, Alert, Chatter) that expires; feed ⇄ map |
| Chats · চ্যাট | DMs, groups, area channels, "everyone on this Wi-Fi"; voice notes |
| Tasks · কাজ | Local errands with signed steps and an offline handoff code |
| Khata · খাতা | IOUs both people sign; settle with a bKash/Nagad TrxID |
| Me | Profile and keys, tools, Settings → Modules |

Every post, message, task, pin and IOU is a signed Nostr-shaped event carried over a ladder of routes: internet relays → a nearby phone with data → neighbourhood node (LAN) → Bluetooth mesh → SMS → QR → saved on the phone.

## Build

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
node tools/build-site.mjs && node tools/verify-site.mjs
```

JDK 17, Android SDK 35. See `AGENTS.md`, `TASKS.md` and `docs/`.

## Licence

AGPL-3.0-or-later — see `LICENSE`. Third-party code and assets are listed in `NOTICE`.
