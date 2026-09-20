# ProofStamp — Verifiable Timestamp Camera

Offline-first evidence camera for field work. Every photo gets a visible proof stamp
(date/time to the second, GPS, project, operator, note, session #), a unique Photo ID,
a short verification code / QR, a SHA-256 content hash and an on-device ECDSA signature
(Android Keystore). Nothing leaves the device; no login, backend or paid API.

Kotlin · Jetpack Compose · CameraX · Room · DataStore · minSdk 26 · targetSdk 35

## Build

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:testDebugUnitTest      # unit tests
./gradlew :app:lintDebug              # lint
./gradlew :app:bundleRelease          # AAB for Google Play
```

## Release signing & AdMob IDs

Copy `keystore.properties.example` to `keystore.properties` (git-ignored) and fill in the
keystore path/passwords plus production AdMob IDs. Without it, the release bundle is
unsigned and Google's AdMob **test** IDs are used.

## Module map

| Package | Purpose |
|---|---|
| `capture/` | `OverlayRenderer` (4 templates, shared by live preview & final image), `CaptureProcessor` (stamp → JPEG → EXIF → hash → sign → Room) |
| `data/crypto/` | SHA-256, canonical `ProofManifest`, Keystore `ProofSigner`, ID/code generation |
| `data/db/` | Room: photos, capture sessions, project presets |
| `share/` | `Verifier` (re-hash & compare), `ExportManager` (original / Clean Share, ZIP + report) |
| `ads/` | Banner (gallery/settings only) + rate-limited interstitial after exports |
| `ui/` | Camera, Gallery, Photo detail, Verify, Sessions, Presets, Settings |
