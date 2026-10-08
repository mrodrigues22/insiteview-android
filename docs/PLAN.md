# insiteview-android plan and checklist

> As of 2026-10-08 · The master plan (scope, architecture, API contract, asset contract, milestones)
> is `insiteview-api/docs/PLAN.md` (M7 · Android app); § numbers without a repo refer to it. The
> iPhone app (`insiteview-ios/docs/PLAN.md`) is the functional spec: this app does what it does,
> screen for screen. The differences are in §6, each with its reason.

Someone scans a plate with an Android phone and sees the building's systems aligned in their room.
With the app installed, the printed URL opens it directly (App Links); without it, the web viewer
opens and offers "Get the app", which brings the visitor back to the same building after install.

## 1. Platform

- **Language and UI:** Kotlin 2.4, Jetpack Compose (Material 3 underneath, the blueprint design on top), coroutines and `StateFlow`.
- **3D and AR:** SceneView 4.53 (Filament + ARCore 1.56). The API's GLB chunks (gltfpack, `EXT_meshopt_compression`, `KHR_mesh_quantization`) load through Filament's gltfio. ARCore is optional in the manifest: phones without it get 3D and no AR, as iPhones without AR would.
- **Devices:** minSdk 26 (Android 8.0). Target: a mid-range 2023 phone (Pixel 7a-class) at 60 fps, a low-end ARCore phone at 30 fps. The ARCore Depth API stands in for LiDAR where supported.
- **Build:** Gradle 9.8, AGP 9.4 (built-in Kotlin and the new DSL in Android modules; the Kotlin Gradle plugin for the JVM modules), version catalog in `gradle/libs.versions.toml`.
- **Build types:** `debug` (applicationId suffix `.local`; `local.properties` can set `iv.apiBaseUrl` / `iv.webBaseUrl`), `staging` (`.staging`), `release`. Until a staging environment exists, all three point at production, as on iOS.

| BuildConfig key | debug | staging | release |
|---|---|---|---|
| `API_BASE_URL` | `local.properties` or production | `https://api.getinsiteview.com` (→ staging API when it exists) | `https://api.getinsiteview.com` |
| `WEB_BASE_URL` | `local.properties` or production | `https://getinsiteview.com` | `https://getinsiteview.com` |
| `SENTRY_DSN` | empty | empty until AND-M6-03 | same |
| `PLAY_STORE_PACKAGE` | `com.getinsiteview.android` | same | same |

## 2. Code structure

```
insiteview-android/
├─ settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├─ core/  modelkit/  api/          # Kotlin/JVM, tested in CI and in Claude Code sessions
├─ design/ scene/ ar/ features/    # Android libraries
├─ app/                            # the application: tabs, sign-in, scanner, profile, App Links
├─ openapi/openapi.json            # synced from insiteview-api
├─ scripts/{sync-openapi.sh,xcstrings-to-android.py}
└─ docs/{PLAN.md,device-test.md}
```

The module table and the porting rules are in `CLAUDE.md`. Architecture follows iOS: one ViewModel
per screen (iOS: one `@Observable` model), an `AppDependencies` container created by the
Application, Navigation Compose with typed routes, and every deep link through `core.Router`.

## 3. Key designs

Everything in `insiteview-ios/docs/PLAN.md` §3 applies. Android-specific notes:

### Networking and auth

- OkHttp with hand-written `@Serializable` models for these operations (the iOS generator filter):
  `health_ready`, `public_building`, `public_visit_by_code`, `public_visit_by_link`, `visit_manifest`,
  `visit_element`, `visit_unlock`, `visit_documents`, `visit_search`, `events_create`, `model_manifest`,
  `model_element`, `auth_login`, `auth_register`, `auth_refresh`, `auth_logout`, `auth_oauth_providers`,
  `auth_oauth_exchange`, `auth_verify_email_send`, `me_get`, `me_update`, `me_delete`, `me_buildings`,
  `me_save_building`, `me_unsave_building`, `search_mine`, `search_building`, `documents_list`,
  `buildings_get`, `orgs_list`, `plates_list`, `plates_update`, `visit_room_observations_create`,
  `room_corrections_set`, plus the hand-written conditional `catalog_get`. (`auth_apple_native` is iOS only.)
- Why not generated: openapi-generator's Kotlin client (7.26) is blocking, wraps errors in its own
  exceptions and turns unknown enum values into a placeholder that loses the raw string. The models are
  small; `ApiContractTest` checks every property name and requiredness against `openapi/openapi.json`,
  so a spec sync that breaks a model fails CI, as a compile error does on iOS.
- Headers: `X-Device-Id`, `X-Client: android`, `X-Client-Version` (`versionName`), `Accept-Language`
  (the app's locales, up to 6 with q-values), `Authorization` (visit token for `visit_*`, the user's elsewhere,
  none for `auth_*`, `health_*`, `catalog_get`). Below `Clients__AndroidMinVersion`, 426 `client.outdated`
  → "Update required" → Google Play.
- Tokens: Android Keystore AES-GCM key, ciphertext in DataStore (iOS: Keychain). Device id: a random UUID in DataStore.
- Sign-in: Google, Microsoft and Apple through Custom Tabs → `/v1/auth/oauth/{provider}/start?returnTo=insiteview://auth/callback`
  → the `insiteview://auth/callback` intent → `auth_oauth_exchange`. Email and password. No SDKs.

### Loading a building

As iOS, with `glb` in place of `usdz`: `ChunkPlan.requests(kinds = [meta, glb])`, architecture first,
3 at a time, sha256 checked, cache in `cacheDir/models/{sha256}.glb`, LRU at 1 GB, the open building pinned.
One `BuildingScene` (Filament entities under one root) moves between the 3D viewer and AR.

### AR

- Plates: ARCore Augmented Images built from each plate's PNG (`imageUrl`) with its physical width
  (`sizeMm`). ARCore's image pose has +Y along the image normal, +Z down the image; `AnchorMath`
  converts it to IVModelKit's `PlateFrame` convention (the iOS ARKit conversion has the same role) and is unit-tested.
- The solver, smoothing, re-anchoring, reference points, sites, room corrections, locate and the
  behind-wall rule are the iOS code ported to `:modelkit`, with the iOS tests ported.
- LiDAR marks (walls, wall corners) use ARCore Depth hit tests on devices that support depth, and
  are hidden elsewhere (iOS hides them without LiDAR).

### Guest entry (no App Clip)

- App Links: `https://getinsiteview.com/b/*` and `/a/*`, `autoVerify`, served by the web's
  `/.well-known/assetlinks.json` (`ANDROID_PACKAGE`, `ANDROID_SHA256_CERT_FINGERPRINTS`).
- Not installed: the web viewer (as today). On Android the web landing shows "Get the app": Google Play
  with `referrer=code=…&plate=…`; on first launch the app reads the Install Referrer once
  (`BuildingHandoff`) and opens that building, as the iOS app does with the App Clip handoff.

## 4. Testing

- `:core`, `:modelkit`, `:api`: JUnit 5, ported from the iOS suites case for case, plus Android-only
  cases (Install Referrer handoff, ARCore pose conversion, the API contract test). Run anywhere with a JDK.
- `:features`: ViewModel tests on the JVM (JUnit 5, coroutines-test), string parity.
- CI: GitHub Actions on every push: JVM tests, `assembleDebug`, `testDebugUnitTest`, `lintDebug`.
- Device: `docs/device-test.md`.

## 5. Release

- Google Play: internal testing track from `main`, production from `v*` tags (workflow in AND-M6-04).
  Upload key from repository secrets (`IV_UPLOAD_KEYSTORE_BASE64`, `IV_UPLOAD_KEYSTORE_PASSWORD`,
  `IV_UPLOAD_KEY_ALIAS`, `IV_UPLOAD_KEY_PASSWORD`); Play App Signing holds the app key. Its SHA-256
  goes into the web's `ANDROID_SHA256_CERT_FINGERPRINTS`.

## 6. Differences from iOS

| iOS | Android | Why |
|---|---|---|
| App Clip | App Links + web viewer + "Get the app" with Install Referrer handoff | Android has no App Clips; Google Play Instant is retired |
| Sign in with Apple (native) | Apple through the web OAuth flow (Custom Tab) | Native Apple sign-in exists only on Apple platforms |
| USDZ chunks (RealityKit) | GLB chunks (Filament) | Each platform gets its native format (master PLAN §6) |
| ARKit image detection, LiDAR | ARCore Augmented Images, Depth API | Platform equivalents |
| QuickLook | `ACTION_VIEW` through a FileProvider | Platform equivalent |
| VisionKit scanner | CameraX + ML Kit barcode scanning | Platform equivalent |
| `SKOverlay` / App Store | Google Play (`market://`) | Platform equivalent |
| Language: system settings link | Per-app language (Android 13+), system settings link before | Platform equivalent |

## 7. Checklist

### AND-M0 · Foundations

- [x] **AND-M0-01** Gradle project: version catalog, eight modules (three JVM, five Android), build types, `local.properties` overrides, JVM-only builds for Claude Code sessions.
- [x] **AND-M0-02** CI on every push: JVM tests, Android build, unit tests, lint.
- [x] **AND-M0-03** `CLAUDE.md`, this plan, `docs/device-test.md`, `scripts/sync-openapi.sh`.
- [ ] **AND-M0-04** (you) Google Play Console: app `com.getinsiteview.android`, Play App Signing, upload key, internal testing track; repository secrets for the upload key; the web's `ANDROID_PACKAGE` and `ANDROID_SHA256_CERT_FINGERPRINTS` in production.

### AND-M1 · Logic modules

- [ ] **AND-M1-01** `:core` ← IVCore, with IVCoreTests ported; Install Referrer handoff parsing.
- [ ] **AND-M1-02** `:modelkit` ← IVModelKit (manifest, chunks, cache, element index, filters, all geometry), with IVModelKitTests ported and GLB in the chunk plan.

### AND-M2 · API client

- [ ] **AND-M2-01** `:api` ← IVAPI: models, `ApiClient`, interceptors, `ApiError`, credentials and refresh, `BuildingConnection`, PIN, analytics uploader, catalog, my buildings, search, documents, account and OAuth; IVAPITests ported; `ApiContractTest`.

### AND-M3 · Guest flow and 3D

- [ ] **AND-M3-01** (needs device check) `:design` ← IVDesign: palette, Anton/Barlow/JetBrains Mono, buttons, chips, loading bar, detent slider, wordmark.
- [ ] **AND-M3-02** (needs device check) `:scene` ← IVScene: GLB chunks in one root, catalog materials, visibility and opacity, highlight, picking, locate pulse, behind-wall fade, orbit viewer.
- [ ] **AND-M3-03** (needs device check) Guest screens: guest flow, landing, PIN, problem states and access gate, building home, status line, 3D viewer, rooms, documents, object card, diagnostics.

### AND-M4 · AR

- [ ] **AND-M4-01** (needs device check) `:ar` ← IVAR: ARCore session, Augmented Images from plates, raycasts, depth marks, site anchors, `AnchorMath`.
- [ ] **AND-M4-02** (needs device check) AR screens: preflight, AR experience (plate coaching, status tag, menu, re-align), points alignment, manual and fine-tune, locate, See inside, plate registration, room corrections, "Fix here", safety sheet.

### AND-M5 · Signed-in app

- [ ] **AND-M5-01** (needs device check) App: tabs (Buildings, Search, Scan, Profile), sign-in (Custom Tabs OAuth, email), update required, App Links and the `insiteview://auth/callback` intent, Install Referrer handoff, save building.

### AND-M6 · Localization and release

- [ ] **AND-M6-01** Strings: `scripts/xcstrings-to-android.py` converts the iOS String Catalog (en, pt-BR, es) into `strings.xml`; native review with iOS's IOS-M5-01.
- [ ] **AND-M6-02** App icon (adaptive, monochrome) from `insiteview-api/brand/render.py`.
- [ ] **AND-M6-03** Sentry in the app (with iOS's IOS-M5-04), Play data safety form.
- [ ] **AND-M6-04** Release workflow: signed `bundleRelease`, upload to the internal track.
- [ ] **AND-M6-05** Run `docs/device-test.md` on two phones and a printed plate; App Link verification on a release build.
