# insiteview-android plan and checklist

> As of 2026-10-08 (every module ported; CI green; nothing run on a device yet) · The master plan (scope, architecture, API contract, asset contract, milestones)
> is `insiteview-api/docs/PLAN.md` (M7 · Android app); § numbers without a repo refer to it. The
> iPhone app (`insiteview-ios/docs/PLAN.md`) is the functional spec: this app does what it does,
> screen for screen. The differences are in §6, each with its reason.

Someone scans a plate with an Android phone and sees the building's systems aligned in their room.
With the app installed, the printed URL opens it directly (App Links); without it, the web viewer
opens and offers "Get the app", which brings the visitor back to the same building after install.

## 1. Platform

- **Language and UI:** Kotlin 2.4, Jetpack Compose (Material 3 underneath, the blueprint design on top), coroutines and `StateFlow`.
- **3D and AR:** SceneView 4.53 (Filament + ARCore 1.56); AR behaviour and the ARKit → ARCore mapping in §3 "AR". The API's GLB chunks (gltfpack, `EXT_meshopt_compression`, `KHR_mesh_quantization`) load through Filament's gltfio. ARCore is optional in the manifest: phones without it get 3D and no AR, as iPhones without AR would.
- **Devices:** minSdk 26 (Android 8.0). Target: a mid-range 2023 phone (Pixel 7a-class) at 60 fps, a low-end ARCore phone at 30 fps. Phones with a time-of-flight depth sensor get the LiDAR features (wall and corner marks); others behave as iPhones without LiDAR (§3 "AR").
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

The iOS AR is specified by its code; `docs/ios-ar-reference.md` describes it as implemented
(session, raycasts, the floor, the state machine, every string, constants with file:line). Port the
behaviour from the Swift files it cites, not from either plan. Where the logic lives:

- **`:modelkit`** (ported with the iOS tests): PlateAlignment (`PlateFrame`, smoother, blend),
  PlateAnchoring (the state machine), AlignmentSites, ReferenceAlignment (matching, refit),
  RoomOutline, RoomCorrections, LidarAim, PlateRegistration/PlateSampler, ManualAlignment
  (`intersectFloor`, `floorHeight`), LocateGuide, BehindWallFade, ModelFilters/SeeInside,
  `Manifest.startingStorey`.
- **`:ar`** (iOS `ARAlignmentView`, `AnchorMath`): the session and its settings, the image
  database, the floor choice (`worldFloorY`) and the 5 mm floor-change notifier, crosshair targeting
  (35° gate, ±0.18 m corner side rays), mark capture (500 ms, 5 samples, 2 cm median filter), the
  placement state and gestures, lost detection, site anchors, the frame loop (anchoring tick, root
  transform, crosshair, locate at 10 Hz, proximity). Same rules, ARCore calls.
- **`:features`** (iOS `ARExperienceModel`/View, `PointsAlignment`, `PlateRegistrationFlow`,
  `SeeInsidePanel`, the preflight): status-tag and coaching priorities, the 4 s "No plate?" offer,
  menus and their conditions, Fix here, room save, registration, the safety card, copy details.

ARKit → ARCore mapping (each line is a decision; device checks in `docs/device-test.md`):

| iOS | Android |
|---|---|
| `detectionImages`, `maximumNumberOfTrackedImages = 1` | One `AugmentedImageDatabase` rebuilt on each settings change (placed plates while `wantsImageDetection && !isMarking`, plus the plate being registered), `session.configure` without reset. Width `sizeMm / 1000` m (quiet zone included). Only `FULL_TRACKING` image updates feed the smoother; `LAST_KNOWN_POSE` is ignored. Re-align drops the image anchors' last poses (iOS removes the anchors to force re-detection). |
| Image pose → `PlateFrame` (`AnchorMath`) | ARCore's augmented-image pose has the same axes as ARKit's (x right, y the normal, z down the image), so the conversion is the same: right = x, up = −z, normal = y. `YawTransform` from a site anchor's pose likewise. Unit-tested in `:ar`'s JVM tests on matrices. |
| `ARPlaneAnchor.classification == .floor` | ARCore has no floor class: `worldFloorY` is the lowest `HORIZONTAL_UPWARD_FACING` plane of at least 0.25 m² (the iOS fallback rule). Device check: reflections on shiny floors. |
| `hasLiDAR` (wall and corner marks, mesh) | `hasDepthSensor`: ARCore `DepthMode.RAW_DEPTH_ONLY` supported **and** the back camera reports `DEPTH_OUTPUT` (a time-of-flight sensor). Motion-stereo depth alone is too noisy for the 1.5 cm / 2° tolerances tuned on LiDAR, so those phones behave as iPhones without LiDAR (floor corners only; the crosshair says to point more straight down). Wall hits: vertical planes first, then a depth hit. ARCore has no door/window plane classes. Mesh reconstruction has no counterpart; iOS raycasts never use it. |
| `estimatedPlane` raycasts, `arView.ray(through:)` | `frame.hitTest` against planes, then depth points; screen rays from the ARCore camera's view and projection matrices. |
| `ARCoachingOverlayView` (horizontal plane, manual placing only) | A coaching card with the same gating and the iOS strings. |
| `SessionRelay` (delegate queue, newest frame only) | ARCore frames come from `session.update()` on the render thread: reduce each to a `FrameSample` value there and hand only the newest to the main thread. |
| Interruption, relocalization, `.limited` reasons | `TrackingState.PAUSED` + `TrackingFailureReason` → the same `TrackingStatus` values; activity pause → interrupted (alignment lost at once), resume → relocalizing. Only relocalizing/not-available start the 2 s lost timer, as on iOS. |
| Thermal (`.serious`: no mesh, no environment texturing, ≤ 30 fps smallest format, no wall aims) | `PowerManager` thermal status ≥ `THERMAL_STATUS_SEVERE` (API 29+; never hot below): light estimation off, a 30 fps `CameraConfig` with the smallest size, no wall aims. The initial status is read at start (iOS only reacts to changes: fixed here). |
| No occlusion, render effects off, `isIdleTimerDisabled` | Depth occlusion off in SceneView, no post-processing in AR, `FLAG_KEEP_SCREEN_ON` while AR runs. |
| `OrbitViewers` release + 1 s camera wait | One Filament engine; the 3D viewer's SceneView is removed before the AR one starts (the building root moves between them, as on iOS). No wait needed unless the device test shows a black feed. |
| RealityKit picking (collision boxes, convex hulls) | Ray–box picking in `:scene` with `PickingShape` boxes; long diagonal runs test the mesh's triangles. |
| `OpacityComponent` (chunk × element) | Material alpha: See inside opacity on the chunk's materials times the proximity fade per element. |
| Camera permission (`restricted`) | Android has no restricted state; "Don't ask again" maps to `CameraDenied(canOpenSettings = true)`. |
| `.searchable` search bars | A search field at the top of the list | Compose has no navigation-bar search |
| Swipe a building to reveal a star | A full swipe toggles the favourite | Material's swipe-to-dismiss; the empty-state text is unchanged |
| Equipment rows: swipe for "Locate in AR" | An inline locate button | Same reason |
| Selection highlight glows (emissive) | The accent colour without glow | SceneView's colour materials have no emissive parameter |
| Long diagonal runs picked by their convex hull | Picked by their enlarged box | Filament keeps no CPU copy of meshes |
| VoiceOver steps the See inside slider between detents | TalkBack adjusts it continuously | Material3's slider semantics |
| A hot phone switches to a cooler camera format at once | Only when the AR session starts | ARCore must pause to change it, which would lose the alignment |
| ARKit tracks one plate image at a time | ARCore may track several | Platform; only FULL_TRACKING poses feed the smoother |
| Scanner without camera access: a message | The message plus "Allow the camera" / "Open settings" | Android lets the app ask again |
| AR as a full-screen cover | A navigation destination | Navigation Compose; the scene claim works the same |
| ARKit is always present | An ARCore check first (install from Play, or "AR isn't available on this phone") | ARCore is a separate service on Android |
| iPhone wording ("on any iPhone", "your iPhone") | "phone" | Android-only strings in `strings_android.xml` / `strings_app.xml` |
| Admin AR (registration, room save) disabled in the App Clip | Always available to admins and owners (there is no Clip). |

Known iOS quirks (`docs/ios-ar-reference.md` §10): Android copies the behaviour except two bugs,
which it fixes and reports to iOS: wall mark discs keep their normal offset after a floor change
(§10.13), and the thermal status is read at start (§10.5).
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
| ARKit image detection, plane classification, LiDAR | ARCore Augmented Images, lowest large upward plane as the floor, ToF depth sensor | Platform equivalents; details and decisions in §3 "AR" |
| QuickLook | `ACTION_VIEW` through a FileProvider | Platform equivalent |
| VisionKit scanner | CameraX + ML Kit barcode scanning | Platform equivalent |
| `ASWebAuthenticationSession` ephemeral | A Custom Tab sharing the browser's cookies | androidx.browser 1.9 has no ephemeral tabs; revisit when it does |
| `SKOverlay` / App Store | Google Play (`market://`) | Platform equivalent |
| Language: system settings link | Per-app language (Android 13+), system settings link before | Platform equivalent |

## 7. Checklist

### AND-M0 · Foundations

- [x] **AND-M0-01** Gradle project: version catalog, eight modules (three JVM, five Android), build types, `local.properties` overrides, JVM-only builds for Claude Code sessions.
- [x] **AND-M0-02** CI on every push: JVM tests, Android build, unit tests, lint.
- [x] **AND-M0-03** `CLAUDE.md`, this plan, `docs/device-test.md`, `scripts/sync-openapi.sh`.
- [ ] **AND-M0-04** (you) Google Play Console: app `com.getinsiteview.android`, Play App Signing, upload key, internal testing track; repository secrets for the upload key; the web's `ANDROID_PACKAGE` and `ANDROID_SHA256_CERT_FINGERPRINTS` in production.

### AND-M1 · Logic modules

- [x] **AND-M1-01** `:core` ← IVCore, with IVCoreTests ported; Install Referrer handoff parsing.
- [x] **AND-M1-02** `:modelkit` ← IVModelKit (manifest, chunks, cache, element index, filters, all geometry), with IVModelKitTests ported and GLB in the chunk plan.

### AND-M2 · API client

- [x] **AND-M2-01** `:api` ← IVAPI: models, `ApiClient`, interceptors, `ApiError`, credentials and refresh, `BuildingConnection`, PIN, analytics uploader, catalog, my buildings, search, documents, account and OAuth; IVAPITests ported; `ApiContractTest`.

### AND-M3 · Guest flow and 3D

- [x] **AND-M3-01** (builds in CI; needs device check) `:design` ← IVDesign: palette, Anton/Barlow/JetBrains Mono, buttons, chips, loading bar, detent slider, wordmark.
- [x] **AND-M3-02** (rules JVM-tested in `modelkit.scene`; builds in CI; needs device check: meshopt GLBs in Filament, transparency, picking of long diagonal runs) `:scene` ← IVScene: GLB chunks in one root, catalog materials, visibility and opacity, highlight, picking, locate pulse, behind-wall fade, orbit viewer.
- [x] **AND-M3-03** (rules JVM-tested; builds in CI; needs device check) Guest screens: guest flow, landing, PIN, problem states and access gate, building home, status line, 3D viewer, rooms, documents, object card, diagnostics.

### AND-M4 · AR

- [x] **AND-M4-01** (the iOS view's state machine and rules are `modelkit.ar`, JVM-tested; builds in CI; needs device check) `:ar` ← IVAR: ARCore session, Augmented Images from plates, raycasts, depth marks, site anchors, `AnchorMath`.
- [x] **AND-M4-02** (rules JVM-tested; builds in CI; needs device check) AR screens, as `docs/ios-ar-reference.md` §3–§8: preflight; AR experience (plate coaching, status tag, finding-the-floor row, crosshair states, ⋮ menu with Level, Room, Re-align, align by points, Adjust placement / Place again / Place manually, safety note, admin items); the 4 s "No plate?" offer; points alignment (room picker, marks, matching, symmetric choice, confirm, copy details); manual placement and fine-tune; starting storey and floor-change refit; floor glue; sites and Fix here; locate chip and arrow; See inside; plate registration and Test now; room save card; the first-use safety card; haptics; analytics.

### AND-M5 · Signed-in app

- [x] **AND-M5-01** (builds in CI; needs device check: Custom Tabs return, App Links, Install Referrer, camera) App: tabs (Buildings, Search, Scan, Profile), sign-in (Custom Tabs OAuth, email), update required, App Links and the `insiteview://auth/callback` intent, Install Referrer handoff, save building.

### AND-M6 · Localization and release

- [ ] **AND-M6-01** Strings: `scripts/xcstrings-to-android.py` converts the iOS String Catalog (en, pt-BR, es) into `strings.xml` (done; re-run it whenever the catalog changes); Android-only strings in `strings_android.xml` / `strings_app.xml`. Open: native review with iOS's IOS-M5-01.
- [x] **AND-M6-02** App icon (adaptive, monochrome) and the Play Store icon from `insiteview-api/brand/render.py --android`.
- [ ] **AND-M6-03** Sentry in the app (with iOS's IOS-M5-04), Play data safety form.
- [x] **AND-M6-04** Release workflow (`.github/workflows/release.yml`): signed `bundleRelease` on `v*` tags or by hand, AAB/APK/mapping as artifacts, upload to a Play track when `PLAY_SERVICE_ACCOUNT_JSON` is set; CI also builds the release variant. Checked unsigned (no secrets yet); signing and upload wait on AND-M0-04.
- [ ] **AND-M6-05** Run `docs/device-test.md` on two phones and a printed plate; App Link verification on a release build.
