# Changelog

## 0.3.0

The Nearby feed is live over public relays, and keeps working offline from what the phone already holds.

- **Nearby** shows anonymous posts from within 3 km of your area, newest first, each with its place, distance, age, time left and how it travelled. The feed is read from the phone's encrypted database, so it still shows when there's no network.
- **Post nearby** (the + button): text plus an optional category (Traffic, Food, Crowd, Stock, Alert, Chatter) and optional details (e.g. Fuel · Available). Posts expire on loka's schedule (Traffic 3 h, Crowd 4 h, Alert 12 h, Stock 24 h, Chatter 48 h, Food never), carry proof of work tuned to the phone, and are signed with the weekly area key, so they're anonymous.
- Votes, and **block, report, mute** on every card. A post hides on your phone after reports from three different people or a score of −5. Bangla and English word filters and your own mute words apply locally.
- Composer rules from loka: up to 400 characters, one link, no phone numbers in Chatter or Alert, one post a minute (10 an hour, 30 a day). Peers drop posts that break the limits or lack proof of work.
- Relay pool: the default relays (rewritten weekly by an automatic check), NIP-11 limits, reconnects with backoff, an outbox that retries until a relay says OK, and NIP-77 negentropy catch-up on relays that offer it. Edit the list in Settings → Relays.
- The connectivity pill now shows **Online** when a relay is connected. Relays run only while the app is open; a background check every 30 minutes (only with network and battery not low) sends what's waiting and tidies storage (20 MB of other people's posts at most; your own data is never cleared).
## 0.2.0

The protocol core: everything Harkara sends is now a signed Nostr event.

- Your key is made silently the first time you pick an area. Me shows your public key (`npub1…`) and a new **Back up key** row: lock the key with a password and save it to a file you choose, or show it as a QR code.
- **Restore from backup** on the first screen brings a key back on a new phone (paste the text or open the file, then type the password).
- Panic wipe also deletes the key and its Android Keystore protection.
- Under the hood: NIP-01 events with BIP-340 signatures, NIP-44 v2 encryption inside NIP-59/NIP-17 gift wraps, NIP-49 backups, NIP-13 proof of work, a weekly anonymous area key and a daily mesh id derived from one seed, a compact binary form for Bluetooth and SMS, and the mesh relay rules (dedupe, rate limits, SOS limits, fragments, partition heal) on events. Checked against the official NIP and BIP-340 test vectors.

## 0.1.0

First APK: the app shell. Beta — most features arrive in later updates.

- Five tabs (Nearby, Chats, Tasks, Khata, Me) in the Harkara Day and Night themes, built from the Figma tokens, with Geist, Anek Bangla and JetBrains Mono bundled.
- Onboarding without sign-up: tap your area (or optionally use your location) and the Nearby feed opens.
- The connectivity pill always tells the truth; nothing is wired yet, so it says "Saved, sends later". Tap it to see how messages will get out.
- Settings → Modules lists every optional module with its default; grey-area SMS modules are off.
- Panic wipe from Settings or by tapping the logo three times; "Copy crash report"; English and Bangla, with an in-app language override.
- Update card when a newer release appears on the website (checked at most once a day).
- Landing site at https://tanzir71.github.io/harkara/ in Banglish and English.

## M0 (no release)

- Repository seeded from Nearcast under AGPL-3.0-or-later; packages renamed to `io.github.tanzir71.harkara`; design tokens and icons imported and generated for Android and the site; CI skeleton.
