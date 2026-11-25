# Unverified on hardware

Everything below is covered by fakes, Robolectric or JVM tests, but has **not** been proven on a real phone, SIM or radio. Nothing in this list blocks a milestone or a release. When someone tries it on hardware, note the phone, Android version and result next to the item.

## Bluetooth mesh

- Android 12+ runtime prompts for scan, connect and advertise; Android 8–11 location prompt for BLE scanning.
- Foreground service keeps the mesh alive with the screen off on budget phones (Xiaomi, vivo, OPPO, Realme battery managers).
- MTU negotiation at 512 bytes and fallback at the 23-byte floor.
- Relay latency and battery drain in Saver and Event modes.

## Crypto providers

- secp256k1 JNI library loads on arm64-v8a and armeabi-v7a phones running API 26-35.
- secp256k1-kmp 0.16.0's `libsecp256k1-jni.so` is not 16 KB page aligned; it may fail to load on 16 KB page-size devices (Android 15+ with 16 KB pages) until the library is upgraded.
- SQLCipher native library loads on API 26 phones.

## Keys and backup

- Keystore wrapping unverified on hardware: the root seed is sealed with an AndroidKeyStore AES-256-GCM key (`AndroidKeystoreSeedWrapper`). Robolectric has no AndroidKeyStore, so tests cover the plain-file fallback and a fake AES-GCM wrapper only. Also unverified: behaviour after a keystore reset (the app should start a new identity and offer restore).
- NIP-49 backup and restore use scrypt with log_n 16 (64 MiB, about 0.5 s on a laptop JVM); time and memory on a 2 GB budget phone are untested.
- "Save to a file…" and "Open a file…" go through the Storage Access Framework; OEM file pickers (Xiaomi, vivo, OPPO, Realme) are untested.
- The backup QR (240 dp, error correction M) has not been scanned by a real camera yet (the in-app scanner arrives in M10).
## Storage and relays

- SQLCipher's native library loads and opens the encrypted database on API 26 phones and on 32-bit armeabi-v7a.
- WorkManager's 30-minute relay check actually runs on budget phones with aggressive battery managers.
- Live WebSocket connections to the public relays survive switching between mobile data and Wi-Fi.

## Maps, location and sensors

- MapLibre Native 13.7.1 renders the installed pack through `pmtiles://file://…` on real phones (OpenGL ES, TextureView mode) on API 26-35, including 32-bit armeabi-v7a; untested on any device. If it fails, the fallback is a small local HTTP handler over `core/map/PmTiles.kt`.
- Bangla labels: MapLibre Native has no complex-script shaping for Bengali, so `name` / `name:bn` labels (glyphs from the bundled Noto Sans 2304-2559 range) may show broken conjuncts and misplaced vowel signs. Unverified on hardware; switching the app to English shows `name:en` labels instead.
- Native libraries stored compressed (`useLegacyPackaging`) extract correctly at install on budget phones and the ~20 MB extra installed size is acceptable.
- Map frame rate, memory and battery while panning the z15 pack on a 2 GB phone; Compose markers staying aligned with the map during fast pans and zooms.
- GPS / fused accuracy on real phones: the live share and the map use LocationManager (fused provider on Android 12+, else GPS and network) with no Play Services. Accuracy indoors, under Dhaka's dense buildings and with only approximate permission is untested.
- The live-share foreground service (type `location`) keeps sending with the screen off on Xiaomi, vivo, OPPO and Realme battery managers, and stops at the share's end; Android 14+ accepts starting it right after the permission prompt.
- Compass: rotation-vector sensor (fallback accelerometer + magnetometer) and the magnetic declination correction; calibration and interference near steel structures and inside rickshaws/buses are untested. Phones without a magnetometer show the bearing from north instead.
- The trip-plan WorkManager job fires near the expected arrival with the phone idle (Doze may delay it by minutes) and the overdue alert goes out as soon as any rung opens. On the watcher's side the alert arrives with the app open or during the 30-minute background check, then shows a high-priority notification (Android 13+ only after notifications were allowed).
- Map pack download (22.8 MB) over slow mobile data resumes cleanly after a dropped connection (it restarts from zero today) and the SHA-256 check finishes quickly on a budget phone.
- Importing a pack or a GPX/KML/GeoJSON file through the Storage Access Framework from OEM file pickers.
