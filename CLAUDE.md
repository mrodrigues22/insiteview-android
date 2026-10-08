# insiteview-android

Android app for Insite View: a port of the iPhone app (`../insiteview-ios`) that behaves the same.
The plan and checklist are in `docs/PLAN.md` (items `AND-M{n}-{nn}`); the master plan (API contract,
asset contract, milestones) is `../insiteview-api/docs/PLAN.md`. When the two apps should differ,
the reason is written in `docs/PLAN.md` §6 "Differences from iOS"; otherwise iOS is the spec, and
the spec is the iOS code (its plan can be stale). For AR start from `docs/ios-ar-reference.md`, then
read the Swift it cites.

## Commands

- Everything (needs Google's Maven): `./gradlew assembleDebug testDebugUnitTest lintDebug` and
  `./gradlew :core:test :modelkit:test :api:test`. CI (`.github/workflows/ci.yml`) runs both on every push.
- JVM modules only: `IV_JVM_ONLY=1 ./gradlew :core:test :modelkit:test :api:test`
  (or `-PjvmOnly=true`). Leaves the Android modules out of the build, and AGP off the classpath.
- One test: `IV_JVM_ONLY=1 ./gradlew :modelkit:test --tests "*PlateAlignmentTest*"`
- Spec: `scripts/sync-openapi.sh` copies `../insiteview-api/openapi/v1.json` to `openapi/openapi.json`.

## What you can check in a Claude Code session

The session proxy blocks `dl.google.com` (and `maven.google.com` redirects there), so AGP, androidx,
ARCore and ML Kit don't resolve. Build and test `:core`, `:modelkit` and `:api` with `IV_JVM_ONLY=1`,
push, and read the GitHub Actions run for the Android modules (`mcp__github__actions_list`,
`get_job_logs`). Rendering, AR, camera, App Links and the Play Install Referrer need a device:
`docs/device-test.md`. Say so in the commit or PR when a change touches them.

## Modules

| Module | iOS | Kind |
|---|---|---|
| `:core` | IVCore | Kotlin/JVM: router, codes, catalog, units, analytics events, timestamps |
| `:modelkit` | IVModelKit | Kotlin/JVM: manifest, chunks and cache, element index, filters, all AR geometry |
| `:api` | IVAPI | Kotlin/JVM: OkHttp client, interceptors, auth, visits, analytics uploader |
| `:design` | IVDesign | Android: palette, fonts, buttons, chips, slider, wordmark |
| `:scene` | IVScene | Android: GLB chunks in SceneView/Filament, materials, picking, orbit viewer |
| `:ar` | IVAR | Android: ARCore session, plate images, raycasts, anchors |
| `:features` | IVFeatures | Android: every Compose screen and its ViewModel |
| `:app` | App/ | Android app: tabs, sign-in, scanner, profile, App Links |

- `:core`, `:modelkit` and `:api` never import `android.*` or `androidx.*`; their tests run on the JVM.
  Pure logic goes there (as on iOS), so it gets tested here.
- Packages: `com.getinsiteview.{core,modelkit,api,design,scene,ar,features}`; the app is
  `com.getinsiteview.android`.

## Porting from iOS

- One Swift file → one Kotlin file with the same name and the same behaviour, in the matching module
  (`Sources/IVModelKit/Geometry/PlateAlignment.swift` → `modelkit/.../geometry/PlateAlignment.kt`).
  Keep the doc comments (adapted), constants and thresholds exactly.
- One Swift Testing suite → one JUnit 5 class (`kotlin.test` assertions) with the same cases and
  the same names in backticks. Fixtures are copied from `Packages/InsiteViewKit/Tests/*/Fixtures`
  to `src/test/resources/`.
- Swift → Kotlin: `struct` → `data class` (immutable; `copy`), `enum` with payloads → `sealed interface`,
  `async`/actors → `suspend` and `Mutex`, `@Observable` models → ViewModels with `StateFlow`,
  `Codable` → `@Serializable` (kotlinx.serialization, `ignoreUnknownKeys`), `SIMD3<Float>` →
  `modelkit.geometry.Vec3`, `Date` → `java.time.Instant`, `URL` → `java.net.URI`.
- Open enums (iOS `OpenEnums.swift`): `@JvmInline value class X(val raw: String)` with known values
  in its companion and a serializer that keeps unknown strings. `when` on them needs `else`.

## API client (`:api`)

- Hand-written `@Serializable` models for the operations the app calls (the list in `docs/PLAN.md` §3,
  the same as iOS `openapi-generator-config.yaml`). `ApiContractTest` checks them against
  `openapi/openapi.json`: a renamed or removed field fails the test after a spec sync.
- Every request carries its operationId (`OperationId` tag); interceptors key on it like the iOS
  middlewares. Order: device id, client version (`X-Client: android`, `X-Client-Version`; 426
  `client.outdated` → `onClientOutdated`), Accept-Language, visit token, bearer (innermost; refresh
  once and retry on 401).
- Errors: every response of 400 or more becomes `ApiError` (ProblemDetails `code`). Switch on `code`,
  never on `title`.

## Android specifics

- Compose only (no XML layouts). Strings in `res/values{,-b+pt+BR,-es}/strings.xml` (`:features` and
  `:app`); add every key to all three (English text until translation); `StringsParityTest` fails
  otherwise. Catalog names (systems, kinds, properties) and model data come from the API, never from
  resources.
- Colours and type come from `:design` (`IvTheme`, `Palette`); system colours from the catalog.
- No App Clip: printed plates open the app through App Links (`/b/*`, `/a/*`, verified with the web's
  `/.well-known/assetlinks.json`), or the web viewer when it isn't installed. The web's "Get the app"
  passes the building through the Play Install Referrer (`BuildingHandoff`).
