# Releasing

Releases are cut by pushing a tag `vX.Y.Z` to `main`. Nobody reviews or approves anything.

1. Bump `harkaraVersion` in `gradle.properties` and add a `## X.Y.Z` section to `CHANGELOG.md`.
2. Commit, push, wait for `ci.yml` to go green.
3. `git tag vX.Y.Z && git push origin vX.Y.Z`.

`release.yml` then builds signed release APKs with ABI splits, names them `harkara-<version>-<abi>.apk`
(`arm64-v8a`, `armeabi-v7a`, `universal`), verifies each signature against the certificate below,
writes `SHA256SUMS` and `latest.json`, creates a normal (not pre-release) GitHub release with notes from
`CHANGELOG.md`, and runs `pages.yml` so the landing page shows the new version, size and SHA-256.

`versionCode = major * 10000 + minor * 100 + patch` (computed in `app/build.gradle.kts`).

## Signing certificate

Every Harkara APK is signed with the same key. Android refuses an update signed with a different key.

```
SHA-256: B1:F7:C2:E3:97:1E:6C:07:67:DE:07:78:49:38:91:1A:66:A1:5F:41:85:CD:6B:DF:EF:96:D2:BB:BC:3B:D1:B7
Subject: CN=Harkara, OU=Harkara, O=tanzir71, L=Dhaka, C=BD
```

Check a downloaded APK with `apksigner verify --print-certs harkara-<version>-arm64-v8a.apk`.

The keystore (`harkara-release.jks`, PKCS12, RSA 4096, valid 100 years) and its password live outside the
repository in `../harkara-signing/` on the owner's machine, and as the repository secrets
`HARKARA_KEYSTORE_B64`, `HARKARA_KEYSTORE_PASS`, `HARKARA_KEY_ALIAS`, `HARKARA_KEY_PASS`.
`.gitignore` blocks `*.jks`, `*.keystore` and `harkara-signing/`.

## latest.json

```json
{ "version": "0.1.0", "versionCode": 100, "published": "…", "apks": [{ "abi": "arm64-v8a", "url": "…", "bytes": 0, "sha256": "…" }], "notes": "…" }
```

The app reads `https://tanzir71.github.io/harkara/latest.json` at most once a day while online.
