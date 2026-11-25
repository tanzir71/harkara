# Unverified on hardware

Everything below is covered by fakes, Robolectric or JVM tests, but has **not** been proven on a real phone, SIM or radio. Nothing in this list blocks a milestone or a release. When someone tries it on hardware, note the phone, Android version and result next to the item.

## Bluetooth mesh

- Android 12+ runtime prompts for scan, connect and advertise; Android 8–11 location prompt for BLE scanning.
- Foreground service keeps the mesh alive with the screen off on budget phones (Xiaomi, vivo, OPPO, Realme battery managers).
- MTU negotiation at 512 bytes and fallback at the 23-byte floor.
- Relay latency and battery drain in Saver and Event modes.
- Dual-role GATT advertising/scanning, notification subscriptions, one-at-a-time writes and simultaneous links are unverified on hardware. Saver scans 10 s per minute (4 links, TTL 5); Event scans continuously (8 links, TTL 7).
- Wi-Fi Aware discovery-message delivery and OEM payload limits, Bluetooth/Wi-Fi coexistence, rotating presence and RSSI range estimates are unverified on hardware. Aware is optional; unavailable radios leave events Saved.
- The connected-device foreground notification and explicit stop, bridge reachability propagation and OEM screen-off restrictions are unverified on hardware. No boot receiver or sticky radio restart is installed.
- Node/APK download reachability on real hotspots, multicast isolation and Android firewall/VPN rules are unverified on hardware. Sharing ends when its screen closes; node mode continues only after explicit opt-in.

## Crypto providers

- secp256k1 JNI library loads on arm64-v8a and armeabi-v7a phones running API 26-35.
- secp256k1-kmp 0.16.0's `libsecp256k1-jni.so` is not 16 KB page aligned; it may fail to load on 16 KB page-size devices (Android 15+ with 16 KB pages) until the library is upgraded.
- SQLCipher native library loads on API 26 phones.

## Keys and backup

- Keystore wrapping unverified on hardware: the root seed is sealed with an AndroidKeyStore AES-256-GCM key (`AndroidKeystoreSeedWrapper`). Robolectric has no AndroidKeyStore, so tests cover the plain-file fallback and a fake AES-GCM wrapper only. Also unverified: behaviour after a keystore reset (the app should start a new identity and offer restore).
- NIP-49 backup and restore use scrypt with log_n 16 (64 MiB, about 0.5 s on a laptop JVM); time and memory on a 2 GB budget phone are untested.
- "Save to a file…" and "Open a file…" go through the Storage Access Framework; OEM file pickers (Xiaomi, vivo, OPPO, Realme) are untested.
- The backup QR (240 dp, error correction M) has not been scanned by a real camera yet (M10 supports paste; an in-app camera scanner is not implemented).
## Storage and relays

- SQLCipher's native library loads and opens the encrypted database on API 26 phones and on 32-bit armeabi-v7a.
- WorkManager's 30-minute relay check actually runs on budget phones with aggressive battery managers.
- Live WebSocket connections to the public relays survive switching between mobile data and Wi-Fi.

## Maps, location and sensors

- MapLibre Native 13.7.1 renders the installed pack through `pmtiles://file://…` on real phones (OpenGL ES, TextureView mode) on API 26-35, including 32-bit armeabi-v7a; untested on any device. If it fails, a small local HTTP handler over `core/map/PmTiles.kt` is a possible fallback, not an implemented runtime fallback.
- Bangla labels (`name:bn`, then `name`, with bundled Noto Sans 2304-2559 glyphs): Bengali complex-script shaping is unverified on hardware; conjuncts and vowel signs may be broken or misplaced. The style falls back by property availability, not shaping failure; switching the app to English selects `name:en`, then `name`.
- Native libraries stored compressed (`useLegacyPackaging`) extract correctly at install on budget phones and the ~20 MB extra installed size is acceptable.
- Map frame rate, memory and battery while panning the z15 pack on a 2 GB phone; Compose markers staying aligned with the map during fast pans and zooms.
- GPS / fused accuracy on real phones: the live share and the map use LocationManager (fused provider on Android 12+, else GPS and network) with no Play Services. Accuracy indoors, under Dhaka's dense buildings and with only approximate permission is untested.
- The live-share foreground service (type `location`) keeps sending with the screen off on Xiaomi, vivo, OPPO and Realme battery managers, and stops at the share's end; Android 14+ accepts starting it right after the permission prompt.
- Compass: rotation-vector sensor (fallback accelerometer + magnetometer) and the magnetic declination correction; calibration and interference near steel structures and inside rickshaws/buses are untested. Phones without a magnetometer show the bearing from north instead.
- The trip-plan WorkManager job fires near the expected arrival with the phone idle (Doze may delay it by minutes) and the overdue alert goes out as soon as any rung opens. On the watcher's side the alert arrives with the app open or during the 30-minute background check, then shows a high-priority notification (Android 13+ only after notifications were allowed).
- Map pack download (22.8 MB) over slow mobile data currently restarts from zero after a dropped connection; interruption behavior and SHA-256 check time on a budget phone are unverified on hardware.
- Importing a pack or a GPX/KML/GeoJSON file through the Storage Access Framework from OEM file pickers.

## Tools

- Camera torch control (CameraManager.setTorchMode) on budget phones; strobe timing at 80/120 ms.
- Siren volume through STREAM_ALARM and the emergency-alert tone on real speakers.
- `tel:999` opens the dialer on phones without a SIM.

## Chats and the Wi-Fi room (M5)

- NSD (mDNS/DNS-SD) discovery of `_harkara._tcp` on real Wi-Fi: routers that block multicast or isolate clients (common on campus and hotel Wi-Fi) will hide phones from each other; behaviour on phone hotspots is untested.
- The Wi-Fi multicast lock (`CHANGE_WIFI_MULTICAST_STATE`) is needed on some phones to receive mDNS; whether it is enough on Xiaomi, vivo, OPPO and Realme builds is unverified.
- Resolving many services at once before Android 14 (one resolve at a time, queued) and service names that the router renames on collision.
- The room server binding a port on the Wi-Fi interface, and peers reaching it through Android's firewall/VPN apps; IPv6-only networks.
- Behaviour when the phone switches Wi-Fi networks while the room is open (servers and pools are restarted only on the next foreground).
- `MediaRecorder` voice notes: Opus/OGG at 16 kbps on Android 10+ and AAC/MP4 at 24 kbps on Android 8–9, mono 16 kHz, stopped at 15 s; the recorded size (expected 30–45 KB, at most 128 KB) and playback of OGG/Opus notes on Android 8–9 receivers (which may not decode Opus) are untested.
- The microphone permission prompt and recording while another app holds the microphone.
- "Share my location" reads the last known fix only (no GPS is switched on); phones that have no recent fix will offer only "Area".

## LAN file drop and push-to-talk (M11)

- LocalSend v2 HTTP interoperability with other clients, manual numeric IPv4/port reachability, hotspot/client isolation and firewall/VPN behavior are unverified on hardware. There is no multicast LocalSend discovery or TLS; aliases/fingerprints are unauthenticated claims, so use only a user-selected trusted LAN and explicitly approve file offers.
- Storage Access Framework pick/save behavior, document providers reporting a known file size and interruption of streamed transfers are unverified on hardware. Default limits are 25 MiB/file and 100 MiB/offer; received files persist in a bounded private inbox. Save copies a file elsewhere; Delete removes the private copy.
- `AudioRecord`/`AudioTrack` microphone permission, 16 kHz mono PCM16 capture/playback, latency, feedback, audio focus and the 15-second held-to-talk cutoff are unverified on hardware. PTT requires explicit listening and a peer-IP-bound bearer token; HTTP exposes that token and audio to LAN observers.
- Releasing/cancelling a hold or losing focus stops capture; `ON_STOP` and screen disposal close the listener, pending transfers and audio. OEM timing and resource release on real devices are unverified; returning to foreground requires an explicit listener restart, and these tools use no background service.

## SMS, QR and links (M10)

- The SMS app opens with the text prefilled from `ACTION_SENDTO smsto:` + `sms_body` on common OEM SMS apps (Google Messages, Samsung, Xiaomi, vivo, OPPO, Realme); some apps may drop the body or split it differently.
- Two-part GSM-7 SMS (306 characters) are joined again on the receiving phone on Grameenphone, Robi, Banglalink and Teletalk, and survive forwarding; the receiver can paste or share the text into Harkara.
- "Send SMS directly": `SmsManager.sendDataMessage` to port 7471 between Bangladeshi operators (some networks drop or charge data SMS differently), and `DATA_SMS_RECEIVED` delivery to the enabled receiver on API 26–35.
- Volunteer gateway: `SMS_RECEIVED` with a two-part text SMS from a member, publishing to relays when the volunteer's phone is online, and `sendMultipartTextMessage` back to a member; the daily cap with real traffic.
- Android 13+ restricted settings: on a sideloaded APK the SMS permission request is blocked until App info → ⋮ → Allow restricted settings; the guidance screen's steps and wording on Pixel, Samsung, Xiaomi and vivo settings apps.
- Enabling/disabling the SMS receivers with `setComponentEnabledSetting` takes effect without restarting the app on budget phones.
- QR: the in-app scanner uses ZXing Android Embedded behind `QrCamera`, opens only after an explicit scan and camera permission, and releases preview/decoding on pause, disposal, Stop or a result. Camera focus, low light, frame orientation, driver errors and 700-character codes on actual phones remain unverified on hardware. Fake adapter/permission/lifecycle tests and generated QR pixels cover the automated path. Paste remains available without camera permission; the system camera app handing `harkara:` links to Harkara is also unverified on hardware.
- Deep links: Chrome and other browsers on Android handing `harkara:` links to the app from the web page's "Open in Harkara" button; `https://tanzir71.github.io/harkara/o/…` links open in the browser on Android 12+ (not verified) and in the app chooser on older versions.
- The web page on budget-phone browsers: `DecompressionStream('deflate-raw')` support (old Chrome or WebView versions), WebSocket connections to the public relays over mobile data, and Bangla rendering with the bundled fonts.
