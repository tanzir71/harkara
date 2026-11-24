# Unverified on hardware

Everything below is covered by fakes, Robolectric or JVM tests, but has **not** been proven on a real phone, SIM or radio. Nothing in this list blocks a milestone or a release. When someone tries it on hardware, note the phone, Android version and result next to the item.

## Bluetooth mesh

- Android 12+ runtime prompts for scan, connect and advertise; Android 8–11 location prompt for BLE scanning.
- Foreground service keeps the mesh alive with the screen off on budget phones (Xiaomi, vivo, OPPO, Realme battery managers).
- MTU negotiation at 512 bytes and fallback at the 23-byte floor.
- Relay latency and battery drain in Saver and Event modes.

## Crypto providers

- secp256k1 JNI library loads on arm64-v8a and armeabi-v7a phones running API 26–35.
- SQLCipher native library loads on API 26 phones.
