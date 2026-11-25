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
