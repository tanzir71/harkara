# Changelog

## 0.9.0 (unreleased)

SMS, QR and a web page for people who don't have Harkara.

- **Send by SMS**: check-ins, SOS and SOS updates, task steps (offer to help, picked up, stepped back, delivered with the code), Khata signatures and payments, and short private messages fit in **two ordinary SMS**. Your SMS app opens with the message ready; you choose who gets it and press send. Harkara needs no SMS permission for this and stores no number. The message is a short Latin line plus a code; Bangla text travels inside the code, so it stays two SMS. The other phone checks the signature and gets exactly what you signed.
- **Share as QR** for anything, as one code or a numbered set for longer things, and **Settings → Open a link, QR text or SMS** to paste what someone sent you. Harkara also opens harkara: links and text shared to it.
- **The web page for non-users** (tanzir71.github.io/harkara/o/, Banglish and English): opens a Harkara link in any browser and checks the signature right there. Someone without the app can **confirm an IOU** from your SMS reminder (Khata reminders now carry that link), **follow a task**, or **read an area's Nearby posts**. The page has no server and talks only to the public relays it lists.
- Two optional switches, **off unless you turn them on**, each saying what it costs: **Send SMS directly** (data SMS from your SIM, 1–2 SMS per message) and **Volunteer SMS gateway** (relays up to 100 SMS a day for the contacts or one group you add; your number goes only into your gateway QR). Android asks for SMS permission only when you turn one on; if it refuses, Harkara shows the steps for "Allow restricted settings".

## 0.4.0

Chats: message people and groups, talk to your area, and chat with everyone on the same Wi-Fi even when the internet is down.

- **Chats** tab: your DMs and groups, newest first, plus two rooms that are always there — **Everyone on this Wi-Fi** and your **area channel**.
- **New chat**: paste someone's key (`npub1…`) and give them a name that stays on your phone, or make a **group** from people you already chat with. Your own key is there to copy or show as a QR code. No phone number anywhere.
- Messages and groups are end-to-end encrypted (NIP-17 gift wraps) and go by every route that is open: internet relays and, at the same time, the local Wi-Fi room. With no route they wait as "Saved" and go by themselves later.
- Every message you send shows how far it got — Saved → Left this phone → Reached the network → Delivered → Read — and the glyph of the route that carried it.
- **Everyone on this Wi-Fi**: phones with Harkara open on the same Wi-Fi find each other and chat with no internet at all — a hall, a building, a campus during a power cut. DMs and groups use it too.
- **Area channel**: an anonymous room for anyone near your area (no names unless you add one); messages fade after a day.
- **Voice notes** up to 15 seconds in DMs and groups (tap the mic).
- **Cards in chats**: share a one-off location card (exact, about 100 m, or just the area, with a Plus Code) or an "I'm safe" check-in; task, IOU and pin cards show up in chats as those features arrive.
- Long-press a message to **copy** it, **make it a task** (opens the task composer with the message) or **block** the sender.
- Your name (the same one Tasks and Khata use) is asked once, the first time you send if you haven't set it yet; only the people you message see it, and it can't contain a phone number.

### Also in this release

These milestones finished ahead of their own version tags and ship here first.

#### Tasks

Tasks: local errands with signed steps and an offline handoff.

- **Tasks** tab: your tasks (posted or running) and open tasks near you. **New task** with schema-driven types from litegig (Pickup, Buy and bring, Photocopy / print, Errand), a price in taka and the type's fields. Your name is asked the first time you post or take a task — never a phone number.
- Every step is signed: offer to help → the requester **chooses** you (the accept only counts once they countersign) → picked up → delivered → paid → rated. Each phone replays the steps itself; only the right person can make each step, anything else is ignored.
- **Offline handoff**: the requester's phone shows a QR (their signed, single-use token) and 6 digits. The runner scans the QR or types the digits and their phone checks it with every radio off, then marks the task delivered.
- **Record payment** as cash or a bKash/Nagad TrxID (encrypted to the runner); the runner confirms, so both sign. Ratings both ways; **Problem** pauses the task with a reason (item, payment, delivery, safety, other).
- Reputation ("38 done") counts only people within two steps of your verified contacts.
- A Stock post's menu has **Ask someone to fetch it**, which starts a Buy-and-bring task.

#### Khata

Khata: IOUs both people sign.

- **Khata** tab: what you'll get and what you owe, filters (All, Waiting, Agreed, Settled) and the ledger. **New IOU**: they owe me / I owe them, amount, what for, their name and (optionally) their Harkara key.
- An IOU is only a **claim** until both people sign exactly the same terms; then it's **agreed**. Updates travel end-to-end encrypted (gift-wrapped) to the other person, or **face to face as a QR** with no network: show yours, they scan and sign, they show theirs back.
- **Settle** with cash or a bKash/Nagad TrxID typed as text; it's settled once both sign the same settlement.
- **Split a bill**: one IOU per person, shares that add up exactly.
- **Remind by SMS** opens your SMS app with a short Latin-letter reminder and a help link (no number is stored). **Export** the ledger as CSV and JSON.
- Paid tasks show up as settled receipts.
- IOUs also travel over the Wi-Fi room, so two people on the same Wi-Fi can co-sign and settle with no internet.

#### Tools and safety

Tools and safety.

- **SOS**: hold the connectivity pill for 3 seconds. **Call 999** comes first; **Send SOS** puts your SOS and location on every open route at top priority (optionally also by SMS from your SMS app). One SOS per 30 minutes; mark it **I'm safe now** or **False alarm**. SOS from people within 10 km show as cards with Call 999; they make a sound only if you turn on Emergency alerts.
- **Emergency handbook** in Bangla and English, offline: 42 sections on first aid, floods, cyclones, fire, earthquakes, power cuts, crowds and a go-bag, plus Bangladesh's emergency numbers (999 first).
- **Prayer times and Qibla** for your area, Karachi method with Hanafi Asr.
- **Torch, strobe, Morse SOS and siren**; everything stops when you leave the screen (the screen flashes if the phone has no controllable torch).
- **Lite mode** checks for news every 60 minutes instead of 30.

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
