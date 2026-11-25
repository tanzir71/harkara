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
## Reproducible builds

The build sets fixed archive timestamps and entry order (`AbstractArchiveTask` in the root build), leaves
out the dependency-metadata signing block (`dependenciesInfo`) and VCS info, and pins every dependency in
the version catalog. `.github/workflows/reproducible.yml` (on every tag, or by hand) builds the unsigned release APKs twice
from clean checkouts on separate runners and compares them entry by entry with `tools/compare_apks.py`;
content/container differences are informational and never gate a release, while missing or invalid comparison inputs fail the check.
The workflow fixes timezone/locale and `SOURCE_DATE_EPOCH`, disables build/configuration caches, uses the tag's version and retains the complete `reproducibility-report` artifact and job summary.
Fresh-runner unsigned builds retry at most three times for transient dependency/plugin-resolution failures;
the two jobs still start from separate clean checkouts, and persistent failures fail the workflow.

The v0.5.0 run `45566932709` compared independent clean builds and reported `MATCH` for arm64, ARMv7 and universal APKs: identical ZIP entry contents/metadata and whole-file hashes. Its report is retained as `reproducibility-report`. This is historical evidence, not a claim about a later tag; each final tag repeats the comparison.

## Final handover release: v0.11.0 (2026-11-25)

The release source is tag `v0.11.0`, commit `b992e43f5e54e0c7ba008583cd68a769e83a321b`.

- [CI 45595278311](https://github.com/tanzir71/harkara/actions/runs/45595278311): 285 core and 285 app tests, Day/Night screenshot verification, lint and assembly; ABI size gate; 816 translated app resources, 24 normal-text contrast checks and 80 extended accessibility states at 1.3× font scale; 40 browser cases in each of the coming-soon and fixture-release states.
- [Release 45596337546](https://github.com/tanzir71/harkara/actions/runs/45596337546): all three APKs signed and verified against the certificate above. Uploaded asset hashes and sizes match `latest.json` and [SHA256SUMS](https://github.com/tanzir71/harkara/releases/download/v0.11.0/SHA256SUMS).
- [Clean builds 45596337597](https://github.com/tanzir71/harkara/actions/runs/45596337597): **MATCH** for arm64, ARMv7 and universal unsigned APKs. Whole-file hashes and ZIP entry order are identical; there are no remaining differences. The complete [comparison report](reproducibility-v0.11.0.md) is committed here and [attached to the release](https://github.com/tanzir71/harkara/releases/download/v0.11.0/reproducibility-v0.11.0.md), independent of Actions artifact retention. These unsigned hashes are for build comparison; use the release's `SHA256SUMS` to verify downloaded signed APKs.
- [Pages 45596605541](https://github.com/tanzir71/harkara/actions/runs/45596605541): deployment succeeded. Live Banglish and English landing pages point to 0.11.0; the live `latest.json` version, ABI URLs, byte sizes and hashes match the signed release.

| Signed APK | Bytes | Budget |
|---|---:|---|
| arm64-v8a | 23,156,938 | < 30,000,000 |
| armeabi-v7a | 22,119,830 | < 30,000,000 |
| universal | 46,880,986 | reported, not limited |

All handover milestones through M12 are complete. The landing keeps its beta notice. Hardware-only
behavior remains unverified as listed in `HARDWARE.md`; version 1.0 is left for the owner to declare.

## Map data

Map refresh is separate and non-gating: `mappack.yml` runs monthly; its manual trigger is `gh workflow run mappack.yml --ref main`. It publishes a `maps-YYYYMM` release without marking it as the latest app release. Implementation/tests do not fetch map or live-relay datasets. The no-pack Lite fallback works until a pack is available or imported from a friend.

## APK size

`ci.yml` builds the unsigned release APKs with ABI splits and fails if an `arm64-v8a` or `armeabi-v7a`
APK exceeds 30 MB (`tools/check-apk-size.mjs`, 30 000 000 bytes per ABI); the universal APK is reported only.
Both ABI splits must be present, nonempty and uniquely named. The reproducibility builds also run this gate; `tools/release-meta.mjs` runs it before writing publication metadata.

Local split builds use `./gradlew :app:assembleRelease -PharkaraAbiSplits=true`. Do not mix old unsplit APKs into the release output directory: the gate rejects unexpected/duplicate artifacts. On Windows, stop the Gradle daemon before a clean if lint has an output JAR locked. The integrated map-enabled unsigned checkpoint measured 23,050,590 bytes (arm64) and 22,013,478 bytes (ARMv7), both below the decimal 30 MB limit.
