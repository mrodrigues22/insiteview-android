# Insite View for Android: iOS parity plan (UX, UI, features)

> As of 2026-11-08 · Reference: `insiteview-ios` at `8bff80b` (2026-10-30) · Android port at `2c17710` (2026-10-08).
> Companion to `docs/PLAN.md` (platform, architecture, checklist `AND-M*`) and `docs/ios-ar-reference.md`
> (the iOS AR internals). This file is the **product spec**: what an Android user sees and does, screen by
> screen, so the app behaves exactly like the iPhone app. Code can differ (Kotlin, Compose, ARCore, Filament);
> what users see and do must not, unless the difference is listed in `docs/PLAN.md` §6 or decided here.

## How to use this document

- **The iOS code is the spec.** Every rule below cites the Swift (`file:line`) it was read from. When this file,
  the iOS plan and the iOS code disagree, the code wins: open it, then fix this file.
- **Every item has a parity status** against the current Kotlin, checked by reading it, not assumed:
  **Done** (same for the user), **Differs** (how, with the Kotlin `file:line`), **Missing**, or
  **Decided** (an intended difference, see §3).
- **Paths.** iOS paths are relative to `../insiteview-ios` (`Kit/` = `Packages/InsiteViewKit/Sources/`); Android
  paths are relative to this repository (`features/…/` = `features/src/main/kotlin/com/getinsiteview/features/`,
  `app/…/android/` = `app/src/main/kotlin/com/getinsiteview/android/`). Some sections abbreviate files (`kAEV`,
  `PRE`, …); each defines its abbreviations at the top.
- **Strings.** English source text in quotes is the `Localizable.xcstrings` key, and the Android
  `strings.xml` entries come from it (`scripts/xcstrings-to-android.py`). "iPhone" becomes "phone" on Android.
- **Numbering.** `§N` is a top-level section of this file. `¶N` is subsection N inside the section it appears in (or the one it names: "§8 ¶3" is subsection 3 of the AR section). `PLAN §N` means `docs/PLAN.md`, and `master PLAN §N` means `insiteview-api/docs/PLAN.md`.
- **Work items** are in §9 (backlog, by priority) and §10 (milestones with exit criteria). They extend the
  `AND-M*` checklist in `docs/PLAN.md` §7. Tick both when an item ships.

| § | Contents |
|---|---|
| 1 | Where things stand |
| 2 | The product in one page: users, the moment, feature inventory |
| 3 | Parity rules: what may differ, what may not, Android conventions |
| 4 | Prerequisites outside this repository (API, web, Play Console) |
| 5 | App shell, account and signed-in tabs |
| 6 | Guest flow and building screens |
| 7 | Design system and 3D viewer |
| 8 | AR experience (with the AR device-test checklist) |
| 9 | Parity backlog: every gap in one table |
| 10 | Milestones to release |
| 11 | Keeping parity after launch |
| 12 | Open decisions |

## 1. Where things stand

The Android app isn't empty. In October 2026 the whole iPhone app was ported, module by module (see
`CLAUDE.md` and `docs/PLAN.md` §7: AND-M0 to AND-M5 ticked). The port has 192 Kotlin files, about 37,000 lines,
and the iOS test suites ported case for case. CI builds it, but **it has never run on a phone**, and two
branches hold it (`claude/festive-hypatia-xhua3q`, the remote's default, and `claude/android-app-plan-st0bwi`),
neither of them `main`.

The audit for this file read each iOS screen against its Kotlin counterpart:

- **Most of the app matches.** That covers guest flow, PIN, problem states, access gate, building session,
  search, rooms, documents, object card, viewer controls, every AR state and its strings, the admin tools,
  the tabs and sign-in. The design tokens match hex for hex, and the font files are byte-identical.
- **Three things outside this repository block any release** (§4):
  - The API rejects the visit platform the app sends (`AndroidApp`).
  - The web serves no `assetlinks.json`, so App Links can't verify.
  - The web's Google Play link carries no install referrer.
- **About 40 visible differences remain inside the app** (§9). The largest:
  - Turning the phone restarts AR and loses the alignment.
  - The camera pre-prompt still has "Not now", which iOS removed on 2026-10-30.
  - Portuguese and Spanish plurals are broken.
  - Action buttons have no icons.
  - Tab titles aren't the large Anton titles.
  - Line heights are tighter than iOS.
  - The 3D camera angle resets after AR.
- **Everything needs a device.** Rendering (meshopt GLBs in Filament, transparency, highlight glow, lighting),
  AR accuracy, App Links, Custom Tabs sign-in, the Install Referrer and the camera scanner still have to be
  checked on a phone.

So the plan is: unblock the API and web, close the parity backlog, run the device tests, field-test AR on
printed plates, then do localization, the store and release (§10).

## 2. The product in one page

**The moment the app exists for.** Someone points a phone at a printed plate on a wall. Within seconds, and
with no account, they see the building's hidden systems (electrical, plumbing, HVAC, …) aligned in the room
in AR, can tap an outlet or valve to see what it is, and can switch to a 3D model of the whole building.
On iPhone the App Clip makes that happen with no install. On Android, the installed app opens straight from
the plate URL (App Links); without it, the web viewer opens and offers "Get the app", which brings the visitor
back to the same building after install (Install Referrer).

**Who uses it**

| User | How they arrive | What they get |
|---|---|---|
| Guest (no account) | Scans a plate (`/b/{code}/{plate}`) or opens a temporary link (`/a/{token}`) | Landing, "What do you need?" presets, PIN if the building has one, 3D, AR, object card, rooms, documents. Systems limited to what the plate or link grants. No technical details (IFC class, GlobalId, property sets). |
| Signed-in user with a grant or saved building | Buildings tab, Search, a plate | The same, plus saved buildings and search across them. |
| Organization member | Buildings tab | Member audience: technical details on the object card, the status line. |
| Admin or owner | Same | Admin AR tools: register plate positions, test them, and save room corrections. On iOS these are app-only, not in the Clip; on Android they're always in the app. |

**Feature inventory (iOS → Android today)**

| Feature (iOS) | Android status | Details |
|---|---|---|
| Open a building from a printed plate (App Clip) | Built (App Links); **blocked**: no `assetlinks.json` on the web | §4.2, §5 ¶3, §6 ¶14 |
| Clip → app handoff | Built (Install Referrer); **blocked**: no referrer on the web's Play link | §4.3, §6 ¶14 |
| Visits (plate, link, PIN, re-check of access) | Built; **blocked**: the API rejects `platform: "AndroidApp"` | §4.1, §6 ¶2, ¶7 |
| Guest landing, need chips, save building | Done, with icon, chip-role and padding differences | §6 ¶3 |
| PIN sheet, lockout | Done, with reduced-motion and haptic differences | §6 ¶4 |
| Problem states, access gate | Done | §6 ¶5–6 |
| Building details, search, status line, rooms, documents | Done, with minor differences | §6 ¶8–11 |
| Object card (guest and member audiences) | Done; "Show in 3D" icon | §6 ¶12 |
| 3D viewer: chunks, cache, filters, See inside, picking, locate | Done; camera angle after AR, double-tap-drag, long-run picking | §7 B |
| AR: plate alignment, smoothing, re-anchoring, lost/relocalizing | Done in code, untested; **rotation restarts AR** | §8 ¶3, ¶7 |
| AR: manual placement, fine-tune, points alignment, Fix here, storeys | Done in code, untested; no tap-to-place before a plane | §8 ¶7–10 |
| AR: locate, See inside, ⋮ menu, safety note | Done; minor layout and TalkBack differences | §8 ¶11–13 |
| Admin AR: plate registration and Test now, room-save card | Done in code (always available to admins) | §8 ¶14 |
| Tabs: Buildings, Search, Scan, Profile | Done; title style, tints, nav-bar pill | §5 ¶4–9 |
| Sign-in: Google, Microsoft, Apple, email | Done (Custom Tabs); Apple only if the API lists it | §5 ¶10 |
| Forced update (`client.outdated`) | Built; **the API never sends it to Android** | §4.1, §5 ¶5.1 |
| Analytics events | Done (same events; batches every 30 s) | §6 ¶16, §8 ¶18 |
| Localization en / pt-BR / es | Converted from the iOS catalog; **plurals broken** | §6 ¶19 |
| Crash reporting (Sentry) | Missing on both platforms (AND-M6-03 with IOS-M5-04) | §10 M4 |
| Store listing, privacy declaration | Play listing and Data safety missing | §5 ¶15–16 |

(¶ numbers refer to the numbered subsections inside each section.)

## 3. Parity rules

### 3.1 Must match iOS exactly

- **Flows.** The order of screens, what each button leads to, and which screens appear in which state.
- **Content.** Every string (translated), every icon's meaning, which rows and actions exist, and the
  conditions for each (role, audience, state, LiDAR or ToF).
- **Behaviour.** Timers, thresholds and debounces the user can perceive (constants tables in §6 ¶17 and
  §8 ¶20), what is stored and for how long, retries, and error code → message mapping.
- **Design.** Colour tokens, fonts, sizes, line heights, square corners, button and chip styles, and the
  dark theme (§7 A).
- **Data rules.** Guests never see technical details; never show a system that isn't in the scoped manifest;
  switch on error `code`, never on `title`.
- **Analytics.** The same event names, payloads and moments (master PLAN §12).

### 3.2 Decided differences

Updated 2026-11-08 after the parity pass (§9): the only differences left are the platform ones in
`docs/PLAN.md` §6:
- App Links and the Install Referrer instead of the App Clip.
- **No Apple sign-in on Android** (product decision): Google, Microsoft and email.
- GLB with Filament instead of USDZ with RealityKit.
- ARCore equivalents for plates, floor and depth (and an ARCore install check).
- `ACTION_VIEW` instead of QuickLook.
- CameraX with ML Kit instead of VisionKit.
- Custom Tabs (with PKCE), Google Play, and per-app language.
- Glass blurs Compose content only; over the camera and the 3D model it's tinted (SurfaceViews can't be
  sampled).
- With an alignment held, a hot phone's cooler camera format waits for the session's next start (ARCore
  must pause to change it); rendering lightens at once.
- "phone" instead of "iPhone".

Everything that used to be listed here as forced by Compose (search placement, swipe actions, emissive
glow, box picking, TalkBack slider, several tracked images, scanner buttons, AR as a destination) now
matches iOS. **The visual reference is the iPhone app as it ships (iOS 26 Liquid Glass):
`insiteview-ios/docs/app-store-screenshots`**; where the screenshots and a reading of the Swift
disagree, the screenshots win.

Don't add to that list without a reason written next to the entry.

### 3.3 Android conventions we adopt (not differences, just Android)

- **System Back and predictive back.**
  - Back closes the top sheet or dialog, then pops the screen.
  - In AR, Back exits AR as the close button does: same analytics, scene handed back to the viewer.
  - At a tab's root, Back exits the app. iOS has no equivalent; Decided, §5 ¶4.
- **Edge to edge.** Status and navigation bars are transparent. The 3D viewer and AR draw under them, as on
  iOS, where the model fills the screen; today the viewer stops at the navigation bar (§7 B gap 9).
  Content insets keep controls clear of the bars and the camera cutout.
- **Rotation survives.** iOS keeps the AR session and the 3D camera when the phone turns. Android must not
  recreate state on a configuration change (§9 P0-04).
- **Font scale** follows the iOS Dynamic Type behaviour described in §7 A.3. Layouts must hold at 200 %.
- **TalkBack** gets the same labels, hints and grouping as VoiceOver (accessibility lists in §6 ¶18, §7 B.11,
  §8 ¶19). Modal cards are modal for TalkBack too.
- **Touch targets** are at least 48 dp (iOS 44 pt).
- **Haptics** use `HapticFeedbackConstants` matching each iOS feedback (§8 ¶16) and respect the system
  setting.
- **Reduced motion.** When animations are off (`ANIMATOR_DURATION_SCALE` = 0), skip shakes and pulses, as
  iOS does with Reduce Motion.

## 4. Prerequisites outside this repository

These block every real-world use, and none of them can be fixed in this repository. Do them first (§10 M0).

### 4.1 API (`insiteview-api`)

1. **Accept `AndroidApp` as a visit platform. This blocks release.**
   - The app sends `platform: "AndroidApp"` on every visit (`features/…/app/AppDependencies.kt:170`).
   - The API's `VisitPlatform` has only `IosApp, IosClip, WebIos, WebAndroid, WebDesktop, Other`
     (`src/InsiteView.Core/Visits/Visit.cs:43-51`), and `JsonStringEnumConverter` rejects unknown values, so
     every visit from the app fails.
   - Fix:
     - Add `AndroidApp` to the enum. Enums are stored as strings (`AppDbContext.cs:93`), so no migration
       is needed.
     - Regenerate `openapi/v1.json`, then sync the spec into web, iOS and Android.
     - Add the platform to the analytics breakdowns and to the cross-platform tests.
2. **Forced updates for Android.**
   - `ClientVersionCheck` only checks `X-Client: ios` against `Clients:IosMinVersion`, and its detail text
     says "App Store" (`src/InsiteView.Api/Setup/ClientVersionCheck.cs`).
   - Add `Clients:AndroidMinVersion` with the same validation, an `.env.example` entry and a PLAN §4 entry.
   - Check `X-Client: android` against it, and make the detail text name the right store. The app shows
     its own string anyway.
3. ~~Apple sign-in on the web flow.~~ Decided 2026-11-08: Android has no Apple sign-in; the app ignores
   `apple` in `/v1/auth/oauth/providers`.
4. **OAuth callback hardening.** On Android, `insiteview://auth/callback` is an exported intent filter any
   app can also register. The exchange code is short-lived and single-use, but bind it to the request: a
   `state` or PKCE `code_verifier` that the app sends to `start` and to `auth_oauth_exchange`. iOS benefits
   too.

### 4.2 Web (`insiteview-web`): Digital Asset Links

- Add `src/app/.well-known/assetlinks.json/route.ts`, built like the AASA route (`src/lib/aasa.ts`) from
  `ANDROID_PACKAGE_NAME` and a new `ANDROID_SHA256_CERT_FINGERPRINTS`.
  - Comma-separated; include Play App Signing's key and the upload key.
  - Serve `application/json`, no redirect, 404 when unconfigured.
- The proxy matcher already leaves `/.well-known/assetlinks.json` alone (`src/proxy.test.ts:36`).
- Add the keys to `.env.example`, `docs/PLAN.md` §2 and `deploy.yml`.
- Without it, `autoVerify` fails, and plates open the browser (or a chooser) instead of the app.

### 4.3 Web: Install Referrer on "Get the app"

- `mobileAppsFromEnv()` builds `https://play.google.com/store/apps/details?id=…` with no referrer
  (`src/lib/guest/apps.ts:42-46`).
- On a guest page, `AppPromo` should link to
  `…&referrer=` + `encodeURIComponent("code={code}&plate={plate}")` (or `token={token}` on `/a/`), the
  format `app/…/android/InstallReferrerHandoff.kt` parses. Then the first launch opens that building, as
  the App Clip handoff does on iOS.
- Add a test next to `guest.test.tsx:540`.
- Once the app is live, set `ANDROID_PACKAGE_NAME=com.getinsiteview.android` in production. Until then
  Android guests are told "the app is on iPhone".

### 4.4 Google Play Console (owner)

AND-M0-04, still open:
- App `com.getinsiteview.android`, with Play App Signing and an upload key.
- Internal and closed testing tracks.
- Repository secrets for the release workflow.
- The signing SHA-256 values for §4.2.
- A service account for uploads.
- The Data safety form and the listing (§5 ¶15–16).


## 5. App shell, account and signed-in tabs

Scope: what a person sees from launch to a building being pushed, the four tabs (Buildings, Search, Scan, Profile), the sign-in sheet, account actions, deep links, the forced update, and the store/privacy material. The building screens themselves (guest landing, BuildingHome, viewer, AR, the camera pre-prompt) are other sections; they are only named here as destinations.

Conventions used below:

- iOS paths are relative to `../insiteview-ios`; `Kit/` = `Packages/InsiteViewKit/Sources/`. Android paths are relative to `this repository`; `app/…/android/` = `app/src/main/kotlin/com/getinsiteview/android/`, `features/…/` = `features/src/main/kotlin/com/getinsiteview/features/`.
- Strings are the English source text (`Localizable.xcstrings` key = English). Every string named in this section exists in all three iOS locales (en, pt-BR, es) and has an Android key in `features/src/main/res/values/strings.xml` or `app/src/main/res/values/strings_app.xml` (checked by script; interpolated keys such as `Save %@` → `save_x` are present too).
- Tokens: `Palette.background/surface/sunk/ink/muted/line/accent/accentSoft/warn/warnSoft/onAccent/onInk` (`Kit/IVDesign/Palette.swift:14-36`; Android `design/…/Palette.kt`), type `ivDisplay` (Anton, uppercase), `ivBody` (Barlow), `ivHeadline` (Barlow SemiBold 17), `ivMono` (JetBrains Mono), `ivEyebrow` (mono 11 medium, uppercase, tracked) (`Kit/IVDesign/Typography.swift:50-125`; Android `IvType.display/body/headline/mono/eyebrow`). Buttons: `.primaryAction` (red fill, square, uppercase Barlow SemiBold 13, min height 46, ink while pressed) and `.secondaryAction` (surface fill, 1.5 pt ink outline, red outline while pressed) (`Kit/IVDesign/Components.swift:7-80`; Android `PrimaryActionButton` / `SecondaryActionButton`, `design/…/Components.kt:49-115`).
- Parity: **Done** = same for the user; **Differs** = described with the Kotlin location; **Missing** = not in Android.

### 1. App targets and what Android does instead

| iOS | Android | Notes |
|---|---|---|
| App target `InsiteView` (`App/*.swift`), iPhone only (`project.yml` `TARGETED_DEVICE_FAMILY: "1"`), iOS 18+, all orientations (no `UISupportedInterfaceOrientations` key) | One activity `MainActivity` (`singleTask`, `adjustResize`), minSdk 26, targetSdk 36, rotates | Done |
| App Clip `InsiteViewClip` (`Clip/InsiteViewClipApp.swift`): root is the guest flow for the invoked link (`ClipRootView`, :45-79); no sign-in; without an invocation shows `"Scan an Insite View code"` / `"Open the Camera app and point it at the code on the plate."` with the Wordmark and `qrcode.viewfinder`; "Update" opens an `SKOverlay` for the full app | No Clip (PLAN §6). Not installed → printed URL opens the web viewer, whose "Get the app" goes to Google Play; installed → App Links open the app (¶3 below) | Done by design. The two Clip-only strings exist in Android resources but are unused, correctly |
| Clip → app handoff through the App Group (`Kit/IVCore/GuestOnboarding.swift:79-115`, `BuildingHandoff`, `maxAge` 7 days) taken in `AppRootModel.init` (`Kit/IVFeatures/App/AppRootModel.swift:30-33`) | Play Install Referrer `code=…&plate=…` read once per install (`app/…/android/InstallReferrerHandoff.kt:32-48`) into `BuildingHandoff` (`core/…/GuestOnboarding.kt:92-139`, `MAX_AGE` 7 days) | Done in the app. **Cross-repo gap:** the web's Google Play link has no `referrer` parameter (`insiteview-web/src/lib/guest/apps.ts:42-46` builds `https://play.google.com/store/apps/details?id=…` only), so the handoff never carries a building today |

### 2. Launch sequence and cold-start routing

iOS (`App/InsiteViewApp.swift:7-55`):

1. `init`: `IVFonts.register()` (Anton, Barlow, JetBrains Mono) and `IVAppearance.configure()` (navigation bar large titles in Anton 34, inline titles in Barlow SemiBold 17, transparent at scroll edge) (`Kit/IVDesign/Typography.swift:129-150`).
2. `AppRootModel(target: .app, …)` builds `AppDependencies` from Info.plist (`API_BASE_URL`, `WEB_BASE_URL`, `APP_STORE_ID`, `CFBundleShortVersionString`; `Kit/IVCore/AppConfiguration.swift:45-66`). A bad config shows its description as plain text with padding (`InsiteViewApp.swift:24-27`): `"API_BASE_URL is missing from Info.plist."` or `"… is not an http(s) URL: "…". Write URLs in xcconfig files as https:/$()/host."` (developer-only, not localized).
3. `AppDependencies.init` (`Kit/IVFeatures/App/AppDependencies.swift:53-117`): device id (Keychain `com.getinsiteview.device`/`deviceId`, a lower-case UUID; `Kit/IVAPI/DeviceIdentity.swift:14-26`), the API clients, credentials (Keychain `com.getinsiteview.auth`/`tokens`), catalog, analytics (`start()`: flush every 30 s, `Kit/IVAPI/AnalyticsUploader.swift:18`), favourites/recents, web links, then `account.start()` (reads the Keychain and `GET /v1/me`).
4. The App Group handoff from the Clip is taken and opened before any link (`AppRootModel.swift:30-33`).
5. `RootTabView` shows with the **Buildings** tab selected (`InsiteViewApp.swift:82`).
6. While the Keychain is read, `AuthState.phase == .restoring` → `isSignedIn` is false, so tabs briefly render their signed-out states (a flash, no spinner). Same on Android (DataStore read is async).
7. Universal link (cold or warm) → `onOpenURL` / `onContinueUserActivity(NSUserActivityTypeBrowsingWeb)` → `AppRootModel.open(url)` (`InsiteViewApp.swift:40-47`).
8. Scene goes to background → `didEnterBackground()` flushes analytics inside a background task (`AppRootModel.swift:50-61`).

Android (`app/…/android/InsiteViewApplication.kt:22-47`, `MainActivity.kt:20-57`):

| Step | Android | Parity |
|---|---|---|
| Fonts/appearance | Fonts are resources; `IvTheme` wraps the content; `enableEdgeToEdge()`; window background paper `#F1F1F0` / night `#0D0D0E` (`app/src/main/res/values{,-night}/app.xml`) shows before Compose and as the Android 12+ splash background | Done |
| Dependencies | `AppRootModel.live` in `Application.onCreate` from `BuildConfig` (`API_BASE_URL`, `WEB_BASE_URL`, `VERSION_NAME`, `PLAY_STORE_PACKAGE`); device id and stored units read once with `runBlocking(Dispatchers.IO)` (`features/…/app/AppDependencies.kt:204-225`) | Done |
| Config error | Plain text with 16 dp padding on `Palette.background` (`RootTabView.kt:66-70`) | Done |
| Handoff | Install Referrer read in `applicationScope`; opened only if no link has been opened yet (`openRequests == 0`), else just consumed (`InsiteViewApplication.kt:39-46`) | Done (same "a link wins" rule) |
| Links | `ACTION_VIEW` in `onCreate` (only when `savedInstanceState == null`) and `onNewIntent`; ignored when `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (reopened from Recents); `insiteview://auth/callback` goes to `OAuthCallbackBroker.complete` first (`MainActivity.kt:48-57`) | Done |
| Background flush | `ProcessLifecycleOwner` `onStop` → `didEnterBackground()` (`InsiteViewApplication.kt:33-37`) | Done |

### 3. Deep links and the Router

`Router` (`Kit/IVCore/Router.swift:20-90`; Android `core/…/Router.kt`) is the only parser for universal/App Links, Clip invocations and scanned codes.

Accepted hosts: `getinsiteview.com`, `staging.getinsiteview.com`, plus the host of `WEB_BASE_URL` (`Router.swift:21-40`). Schemes `https` or `http`. Host comparison is case-insensitive.

| Path | Result | Rule |
|---|---|---|
| `/b/{code}` | `DeepLink.building(code, plate: nil)` | `code` parses as `BuildingCode`: 6 Crockford base32 chars (`0-9A-Z` without I L O U), case-insensitive, or display form `IV-XXXX-XX` (`Kit/IVCore/BuildingCode.swift:17-32`) |
| `/b/{code}/{plate}` | `.building(code, plate: n)` | plate = positive ASCII decimal (`2`, not `+2`, `0`, `02x`) (`Router.swift:79-84`) |
| `/a/{token}` | `.accessLink(token:)` | token chars: ASCII letters, digits, `-._~` (`Router.swift:87-89`) |
| anything else (other path, extra segments, other host, other scheme) | `nil` → "This isn't an Insite View code" | |
| Scanned text | trimmed; must be non-empty, contain no whitespace, parse as a URL, then the rules above. A bare code like `8K29X7` is rejected (`Router.swift:70-76`) | |

Registered entry points:

| Entry | iOS | Android | Parity |
|---|---|---|---|
| Universal / App Links | `applinks:` + `appclips:` `$(ASSOCIATED_DOMAIN)` = `getinsiteview.com` (Local adds `?mode=developer`) (`project.yml:88-90`); web AASA lists `/b/*`, `/a/*` (`insiteview-web/src/lib/aasa.ts:42-44`) | `autoVerify` intent filter, `https://${appLinkHost}` = `getinsiteview.com`, `pathPrefix` `/b/` and `/a/` (`app/src/main/AndroidManifest.xml` activity filters; `app/build.gradle.kts:33`) | Done in the app. **Cross-repo gap:** the web has no `/.well-known/assetlinks.json` route (only `src/app/.well-known/apple-app-site-association/route.ts`), so verification fails and links open the browser or a chooser |
| OAuth return | `insiteview://auth/callback` is **not registered**; `ASWebAuthenticationSession` intercepts it (`Kit/IVAPI/Account/OAuth.swift:26-29`) | Exported intent filter `insiteview://auth/callback` (manifest) → `OAuthCallbackBroker` | Differs by necessity (Custom Tabs). Note: any app can register the same scheme or fire it; the iOS design relied on nobody else seeing the code. Consider PKCE/state on the API before launch |
| Email verification, invitations, password reset, unsubscribe | Not handled by the app: they are web pages (`/verify-email`, `/invite/…`, `/reset-password`, `/unsubscribe`) and the AASA doesn't claim them | Same (filter is `/b/`, `/a/` only) | Done |
| Push notifications | None | None | Done |

Opening a link (`AppRootModel.open(_ link:)`, `AppRootModel.swift:91-98`):

- `openRequests += 1` (the tab bar switches to **Buildings** on every change, `InsiteViewApp.swift:104-106`; Buildings also re-reads Recent).
- If the link equals `currentLink` and the Buildings stack isn't empty → nothing more (one link can arrive twice).
- Else `currentLink = link` and the Buildings stack is **replaced** by `[link]` (whatever building was open is gone; Back from the new building returns to the list).
- Android: `AppRootModel.open` (`features/…/app/AppRootModel.kt:117-122`) publishes `pendingOpen`; `BuildingNavigationEffect` (`features/…/app/BuildingDestinations.kt:218-235`) navigates with `popUpTo(graph.startDestinationId)` then the guest flow; `isBuildingOnStack` replaces `!path.isEmpty`. Tab switch: `snapshotFlow { openRequests }.drop(1)` (`RootTabView.kt:113-115`). **Done.**
- Foreign URL (universal link that doesn't parse) → `showsForeignCode = true` → alert (¶5.2). Android also treats an unparseable `Intent.data` string as foreign (`AppRootModel.kt:96-104`). **Done.**

Destinations a tab stack can show (`Kit/IVFeatures/App/BuildingDestinations.swift:11-18`): `DeepLink` → `GuestFlowView(link:)`; `OpenBuilding(link, elementID)` → `GuestFlowView(link:elementID:)` (search hit: the element's card opens first). Android: `GuestFlowRoute(code, plate (0 = none), token, elementID)` plus the `BuildingRoute`s, added to both the Buildings and Search NavHosts (`app/…/android/AppNavigation.kt:30-37`, `BuildingDestinations.kt:152-183`). **Done.** (AR is a nav destination on Android, a full-screen cover on iOS; covered by the AR section.)

### 4. Root tab bar

iOS `RootTabView` (`App/InsiteViewApp.swift:72-123`): SwiftUI `TabView` with `Tab(…)` items, `.tint(Palette.accent)`.

| Order | Title | SF Symbol | Material equivalent (Android uses) | Content |
|---|---|---|---|---|
| 1 | `"Buildings"` | `building.2` | `Icons.Outlined.Apartment` | ¶6 |
| 2 | `"Search"` | `magnifyingglass` | `Icons.Outlined.Search` | ¶7 |
| 3 | `"Scan"` | `qrcode.viewfinder` | `Icons.Outlined.QrCodeScanner` | ¶8 |
| 4 | `"Profile"` | `person.crop.circle` | `Icons.Outlined.AccountCircle` | ¶9 |

Behaviour:

- Selected item tinted `Palette.accent`, others system grey; system tab bar material. Each tab keeps its own `NavigationStack`; re-tapping the selected tab pops to root (system).
- The sign-in sheet (¶10) is presented from here whenever `signInPrompt.reason != nil`; dismissing it calls `prompt.finish(signedIn: account.isSignedIn)` (`InsiteViewApp.swift:107-118`).
- "This isn't an Insite View code" alert (¶5.2) is attached here.
- No haptics, no analytics, no badges.

Android (`app/…/android/RootTabView.kt:103-202`): Material 3 `Scaffold` + `NavigationBar(containerColor = Palette.surface)`, items with label; selected icon/text `Palette.accent`, unselected `Palette.muted`, **indicator pill `Palette.accentSoft`**; `rememberSaveable` tab; one `NavHostController` per stack tab hoisted here; only the selected tab is composed, each inside `SaveableStateProvider(tab.name)`; re-tap pops Buildings/Search to their root route (`:126-137`).

| Item | Parity |
|---|---|
| Items, order, titles, icons, accent tint | Done |
| Selection indicator | Differs: M3 pill in `accentSoft` (`RootTabView.kt:143`); iOS has none. Set `indicatorColor = Color.Transparent` for parity, or accept as platform styling |
| Re-tap pops to root | Done for Buildings/Search; iOS also scrolls a list to top on re-tap at root — Missing (minor) |
| System Back at a tab's root | Android-only: exits the app from any tab. iOS has no back. Decide: Material guidance is Back on Search/Scan/Profile root → Buildings, then exit. Not implemented |
| Predictive back | targetSdk 36 enables system predictive-back animations by default; Navigation Compose stacks animate. Needs device check |
| Keeping each stack across tab switches | Implemented with hoisted controllers; needs device check (NavHost leaving composition) |

### 5. Global overlays and dialogs

#### 5.1 "Update required" (forced update)

Trigger: any API response whose ProblemDetails `code` is `client.outdated` (sent as 426 when `X-Client-Version` is below the API's minimum) calls `onClientOutdated` before the error is thrown (`Kit/IVAPI/APIClient.swift:156-188`) → `ClientStatus.markOutdated()`; stays set for the process (`AppDependencies.swift:174-188`). Separately, a manifest whose schema is newer than the app reads maps to `GuestProblem.updateRequired` inside the building flow only (not the global cover) (`Kit/IVAPI/GuestProblem.swift:33-41`).

Layout (`UpdateRequiredView`, `InsiteViewApp.swift:59-67` → `ProblemView(.updateRequired)`, `Kit/IVFeatures/Guest/ProblemView.swift:42-137`): full-screen `Palette.background`, takes all touches, fades in (`.transition(.opacity)`), covers the tab bar (but not a sheet already presented above it).

- Icon `arrow.down.app` (Android `Icons.Outlined.SystemUpdate`).
- Title `"Update required"`.
- Message `"This version of Insite View can't open this building. Update the app to continue."`
- Button `"Update"` (`.borderedProminent`, tint accent): opens `itms-apps://apps.apple.com/app/id6820651362`, falling back to `https://apps.apple.com/app/id…`; no button when `APP_STORE_ID` is empty (`Kit/IVCore/WebLinks.swift:42-57`).

Android (`RootTabView.kt:72, 83-92`; `features/…/guest/ProblemView.kt:80-134`): `Surface` over everything with `safeDrawingPadding`; Back finishes the activity; `"Update"` is an M3 `Button` (accent/onAccent) → `market://details?id=com.getinsiteview.android`, else `https://play.google.com/store/apps/details?id=…` (`core/…/WebLinks.kt:43-60`); hidden when `PLAY_STORE_PACKAGE` is empty. Header `X-Client: android`, `X-Client-Version: versionName`.

Parity: **Done** (fade-in transition Missing, trivial). Both share the quirk that an open sign-in sheet stays above the cover.

#### 5.2 "This isn't an Insite View code"

- iOS: `.alert(Text("This isn't an Insite View code"))` with one `"OK"` (cancel role), no message (`InsiteViewApp.swift:119-121`). Shown when a universal link doesn't parse. (Scanner payloads don't use this alert; they use the scanner's hint, ¶8.)
- Android: M3 `AlertDialog`, title only, `TextButton "OK"`; tap-outside dismisses (`RootTabView.kt:176-186`). **Done.**

### 6. Buildings tab

Purpose: the signed-in user's buildings (company, shared through a grant, saved), with device-local Recent and Favorites; the stack that links and scans push onto. iOS `App/BuildingsTab.swift`; Android `app/…/android/BuildingsTab.kt`.

Entry points: launch (default tab); tab bar; every link/scan/handoff (switches here and pushes); "Scan a code" sends to Scan. Exits: a row pushes the building's guest flow (`DeepLink.building(code, plate: nil)`); `+` opens the web.

#### 6.1 Navigation bar

| Element | iOS | Android | Parity |
|---|---|---|---|
| Title | `"Buildings"`, large title (Anton 34 via `IVAppearance`), collapses to inline Barlow SemiBold 17 on scroll | `IvTopBar` = small `TopAppBar`, title in `IvType.headline`, background `Palette.background` (`features/…/ui/ListParts.kt:48-71`) | **Differs** (all four tabs): no large Anton title. Use `LargeTopAppBar`/`MediumTopAppBar` with `IvType.display(34.sp)` collapsing to `headline` and `exitUntilCollapsedScrollBehavior` |
| Trailing action (signed in only) | `Label("Add building", systemImage: "plus")` (icon only, VoiceOver "Add building") → `openURL(webLinks.addBuilding)` = `{WEB}/app/buildings/new` in Safari (`BuildingsTab.swift:36-46`) | `IconButton` `Icons.Outlined.Add`, contentDescription `"Add building"` → Custom Tab (`BuildingsTab.kt:134-140`) | Done (Custom Tab vs Safari is the platform equivalent) |

#### 6.2 States

Loading runs in `.task(id: isSignedIn)`: bind, refresh local lists, then (signed in) `load()` and refresh local lists again (`BuildingsTab.swift:48-54`). `load(refresh:)` sets `.loading` only when the list is empty, calls `GET /v1/me/buildings` (`myBuildings.list`) then `GET /v1/orgs` (`trialEnds`; failures there are ignored), `.failed` on any other error, cancellation ignored (`:274-285`). Android: `LaunchedEffect(isSignedIn)` (`BuildingsTab.kt:119-125`), `BuildingsTabModel.load` (`:415-427`). **Done.**

| State | iOS | Android | Parity |
|---|---|---|---|
| Signed out (also during Keychain restore) | `ContentUnavailableView`: icon = `Wordmark(size: 30)` with 8 pt bottom padding; title `"Your buildings, in one place"`; description `"Sign in to see your company's buildings, the ones shared with you and the ones you saved."`; action `"Sign in"` `.primaryAction` → `requestSignIn(.account)` (`:190-206`). No `+` button | `ContentUnavailable` (`app/…/android/ContentUnavailable.kt:38-71`) with `Wordmark(size = 30.sp)`, same strings, `PrimaryActionButton` (`BuildingsTab.kt:377-389`) | Done |
| Loading, list empty | Centred `ProgressView` | `CircularProgressIndicator(color = accent)` | Done |
| Failed, list empty | `ContentUnavailableView`: `wifi.slash`; `"Couldn't load your buildings"`; `"Check your connection and try again."`; plain (tinted) button `"Try again"` → `load(refresh: true)` (`:66-79`) | `Icons.Outlined.WifiOff`, same strings, `PlainActionButton` accent text (`BuildingsTab.kt:166-177`) | Done |
| Failed with a list already shown | Keeps the list (no banner) | Same | Done |
| List | ¶6.3 | ¶6.3 | |

#### 6.3 List

iOS: `List(.insetGrouped)` with `.refreshable` (pull to refresh → `load(refresh: true)` + local lists) (`:89-120`).

1. Segmented `Picker` (`.segmented`, clear row background, insets 4/0/4/0): `"All"`, `"Active"`, `"Recent"`, `"Favorites"`. Selection is in-memory per launch (defaults to All; not persisted).
2. Rows of the segment, or the segment's empty state.

Segment rules (`Kit/IVAPI/Buildings/MyBuildings.swift:99-119`):

| Segment | Rows |
|---|---|
| All | every building, sorted by `name` with `localizedStandardCompare`, ties by `code` |
| Active | All filtered to `status` `TrialLive` or `Active` (not `Expired`) |
| Recent | this device's recents (newest first, max 20) that are in my buildings, in recents order |
| Favorites | All filtered to codes starred on this device |

Empty states (`BuildingsTab.swift:161-188`):

| Segment | Content |
|---|---|
| All | `ContentUnavailableView`: icon `building.2`; `"No buildings yet"`; `"Scan a building's code, or add one on the web."`; plain button `"Scan a code"` → selects the Scan tab |
| Active | muted text `"None of your buildings are live right now."` |
| Recent | muted text `"Buildings you open show up here."` |
| Favorites | muted text `"Swipe a building and tap the star to keep it here."` |

Row (`BuildingRowContent`, `:215-255`), vertical spacing 4, vertical padding 2:

- Line 1: name (`Text(verbatim:)`, `.ivHeadline`) + if favourite, `star.fill` at `.caption2`, `Palette.accent`, accessibility label `"Favorite"`.
- Line 2 (optional, `.subheadline`, muted): `city` if non-empty; else `organizationName` for `via == grant` and for members; nothing for `via == saved`.
- Line 3 (members only, `via == member`): `StatusLineView` (`Kit/IVFeatures/Building/StatusLineView.swift`), `.subheadline`, `Palette.warn` when `isAttention` (paused, expired, trial ≤ 3 days) else muted:

  | Status | Icon | Text |
  |---|---|---|
  | `Draft` | `tray` | `"No model yet"` |
  | `Ready` | `clock` | `"Not live yet"` |
  | `TrialLive`, days ≤ 0 | `hourglass` | `"Trial · ends today"` |
  | `TrialLive`, n days | `hourglass` | `"Trial · %lld day(s) left"` (plural; days rounded up, `BuildingStatus.swift:65-69`) |
  | `TrialLive`, no org trial end | `hourglass` | `"Trial"` |
  | `Active` | `checkmark.seal` | `"Active"` (the list has no activation year; BuildingHome shows `"Active until %@"`) |
  | `Paused` | `pause.circle` | `"Paused · Activate on the web"` (plain text, never a link) |
  | `Expired` | `archivebox` | `"Expired · read-only"` |
  | unknown | `info.circle` | raw value |

- Tap: `NavigationLink` (chevron) when the code parses and `status != Draft`; otherwise not tappable and drawn muted (`:128-137`).
- Leading swipe (only rows with a code): one action tinted accent, `"Favorite"` + `star` or `"Unfavorite"` + `star.slash`; full swipe triggers it. Toggles `FavoriteBuildings` and refreshes (`:138-149`).
- `onChange(of: openRequests)` refreshes favourites/recents so Recent follows links (`:55-58`).

Android (`BuildingsTab.kt:183-374`): `PullToRefreshBox` + `LazyColumn`; `SingleChoiceSegmentedButtonRow` (16 dp side padding, M3 segmented shape); rows are `ListRow` (full-width surface rows, 0.5 dp `Palette.line` divider inset 16, min height 48, chevron `KeyboardArrowRight` muted) (`features/…/ui/ListParts.kt:103-142`); swipe is `SwipeToDismissBox` start-to-end with accent background, `Star`/`StarBorder` + label in `onAccent`; a completed swipe toggles and snaps back (`BuildingsTab.kt:282-310`).

| Item | Parity |
|---|---|
| Segments, sort and filter rules, empty texts, "Scan a code" | Done |
| Row content (name, star, subtitle rule, status line) | Done |
| Draft/unparseable rows muted and inert | Done (only the name is recoloured; subtitle and status already muted) |
| Swipe to favourite | Differs slightly: iOS reveals a tappable button on a partial swipe; Android only acts on a full swipe. Acceptable; a partial-swipe-then-tap pattern isn't idiomatic in M3 |
| `star.slash` | Android uses `StarBorder` (Material has no slashed star). Fine |
| List style | Differs cosmetically: iOS inset grouped cards with rounded corners; Android flat full-width rows (applies to Search and Profile too) |
| Pull to refresh | Done |

### 7. Search tab

Purpose: one search across every building the person can open (`GET /v1/me/search`). iOS `App/SearchTab.swift`; Android `app/…/android/SearchTab.kt`.

Layout (iOS):

- Navigation title `"Search"` (large), `.searchable` field in the navigation bar with prompt `"Search your buildings, rooms and equipment"` — present signed in **and** signed out (`SearchTab.swift:20-37`).
- Body by state:

| State | Content |
|---|---|
| Signed out | `ContentUnavailableView`: `magnifyingglass`; `"Search your buildings"`; `"Sign in to search across every building you can open."`; `"Sign in"` `.primaryAction` → `requestSignIn(.account)` (`:125-141`) |
| Query blank (trimmed of spaces) | `ContentUnavailableView`: `magnifyingglass`; `"Search across your buildings"`; `"Find a building by name, or an outlet, valve or panel across every building you can open."` |
| Searching, no results yet | Centred `ProgressView` |
| Results empty | `ContentUnavailableView.search(text: query)` (system: `No Results for "…"` / `Check the spelling or try a new search.`) |
| Results | `List(.insetGrouped)`: section `"Buildings"` (header) with building rows, then one section per building group (header = building name, verbatim) with hits room by room |
| Request failed | Nothing changes: the previous results stay, or (none yet) the body is empty (`Color.clear`) — no error message |

Behaviour (`SearchTabModel`, `:165-208`):

- `.task(id: query)`: every keystroke cancels and restarts; trimmed (whitespace and newlines) empty → results cleared; else sleep **300 ms** (debounce), then `isSearching = true`, load my-buildings codes (cached list; `[id: code]`), `api.searchMyBuildings(query)`; errors and cancellation keep the old state.
- Grouping (`Kit/IVAPI/Buildings/Search.swift:34-70`): buildings in API order; hits grouped by `buildingId` in first-seen order; within a building by trimmed `room` in first-seen order, hits with no room last. A group's code comes from the response's buildings or my buildings; `nil` → its rows are muted and inert.
- Building row: `Label` with icon `building.2`; name (ink) and subtitle = first non-empty of `addressLine`, `city` (`.footnote`, muted). Tap → push `DeepLink.building(code, plate: nil)` (the landing).
- Hit row: icon `door.left.hand.open` when `type == room`, else `cube`; `title` (ink) and place = `room · storey` (joined with `" · "`, omitted when both nil) (`.footnote`, muted). Tap → push `OpenBuilding(.building(code, plate: nil), elementID: hit.id)` (landing, then the element's card).
- No analytics here (`search_performed` is a visit event, fired inside a building).
- Quirk (both platforms): signed out, typing still runs the request (it fails silently).

Android: `OutlinedTextField` under the top bar (16/8 dp padding, leading `Search` icon, trailing `Close` "Clear search" when non-empty, IME action Search clears focus; accent focus border, surface fill) (`SearchTab.kt:104-127`); states `:139-177`; `DEBOUNCE_MS = 300` (`:285`); rows `ListRow` + `RowIcon` (accent) with `Apartment`, `MeetingRoom`, `ViewInAr` (`:180-205`); empty results `"No results for “%s”"` / `"Check the spelling or try a new search."` (Android-only strings, `strings_app.xml`).

| Item | Parity |
|---|---|
| States, strings, debounce, grouping, ordering, tap targets | Done |
| Field placement | Differs (expected): iOS search field lives in the nav bar under the large title; Android a field below the top bar. A Material `SearchBar`/`DockedSearchBar` would be closer, not required |
| `isActive` trimming | Android trims only space/tab (`:235`); iOS `.whitespaces` (all Unicode spaces). Negligible |
| Section headers | Android `SectionHeader` uppercases in `ivEyebrow` style, including building names (`ListParts.kt:75-82`); iOS inset-grouped headers are system small caps-like grey text. Close enough; note the building name is model data shown uppercased |

### 8. Scan tab and QR scanner

iOS `App/ScanTab.swift`, `App/ScannerView.swift`; Android `app/…/android/ScanTab.kt`, `ScannerView.kt`, `ScanDebouncer.kt`.

Purpose: scan a plate inside the app. Only Insite View URLs are accepted (¶3 rules); an accepted code switches to Buildings and pushes the building.

Lifecycle: the scanner only exists while the tab is selected (black otherwise); a fresh scanner (new identity) every time the tab is shown again, because the previous one stopped after accepting (`ScanTab.swift:6-29`). Android composes only the selected tab, so every visit builds a new `ScannerView` (`ScanTab.kt:23-40`, `RootTabView.kt:160`). **Done.**

Layout (iOS, full-bleed, no navigation title, no Cancel in the tab; `showsCancel` adds a `"Cancel"` toolbar button when used as a sheet — not used anywhere today):

- Camera: VisionKit `DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced, recognizesMultipleItems: false, isHighFrameRateTrackingEnabled: false, isPinchToZoomEnabled: true, isGuidanceEnabled: true, isHighlightingEnabled: true)` (`ScannerView.swift:67-77`), `.ignoresSafeArea()`.
- Bottom hint, 32 pt above the bottom, `.callout` medium, 12 pt padding, `.ultraThinMaterial` square background, animated swap: `"Point the camera at the Insite View code"`, or after a foreign payload `"This isn't an Insite View code"` (stays until the scanner is rebuilt) (`:27-34`).
- No torch button, no gallery import, no manual code entry.

Behaviour:

- Each recognised payload: the same payload is ignored for **2 s** after it was last handled (`:104-108`); a different one is handled at once.
- `AppRootModel.open(scannedText:)` → Router; accepted → `didAccept`, `stopScanning()`, `UINotificationFeedbackGenerator().notificationOccurred(.success)` haptic (`:109-114`), tab switches to Buildings, building replaces the stack. Rejected → hint changes, scanning continues.
- Unavailable (`!isSupported || !isAvailable`: no camera, permission denied/restricted): `ContentUnavailableView` icon `camera.metering.unknown`, title `"Scanning isn't available"`, description `"Allow camera access in Settings, or open the code with the Camera app."`; **no buttons** (`:35-45`).
- Permission: no pre-prompt screen in the Scan tab; the system prompt (text `NSCameraUsageDescription` `"Camera access is required for augmented reality."`, localized in `Resources/InfoPlist.xcstrings`) comes from VisionKit/AVFoundation. Whether `isAvailable` is true before the first prompt (`.notDetermined`) decides if first-run users see the prompt or the unavailable view: device check.

Android (`ScannerView.kt:81-217`):

| Item | Android | Parity |
|---|---|---|
| Engine | CameraX `LifecycleCameraController` (image analysis only, keep-latest), `PreviewView` `FILL_CENTER` (pinch-zoom and tap-to-focus from the controller), ML Kit `FORMAT_QR_CODE` | Done (PLAN §6) |
| Highlight boxes around codes, VisionKit guidance | Not drawn | Differs (minor): no on-screen highlight of the detected code |
| Repeat filter | `ScanDebouncer(window = 2.seconds)` (`ScanDebouncer.kt:14-28`) | Done |
| Accept | `clearImageAnalysisAnalyzer()`, `HapticFeedbackType.Confirm` (`ScannerView.kt:188-193`) | Done |
| Hint | `AnimatedContent`, Barlow 16 Medium, ink on `surface` at 85 % alpha, 12 dp padding, 32 dp above the nav bar (`:119-132`) | Done |
| Permission | Requests `CAMERA` the first time the scanner shows (once, `rememberSaveable`), re-checks on resume (`:88-106`) | Done (Android has no usage string in the prompt) |
| Denied | `ContentUnavailable` with `NoPhotography`, iOS title/description, plus `PrimaryActionButton "Allow the camera"` (while `shouldShowRequestPermissionRationale`) and `SecondaryActionButton "Open settings"` → app details settings (`:134-148`) | Differs (additive): iOS shows no buttons. Keep — Android has no other route back to the prompt. Copy says "the Camera app", which many Android camera apps satisfy |
| Torch | None | Done (neither has one) |

### 9. Profile tab

iOS `App/ProfileTab.swift`; Android `app/…/android/ProfileTab.kt`. Purpose: account, organizations and role, units, language, help/legal, sign out, delete account. No billing (master PLAN §11).

Shown content depends on `account.me` (not `isSignedIn`): `me == nil` → signed-out state. `.task` on every appearance: if signed in, `refreshProfile()` (`GET /v1/me`, errors ignored) (`ProfileTab.swift:45-49`). Android: `LaunchedEffect(Unit)` each time the tab is composed (`ProfileTab.kt:79-81`). **Done.**

Navigation bar: title `"Profile"`; DEBUG builds only, trailing `scope` icon button (VoiceOver `"AR marking test"`, not localized) → full-screen `MarkingTestView` (`:29-43`). Android: `CenterFocusStrong`, `BuildConfig.DEBUG`; the test replaces the tab content (tab bar stays visible), Back closes (`ProfileTab.kt:83-100`). Done (debug only).

#### 9.1 Signed out

`ContentUnavailableView`: icon `person.crop.circle`; `"Your account"`; `"Sign in to manage your account, organizations and units."`; `"Sign in"` `.primaryAction` → `requestSignIn(.account)`; bottom inset: attribution footer, 16 pt padding (`:52-71`).

Attribution footer (`:75-86`): two centred lines, `ivMono(11)` muted, spacing 4: `"Insite View · Numerics Engineering"` (localized) and `Numerics Consultoria em TI LTDA · CNPJ 69.268.892/0001-02` (verbatim).

Android `ProfileSignedOut` / `AttributionFooter` (`ProfileTab.kt:116-154`). **Done.**

Quirk (both): signed in but launched offline → `me == nil` → this signed-out view with a "Sign in" button; signing in is refused silently (`AccountService.signIn` only runs from `signedOut`/`restoring`, `Kit/IVAPI/Account/AccountService.swift:172`; Android `api/…/account/AccountService.kt:179`). Keep parity or fix both (e.g. show a "Couldn't load your profile / Try again" state when `isSignedIn && me == nil`).

#### 9.2 Signed in (`ProfileContent`, `:88-324`), `List(.insetGrouped)`, top to bottom

| # | Section (header / footer) | Rows | Behaviour |
|---|---|---|---|
| 1 | Header `"Account"` | `LabeledContent` `"Name"` → `displayName`; `"Email"` → `email` (values secondary grey). If `!emailConfirmed`: button `Label("Confirm your email", systemImage: "envelope.badge")` (accent) → `POST` send confirmation (errors ignored) → row becomes `Label("Confirmation email sent. Check your inbox.", systemImage: "checkmark.circle")` muted (state lasts while the view lives) | `:148-182` |
| 2 | Header `"Organizations"`, footer `"Manage members and billing on the web."` (only when the user has organizations) | One `LabeledContent` per org: label = org name (verbatim), value = role `"Owner"` / `"Admin"` / `"Member"` / raw value | `:186-209` |
| 3 | Header `"Measurements"`, footer `"Sizes and distances use this system, on this and your other devices."` | `Picker` `"Units"`: `"Metric"`, `"Imperial"` (menu-style picker in a list row: current value with up/down chevrons). Initial value = `me.units`. On change: `dependencies.unitSystem = new` (UserDefaults `com.getinsiteview.units`) and `PATCH /v1/me {units}` (errors ignored) | `:213-230` |
| 4 | (no header) | `"Language"` (`globe`, trailing `arrow.up.forward` muted footnote) → iOS Settings app page for Insite View; `Link "Help"` (`questionmark.circle`) → `{WEB}/faq`; `Link "Terms"` (`doc.text`) → `{WEB}/terms`; `Link "Privacy"` (`hand.raised`) → `{WEB}/privacy`. Links open Safari. In an iOS list these buttons/links render in the tint colour (accent red, text and icon) | `:234-259` |
| 5 | (no header) | Destructive `"Sign out"` (with trailing spinner while running, disabled meanwhile); destructive `"Delete account"` (spinner while deleting) → confirmation alert | `:263-294` |
| 6 | Footer: attribution footer (12 pt top padding) | `LabeledContent` `"Version"` → `CFBundleShortVersionString` in `ivMono(15)`; whole row muted | `:296-311` |

Delete account flow (`:126-143`, `:313-323`):

1. Alert `"Delete your account?"`, message `"This permanently removes your account and the buildings you saved. Buildings owned by your company aren't affected. This can't be undone."`, buttons `"Delete account"` (destructive) and `"Cancel"`.
2. `DELETE /v1/me`. Success → state `deleted` → signed out, tokens cleared, my-buildings cache cleared; Profile shows ¶9.1.
3. 409 / `conflict` → alert `"Hand over your company first"`, `"You're the only owner of a company that has other members. Make someone else an owner on the web, then delete your account."`, `"OK"`.
4. Anything else (offline included) → alert `"Couldn't delete your account"`, `"Check your connection and try again."`, `"OK"`.

Sign out (`Kit/IVAPI/Account/AccountService.swift:215-222`): signed out on the device at once (state, Keychain), then best-effort `POST /v1/auth/logout` with the refresh token; `myBuildings.clear()`. Favourites, recents, units and the device id stay.

Roles: Profile shows the role only; nothing is hidden or shown by role in the app shell. Members see status lines in Buildings (¶6.3); grant/saved buildings don't.

Android (`ProfileTab.kt:158-379`): a scrolling `Column` of `SectionHeader` (eyebrow, uppercase) / `LabeledRow` / `ListRow` / `SectionFooter`, 20 dp spacers between header-less groups; units are a `ListRow` whose accent value opens a `DropdownMenu`; Language → per-app language settings on Android 13+ (`Settings.ACTION_APP_LOCALE_SETTINGS`), else system locale settings (`app/…/android/WebPages.kt:38-45`, PLAN §6); Help/Terms/Privacy in a Custom Tab; dialogs are M3 `AlertDialog`s.

| Item | Parity |
|---|---|
| Sections, order, strings, conditions, flows, error mapping | Done |
| Language destination | Done (PLAN §6 difference) |
| Language/Help/Terms/Privacy row colour | **Differs**: Android text is `Palette.ink` with accent icons (`ProfileTab.kt:263, 269, 273, 277`); iOS list buttons/links are tinted accent (text and icon). Use `Palette.accent` text for parity |
| Sign out / Delete colour | Differs (minor): iOS destructive = system red (`#FF3B30`/`#FF453A`); Android `Palette.accent` (`:295, 302`). Fine to keep brand red |
| Version row | Differs (minor): iOS whole row muted; Android label is ink (`LabeledRow`, `ListParts.kt:146-152`) |
| Units picker | Done functionally (dropdown vs iOS menu picker) |
| Section headers | Android uppercase eyebrow vs iOS system grouped header. Cosmetic |
| Debug marking test | Done (debug only; tab bar remains visible on Android) |

Quirk (both): the units row shows the **account's** `me.units`, but the 3D/AR formatting uses the **device** value (`UserDefaults`/DataStore, else locale default; `AppDependencies.swift:157-171`, Android `AppDependencies.kt:175-188`); the account value is never copied to the device on sign-in, so the two can disagree until the user changes the picker.

### 10. Sign-in sheet

Entry points: `"Sign in"` on Buildings, Search and Profile (reason `.account`); the guest landing's "Save this building" (reason `.saveBuilding(name:)`, covered by the guest section; it awaits the result and saves on success). One prompt at a time: a new request finishes the previous one with `false` (`Kit/IVFeatures/Account/AccountModel.swift:117-140`; Android `features/…/account/AccountModel.kt:125-147`). Exits: success → `finished()` closes the sheet (`prompt.finish(signedIn: true)`); Cancel / swipe-down → `finish(signedIn: account.isSignedIn)`.

#### 10.1 Sign-in options (iOS `App/SignInView.swift:12-117`)

Presentation: `.sheet` (large) containing a `NavigationStack`; `ScrollView`, 24 pt padding, `Palette.background`; toolbar `"Cancel"` (cancellation placement, top leading); swipe-down disabled while a sign-in runs (`interactiveDismissDisabled(busy != nil)`, `:90`).

Top to bottom (spacing 24):

1. Header (spacing 8, centred):
   - `.account`: `Wordmark(size: 34)` + 8 pt; `"Sign in to Insite View"` `ivDisplay(22)` (uppercase); `"See your company's buildings, the ones shared with you and the ones you saved."` muted.
   - `.saveBuilding(name)`: `bookmark` 44 pt light, accent (hidden from VoiceOver); `"Save %@"` `ivDisplay(26)` (name interpolated); `"A free account keeps your buildings in one place, on any iPhone."` muted.
2. Buttons (spacing 12, all disabled while any sign-in runs):
   - Native **Sign in with Apple** `SignInWithAppleButton(.continue)`, black in light / white in dark, height 50, always shown (`:34-42`). Requests full name and email.
   - One `.secondaryAction` button per provider the server offers, in order Google, Microsoft (`GET /v1/auth/oauth/providers`, cached per process when it succeeds, `[]` offline; `Kit/IVAPI/Account/AccountService.swift:140-148`, `OAuth.swift:16-19`): `"Continue with Google"`, `"Continue with Microsoft"` (unknown names: capitalised raw value); spinner inside while that one runs.
   - `.secondaryAction` `Label("Continue with email", systemImage: "envelope")` → pushes ¶10.2.
3. Error line if the last attempt failed (¶10.3).
4. Terms notice: `"By continuing you agree to Insite View's terms and privacy policy."` then links `"Terms"` and `"Privacy"` (16 pt apart), footnote, muted, centred (`:372-392`).

Provider flow: `ASWebAuthenticationSession` (ephemeral) on `{API}/v1/auth/oauth/{provider}/start?returnTo=insiteview%3A%2F%2Fauth%2Fcallback&terms=2026-09` (`OAuth.swift:33-47`; terms version `TermsVersion.current = "2026-09"`, `WebLinks.swift:62-64`) → callback `insiteview://auth/callback?code=…` → `POST /v1/auth/oauth/exchange` (code valid 60 s); `error=email_in_use` → `.emailInUse`, any other `error`/no code/wrong URL → `.providerFailed` (`OAuth.swift:57-75`). Apple: identity token + authorization code + (first time only) full name → `POST /v1/auth/apple/native` with the terms version. Cancelling either returns silently. Device name sent with the refresh token: `UIDevice.current.model` ("iPhone").

Android (`app/…/android/SignInView.kt:83-224`): `ModalBottomSheet` (`skipPartiallyExpanded`, no drag handle, `Palette.background`), dismiss blocked while busy (`confirmValueChange`, `onDismissRequest`); top-left `TextButton "Cancel"` (accent); header identical (`.uppercase()` + `IvType.display`); providers from the server **including Apple**, sorted Apple first (`:103-106`), each a `SecondaryActionButton` with a start-aligned 18 dp spinner while busy; Apple label `"Continue with Apple"` (Android-only string); save subtitle `"A free account keeps your buildings in one place, on any phone."` (Android-only string). Browser step: `OAuthCallbackBroker.authenticate` opens a Custom Tab (shares browser cookies; PLAN §6) and waits for the callback intent; returning to the app without a callback (tab closed) cancels silently (`OAuthCallbackBroker.kt:41-99`); no browser → `ProviderFailed`. Device name `Build.MODEL`.

| Item | Parity |
|---|---|
| Header, terms notice, Cancel, busy rules, error texts | Done |
| Apple | Differs by decision (PLAN §6): web flow, and only when the server lists `apple`; iOS always shows the native button. Styling also differs (outlined secondary button vs Apple's black/white button). Make sure the API lists `apple` for Android, or Android users lose Apple sign-in |
| `"Continue with email"` icon | **Differs**: Android has no `envelope` icon (`SignInView.kt:159-163`); add `Icons.Outlined.Email` as a leading icon |
| Terms/Privacy links colour | Android accent `TextButton`s (`:435-440`); iOS sets the whole notice muted (links likely render muted too). Device check, cosmetic |
| Ephemeral browser session | Differs by decision (PLAN §6) |
| Back gesture | Android: Back on the options page closes the sheet (unless busy); on the email page returns to options (`BackHandler`, `:130`) | Done (platform) |

#### 10.2 Email screen (iOS `EmailSignInView`, `SignInView.swift:174-336`)

Pushed inside the sheet; inline title `"Sign in with email"` or `"Create an account"` by mode; system back button. `Form`:

1. Segmented picker: `"Sign in"` | `"Create account"` (clear background, no insets). Changing mode clears the error (`:297-299`).
2. Fields section:
   - Register only: `"Your name"` (`textContentType .name`, Next) + its field errors.
   - `"Email"` (`.username` content type for Password AutoFill, email keyboard, no autocapitalisation, no autocorrect, Next) + field errors.
   - `"Password"` `SecureField` (`.password`, or `.newPassword` when registering; Go) + field errors.
   - Footer (register only): `"At least 10 characters."`
3. Submit section: a plain Form-row button (accent text, centred, full width, spinner before the label while busy): `"Sign in"` / `"Create account"`; disabled until valid. Sign-in mode only: plain button `"Forgot password?"` → Safari `{WEB}/forgot-password`. Footer: the error line if it isn't a field error; register mode: the terms notice.

Validation (`:197-203`): email contains `@` and is ≥ 3 chars; sign in also needs a non-empty password; register needs password ≥ 10 and a name that isn't blank. Return key: name → email → password → submit if valid (`:289-296`). Email and name are trimmed before sending (`AccountService.swift:108-126`); register sends `termsVersion 2026-09` and `locale` = preferred catalog language (`en`/`pt-BR`/`es`).

Field errors: `validation` ProblemDetails messages for `displayName`, `email`, `password` (case-insensitive keys), shown verbatim (the API localises them), footnote, `Palette.warn` (`:302-309`).

On `emailTaken` while registering: the error is set and the mode flips to Sign in — which clears the error via `onChange(of: mode)`, so iOS ends up on Sign in with no message (`:330-333`).

Android `EmailSignInView` (`SignInView.kt:227-383`): replaces the options inside the same sheet; header row with `ArrowBack` ("Back") and the title in `IvType.headline`; `SingleChoiceSegmentedButtonRow`; `OutlinedTextField`s (accent focus border, surface fill) with autofill content types `PersonFullName`, `Username`, `Password`/`NewPassword`, IME Next/Next/Go, `"At least 10 characters."` as supporting text; submit is a `PrimaryActionButton`; `"Forgot password?"` `TextButton` → Custom Tab; then the error, then terms (register).

| Item | Parity |
|---|---|
| Modes, fields, validation, IME order, field errors, forgot password, terms | Done |
| Submit button style | **Differs**: Android `PrimaryActionButton` (red fill, uppercase) (`:367-373`); iOS a plain accent text row. Either restyle, or accept (the Android one arguably reads better) |
| `emailTaken` | **Differs**: Android keeps the message `"There's already an account with this email. Sign in instead."` after switching to Sign in (`:258-264`, commented). iOS clears it (looks like an iOS bug). Decide once; recommended: fix iOS to keep the message |
| Name blank check | Android trims only space/tab (`:244`) |
| State across rotation | Android keeps mode/email/password/name (`rememberSaveable`); iOS n/a |

#### 10.3 Sign-in error texts (`SignInErrorText`, `SignInView.swift:339-369`; `SignInError` mapping `Kit/IVAPI/Account/AuthState.swift:45-84`)

Shown with `exclamationmark.circle` (Android `ErrorOutline` 16 dp), footnote, `Palette.warn`, leading-aligned. Never the API's message.

| `SignInError` | From | Text |
|---|---|---|
| `invalidCredentials` | `auth.invalid_credentials`, else status 401 | `"That email and password don't match."` |
| `emailTaken` | `auth.email_taken` | `"There's already an account with this email. Sign in instead."` |
| `lockedOut(seconds)` | `auth.locked_out`, else 423 (`Retry-After`) | `"Too many tries. Try again in %lld minute(s)."` (minutes = max(1, ⌈s/60⌉)); without seconds `"Too many tries. Try again later."` |
| `emailInUse` | `auth.email_in_use`, else 409; callback `error=email_in_use` | `"This email already has an account. Sign in with your email and password."` |
| `codeExpired` | `auth.token_invalid` | `"Signing in took too long. Try again."` |
| `providerFailed` | callback error / bad callback; Apple failure | `"Signing in didn't work. Try again."` |
| `providerUnavailable` | `not_found` (provider not configured) | `"This way of signing in isn't available right now."` |
| `validation` (non-field) | `validation` | `"Check the details and try again."` |
| `rateLimited` | `rate_limited`, else 429 | `"Too many tries. Wait a moment, then try again."` |
| `offline` | network errors (iOS `offlineCodes`, `GuestProblem.swift:104-107`; Android `UnknownHost`/`Connect`/`NoRouteToHost`/`Socket`/`InterruptedIO`) | `"You're offline. Connect to the internet and try again."` |
| `cancelled` | user closed the sheet/tab | nothing shown |
| `unavailable` | anything else, or a response without tokens | `"Something went wrong. Try again."` |

Android `SignInErrorText` (`SignInView.kt:399-421`), plural `too_many_tries_try_again_in_n_minute`. **Done.**

### 11. Account state, session end, offline

`AuthState` (`Kit/IVAPI/Account/AuthState.swift:96-183`; Android `api/…/account/AuthState.kt`): phases `restoring → signedOut | signedIn`, `signingIn(method)`. Only one sign-in at a time; results nobody waits for are dropped.

| Event | What the user sees |
|---|---|
| Launch with tokens | Signed in at once; `GET /v1/me` fills Profile. Offline → signed in without `me` (Buildings shows the load error; Profile shows the signed-out view, ¶9.1 quirk) |
| Access token near expiry (< 60 s) | Refreshed before the call (`Kit/IVAPI/Credentials.swift:35, 62-72`); a 401 refreshes once and retries (`Kit/IVAPI/Middlewares.swift:197-228`). Invisible |
| Refresh rejected (401 or `auth.refresh_reused`) | Tokens cleared → `sessionEnded` → signed out; my buildings cleared; tabs switch to their signed-out states. **No message** — `AuthState.sessionEnded` ("Your session ended. Sign in again." in a doc comment) is set but nothing displays it on either platform |
| Refresh fails for lack of network | Tokens kept; the call goes with the old token |
| Sign out / delete | ¶9.2 |
| Signed out while a building is open | The open building stays (its visit scope was fixed at start) |

Android: same machine (`AccountService.kt:108-114, 179, 227-251`), tokens AES-GCM sealed with an Android Keystore key in DataStore (`features/…/storage/KeystoreTokenStore.kt`). **Done.** (Both Missing the session-ended message; add it to both or neither.)

### 12. Persistence (device)

| What | iOS | Android | Cleared on sign-out |
|---|---|---|---|
| Device id (`X-Device-Id`, 8–64 `[A-Za-z0-9_-]`) | Keychain `com.getinsiteview.device` / `deviceId` (survives reinstall) | DataStore (does not survive reinstall; `allowBackup="false"`) | No |
| Tokens | Keychain `com.getinsiteview.auth` / `tokens`, `AfterFirstUnlockThisDeviceOnly` | Keystore-sealed DataStore | Yes |
| Favourites (codes, sorted array) | UserDefaults `com.getinsiteview.favorites` | DataStore same key (JSON) | No |
| Recents (code + opened-at, newest first, max 20) | UserDefaults `com.getinsiteview.recents`; recorded when a building's manifest loads, app only (`Kit/IVFeatures/Building/BuildingSession.swift:257-259`) | DataStore same key; `BuildingSession.kt:386` | No |
| Units | UserDefaults `com.getinsiteview.units` (`Metric`/`Imperial`) | DataStore same key | No |
| Handoff | App Group `com.getinsiteview.handoff` | DataStore `com.getinsiteview.handoff` + `…handoff.referrerRead` | — |
| Selected tab, Buildings segment | Memory only (launch → Buildings, All) | `rememberSaveable` tab (survives rotation/process death); segment in the list's ViewModel | — |
| My buildings, orgs | In-memory cache (`MyBuildingsRepository`), refetched on pull to refresh | Same | Yes |

Parity: **Done**, except the device id surviving reinstall on iOS only (platform limitation; fine).

### 13. Web pages the app opens

From `WebLinks` (`Kit/IVCore/WebLinks.swift:6-37`; Android `core/…/WebLinks.kt:13-40`), base `WEB_BASE_URL` (`https://getinsiteview.com`), no locale prefix (the web reads `Accept-Language`), query/fragment stripped.

| Link | Path | Where |
|---|---|---|
| Forgot password | `/forgot-password` | Email sign-in |
| Add building | `/app/buildings/new` | Buildings `+` |
| Terms | `/terms` | Sign-in terms notice, Profile |
| Privacy | `/privacy` | Sign-in terms notice, Profile |
| Help | `/faq` | Profile |

iOS opens them in Safari; Android in a Custom Tab with the page title shown (`app/…/android/WebPages.kt:18-27`; nothing happens without a browser). **Done.** No purchase links anywhere.

### 14. Units and language

- Default unit system: imperial for regions `US`, `LR`, `MM`, else metric (`Kit/IVCore/Units.swift:10-21`; Android `core/…/Units.kt:32-37`), overridden by the Profile picker (¶9.2). Formatting rules belong to the viewer/AR sections.
- Language: the app follows the device (iOS) / app locale (Android 13+ per-app, `app/src/main/res/xml/locales_config.xml`: en, pt-BR, es). `Accept-Language` sends up to 6 tags with q-values (`pt-BR, en-US;q=0.9`, `Kit/IVAPI/Middlewares.swift:86-97`), read on every call. Catalog names use `CatalogLanguage.preferred` (en, pt-BR, es, English otherwise). New accounts get that locale.
- Parity: **Done** (Language destination per PLAN §6).

### 15. Permissions, privacy and data safety

| iOS | Android | Parity |
|---|---|---|
| `NSCameraUsageDescription`: en `"Camera access is required for augmented reality."`, pt-BR `"O acesso à câmera é necessário para a realidade aumentada."`, es `"Se necesita acceso a la cámara para la realidad aumentada."` (`Resources/InfoPlist.xcstrings`) | `android.permission.CAMERA`; system dialog has no app text; `camera` and `camera.ar` features optional; `com.google.ar.core` optional | Done (platform) |
| `CFBundleDisplayName` `Insite View` | `app_name` `Insite View` (not translatable) | Done |
| No tracking, no ATT prompt (device id is not an advertising id) | No advertising id, no `AD_ID` permission | Done |
| Privacy manifest (`Resources/PrivacyInfo.xcprivacy`): collected Name, Email, User ID (linked, app functionality), Device ID (linked, app functionality + analytics), Product interaction (linked when signed in, analytics); required-reason APIs: file timestamps C617.1 (model cache), UserDefaults CA92.1 | Play **Data safety** form: not written (AND-M6-03 open) | **Missing**: declare the same: Personal info → Name, Email, User IDs (collected, not shared, app functionality, account deletion available in-app); Device or other IDs (app functionality, analytics); App activity → App interactions (analytics); data encrypted in transit; users can request deletion (Profile → Delete account) |
| App Store privacy labels table (`docs/app-store-listing.md`) | — | Missing (same as above) |
| `ITSAppUsesNonExemptEncryption: false` | Play has no equivalent export question beyond standard HTTPS | n/a |
| Sentry DSN in the app only | `SENTRY_DSN` empty until AND-M6-03 | Missing (planned) |

### 16. Store listing → Google Play listing

iOS: `docs/app-store-listing.md`. Android has no listing document (`docs/` holds `PLAN.md`, `device-test.md`, `ios-ar-reference.md`): **Missing**. Equivalent, adapted (no App Clip, "iPhone" → "phone", "QR code with the iPhone camera" → "with your phone's camera or the app"):

| Play field (limit) | Content |
|---|---|
| App name (30) | `Insite View` |
| Short description (80) | `See inside the walls in AR: pipes, wiring and ducts aligned to the QR code.` (iOS subtitle `See inside the walls in AR` + promo text, trimmed to 80) |
| Full description (4000) | iOS description with: "opens on your iPhone" → "opens on your phone"; the "FOR OWNERS, RESIDENTS AND TRADES" paragraph rewritten: "Scan a QR code with your phone's camera. Without the app, the building opens in your browser, with nothing to install and no account. Get the app to keep the building and open it again anytime."; keep FOR BUILDERS…, FEATURES (drop nothing; "On-site QR code registration for company admins" stays), SAFETY, and the last paragraph |
| Category | Productivity (Play has one category; tags: Business) |
| Contact / website / privacy policy | `https://getinsiteview.com/faq` (support), `https://getinsiteview.com`, `https://getinsiteview.com/privacy` |
| Content rating | IARC questionnaire → Everyone (no objectionable content, no user-to-user messaging) |
| Price / ads | Free, no ads, no in-app purchases |
| App access (review) | Same demo account and notes as App Review, minus "App Clip": "open https://getinsiteview.com/b/MRRMYZ on the phone (opens the app when installed)… tap Continue with email…"; "Accounts can be deleted in Profile → Delete account" |
| Account deletion URL (Play requirement) | Not in iOS. Needs a web page or the in-app path documented (Profile → Delete account); Play also asks for a web link for deleting without the app |
| Graphics | Icon from `insiteview-api/brand/render.py --android` (done, AND-M6-02); feature graphic 1024×500 and phone screenshots: Missing |

### 17. Camera pre-prompt after iOS commit 7ac7e5f ("Drop 'Not now' from the camera pre-prompt screen")

iOS now (`Kit/IVFeatures/Onboarding/ARPreflightViews.swift:124-203`): the screen before the system camera prompt has a single action `"Continue"` (`.primaryAction`), which shows the system prompt; there is no way to close it (App Review guideline 5.1.1(iv)). The denied/restricted state still has `"Open Settings"` (when settings can open) and `"Not now"`. Catalog comment for `Not now` changed to "Camera denied state: close without the camera." Build number bumped to 2.

Android (`features/…/onboarding/ARPreflightViews.kt:213-259`): **Differs** — the pre-prompt still shows `SecondaryActionButton("Not now")` under `"Continue"` (`:255-256`). Fix: remove line 256 so `"Continue"` is the only action. The denied branch (`:249-253`) is correct. Also update the `not_now` comment in `features/src/main/res/values/strings.xml:412` ("Camera explainer and denied state" → "Camera denied state"), or re-run `scripts/xcstrings-to-android.py`. System Back still leaves the screen on Android; Google Play has no 5.1.1(iv) rule, so keeping Back working is acceptable — note it as the one remaining exit. The Scan tab has no pre-prompt on either platform, so nothing else in this section has "Not now". (`ARCoreGate.kt:156` uses `"Not now"` for the ARCore install prompt, which is not a camera pre-prompt.)

### 18. Analytics in the shell

None of the tabs, the sign-in sheet or Profile fire events. Analytics batches are flushed every 30 s and when the app backgrounds (¶2). Events are fired inside buildings (other sections). Parity: **Done**.

### 19. Parity gaps, most important first

| # | Gap | Where | Status |
|---|---|---|---|
| 1 | Camera pre-prompt still has `"Not now"` (iOS removed it in 7ac7e5f for App Review 5.1.1(iv)) | `features/…/onboarding/ARPreflightViews.kt:256` | Differs |
| 2 | Web serves no `/.well-known/assetlinks.json`, so App Links don't verify and plates open the browser instead of the app | `insiteview-web/src/app/.well-known/` (only the AASA route) | Missing (web) |
| 3 | Web "Get the app" Play URL has no `referrer=code=…&plate=…`, so the Install Referrer handoff never opens a building | `insiteview-web/src/lib/guest/apps.ts:42-46` | Missing (web) |
| 4 | Apple sign-in only appears if the API lists `apple` in `/v1/auth/oauth/providers`; iOS always shows it | `SignInView.kt:103-106`, `api/…/account/OAuth.kt:30-32` | Differs (by decision; verify API config) |
| 5 | Root tab titles are small Barlow top bars; iOS uses large Anton titles that collapse | `features/…/ui/ListParts.kt:48-71` (used by all tabs) | Differs |
| 6 | Play Data safety form and Play listing document | `docs/` | Missing |
| 7 | Profile Language/Help/Terms/Privacy text in ink; iOS tints them accent | `ProfileTab.kt:263-277` | Differs |
| 8 | Email form submit is a red primary button; iOS a plain accent row | `SignInView.kt:367-373` | Differs |
| 9 | `emailTaken` keeps its message on Android; iOS clears it when flipping to Sign in | `SignInView.kt:258-264` | Differs (decide; iOS looks wrong) |
| 10 | `"Continue with email"` lacks the envelope icon | `SignInView.kt:159-163` | Differs |
| 11 | Scanner denied state has extra "Allow the camera" / "Open settings" buttons; no code highlight overlay | `ScannerView.kt:134-148` | Differs (keep the buttons) |
| 12 | Navigation bar shows an M3 selection pill (`accentSoft`) | `RootTabView.kt:143` | Differs (cosmetic) |
| 13 | Back at a tab's root exits the app; no Back → Buildings rule | `RootTabView.kt` | Android-only decision |
| 14 | OAuth callback scheme is exported and claimable by other apps (iOS never registers it) | `AndroidManifest.xml`, `OAuthCallbackBroker.kt` | Differs (security; consider PKCE/state in the API) |
| 15 | Version row label not muted; re-tap doesn't scroll to top; Update cover has no fade | `ListParts.kt:146-152`, `RootTabView.kt:126-137`, `:72` | Differs (minor) |
| Shared | No "session ended" message; offline-signed-in Profile shows "Sign in"; units row vs device units can disagree; signed-out Search still sends requests | both platforms | Same on both: fix together or leave |

## 6. Guest flow and building screens

Scope: everything after a plate QR (`/b/{code}[/{plate}]`) or a shared link (`/a/{token}`) is opened, up to (not including) the 3D renderer internals and AR. The iOS Swift is the spec. Paths below are relative to `insiteview-ios/Packages/InsiteViewKit/Sources/` (iOS) and `insiteview-android/features/src/main/kotlin/com/getinsiteview/features/` (Android) unless written in full. `file:line` cites the line where the rule lives.

Conventions used in this section:

- **Tokens** are the shared design tokens: colours `Palette.background / surface / sunk / ink / muted / line / accent / accentSoft / warn / warnSoft / onAccent / onInk` (iOS `IVDesign/Palette.swift`, Android `:design` `Palette`); type: `display(size)` = Anton, upper-cased; `body(size, weight)` = Barlow; `mono(size)` = JetBrains Mono; `eyebrow` = mono 11 medium, upper-cased, tracking 1.2, muted; `headline` = body 17 semibold. iOS text styles map as: `.subheadline` = body 15, `.footnote` = body 13, `.callout` = body 16, `.caption` = body 12.
- **Buttons**: `primaryAction` = full width, min height 46, square, accent fill (ink while pressed), body 13 semibold, upper-cased, tracking 0.8; `secondaryAction` = same size, surface fill, 1.5 pt ink outline (accent while pressed). Android `PrimaryActionButton` / `SecondaryActionButton` (`design/.../Components.kt:49,82`).
- **Parity**: `Done` (matches), `Differs` (how, with Kotlin `file:line`), `Missing`. `Decided` = a difference already listed in Android `docs/PLAN.md` §6 / ¶3; it is applied, not argued.
- Icons: SF Symbol → Compose `Icons.*` name (Material Symbols name in brackets when the Compose name differs).

---

### 1. Flow map and navigation

```
Plate QR / App Link / Buildings row / Search result / Install Referrer handoff
        │
        ▼
GuestFlow (one BuildingSession for the whole flow)
   ├─ Loading  "Opening…"
   ├─ Problem  (ProblemView: paused, not live, not found, link expired/revoked, offline, update…)
   └─ Landing ──┬─ See in AR ───────────────► AR (full-screen cover / AR destination)
                ├─ Explore in 3D ───────────► Viewer3D
                ├─ What do you need? chip ──► Rooms(need) ─► Viewer3D(room) ─► AR
                ├─ Building details ────────► BuildingHome ─┬─ View in AR / Locate in AR ► AR
                │                                           ├─ 3D model / a system ► Viewer3D
                │                                           ├─ a room / All N rooms ► Viewer3D / Rooms(nil)
                │                                           ├─ search result ► Object card / Viewer3D(room)
                │                                           └─ Documents ► Documents
                ├─ Documents ───────────────► Documents
                ├─ Enter PIN (PIN banner) ──► PIN sheet
                └─ initial element (search) ► Object card sheet
```

| Route (iOS `BuildingRoute`, `IVFeatures/Guest/GuestFlow.swift:8-17`) | Android (`app/BuildingDestinations.kt:66-125`) | Parity |
|---|---|---|
| `.home` → `BuildingHomeView` | `BuildingRoute.Home` | Done |
| `.viewer(ViewerFocus)` (need, systems, storeyID, roomID, elementID) | `BuildingRoute.Viewer` (fields flattened; `systems` comma-joined, `need` = raw key) | Done |
| `.rooms(ProfessionalNeed?)` | `BuildingRoute.Rooms(need: String?)` | Done |
| `.documents` | `BuildingRoute.Documents` | Done |
| AR = `fullScreenCover` on the presenting screen | `BuildingRoute.AR` destination | Decided (PLAN §6 "AR as a full-screen cover → a navigation destination") |

Entry points into a building (all end in `GuestFlowView(link:elementID:)`):

| Entry | iOS | Android | Parity |
|---|---|---|---|
| Universal / App Link `https://getinsiteview.com/b/…`, `/a/…` | `AppRootModel.open(_ url:)` (`IVFeatures/App/AppRootModel.swift:72-79`); foreign URL → alert "This isn't an Insite View code" | `AppRootModel.open(url)` (`app/AppRootModel.kt`); App Links intent filter `autoVerify`, `/b/`, `/a/` (`app/src/main/AndroidManifest.xml:46-54`) | Done in-app; see ¶11 for the web side |
| Scanned QR (Scan tab) | `open(scannedText:)` | `openScannedText` | Done |
| Buildings tab row, Search tab building row | `NavigationLink(value: DeepLink.building(code, plate: nil))` | `GuestFlowRoute` | Done |
| Search tab element hit (card opens first) | `OpenBuilding(link:, elementID:)` (`App/SearchTab.swift:117`) | `GuestFlowRoute(elementID=…)` | Done |
| App Clip → app handoff (App Group, once, ≤ 7 days) | `BuildingHandoff.take()` at launch (`AppRootModel.swift:31-33`, `IVCore/GuestOnboarding.swift:78-116`, `maxAge` 7 × 24 h at :82) | Play Install Referrer → `BuildingHandoff.saveReferrer` → `openHandoff()` (`core/.../GuestOnboarding.kt:93-160`, `MAX_AGE` 7 days; `app/.../InsiteViewApplication.kt:41-45`) | Decided (PLAN §6); **web side Missing**, see ¶11 |

Opening rules (`AppRootModel.swift:91-98`): a new building replaces the whole stack (`path = [link]`); the same link while it is open is ignored (a universal link may arrive twice). `openRequests` is bumped so the app brings the Buildings tab forward. Android: `openBuilding` pops to the graph start then pushes (`app/BuildingDestinations.kt:205-210`); "same link" uses `isBuildingOnStack` (`:217-235`). Parity: Done.

Back handling (Android): system Back / the top-bar arrow (`IvTopBar`, `ui/ListParts.kt:49-71`, content description "Back") pops one destination; Back on a `ModalBottomSheet` (PIN, object card) closes it; on the viewer's non-modal card panel a `BackHandler` closes the panel first (`objectcard/ObjectCard.kt:460`). iOS: the navigation bar back button / edge swipe; sheets swipe down.

Session lifetime: one `BuildingSession` per flow. iOS keeps it in the `GuestFlowModel` `@State` of the pushed `GuestFlowView`. Android scopes `GuestFlowModel` (a ViewModel) to the `GuestFlowRoute` back-stack entry; every later `BuildingRoute` looks up the nearest `GuestFlowRoute` entry (`app/BuildingDestinations.kt:190-199`), and `onCleared` cancels loading and destroys the Filament scene (`guest/GuestFlow.kt:140-145`). Parity: Done.

---

### 2. GuestFlow (loading → landing or problem)

iOS `IVFeatures/Guest/GuestFlow.swift:35-163`; Android `guest/GuestFlow.kt:57-189`, `guest/GuestFlowRules.kt`.

**Purpose:** show the landing summary in under 3 s, then start the visit and the model download in the background.

**`load()`** (`GuestFlow.swift:61-83`):

1. `state = .loading`; read `credentials.isSignedIn`.
2. Plate code (`.building(code, plate)`): `GET /v1/public/buildings/{code}` → `summary`. If **signed out** and the summary's status is `NotLive` or `Paused` → `state = .problem(.notLive / .paused)` and stop (`GuestProblem(status:)`, `IVAPI/GuestProblem.swift:77-83`). Signed in, the status is ignored: a member or grant holder keeps access whatever the status, so only the visit decides. A thrown error → `.problem(GuestProblem(error))`.
3. Access link (`.accessLink(token)`): no summary endpoint; the visit returns the building.
4. `state = .landing`; `session.start()`.

**What replaces the landing** (`GuestFlow.swift:91-96`, Android `GuestFlowRules.problem`): in order, (a) the summary's problem, (b) `session.accessProblem` (access ended mid-visit), (c) `session.phase == .failed(p)` **and** the architecture chunk isn't in the scene yet. Once architecture shows, a later failure doesn't replace the landing.

**Retry** (`:98-104`): from a summary problem, run `load()` again; otherwise `session.start()` (which restarts only after a failure).

**Layout**

| State | iOS | Android | Parity |
|---|---|---|---|
| Loading | `ProgressView { Text("Opening…") }` centred, no title | `CircularProgressIndicator` (accent) + "Opening…" body muted, 12 dp gap; `IvTopBar("")` with Back (`GuestFlow.kt:174-186`) | Done |
| Problem | `ProblemView(problem) { retry }` (no navigation title; the app's back button stays) | `ProblemView` centred in a `Scaffold` whose top bar shows `building.displayCode` (`GuestFlow.kt:166-173`) | Differs (cosmetic): Android shows the display code as title on the problem screen; iOS shows none |
| Landing | `GuestLandingView` | `GuestLandingView` | see ¶3 |

**Foreground / background → analytics** (`GuestFlow.swift:154-162`): `scenePhase == .background` → `session.endSession()` (`session_ended`, then flush); `.active` → `session.resumeSession()`. Not on disappear (pushing BuildingHome hides the landing too). Android: a `ProcessLifecycleOwner` observer registered by the ViewModel, `onStop` → `endSession`, `onStart` → `resumeSession` (`GuestFlow.kt:87-95`). Parity: Done.

---

### 3. Guest landing

iOS `IVFeatures/Guest/GuestLandingView.swift:14-297`; Android `guest/GuestLandingView.kt:92-404`.

**Purpose:** A-03 screen 5: who built it, a preview, "See in AR" as the biggest action, "Explore in 3D", trade shortcuts, details, documents, saving.

**Entry:** every building opening (¶1). **Exits:** AR, Viewer3D, Rooms(need), BuildingHome, Documents, PIN sheet, object card sheet, back (closes the building; the flow's session is freed).

**Navigation title:** `building.displayCode` (e.g. `IV-8K29-X7`), inline (`:58-59`). Android `IvTopBar(displayCode)` (`:124`). Done.

**Layout, top to bottom** (`ScrollView` → `VStack(alignment: .leading, spacing: 20)`, bottom padding 32; background `Palette.background`):

| # | Element | Content and rules | iOS | Android | Parity |
|---|---|---|---|---|---|
| 1 | Preview | 240 pt high, full width, 16 pt side padding, square clip. Architecture loaded → live `ModelViewer` (non-interactive, hidden from accessibility, `fitRequest: 0`). Else the thumbnail (`thumbnailUrl`, scaled to fill on a `surface` placeholder). Else `house` symbol 48 pt light, `line` colour, on `surface`. While `phase` is `connecting` or `loading`: a `LoadingBar` (accent progress, caption "Loading the model…" mono 12 muted) inset 12 pt on `.ultraThinMaterial`, bottom-leading. | `:102-143` | `:174-211`; `Icons.Outlined.Home`; bar on `surface` at 85 % alpha | Done (material → translucent surface) |
| 2 | Name | `building.name`, `display(34)` (upper-cased), ink. Before any building data (an access link before the visit returns): `session.name`, redacted placeholder while empty. | `:150-152`, `:176-178` | `DisplayText(...)` (`:222`, `:244`) | Differs (minor): no placeholder shimmer while the name is empty (`GuestLandingView.kt:244`) |
| 3 | Address | `[addressLine, city]` non-nil joined with ", "; hidden when empty. Body, muted. | `:153-156` | `:223-226` | Done |
| 4 | Builder line | Body 15, muted. Builder name and `verifiedAt` → "Built by %@ · verified %@"; name only → "Built by %@"; date only → "Verified %@"; neither → nothing. Date format: abbreviated month + year ("Oct 2026"). | `:184-201` | `:249-260`, skeleton `MMMyyyy` | Done |
| 5 | Status line (members only) | `if session.isMember, let line = session.statusLine` → `StatusLineView` (¶9). | `:158-160` | `:228-231` | Done |
| 6 | Read-only archive | When `building.status == Expired` or `visit.readOnly`: `archivebox` + "Read-only archive", body 13, `warn`. | `:161-165` | `Icons.Outlined.Archive`, 16 dp (`:232-234`) | Done |
| 7 | Shared-link expiry | When `visit.via == Link` and `visit.expiresAt`: `link` + "Shared access until %@", body 13, muted. Date: day, abbreviated month, hour, minute ("12 Oct, 14:30" / "Oct 12, 2:30 PM"). | `:166-174` | `Icons.Outlined.Link`, skeleton `dMMMjmm` (`:235-242`) | Done |
| 8 | PIN banner | Only while `session.isPinLocked` (`visit.pinLocked == true`). Row, 12 pt spacing, 12 pt padding, `warnSoft` fill, square: `lock.fill` (warn, a11y hidden) + column (8 pt): "Pipes and wiring are hidden until the building's PIN is entered." (body 15, ink) and the text button **"Enter PIN"** (body 15 semibold, accent) → PIN sheet (¶4). | `:204-226` | `Icons.Filled.Lock`; `TextButton` with 0 padding (`:271-289`) | Done |
| 9 | Primary action | **"See in AR"** (`arkit` icon), `primaryAction` → AR. | `:232-237` | `PrimaryActionButton` (`:296`) | Differs: no leading icon (Android buttons take text only, `design/.../Components.kt:49-77`); iOS shows the symbol before the label. Use `Icons.Outlined.ViewInAr` |
| 10 | Plate hint | Only when the URL had a plate number: "Point your camera at plate %lld to line the model up with your room." Body 13, muted, leading. AR then opens on "Point at the plate you scanned" (AR section). | `:238-244` | `:297-306` | Done |
| 11 | Secondary action | **"Explore in 3D"** (`cube`), `secondaryAction` → `Viewer3D(ViewerFocus())`. | `:246-249` | `SecondaryActionButton` (`:307`) | Differs: no `cube` icon (`Icons.Outlined.ViewInAr` is taken by AR; use Material Symbols `deployed_code` or `Icons.Outlined.ThreeDRotation`) |
| 12 | "What do you need?" | Eyebrow "What do you need?" (16 pt side padding), then a horizontal `ChipRow` (8 pt gap, 16 pt side, 4 pt vertical padding) of need chips (¶3.1). Hidden when no need applies. | `:256-273`, `NeedLabel` `:355-367` | `:313-326` | Differs: see ¶3.1 |
| 13 | Links | VStack (14 pt spacing, 16 pt side padding): `Label("Building details", list.bullet.rectangle)` → BuildingHome; `Label("Documents", doc.text)` → Documents. iOS renders these `NavigationLink`s outside a List: accent-tinted label (icon + text), leading-aligned, no row background, no chevron. | `:38-48` | `ListRow` on `surface` with accent `RowIcon`, ink text, chevron, divider (`:142-149`, `:341-347`); icons `AutoMirrored.Outlined.ListAlt`, `Outlined.Description` | Differs (visual): rows instead of tinted text links |
| 14 | Keep / save | App: `SaveBuildingSection` when `session.canSave` (¶3.2). Clip: "Keep this building on your phone" (¶10). | `:49-53` | `:150-152` | Done (Clip part N/A) |

**Behaviours**

- **Scene claim** (`:60-66`): `onAppear` claims the shared scene for the preview (`claimScene(previewID, .viewer)`); `onDisappear` releases it unless AR is up (the scene returns to the preview when AR lets go). Android `DisposableEffect` + `arOpening` flag set just before `navigator.showAR()` (`:99-113`). Done.
- **Initial element** (search hit, IOS-M3-04, `:68-73`): once per flow, opens the object card for `initialElementID` with "Locate in AR" and "Show in 3D". Android `:115-121` (flag kept on the model). Done.
- **Object card** (`:74-77`): context `.list`; "Locate in AR" → `session.locate(id)` then AR; "Show in 3D" → push Viewer3D with `elementID`. Android `:156-163`. Done.
- **AR cover** (`:81-83`); Clip-only `arDismissed` offers the full app once after the first AR session (`:93-98`). Android: AR destination; no Clip.

#### 3.1 "What do you need?" chips (ProfessionalNeed)

`IVCore/ProfessionalNeed.swift` (Android `core/.../ProfessionalNeed.kt`, identical).

| Chip (exact title) | Systems shown | Subsystems preselected (others of that system hidden) |
|---|---|---|
| "Electrical" | `electrical`, `data` | electrical: `power`, `lighting`, `panels` |
| "Plumbing" | `plumbing`, `gas` | plumbing: `cold_water`, `hot_water`, `drainage`, `stormwater`, `fixtures` |
| "HVAC" | `hvac` | hvac: `supply`, `return`, `exhaust`, `refrigerant`, `equipment` |
| "Structure" | `structure` | all |
| "Architecture" | `architecture` | all |
| "Other" | `gas`, `fire`, `data`, `other` | all |

- **Which chips show** (`GuestLandingView.swift:257`): before the manifest arrives, all six; afterwards `ProfessionalNeed.available(for: manifest.systems)` = needs with at least one of their systems in the scoped manifest, in the table's order, so a chip never opens an empty model. None available → the whole block is hidden.
- **Tap** → push `Rooms(need)` (¶10). The preset is applied by the Rooms screen (`session.apply(need)`, `Building/BuildingSession.swift:419-428`): for both the 3D viewer and AR filters, `ModelFilters.preselected(for:in:includeArchitecture:)` (`IVModelKit/ModelFilters.swift:107-124`) → systems = the need's systems that are in scope, plus `architecture` in the **viewer** only (context); `hiddenSubsystems` = every subsystem of a listed system not in the need's set. A need with no system in scope leaves that mode's filters unchanged.
- **Chip look** (`NeedLabel`, `:355-367`): body 15 medium, horizontal padding 14, min height 36, ink text, `surface` fill, 1 pt `line` border, square, no colour dot, never "selected". It is a `NavigationLink` (a button for VoiceOver).
- Android (`GuestLandingView.kt:320-324`) uses the toggle `Chip(isSelected = false)`: same colours and height, but horizontal padding 12 (`design/.../Components.kt:142`) and **`selectable(role = Role.Checkbox)`** (`Components.kt:141`), so TalkBack announces "Electrical, not checked, checkbox" for a navigation action. **Differs**: give need chips a plain `clickable(role = Role.Button)` variant and 14 dp padding.
- The Rooms screen's title is the chip title (`needTitle`, `GuestLandingView.kt:328-339`). Done.

#### 3.2 "Save this building" (IOS-M3-08)

iOS `GuestLandingView.swift:301-352`, `BuildingSession.swift:765-803`; Android `GuestLandingView.kt:353-394`, `building/BuildingSession.kt` (save section), `BuildingSessionRules.saveState`.

- **Shown when** `session.canSave` (`BuildingSession.swift:768-771`): a visit exists, the building has a code, `visit.via` is neither `Member` nor `Grant`, and `visit.building.status.isViewable` (`Live` or `Expired`). Never in the Clip.
- **State** (`refreshSaveState`, `:779-790`, run on each appearance): signed out or no code → `unknown`; `GET /v1/me/buildings` lookup by code: `Saved` → `saved`; `Member`/`Grant`/unknown value → `mine`; absent → `notSaved`.

| `saveState` | Shows | Container |
|---|---|---|
| `mine` | nothing | no padding, clear |
| `saved` | `checkmark.circle.fill` + "Saved to your buildings", body 15 medium, accent | 16 pt padding, `surface` |
| `unknown`, `notSaved`, `failed`, `saving` | "Keep this building" (headline) · "Save it to a free account to find it again in Buildings, on any iPhone." (body 15, muted) · `secondaryAction` button `Label("Save this building", bookmark)` (while `saving`: a `ProgressView` inside the disabled button) · when `failed`: "Couldn't save the building. Try again." (body 13, warn) | 16 pt padding, `surface` |

- **Tap** (`save()`, `:344-351`): signed out → `signInPrompt.requestSignIn(.saveBuilding(name:))` (the sign-in sheet titled "Save %@", covered by the account section); cancelled → stop; signed in → `refreshSaveState()`; if now `saved` or `mine` → stop; else `session.save()` → `POST /v1/me/buildings/{code}` (`me_save_building`) → `saved` + `building_saved` event; error → `failed`.
- Android parity:
  - Strings: "…on any phone." (`save_it_to_a_free_account_on_any_phone`) — Decided (PLAN §6 iPhone wording → phone).
  - `saving` (`GuestLandingView.kt:371-374`): the button is replaced by a 46 dp box with a 24 dp spinner. **Differs (minor)**: iOS keeps the outlined button, disabled, with the spinner inside.
  - No `bookmark` icon on the button (`:376`). **Differs** (same cause as ¶3 row 9).
  - Saved icon `Icons.Filled.CheckCircle` 18 dp. Done.

---

### 4. PIN entry sheet

iOS `IVFeatures/Guest/PinEntryView.swift:12-219`, `IVAPI/PinPad.swift`; Android `guest/PinEntryView.kt:77-308`, `api/.../PinPad.kt`, `guest/PinLockout.kt`.

**Entry:** "Enter PIN" on the landing's PIN banner (only shown while `visit.pinLocked`). **Exits:** "Cancel" (top-leading, `cancellationAction`), swipe down, success (auto-dismiss), access ended (auto-dismiss over the problem screen).

**Presentation:** iOS `.sheet` with `.presentationDetents([.large])`, its own `NavigationStack` (no title). Android `ModalBottomSheet(skipPartiallyExpanded = true)`, `containerColor = background`, a `TextButton("Cancel")` row at the top-leading (`:151-155`). Android's sheet wraps its content instead of filling the screen: Differs (minor). Back closes it: Done.

**Layout** (`VStack(spacing: 28)`, 24 pt vertical padding, `background`):

1. Header (8 pt spacing, 24 pt side padding, centred): `lock.fill` `.largeTitle`, accent, a11y hidden (Android `Icons.Filled.Lock` 36 dp) · **"Enter the building's PIN"** `display(26)` · "The builder or owner can give it to you. Pipes and wiring show once it's entered." body 15, muted, centred.
2. Dots: 4 circles 16 × 16, 18 pt apart, 1.5 pt ink stroke, filled ink up to the digit count. One accessibility element: "%lld of %lld digits entered" (`:119-130`; Android `:180-196`). Shakes on a wrong PIN.
3. Status line (body 16 / `.callout`, centred, min height 44, 24 pt side padding), refreshed every second (`TimelineView(.periodic(by: 1))`; Android a 1 s `delay` loop, `:199-208`):

| `PinPad.Status` | Text (colour) |
|---|---|
| `entering` | a single space (keeps the height) |
| `checking` | spinner |
| `wrong(attemptsLeft: n)` | "Wrong PIN. ^[%lld try](inflect: true) left." → "Wrong PIN. 1 try left." / "Wrong PIN. 3 tries left." (warn) |
| `wrong(nil)` | "Wrong PIN. Try again." (warn) |
| `locked(until)` and not yet past | "Too many wrong PINs. Try again in %@." with the remaining time in hours/minutes/seconds, wide units, at most 2 units ("2 minutes, 30 seconds", "1 hour") (warn) |
| `locked` and past | a space (next tick → `entering`) |
| `failed(.offline)` | "You're offline. Connect and try again." (warn) |
| `failed(other)` | "Something went wrong. Try again." (warn) |

4. Keypad: `Grid` (20 pt horizontal, 14 pt vertical spacing); rows 1-2-3 / 4-5-6 / 7-8-9 / [empty 72 × 72] 0 [delete]. Digit keys 72 × 72, `surface` fill, 1 pt `line` border, square, digit in `mono(28)` ink. Delete: `delete.left` `.title2`, ink, a11y label "Delete" (Android `AutoMirrored.Outlined.Backspace`). Whole keypad disabled while `checking` (Android: alpha 0.5 + disabled, `:249-275`).

**Rules** (`PinPad`, master PLAN §10 "PIN"):

- Length **4** (`PinPad.length`). Typing ignores digits outside 0…9, a full pad, `checking`, and an active lockout. A digit after `wrong`/`failed` returns to `entering`. The 4th digit sets `checking` and returns the PIN → `session.unlock(pin)` → `BuildingConnection.unlock` → `POST /v1/visit/unlock {pin}` (a new visit token that sees every system; the connection keeps the PIN in memory for later re-visits; `IVAPI/BuildingConnection.swift:92-107`).
- Delete: ignored while `checking` or empty.
- Failure (`PinPad.failed`, `PinPad.swift:57-69`): digits cleared; `pin.invalid` → `wrong(attemptsLeft)`; `pin.locked` **or** `rate_limited` → `locked(until: now + retryAfter ?? 3600 s)`; anything else → `failed(GuestProblem)`.
- Server limits (master PLAN §10): 5 attempts per device and IP per 15 min, 20 per building per hour, then 1 h lock. The client shows whatever `attemptsLeft` / `Retry-After` say.
- Success: `isUnlocked` → success haptic → dismiss; `BuildingSession.unlock` stores the new visit and reloads (cancel the current load, run again: chunks already in the scene stay, newly allowed systems download; `BuildingSession.swift:313-318, 345-353`). The banner disappears because `visit.pinLocked` is now false.
- Access ending while the sheet is open (`session.accessProblem != nil`) dismisses it (`:36-39`, `:114-116`).
- A PIN that later stops working (changed or locked) on a re-visit: the connection retries the visit without the PIN and carries on locked with architecture only (`BuildingConnection.swift:123-128`). Android `api/.../BuildingConnection.kt:160-168`. Done.

**Haptics and motion**

| Event | iOS | Android | Parity |
|---|---|---|---|
| Digit count changes (type, delete, clear after failure) | `.sensoryFeedback(.selection, trigger: digits)` (`:184`) | `HapticFeedbackType.KeyboardTap` on **every key press**, including ignored presses (`PinEntryView.kt:251-254`) | Differs (minor): fire on `pad.digits` changes instead |
| Wrong PIN | `.error` (`:109`) + shake | `Reject` + shake (`:130-140`) | Done |
| Unlocked | `.success` (`:110`) | `Confirm` (`:141-146`) | Done |
| Shake | x offset keyframes +10 (0.06 s) → −10 (0.08 s) → +5 (0.08 s) → 0 (0.06 s); amount 0 under Reduce Motion (`:83`, `:203-219`) | Same keyframes at 60/140/220/280 ms; **no reduced-motion check** | Differs: use `isReducedMotion(context)` (exists at `onboarding/ARPreflightViews.kt:80`) |

Lockout formatting: Android `formatLockout` = ICU `MeasureFormat` WIDE with `lockoutUnits` (largest non-zero unit and the next if non-zero; `guest/PinLockout.kt`). Done.

---

### 5. Problem screen (guest states)

iOS `IVFeatures/Guest/ProblemView.swift:30-136`, `IVAPI/GuestProblem.swift`; Android `guest/ProblemView.kt:79-176`, `api/.../GuestProblem.kt`.

**Layout:** iOS `ContentUnavailableView`: symbol (large, secondary), title (bold, ~22), message (secondary), actions. Android: centred column, 24 dp side / 32 dp vertical padding, 12 dp gap: icon 48 dp muted, title body 22 bold ink centred, message body 15 muted centred, action. Done.

**Actions:** `updateRequired` → **"Update"** (`borderedProminent`, accent): app → the App Store app URL (`AppStoreLink.appURL`), else its web URL if that can't open (no button until `APP_STORE_ID` is set); Clip → `SKOverlay`. Otherwise, if a retry closure was passed and the problem is retryable → **"Try again"** `primaryAction` with 32 pt side padding. Else no button. Android: "Update" opens `market://` then the Play web page (`openPlayStore`, `:125-127`; no button without `PLAY_STORE_PACKAGE`) — Decided (PLAN §6); "Try again" `PrimaryActionButton` (`:114-118`). Done.

| `GuestProblem` | From | SF Symbol → Android icon | Title | Message | Retry | Ends access |
|---|---|---|---|---|---|---|
| `notFound` | `not_found`, HTTP 404 | `questionmark.square.dashed` → `AutoMirrored.Outlined.HelpOutline` | "We couldn't find this building" | "Check the code on the plate and try again." | no | yes |
| `notLive` | `building.not_live`, `version.not_ready`, summary `NotLive` | `clock` → `Outlined.AccessTime` | "This building isn't live yet" | "The builder hasn't published it yet. Try again later." | no | yes |
| `paused` | `building.paused`, summary `Paused` | `pause.circle` → `Outlined.PauseCircle` | "This digital twin is paused" | "The builder needs to reactivate it before it can be opened." | no | yes |
| `linkExpired` | `link.expired`, HTTP 410 | `link.badge.plus` → `Outlined.Link` (Symbols `add_link` is closer) | "This link has expired" | "Ask the person who shared it for a new link." | no | yes |
| `linkRevoked` | `link.revoked` | same | "This link was turned off" | "Ask the person who shared it for a new link." | no | yes |
| `pinRequired` / `pinInvalid` | `pin.required`, `pin.invalid` | `lock` → `Outlined.Lock` | "This building needs a PIN" | "Ask the builder or owner for the building's PIN, then try again." | no | no |
| `pinLocked` | `pin.locked` | `lock` | "This building needs a PIN" | "Too many wrong PINs were entered. Try again later." | no | no |
| `rateLimited` | `rate_limited`, HTTP 429 | `hourglass` → `Outlined.HourglassEmpty` | "Too many tries" | "Wait a moment, then try again." | yes | no |
| `offline` | `URLError` not connected / connection lost / data not allowed / roaming off / can't find or connect to host / timed out / DNS (`GuestProblem.swift:104-107`) | `wifi.slash` → `Outlined.CloudOff` (Symbols `wifi_off` is closer) | "You're offline" | "Connect to the internet to open this building." | yes | no |
| `updateRequired` | `client.outdated`, HTTP 426, manifest `unsupportedSchema` | `arrow.down.app` → `Outlined.SystemUpdate` | "Update required" | "This version of Insite View can't open this building. Update the app to continue." | "Update" | yes |
| `unavailable` | anything else | `exclamationmark.triangle` → `Outlined.WarningAmber` | "Something went wrong" | "Try again in a moment." | yes | no |

Android offline mapping (`GuestProblem.kt:119-125`): `UnknownHostException`, `ConnectException`, `NoRouteToHostException`, `SocketException`, `InterruptedIOException` (timeouts) → offline; `SSLException` → not offline. Done.

PIN problems don't normally reach this screen: a PIN building opens locked (architecture only) and the keypad shows PIN errors inline.

---

### 6. Access gate (access ending mid-visit)

iOS `IVFeatures/Guest/AccessGate.swift:10-23`, `BuildingSession.noteAccess` (`Building/BuildingSession.swift:327-334`); Android `guest/AccessGate.kt`, `BuildingSession.noteAccess`.

- Every `/v1/visit/*` call re-checks access, so `link.revoked`, `link.expired`, `building.paused`, `building.not_live`, `not_found`, `client.outdated` can arrive on any call (manifest refresh mid-download, element detail, documents, search, unlock, document download).
- `noteAccess(error, elementLookup:)`: if the problem `endsAccess` and none is set yet → `accessProblem = problem` and the download task is cancelled. **A 404 on one element's detail is that element**, not the building (ignored).
- Who reacts:

| Screen | Reaction | iOS | Android | Parity |
|---|---|---|---|---|
| Landing | replaced by `ProblemView` (via `GuestFlowModel.problem`) | `GuestFlow.swift:93` | `GuestFlowRules.kt` | Done |
| BuildingHome, Rooms, Documents, Viewer3D | `.accessGate(session)`: full-size `ProblemView` on `background`, fade transition, over the content (navigation bar stays, so Back works) | `AccessGate.swift` | `AccessGate` composable; also swallows touches (`AccessGate.kt:244-253`) | Done |
| Object card | dismisses itself | `ObjectCard.swift:96-98, 280-282` | `ObjectCard.kt:180-182` | Done |
| PIN sheet | dismisses itself | `PinEntryView.swift:114-116` | `PinEntryView.kt:147-149` | Done |
| AR | closes (AR section) | `ARExperienceView.swift:510` | `ar/ARExperienceView.kt:687` | Done |

- The gate never offers "Try again" (no retry closure; and no access-ending problem is retryable). `accessProblem` is never cleared for the flow's lifetime: the user goes back and re-opens the building.

---

### 7. BuildingSession (the shared model behind every screen)

iOS `IVFeatures/Building/BuildingSession.swift`; Android `building/BuildingSession.kt` (+ `BuildingSessionRules.kt`). Ported property for property; Compose snapshot state replaces `@Observable`.

**Loading** (`start` `:226-232`, `run` `:238-311`):

1. `phase = .connecting`; the catalog loads in parallel (`try?`, failure → no catalog, names fall back to humanized keys, colours to the neutral grey `#9EA6A8`).
2. Start the visit if none (`BuildingConnection.start`; members by id use member endpoints), fetch the scoped manifest, sync the visit. Error → `noteAccess` + `phase = .failed(GuestProblem(error))`.
3. Record the code in recents (app only; Android always — no Clip). Members (`isMember`) → `loadMemberInfo()` in the background (¶9).
4. Viewer filters = every manifest system; AR filters = every system but architecture; apply See inside; `phase = .loading`.
5. Chunks: `ChunkPlan.requests(kinds: [.meta, .usdz])` (Android `defaultKinds` = meta + glb), architecture first, then `priority`, then the rest (3 at a time, sha256-checked, cached; renderer section). Fresh URLs before `urlsExpireAt`: the manifest refresh is also where a revoked link shows up (`noteAccess`).
6. While `holdsSceneWork` (AR aiming at floor corners) events wait, polling every **200 ms**.
7. After the last event: `failed(.offline)` if nothing loaded and some failure was offline, `failed(.unavailable)` if nothing loaded, else `ready`. A chunk that fails is listed in `failedSystems` ("Couldn't load"), never a crash.

**Derived values used by these screens**

| Property | Rule | iOS line |
|---|---|---|
| `name` | manifest building name, else visit's, else "" | `:96-98` |
| `isPinLocked` | `visit.pinLocked == true` | `:113` |
| `scannedPlate` | plate from `/b/{code}/{plate}` | `:116` |
| `isMember` | member access, or `visit.via == Member` (grant holders are **not** members) | `:128-131` |
| `isReadOnly` | `visit.readOnly` or building `Expired` or status line `expired` | `:134-136` |
| `systems` | manifest systems in catalog order | `:139-143` |
| `equipment` | catalog "equipment" kinds in scope, by storey (lowest first), system order, name | `:200-203`, `IVModelKit/ElementIndex.swift:171-195` |
| `rooms(systems:)` | every manifest space, counting only elements of the given systems ∩ scope, architecture and structure never counted; sorted by storey then name; a space without a storey takes its elements' most common storey | `:207-211`, `ElementIndex.swift:198-219` |
| `displayName(record)` | "Kind Tag" ("Outlet K-04"), else a readable IFC name (≤ 40 chars, no `:`/`_`, not ending in a 5+ digit number), else the kind name | `ElementIndex.swift:28-46` |

Parity: Done (Android `BuildingSession.kt` same rules; `loadDocuments` cancellation resets to `Idle` instead of iOS's `failed(.unavailable)`: harmless, better).

---

### 8. BuildingHome ("Building details")

iOS `IVFeatures/Building/BuildingHomeView.swift:14-285`; Android `building/BuildingHomeView.kt:67-345`.

**Purpose:** A-03 screen 1: AR first, then 3D, systems, equipment, rooms, documents, and search.
**Entry:** landing "Building details". **Exits:** AR, Viewer3D (whole, a system, a room, an element), Rooms(nil), Documents, object card sheet, back.
**Title:** `session.name` (iOS navigation title, display mode inherited from the stack; Android `IvTopBar(session.name)`). Gate: yes.

**Layout** (iOS `List`, inset grouped; Android `LazyColumn` of `ListRow`s on `surface`, `SectionHeader` = eyebrow on `background`):

| # | Section | Content | iOS | Android | Parity |
|---|---|---|---|---|---|
| 0 | Search field | Prompt "Search outlets, valves, panels…" | `.searchable` in the navigation bar (`:37`) | `OutlinedTextField` as the first list item: square, `surface`, `line` border (ink focused), `Search` leading icon, `Close` clear button labelled "Cancel" (`:106-133`) | Decided (PLAN §6 "`.searchable` → a search field at the top of the list") |
| 1 | Status line (members) | `StatusLineView` in its own section | `:54-58` | `ListRow { StatusLineView }` (`:136-139`) | Done |
| 2 | Actions | **"View in AR"** (`arkit`) `primaryAction` → AR; **"3D model"** (`cube`) `secondaryAction` → Viewer3D. Rows have clear backgrounds, no separators, insets 12/16/6/16 and 6/16/12/16 ("one control per row") | `:61-79` | Column with 12 dp gap and 16/12 dp padding (`:142-147`) | Differs: no icons (as ¶3 row 9) |
| 3 | Loading | While `connecting`/`loading`: `LoadingBar` caption "Loading the model… %lld%" (rounded percent) | `:81-87` | `:149-156` | Done |
| 4 | "Systems" | One row per system in scope: 12 pt colour dot (catalog colour) + catalog name; trailing "Couldn't load" (body 13, warn) when the chunk failed; → Viewer3D focused on that system (`ViewerFocus(systems: [key])`: only it + architecture) | `:89-108` | chevron rows (`:158-176`) | Done |
| 5 | "Equipment" | Hidden when empty. `ElementRow`: 10 pt dot (subsystem colour, else system), display name (ink), storey name (body 13 muted). Tap → object card. Trailing **swipe action** "Locate in AR" (`arkit`, accent tint) → `session.locate(id)` + AR | `:110-133` | Inline trailing `IconButton(ViewInAr, "Locate in AR", accent)` (`:178-199`) | Decided (PLAN §6 "Equipment rows: swipe → inline locate button") |
| 6 | "Rooms" | First **5** rooms: name (or "Unnamed room"), storey name below (body 13 muted) → Viewer3D with `storeyID` + `roomID`. When more than 5: "All %lld rooms" → Rooms(nil) | `:135-160` | `:201-232` | Done |
| 7 | Documents | `Label("Documents", doc.text)` → Documents | `:162-166` | `:234-240` | Done |

**Search** (IOS-M3-04; `SearchModel` `:182-209`, `BuildingSession.search` `:657-689`):

- Active when the query trimmed of spaces is non-empty; the list then shows only results.
- Debounce **300 ms** (each keystroke cancels the task). Query trimmed of whitespace and newlines.
- Server first (`/v1/visit/search` or the member search); on a connection failure, the offline meta search (`BuildingSearch.run`). Server hits whose system is outside the scope are dropped (architecture and system-less hits kept). Offline search: every word must match the display name, kind, tag, type name, key-property values, system/subsystem name or room name; architecture never searched; tag matches first, then names starting with the query; 50 max (`ElementIndex.swift` search).
- Errors other than offline are swallowed (rows stay); one that ends access sets the gate.
- `search_performed {results}` per completed search.

| Results state | Shows |
|---|---|
| no rows, searching or nothing searched yet | centred spinner |
| no rows after a search | "No matches in the systems you can see." (muted) |
| element row | `ElementRow` (dot colour from system+subsystem), place "Room · Level" → object card |
| room row | `door.left.hand.open` (Android `Outlined.MeetingRoom`) + title + place → Viewer3D on the room |
| offline footer | `wifi.slash` + "Offline: searching the copy saved on this phone." (Android `CloudOff` + footer text) |

Parity: Done (field placement Decided).

**Object card from BuildingHome:** context `.list` ("Locate in AR", "Show in 3D"). Done.

---

### 9. Status line (members)

iOS `IVFeatures/Building/StatusLineView.swift:9-51`, `IVCore/BuildingStatus.swift:30-79`, `BuildingSession.loadMemberInfo` (`:575-588`); Android `building/StatusLineView.kt`, `core/.../BuildingStatus.kt`.

- Loaded only for members: find the building in my buildings by code (`via == Member`), then `GET /v1/buildings/{id}` (status, `expiresAt`, my `role`), then, for `TrialLive`, the organization's trial end from `GET /v1/orgs`.
- Plain text (billing is on the web: no link or button). `Label` body 15; colour `warn` when `isAttention` (paused, expired, trial with ≤ **3** days left), else `muted`.

| Status | Line | SF Symbol → Android |
|---|---|---|
| `Draft` | "No model yet" | `tray` → `Outlined.Inbox` |
| `Ready` | "Not live yet" | `clock` → `Outlined.AccessTime` |
| `TrialLive`, days ≤ 0 | "Trial · ends today" | `hourglass` → `Outlined.HourglassEmpty` |
| `TrialLive`, n days | "Trial · ^[%lld day](inflect: true) left" → "Trial · 1 day left" / "Trial · 9 days left" (days rounded **up**: 8 d 2 h → 9) | same |
| `TrialLive`, end unknown | "Trial" | same |
| `Active`, year known | "Active until %@" (the activation's end year, e.g. 2036) | `checkmark.seal` → `Outlined.Verified` |
| `Active`, no date | "Active" | same |
| `Paused` | "Paused · Activate on the web" | `pause.circle` → `Outlined.PauseCircle` |
| `Expired` | "Expired · read-only" | `archivebox` → `Outlined.Archive` |
| unknown | the raw status | `info.circle` → `Outlined.Info` |

Shown on the landing header and at the top of BuildingHome (and in the app's Buildings rows, app section). Parity: Done in English; **Differs in pt-BR/es** (plural bug, ¶14).

---

### 10. Rooms (professional flow)

iOS `IVFeatures/Rooms/RoomsView.swift:10-144` (file lines); Android `rooms/RoomsView.kt`, `rooms/StoreyGroups.kt`.

**Entry:** a "What do you need?" chip (with a need) or BuildingHome "All N rooms" (no need). **Exits:** Viewer3D (whole building or a room, carrying the need), back.
**Title:** the need's title ("Electrical", …) or "Rooms", inline (`:68-69`). Gate: yes.

**On appear (once per screen):** `session.apply(need)` (¶3.1). If the manifest wasn't there yet, apply again when it arrives (`:70-82`). Android: `rememberSaveable` flags (`RoomsView.kt:57-76`). Done.

**Layout:**

1. With a need only: a section with `SystemChips(session, mode: .viewer)` (full-width chip row: tap toggles a system in the viewer filters → `system_toggled {system, on}`; viewer section owns the chip details) and the footer "Pick a room to see what's inside its walls and floor." (`:23-31`).
2. `Label("Whole building", building.2)` → Viewer3D with `ViewerFocus(need:)` (Android `Outlined.Domain`).
3. No rooms: while connecting/loading a centred spinner; else "This model has no rooms. Open the whole building instead." (muted).
4. Rooms grouped by consecutive storey (list is sorted by storey): section header = storey name; a storey id without a name → "Level"; no storey → no header (Android: an empty `SectionHeader` spacer). Each row → Viewer3D with `need`, `storeyID`, `roomID` (focus room: its level, only its elements, camera on it, `BuildingSession.focusRoom` `:441-454`).

**Room row** (`:104-144`): name (ink) or "Unnamed room" (muted); below, body 13 muted: "^[%lld item](inflect: true)" ("1 item" / "4 items") or "Nothing to show here" when 0; trailing, 4 pt apart, a 10 pt dot per system with elements in the room, in catalog order, each with the system name as its accessibility label. Whole row at 60 % opacity when the count is 0. The counted systems are the need's systems ∩ scope (all in scope without a need).

Parity: Done (Android dims the text column and dots separately, same effect).

---

### 11. Documents

iOS `IVFeatures/Documents/DocumentsView.swift`, `IVAPI/Buildings/Documents.swift`, `BuildingSession.loadDocuments/open` (`:730-763`); Android `documents/DocumentsView.kt`, `api/.../buildings/Documents.kt`.

**Entry:** landing "Documents", BuildingHome "Documents"; element documents also from the object card. **Exits:** the system viewer (QuickLook / another app), back. **Title:** "Documents". Gate: yes.

**Loading:** on first appear when `idle`; pull to refresh reloads (ignored while loading). Members (`memberBuilding.buildingID` known): `GET /v1/buildings/{id}/documents` (every document); guests: `GET /v1/visit/documents` (those shared with guests, within the visit's systems). Items without a confirmed upload (`url == nil`) are dropped from the list.

| State | iOS | Android | Parity |
|---|---|---|---|
| idle / loading | centred `ProgressView` | centred accent spinner, 32 dp padding | Done |
| failed(problem) | `ProblemView(problem)` with "Try again" when retryable | same (`:201-203`) | Done |
| loaded, empty | `ContentUnavailableView`: `doc.text`, **"No documents yet"**, "Manuals, warranties and certificates the builder shares appear here." | `EmptyDocuments` (`Outlined.Description` 48 dp, title body 22 bold, message body 15 muted) | Done |
| loaded | "Whole building" section (no `elementID`), then "Equipment and fixtures" section (with `elementID`) | `SectionHeader`s (`:209-231`) | Done |

**Document row** (`DocumentRow`, `:39-76`): kind icon (`.title3`, accent, 28 pt column, a11y hidden) · title (ink) · below, body 13 muted: kind name, plus " · " + element display name in the building list · trailing spinner while it downloads · disabled when `url == nil` · accessibility hint "Opens the document" (Android `onClickLabel`).

| Kind | Title | SF Symbol → Android |
|---|---|---|
| `Manual` | "Manual" | `book.closed` → `AutoMirrored.Outlined.MenuBook` |
| `Warranty` | "Warranty" | `checkmark.seal` → `Outlined.Verified` |
| `Certificate` | "Certificate" | `rosette` → `Outlined.WorkspacePremium` |
| `Drawing` | "Drawing" | `pencil.and.ruler` → `Outlined.Architecture` |
| `Photo` | "Photo" | `photo` → `Outlined.Photo` |
| `Invoice` | "Invoice" | `doc.plaintext` → `AutoMirrored.Outlined.ReceiptLong` |
| `Other` / unknown | "Document" | `doc.text` → `Outlined.Description` |

**Opening** (`DocumentOpener` `:15-36`, `DocumentDownloader` `Documents.swift:88-152`):

- One download at a time (`opening` = item id; taps on others ignored meanwhile).
- File: `tmp/Documents/{id}/{localFileName}`; reused if present (for the app's session; never cleared in practice). `localFileName` = title with `/\:?%*|"<>`, control chars and newlines → "-", trimmed of spaces/dots/dashes, "Document" if empty, ≤ 80 chars, plus the extension from the MIME (`pdf`, `jpg`, `png`, `heic`) else from the stored file name (≤ 5 alphanumerics).
- Presigned URLs last **15 minutes**: on HTTP 403 fetch the list again (or the element's documents), take this item's fresh URL, retry **once**; none → `DocumentError.unavailable`. Concurrent requests for one id share one download.
- Success → `document_opened` → QuickLook preview (`.quickLookPreview`).
- Failure: access-ending → the gate; cancellation → nothing; else alert **"Couldn't open the document"** / "Check your connection and try again." / "OK".
- Android: `cacheDir/documents/{id}/{name}`, `FileProvider` authority `${applicationId}.files` (`app/src/main/AndroidManifest.xml:64-72`, `res/xml/file_paths.xml` `cache-path documents/`), `ACTION_VIEW` with `FLAG_GRANT_READ_URI_PERMISSION` and the item's MIME (parameters stripped, `*/*` if empty) (`DocumentsView.kt:97-109`). Decided (PLAN §6 QuickLook → ACTION_VIEW). Gaps:
  - No app installed for the type → the same alert, whose message blames the connection (`DocumentsView.kt:86`). **Differs**: iOS can always preview; consider a chooser (`Intent.createChooser`) and, when the MIME is `application/octet-stream`, the type from the extension (`MimeTypeMap`).
  - The viewer app opens as a separate task; Back returns to Documents. Done.

---

### 12. Object card

iOS `IVFeatures/ObjectCard/ObjectCard.swift`; Android `objectcard/ObjectCard.kt`, `objectcard/TechnicalDetails.kt`.

**Purpose:** what an element is, where it is, its key properties and documents. Meta from the chunk shows at once (offline); `GET /v1/visit/elements/{id}` (or the member endpoint) adds documents and, for members, technical details. Cached per session.

**Entry and presentation** (`ObjectCardSheet`, `:377-435`):

| Context | Opened from | Actions | Presentation |
|---|---|---|---|
| `.list` | landing (initial element), BuildingHome equipment and search | "Locate in AR", "Show in 3D" | sheet, detents `.medium` and `.large` |
| `.viewer` | a tap in Viewer3D | "Locate in AR" | same detents, background interactive up through `.medium` (the model behind still orbits/picks) |
| `.ar` | a tap in AR | "Locate" (in place) | sheet |

- "Locate in AR" and "Show in 3D" set a pending action, close the card, and run **after** it has closed (`runPending`): locate → `session.locate(id)` (turns the element's system on in AR if in scope) then AR; Show in 3D → push Viewer3D with `elementID` (`focusElement`: its system and storey on, room focus cleared if elsewhere, camera on it). In AR, "Locate" calls `session.locate(id)` directly.
- On selection change (not `.list`): highlight the element in the scene; in AR, when the card closes the located element stays highlighted. Every opening → `object_opened {kind, system}` (only for elements in the index).
- Android: `ModalBottomSheet` (partial = medium, expanded = large) for `.list`/`.ar`; for `.viewer` a non-modal bottom panel at 50 % height, 92 % when expanded by tap or drag up on the handle, drag down closes, Back closes (`ObjectCard.kt:422-500`). Done.

**Layout** (iOS `List` in a `NavigationStack`, inline empty title):

1. Header: 12 pt colour dot (subsystem colour, else system; first-baseline aligned) · title `body(22, bold)` · system line "System · Subsystem" (catalog names; subsystem only when the catalog is loaded), body 15 muted. Combined into one accessibility element.
2. Actions section (only if any): `Label("Locate in AR", arkit)` or `Label("Locate", scope)` in AR; `Label("Show in 3D", cube)`. Accent-tinted.
3. Place: "Room" → room name (manifest space `longName ?? name`, else the detail's space), a spinner while the detail loads, else "Not in a room"; "Level" → storey name (only when known). `LabeledContent`: label ink, value secondary.
4. "Details" (only if any): key properties from meta (catalog labels, catalog order, unknown keys last by key), else from the detail. Values are the converter's strings (already with units); the app does not convert units here (`UnitFormatter` is only used in AR).
5. Documents: detail documents → "Documents" section of `DocumentRow`s (same opener and alert as ¶11); none yet but meta counts some → muted `doc.text` row: while loading "^[%lld document](inflect: true)" ("1 document" / "3 documents"), after loading "Connect to the internet to open the documents."
6. "Follow circuit" with caption "Coming soon" (`.caption`, muted), icon `point.topleft.down.to.point.bottomright.curvepath`, disabled.
7. Technical details (**members only**, and only if something is left): a collapsed `DisclosureGroup` `Label("Technical details", info.circle)` containing "IFC class" → value; "GlobalId" → value in footnote monospaced, selectable; then one nested collapsed group per property set (sorted Finder-style), each property (sorted, values without display text dropped; booleans "✓"/"✗", whole numbers without decimals, arrays comma-joined) selectable. Footer: "Only members of the building's company see these."

**Guest vs member:** guests never see IFC class, GlobalId or property sets (the API also sends them empty to non-members); names come from the catalog ("Outlet K-04"), never IFC types. Grant holders are guests here.

**Copy:** no copy button; GlobalId and property values are long-press selectable (iOS `.textSelection(.enabled)`, Android `SelectionContainer`). Done.

Android parity details:
- "Show in 3D" uses `Icons.Outlined.ViewInAr`, the same icon as "Locate in AR" (`ObjectCard.kt:215, 227`). **Differs**: use a cube (`deployed_code` / `ThreeDRotation`). "Locate" uses `Outlined.GpsFixed` (≈ `scope`). Done.
- "Follow circuit": `Outlined.Route`, row at alpha 0.5 and disabled. Done.
- Disclosure: chevron `ExpandMore/ExpandLess` rows. Done.
- Sorting: `StandardOrder` (collator SECONDARY + numeric runs) ≈ `localizedStandardCompare`. Done.

---

### 13. Diagnostics

iOS `IVFeatures/Diagnostics/DiagnosticsModel.swift`, `DiagnosticsView.swift`; Android `diagnostics/DiagnosticsModel.kt`, `DiagnosticsView.kt`.

- **Reached:** nowhere in either app. It is the M0 debug screen; no iOS view presents it, and no Android composable calls it. (Keep it unreferenced; don't add an entry point.)
- **Contents:** title "Diagnostics" · "Environment": `API_BASE_URL`, `WEB_BASE_URL` (mono labels, selectable values) or the configuration error in red · "API status": `GET /health/ready` — "Checking…" spinner; ready → green `checkmark.circle.fill` "Ready: %@"; not ready → orange `exclamationmark.triangle.fill` "Not ready: %@"; each check `name → status`; failure → red `xmark.octagon.fill` "Failed: %@"; button "Check again" (disabled while checking) · "Invocation" (only after `open(url)`): the URL (mono footnote) and "Building %@" / "Building %@, plate %lld" / "Access link" / "This isn't an Insite View code". Runs the check on appear.
- Android: same sections; colours hard-coded `#2E9E4F` / `#E08A00` / red for the system green/orange/red. Done.
- **MarkingTestView** (AR writer covers it): Debug builds only, from the Profile tab's trailing toolbar button (`scope`, a11y "AR marking test") as a full-screen cover (`App/ProfileTab.swift:28-42`). English only, not in the catalog. Android: `BuildConfig.DEBUG` top-bar button `Icons.Filled.CenterFocusStrong`, the screen replaces Profile with a `BackHandler` (`app/.../ProfileTab.kt:76-96`). Done.

---

### 14. App Clip experience and how Android covers it

iOS `Clip/InsiteViewClipApp.swift`, Clip branches in `GuestLandingView.swift`, `ProblemView.swift`.

| Clip behaviour (iOS) | Where | Android replacement | Parity |
|---|---|---|---|
| Invoked by a physical code, Safari's card or a Local Experience with the scanned URL; the guest flow is the root (`NavigationStack { GuestFlowView }`, `.id(link)` so a new code restarts it) | `InsiteViewClipApp.swift:17-55` | Installed app: App Links open `/b/*`, `/a/*` straight into `GuestFlowRoute` | Done in the app; **verification Missing on the web** (below) |
| Not installed | — | The web viewer `/b/{code}` | Done (web) |
| Launched without a URL: `Wordmark(30)` + `qrcode.viewfinder`, "Scan an Insite View code", "Open the Camera app and point it at the code on the plate." | `:59-72` | N/A (the app opens to its tabs and has a scanner) | N/A |
| Foreign URL alert "This isn't an Insite View code" | `:75-77` | Same alert in `RootTabView` | Done |
| Landing "Keep this building on your phone" card (headline), "Get the free Insite View app to open this building again any time, even offline.", `secondaryAction` "Save this building" (`arrow.down.app`) → write the App Group handoff, show `SKOverlay` | `GuestLandingView.swift:278-296` | The web's "Get the app" (Google Play) | see below |
| After the first AR session closes, once: handoff + `SKOverlay` | `GuestLandingView.swift:93-98` | none | N/A (no Clip) |
| "Update" on "Update required" → `SKOverlay` (the Clip updates itself on its next launch) | `ProblemView.swift:66-75` | Google Play page | Decided |
| No sign-in, no save, no recents, no plate registration / room corrections, visits sent as `IosClip` | `BuildingSession.swift:257, 594, 632` | Always the full app (`AndroidApp`); admins get registration (PLAN §6 last row) | Decided |
| Handoff read by the full app at launch, once, ≤ 7 days, plate kept | `AppRootModel.swift:31-33` | Install Referrer `referrer=code=…&plate=…` read once, stored as the handoff (`BuildingHandoff.saveReferrer/take`), opened if no other link opened first (`InsiteViewApplication.kt:41-45`) | Done in the app |

Cross-repository gaps (found while checking the handoff path; they decide whether Android "behaves like the Clip"):

- **Missing — web Play link has no install referrer.** `insiteview-web/src/lib/guest/apps.ts:44-47` builds `https://play.google.com/store/apps/details?id={ANDROID_PACKAGE_NAME}` with no `&referrer=code%3D…%26plate%3D…`, and `components/guest/app-promo.tsx` passes it unchanged; the Android referrer parser therefore never receives a building, and a guest who installs from the web lands on the app's home instead of the building. Needed: per-building URL with `referrer=` URL-encoded `code={code}&plate={plate}`.
- **Missing — `/.well-known/assetlinks.json`.** The web has only `app/.well-known/apple-app-site-association` (its proxy matcher already exempts `/.well-known/assetlinks.json`, `src/proxy.test.ts:36`). Without it `autoVerify` fails and printed plates open the browser (or a disambiguation dialog) instead of the app. Android PLAN §3 names `ANDROID_PACKAGE` / `ANDROID_SHA256_CERT_FINGERPRINTS`; `.env.example` has only `ANDROID_PACKAGE_NAME`.
- Web copy on Android (`messages/en.json` `guest.app.android`): "Open buildings straight from their QR code with the Insite View app." / button "Get it on Google Play"; before a Play package is configured, `androidSoon`. Not part of the app; listed for the full picture.

---

### 15. Onboarding before AR (summary; AR section owns the screens)

`IVCore/GuestOnboarding.swift:13-73`, `IVFeatures/Onboarding/ARPreflightViews.swift`.

- `ARPreflightStep.next(hasSeenExplainer:camera:)`: not seen → **explainer** ("Point" / "Align" / "Explore", "Skip", "Next", "Start"; three pages; seen when finished or skipped); then camera `notDetermined` → **camera explainer** ("Allow the camera", "Insite View shows the building's pipes and wiring over what your camera sees. Nothing is recorded or uploaded.", only "Continue"); `denied` → "Camera access is off" + "Open Settings" + "Not now"; `restricted` → no Settings button; `authorized` → AR.
- Flags per device (`UserDefaults`; Android DataStore): `com.getinsiteview.guest.seenARExplainer`, `com.getinsiteview.guest.seenSafetyNote` (the safety note "The model shows the design. Always confirm before drilling or cutting." once; then from the AR menu).
- Android note for the AR writer: the camera explainer also offers "Not now" (`onboarding/ARPreflightViews.kt:256`), which iOS omits on purpose (App Review 5.1.1(iv)); and ARCore availability is checked first (Decided).

---

### 16. Analytics in these screens

Event factories `IVCore/AnalyticsEvent.swift` (Android `core/.../AnalyticsEvent.kt`); queued per audience by `IVAPI/AnalyticsUploader.swift` (Android `api/.../AnalyticsUploader.kt`). `BuildingSession.track` drops events before a visit exists (nothing to send them as). Audience: guests → the visit token (until it expires); member access by id → bearer + building id.

| Event | Props | When (this area) | iOS | Android |
|---|---|---|---|---|
| `object_opened` | `kind`, `system` | object card opens on an indexed element | `BuildingSession.swift:500-503` | Done |
| `search_performed` | `results` (row count) | each completed BuildingHome search | `:686-687` | Done |
| `document_opened` | — | a document file is ready to show (list or card) | `:757` | Done |
| `building_saved` | — | "Save this building" succeeded | `:799` | Done |
| `session_ended` | `ms` (rounded, ≥ 0) since open/resume | app goes to background; then flush | `:521-528` | Done |
| `system_toggled` | `system`, `on` | a system chip on Rooms (and viewer/AR) | `:396-401` | Done |
| `view_3d_opened`, `ar_opened`, `ar_aligned` | — / — / `method`, `ms` | viewer and AR sections | `Viewer3DView.swift:122`, `ARExperienceView.swift:151, 430-440` | Done |

Uploader constants: flush every **30 s** and on background; ≤ **100** events per call; ≤ **10** calls per minute per audience; ≤ **1000** queued per audience (oldest dropped); props > **2048** bytes JSON → props dropped; `rate_limited`/429 → blocked for `Retry-After` (default **60 s**); 5xx/offline → kept; other 4xx and unbuildable requests → dropped; expired visit tokens' queues discarded; queue persisted to a file (iOS `Application Support/Analytics/queue.json`, complete-until-first-unlock; Android `filesDir/analytics-queue.json`). Parity: Done.

---

### 17. Constants

| Constant | Value | Where (iOS) |
|---|---|---|
| Landing preview height | 240 pt | `GuestLandingView.swift:139` |
| PIN length | 4 | `PinPad.swift:7` |
| Lockout when `Retry-After` missing | 3600 s | `PinPad.swift:64` |
| PIN status refresh | 1 s | `PinEntryView.swift:85` |
| PIN key size | 72 × 72 pt; grid 20 × 14 pt | `PinEntryView.swift:161-193` |
| Shake | ±10 pt, 0.28 s total | `PinEntryView.swift:83, 211-216` |
| Search debounce | 300 ms | `BuildingHomeView.swift:200` |
| Rooms on BuildingHome | first 5, "All N rooms" above 5 | `BuildingHomeView.swift:138, 154` |
| Scene-work hold poll | 200 ms | `BuildingSession.swift:289` |
| Trial attention | ≤ 3 days left | `BuildingStatus.swift:76` (IVCore) |
| Document link lifetime / renewals | 15 min / one retry after 403 | `Documents.swift:129-132` |
| Visit token | ≤ 12 h; a 401 that isn't `pin.required` → new visit, retry once | `BuildingConnection.swift:211-231` |
| Handoff max age | 7 days | `GuestOnboarding.swift:82` |

---

### 18. Accessibility checklist

| Item | iOS | Android | Parity |
|---|---|---|---|
| Landing preview hidden | `.accessibilityHidden(true)` | `clearAndSetSemantics {}` | Done |
| Decorative lock icons hidden | yes | `contentDescription = null` | Done |
| PIN dots as one element "n of 4 digits entered" | yes | merged semantics | Done |
| Delete key "Delete" | yes | yes | Done |
| Shake off under Reduce Motion | yes | no | Differs (¶4) |
| Need chips are buttons | `NavigationLink` | `Role.Checkbox` | Differs (¶3.1) |
| Room dots named by system | yes | `contentDescription` per dot | Done |
| Document rows "Opens the document" hint | `accessibilityHint` | `onClickLabel` | Done |
| Object card header read as one | `.combine` | `mergeDescendants` | Done |
| Dynamic type | fonts `relativeTo:` | `sp` | Done |

---

### 19. Strings parity (this area)

- English: every iOS string above exists in Android `features/src/main/res/values/strings.xml` (generated from the iOS catalog by `scripts/xcstrings-to-android.py`), with "iPhone" → "phone" in `strings_android.xml` (Decided).
- **Differs — inflected plurals in pt-BR and es.** iOS uses automatic grammar agreement (`^[%lld tentativa restante](inflect: true)` → "3 tentativas restantes"). The converter pluralizes only the last word of a multi-word phrase via a word list and falls back to `word + "s"` (`scripts/xcstrings-to-android.py:35-43`, `plural_of`), producing:
  - pt-BR `wrong_pin_n_try_left` many/other "PIN incorreto. %d tentativa restantes." (should be "tentativas restantes") — `values-b+pt+BR/strings.xml:771-772`
  - pt-BR `trial_n_day_left` "Teste · %d dia restantes" (should be "dias restantes") — `:719-720`
  - es `wrong_pin_n_try_left` "PIN incorrecto. %d intento restantes." (should be "intentos restantes") — `values-es/strings.xml:771-772`
  - es `trial_n_day_left` "Prueba · %d día restantes" (should be "días restantes") — `:719-720`
  (`n_item`, `n_document` are single words and come out right.)

---

### 20. Parity summary (gaps only, most important first)

| # | Gap | Status | Where |
|---|---|---|---|
| 1 | Web Google Play link carries no `referrer=code=…&plate=…`, so the Clip-style handoff never fires | Missing (web) | `insiteview-web/src/lib/guest/apps.ts:44-47`, `components/guest/app-promo.tsx` |
| 2 | No `/.well-known/assetlinks.json` on the web: App Links can't verify, plates open the browser | Missing (web) | `insiteview-web/src/app/.well-known/` |
| 3 | pt-BR/es "tries left" and "days left" plurals wrong | Differs | `scripts/xcstrings-to-android.py:35-43`; `values-*/strings.xml:719-720, 771-772` |
| 4 | Need chips announce as checkboxes; padding 12 vs 14 | Differs | `guest/GuestLandingView.kt:322`, `design/.../Components.kt:141-142` |
| 5 | Action buttons have no icons ("See in AR", "Explore in 3D", "View in AR", "3D model", "Save this building") | Differs | `design/.../Components.kt:49-112`; `GuestLandingView.kt:296, 307, 376`; `BuildingHomeView.kt:144-145` |
| 6 | PIN shake ignores reduced motion | Differs | `guest/PinEntryView.kt:130-140` |
| 7 | "Couldn't open the document … check your connection" also shown when no app can open the file; MIME fallback | Differs | `documents/DocumentsView.kt:86, 101` |
| 8 | "Show in 3D" shares the AR icon | Differs | `objectcard/ObjectCard.kt:227` |
| 9 | Landing "Building details" / "Documents" drawn as chevron rows, not tinted links | Differs (visual) | `guest/GuestLandingView.kt:142-149, 341-347` |
| 10 | PIN haptic per key press instead of per digit change; sheet wraps content instead of full height | Differs (minor) | `guest/PinEntryView.kt:126, 151, 251-254` |
| 11 | Save button replaced by a spinner while saving | Differs (minor) | `guest/GuestLandingView.kt:371-374` |
| 12 | Problem screen shows the display code as title; empty-name header has no placeholder | Differs (cosmetic) | `guest/GuestFlow.kt:167`; `GuestLandingView.kt:244` |

Everything else in this area (GuestFlow states and rules, GuestProblem mapping, PinPad, access gate, BuildingSession, BuildingHome, search, status line, Rooms, Documents states and download/renewal, object card content and member gating, analytics, Diagnostics) is **Done**; the search field placement, inline equipment locate, AR as a destination, ACTION_VIEW, Google Play update, and the absence of the Clip are **Decided** differences.

## 7. Design system and 3D viewer

Scope: (A) the IVDesign module and the asset catalog → Android `:design`; (B) the 3D viewer and model loading as the user sees them → Android `:scene` and `features/.../viewer`. The iOS code is the spec. iOS paths are relative to `insiteview-ios/Packages/InsiteViewKit/Sources/` unless they start with `App/`, `Clip/`, `Resources/` or `docs/`. Android paths are relative to `insiteview-android/`.

Parity status: **Done** = same behaviour (platform mechanics may differ). **Differs** = what the user sees or does is not the same (how, with a Kotlin `file:line`). **Missing** = not on Android. **Device check** = the code reads right, but only a phone can confirm it (the Android port has never run on a device).

Two notes before the details:

- **iOS `docs/PLAN.md` §3 "3D viewer" is out of date.** It lists an "x-ray slider (architecture opacity)" and "isolate the selection" in the viewer. The iOS code has neither. Android must not add them.
- **Already decided in Android PLAN §6:** the viewer renders GLB chunks with Filament/SceneView, not USDZ with RealityKit. There is no App Clip: iOS-only Clip behaviour, such as the Clip's untinted (system-blue) controls, does not apply. Only the app's behaviour applies.

---

### A. Design system

#### A.1 Colour tokens

Source: `IVDesign/Palette.swift:12-51`, the light and dark values of the web's `globals.css`. Each colour is a dynamic `UIColor` resolved from `userInterfaceStyle`. Android: `design/.../Palette.kt:30-59` (`LightColors` and `DarkColors`), served through `LocalIvColors` by `IvTheme`.

| Token | Use | Light | Dark | iOS | Android | Status |
|---|---|---|---|---|---|---|
| `background` (`--bg`) | Paper: screen backgrounds | `#F1F1F0` | `#0D0D0E` | Palette.swift:14 | Palette.kt:31/47 | Done |
| `surface` (`--surface`) | Cards, panels, chips, secondary buttons | `#FFFFFF` | `#18181A` | :16 | :32/48 | Done |
| `sunk` (`--sunk`) | Tracks, wells, placeholders | `#E3E3E3` | `#222224` | :18 | :33/49 | Done |
| `ink` (`--ink`) | Text and ink fills | `#0D0D0E` | `#F1F1F0` | :20 | :34/50 | Done |
| `muted` (`--muted`) | Secondary text, eyebrows | `#5F5F61` | `#9A9A9E` | :22 | :35/51 | Done |
| `line` (`--line`) | Hairlines, chip borders | `#D4D4D3` | `#2E2E31` | :24 | :36/52 | Done |
| `accent` (`--accent`) | Brand red: primary actions, the wordmark slash, progress, slider | `#E30532` | `#FF3B4E` | :26 | :37/53 | Done |
| `accentSoft` | Red tint (room-focus chip) | `#FBE3E7` | `#3A0E15` | :28 | :38/54 | Done |
| `warn` | Amber warnings (never a primary action) | `#B45309` | `#F59E0B` | :30 | :39/55 | Done |
| `warnSoft` | Amber tint | `#FDF0DC` | `#3A2A0A` | :31 | :40/56 | Done |
| `onAccent` | Text on accent (dark: the surface colour, because white on `#FF3B4E` is only 3.5:1) | `#FFFFFF` | `#18181A` | :34 | :41/57 | Done |
| `onInk` | Text on ink fills (ink button, selected chip) | `#F1F1F0` | `#0D0D0E` | :36 | :42/58 | Done |
| Catalog colour | System and subsystem colours: `Color(catalog:)` from the API catalog's sRGB components, never hard-coded | from API | same | Palette.swift:55 | `CatalogColor.toColor()` Palette.kt:95 | Done |
| Catalog fallback | Unknown system (`CatalogColor.fallback`) | `rgb(0.62, 0.65, 0.66)` ≈ `#9EA6A8` | same | IVCore/Catalog.swift:244 | `CatalogColor.FALLBACK` | Done |

Colours used outside the palette, the same on both platforms:

| Colour | Where | Value | iOS | Android | Status |
|---|---|---|---|---|---|
| 3D viewer background | Behind the model | light `#F3F5F4`, dark `#0E1413` (the old teal-tinted `--bg`: **not** `Palette.background`; iOS's comment calls it `--bg` but the value is stale) | IVScene/OrbitViewerView.swift:135-139 | scene/OrbitViewerView.kt:234-236 (skybox colour, converted to linear) | Done (keep as is; both are stale in the same way) |
| Selection highlight | Selected element | base `#E30532` in light and dark, emissive `#FF3B4E` × 0.8, roughness 0.4, metallic 0 | IVScene/SceneMaterials.swift:14-23 | scene/SceneMaterials.kt:49-58 | Device check (see B.7) |
| Locate marker | Locate in AR pulse | unlit `rgb(0.04, 0.43, 0.47)` ≈ `#0A6E78`, opacity 0.8 | IVScene/BuildingScene.swift:315-316 | BuildingScene.kt:434 | Done |

Global tint:

- In the app, the whole `TabView` has `.tint(Palette.accent)` (`App/InsiteViewApp.swift:103`). Every control that uses tint is therefore red: navigation-bar buttons, the back chevron, the viewer's toolbar icons, toggles, links and the selected tab.
- Android: `IvTheme` maps M3 `primary` to `accent` (`IvTheme.kt:23`), so stock Material controls are red.
- **Differs:** `IvTopBar` sets `navigationIconContentColor` and `actionIconContentColor` to `Palette.ink` (`features/.../ui/ListParts.kt:64-69`). The back arrow and the toolbar icons (See in AR, Level, Fit) are ink on Android and red on iOS. Fix: use `Palette.accent` for both.

Dark mode:

- Both follow the system setting. iOS resolves colours dynamically; Android uses `IvTheme(darkTheme = isSystemInDarkTheme())` (`IvTheme.kt:19`). Done.
- The 3D background changes live on Android (`rememberEnvironment(key = dark)`, OrbitViewerView.kt:366). On iOS it is set when the ARView is created (OrbitViewerView.swift:109). Not a parity issue.

Material 3 mapping (Android only, so stock components match):

- `primary` = accent, `onPrimary` = onAccent, `primaryContainer` = accentSoft.
- `secondary` = ink, `secondaryContainer` = sunk.
- `background`, `surface`, `surfaceContainer*` = surface; `surfaceContainerHighest` and `surfaceVariant` = sunk.
- `outline` = line. `error` = accent.

Source: `IvTheme.kt:22-45`. iOS has no counterpart; the mapping is fine as it is.

#### A.2 Shape, spacing and elevation

- **Square corners everywhere** in IVDesign: buttons, chips, the room chip, loading bars and the See inside panel use `.rect` / `Rectangle()`. Android uses `RectangleShape`. Done.
- **Exceptions (system chrome):** the iOS object-card sheet has the system's rounded top corners and grabber. Android's non-modal viewer card panel is square, with a 36×4 dp rounded handle (`objectcard/ObjectCard.kt:453-500`). Out of scope here (object-card section); noted for consistency.
- **Standard spacing** (iOS points = Android dp):

| Spacing | Value |
|---|---|
| Screen horizontal inset | 16 |
| Chip row: horizontal padding | 16 |
| Chip row: vertical padding | 4 |
| Gap between chips | 8 |
| Chip inner padding | 12 horizontal |
| Gap between a dot or icon and its text | 6 |
| Viewer overlay stack | 8 between items, 12 vertical padding |
| LoadingBar: bar to caption | 6 |
| Button horizontal padding | 16 |

- **Elevation:** IVDesign has no shadows. Android's card panel has `shadowElevation = 8.dp` (object-card section).

#### A.3 Typography

Fonts:

- The OFL Latin subsets are byte-identical on both platforms (md5 checked): Anton-Regular, Barlow Regular / Medium / SemiBold / Bold, JetBrains Mono Regular / Medium.
- iOS keeps them in `IVDesign/Resources/Fonts` and registers them at launch (`IVFonts.register()`, Typography.swift:25). Android keeps them in `design/src/main/res/font/*.ttf`. Done.
- iOS falls back to the system font when a face is missing (Typography.swift:54, 73, 89). Not needed on Android.

Root font:

- iOS sets `.font(.ivBody())` on the app root (`App/InsiteViewApp.swift:37`), so unstyled `Text` is Barlow 17 regular.
- Android: M3 `Text` defaults to `typography.bodyLarge` = `IvType.body(17.sp)` (`IvTheme.kt:56`). Done.

Each style:

| Style | Font | Size (pt = sp) | Weight | Tracking | Case | Dynamic Type curve (`relativeTo`) | iOS | Android | Status |
|---|---|---|---|---|---|---|---|---|---|
| `ivDisplay` / `.ivDisplay(size:)` | Anton | 34 default; callers pass 24–34 | Regular | 0 | UPPERCASE (`.textCase(.uppercase)`) | `.largeTitle` | Typography.swift:53, 121 | `IvType.display` Typography.kt:36; `DisplayText` :72 upper-cases | Done (line height differs; see below) |
| `ivBody` | Barlow | 17 default | Regular, Medium, SemiBold or Bold (others round to the nearest) | 0 | as written | `.body` | :62-77 | `IvType.body` :39-40, `bodyWeight` :56-61 | Done |
| `ivHeadline` | Barlow | 17 | SemiBold | 0 | as written | `.headline` | :80 | `IvType.headline` :43 | Done |
| `ivMono` | JetBrains Mono | 13 default | Regular, or Medium for anything heavier | 0 | as written | `.footnote` | :86-93 | `IvType.mono` :46-51 | Done |
| Eyebrow (`ivEyebrow`) | JetBrains Mono | 11 | Medium | 1.2 pt (≈0.109 em) | UPPERCASE | `.caption2` | :97-110 | `IvType.eyebrow` 0.11 em :54; `Eyebrow` upper-cases :66 | Done |
| Nav bar inline title | Barlow | 17 | SemiBold | 0 | as written | `.headline` | `IVAppearance` :131-133 | `IvTopBar` uses `IvType.headline` (ListParts.kt:55) | Differs: alignment (see below) |
| Nav bar large title | Anton | 34 | Regular | 0 | **mixed case** (UIKit attributes can't upper-case) | `.largeTitle` | :132 | none | **Missing** |
| Primary and secondary button label | Barlow | 13 | SemiBold | 0.8 pt (≈0.0615 em) | UPPERCASE | `.subheadline` | Components.swift:22-24, 58-60 | ActionLabel 0.06 em, Components.kt:110 | Done (0.78 vs 0.8: negligible) |
| Chip label | Barlow | 15 | Medium | 0 | as written | `.subheadline` | Components.swift:106 | Components.kt:153 | Done |
| LoadingBar caption | JetBrains Mono | 12 | Regular | 0 | as written | `.footnote` | Components.swift:154 | Components.kt:188 | Done |
| Wordmark | Anton | 28 default (callers use 30 and 34) | Regular | 0 | "INSITE VIEW" literal | `.title` | Wordmark.swift:10, 22 | Wordmark.kt:18 | Done |

**Line height (Differs).**

- iOS never sets a line height, so SwiftUI uses each font's own metrics. All three faces set `USE_TYPO_METRICS`, with `hhea` = `typo`:
  - Anton: (2409 + 674) / 2048 = **1.505 × size**
  - Barlow: (1000 + 200) / 1000 = **1.20 × size**
  - JetBrains Mono: (1020 + 300) / 1000 = **1.32 × size**
- Android sets fixed multipliers (Typography.kt:36, 40, 50):

| Font | Android | iOS |
|---|---|---|
| Anton | 1.10 × | 1.505 × |
| Barlow | 1.30 × | 1.20 × |
| JetBrains Mono | 1.35 × | 1.32 × |

- Effect: wrapped Anton headlines (building names, the PIN title) are about 27 % tighter on Android, and Barlow paragraphs are about 8 % looser.
- Fix: use `lineHeight = size * 1.505`, `* 1.2` and `* 1.32`, with `LineHeightStyle(Alignment.Center, Trim.None)`. Otherwise drop `lineHeight` and use `PlatformTextStyle(includeFontPadding = false)` to get the font's own metrics.

**System text styles (Differs).**

- Much of iOS's secondary text uses SF Pro, not Barlow. There are 92 explicit `.font(.footnote / .subheadline / .callout / .caption / .caption2 / .title3 / .title2 / .headline / .body)` calls: footnote ×42, subheadline ×23, callout ×10, caption ×8, title3 ×3, caption2 ×2, body ×2, title2 ×1, headline ×1.
- Stock UIKit chrome (menus, alerts, pickers) also uses SF.
- Default sizes at the "Large" content size:

| Style | Size (pt) |
|---|---|
| largeTitle | 34 |
| title | 28 |
| title2 | 22 |
| title3 | 20 |
| headline | 17 semibold |
| body | 17 |
| callout | 16 |
| subheadline | 15 |
| footnote | 13 |
| caption | 12 |
| caption2 | 11 |

- Android uses Barlow for all of these. Examples in this section's scope:
  - the DetentSlider labels (iOS `.caption2`, SF 11) are Barlow 11 on Android (DetentSlider.kt:84);
  - the room-focus chip (iOS `.subheadline.weight(.medium)`, SF 15 medium, Viewer3DView.swift:169) is Barlow 15 medium on Android (Viewer3DView.kt:210);
  - storey menu items (iOS system menu, SF 17) use M3 `labelLarge` = Barlow 15 medium.
- To match: where iOS uses a system text style, use `FontFamily.Default` (Roboto) at the same size and weight.

**Navigation bar** (`IVAppearance.configure()`, Typography.swift:129-148):

- Standard appearance is the default background (system blur plus hairline once content scrolls under it). Scroll-edge appearance is transparent.
- Inline title: Barlow SemiBold 17, **centred**. Large title: Anton 34, mixed case.
- The tab roots ("Buildings", "Search", "Profile": `App/BuildingsTab.swift:34`, `SearchTab.swift:28`, `ProfileTab.swift:28`) use the default large-title mode, which collapses to the inline title on scroll.
- Android `IvTopBar` (`ListParts.kt:48-71`) is an M3 small `TopAppBar`. **Differs** in four ways:
  1. the title is start-aligned, not centred (use `CenterAlignedTopAppBar`);
  2. there is no large Anton title for the tab roots (use `LargeTopAppBar` with `IvType.display(34.sp)` and no upper-casing, plus `exitUntilCollapsed`);
  3. the background is a solid `Palette.background` with no hairline or blur on scroll;
  4. the icons are ink, not accent (see A.1).
- The back affordance is iOS's chevron with the previous title versus Android's Material `ArrowBack` with "Back" (`strings_android.xml`). That is a platform convention. Acceptable.

**Dynamic Type → Android font scale:**

- iOS: every IVDesign font is `Font.custom(name, size:, relativeTo:)`, so it scales along its text style's curve. The curve is non-linear: at AX5, body 17 → 53 (×3.1) but largeTitle 34 → 60 (×1.8).
- Android: every size is in `sp`. Before Android 14, scaling is linear and stops at the system maximum of ×2.0 (×1.3 on older versions). On Android 14+, scaling is non-linear and large sizes grow less, which is close to iOS. Accept that as the platform equivalent.
- Rules to keep:
  1. never use `dp` for text;
  2. never fix the height of a text container: chips and buttons use `heightIn(min = …)` (Components.kt:105, 136);
  3. let text wrap except where iOS sets `lineLimit(1)` (chips, wordmark, top-bar title).
- Wordmark gap: iOS spaces at `size × 0.18` in points, which does not scale (Wordmark.swift:16). Android converts `size × 0.18` from sp, so the gap grows with the font scale (Wordmark.kt:19). Differs (cosmetic; harmless).

#### A.4 Components

**PrimaryActionButton** (iOS `PrimaryActionStyle`, `.primaryAction` / `.inkAction`, Components.swift:7-47; Android `PrimaryActionButton`, Components.kt:49-78)

- Layout:
  - full width (`frame(maxWidth: .infinity)`), min height **46**, horizontal padding **16**, no vertical padding;
  - label centred, multi-line allowed (no line limit), square corners, no border, no shadow, no ripple.
- Colours:

| Variant | State | Fill | Label |
|---|---|---|---|
| `accent` (default) | rest | accent | onAccent |
| `accent` | pressed | ink | onInk |
| `ink` | rest | ink | onInk |
| `ink` | pressed | accent | onAccent |

- Disabled: the whole button at **opacity 0.4**, colours unchanged (Components.swift:30; Kotlin :74).
- Focus: none on iOS. Android passes `indication = null`, so there is no focus ring either. Same.
- **Missing: icons in labels.** iOS labels are often a `Label(text, systemImage:)`, an SF Symbol before the text in the same colour and font:
  - "See in AR" (`arkit`) and "Explore in 3D" (`cube`): GuestLandingView.swift:235, 247
  - "View in AR" (`arkit`) and "3D model" (`cube`): BuildingHomeView.swift:65, 73
  - "Save this building" (`arrow.down.app` or `bookmark`): GuestLandingView.swift:289, 326
  - "Continue with email" (`envelope`): App/SignInView.swift:62
  - "Undo" (`arrow.uturn.backward`): AR/PointsAlignment.swift:500
- Android `ActionLabel(text: String)` (Components.kt:103-115) takes only text. Add an optional `icon: ImageVector?`: about 17 dp, tinted like the label, 8 dp before the text (SwiftUI `Label` spacing), and not upper-cased.

**SecondaryActionButton** (iOS `SecondaryActionStyle`, Components.swift:51-71; Android Components.kt:82-100)

- Same frame and type as the primary button. Fill is surface, with a **1.5** inset border.
- Rest: ink border and ink label. Pressed: accent border and accent label. Disabled: opacity 0.4.
- Status: Done, except label icons (Missing, as above).

**Chip** (iOS `Chip`, Components.swift:84-118; Android `Chip`, Components.kt:122-159)

- Layout: `HStack(spacing: 6)` of an optional colour dot and a title. Horizontal padding 12, **min height 36**, square.
- Dot:
  - a 10×10 circle in the catalog colour;
  - with a 1 pt inner stroke of `surface` at opacity 0.6 when selected (0 when not), so it reads on the ink fill.
- Title: Barlow 15 Medium, one line, truncated at the tail. Already localized (catalog names via `Text(verbatim:)`).
- Unselected: fill surface, 1 pt border in line colour, ink text.
- Selected: fill ink, 1 pt border in ink, onInk text.
- Pressed:
  - iOS `.buttonStyle(.plain)` dims the content while it is pressed;
  - Android `selectable` uses the theme's ripple. **Differs** (minor). To match, use `indication = null` and drop alpha to about 0.7 while pressed.
- Accessibility:
  - iOS: Button plus the `.isSelected` trait (VoiceOver: "Electrical, Selected, Button").
  - Android: `selectable(role = Role.Checkbox)` (TalkBack: "selected / not selected, checkbox"). Platform equivalent; Done.
- Android adds `enabled` (alpha 0.4). iOS has no disabled chip; harmless.
- Touch target: 36 high on both. The chip deliberately stays below 44/48; keep 36 for parity.

**ChipRow** (Components.swift:121-136; Kotlin :163-174)

- A horizontal scroll with no indicators. Content padding 16 horizontal and 4 vertical; spacing 8; items centred vertically. Done.

**LoadingBar** (Components.swift:140-158; Kotlin :178-190)

- `VStack(alignment: .leading, spacing: 6)`:
  - a linear `ProgressView`, clamped to 0…1 and tinted accent. On iOS it is 4 pt high with a rounded track in the system fill grey.
  - below it, a JetBrains Mono 12 caption in muted.
- Android: `LinearProgressIndicator`, accent on a `sunk` track, `gapSize = 0`, no stop indicator, full width. Done.
- The caption strings are in B.3.

**DetentSlider** (iOS DetentSlider.swift:8-106; Android DetentSlider.kt:38-100). Used for the See inside opacity in AR (`AR/SeeInsidePanel.swift:37-46`).

- Behaviour (Done unless noted):
  - Range 0…1, with any number of `Detent(value, label)`.
  - While dragging, each move that reaches or crosses a detent fires a tick, a **selection haptic** (`reachedDetent`, DetentSlider.swift:50). Android: `HapticFeedbackType.SegmentTick`.
  - On release, the value snaps to `snapped(value)`. If it moved, iOS animates with `.snappy` and plays a **light impact** haptic (:56-63, :94). Android: `Confirm` haptic, but no animation (the thumb jumps). **Differs** (minor).
  - The row of labels sits under the slider, 6 apart, each taking equal width: the first is leading-aligned, the last trailing, the others centred.
  - The label of the detent within 0.01 of the value is **semibold ink**; the others are regular muted.
  - Tapping a label jumps to it (animated `.snappy` on iOS, instant on Android) and ticks.
  - Labels are hidden from accessibility.
- Accessibility:
  - iOS: the slider's label is the given text ("Model opacity"); its value is the nearest detent's label; VoiceOver swipe up and down **moves between detents** (`accessibilityAdjustableAction`, :66-77).
  - Android: `contentDescription` plus `stateDescription` = nearest label. TalkBack's adjust action moves by the default continuous step, not between detents. **Differs.** Fix: override `setProgress` in semantics so it goes to the next or previous detent.
- Look:
  - iOS: the native `Slider`, tinted accent. The filled track is accent; the unfilled track is system grey (about 4 pt); the thumb is a **white circle of about 28 pt with a shadow**.
  - Android: the M3 `Slider` (Compose BOM 2026.09, so the M3-Expressive look): an accent **vertical-bar thumb (4×44 dp)**, a **16 dp-thick** track with a gap at the thumb, and stop dots. Colours are only partly overridden (DetentSlider.kt:69-73). **Differs** visibly.
  - Fix: a custom `thumb` (a 28 dp white circle with about 2 dp elevation in light mode, a light-grey circle in dark mode) and a custom `track` (4 dp, accent filled part, `sunk` remainder, no stop indicator, no gap).

**Wordmark** (Wordmark.swift:5-29; Wordmark.kt:18-30)

- "INSITE VIEW" in ink, then "/" in accent. Both Anton at `size` (default 28; 30 on the Buildings empty state, 34 on sign-in).
- Gap `size × 0.18`. Aligned on the first baseline. One line, fixed size.
- Accessibility: a single element labelled "Insite View" with the header trait.
- The name is never translated. Done (gap scaling: see A.3).

**Eyebrow and DisplayText** (Android helpers; iOS modifiers): see A.3. Done.

#### A.5 App icon, launch and brand assets

**App icon** (`Resources/Assets.xcassets/AppIcon.appiconset`, 1024², rendered by `insiteview-api/brand/render.py`):

| Variant | Background | Foreground |
|---|---|---|
| Default | ink `#0D0D0E` | a forward-slash parallelogram in accent `#E30532`: corners at about (559,160), (768,160), (464,864), (256,864) px of 1024, so 68.75 % of the height |
| Dark | ink `#0D0D0E` | the slash in dark accent `#FF3B4E` |
| Tinted | (grey-alpha mask) | the slash |

- Android (`app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml`, `drawable/ic_launcher_foreground.xml`):
  - an adaptive icon, background `#0D0D0E`;
  - foreground path (108-unit viewport) `M57.375,29.25 L72,29.25 L50.625,78.75 L36,78.75 Z` in `#E30532`, which is 49.5 / 72 = 68.75 % of the visible 72 dp;
  - the same path as the `<monochrome>` layer (themed icons, matching iOS "tinted");
  - `ic_launcher-playstore.png` (512²) matches the default.
- Status: Done. iOS's dark-appearance icon has no Android equivalent (platform).

**BrandMark.imageset** (512², the slash on ink): no Swift code references it. Nothing to port.

**Launch:**

- iOS `UILaunchScreen: {}` (`project.yml:53`) is a blank system-background screen with no logo.
- Android: the window background is paper (`#F1F1F0`, night `#0D0D0E`, `app/src/main/res/values{,-night}/app.xml`). On Android 12+, the system splash also shows the launcher icon, and that cannot be turned off. Platform difference; acceptable.

---

### B. 3D viewer and model loading

#### B.1 Where the viewer appears

| Entry | Focus passed (`ViewerFocus`, Viewer3DView.swift:9-29) |
|---|---|
| BuildingHome "3D model" (secondary button) | empty |
| BuildingHome system row | `systems: [key]` |
| Landing "Explore in 3D" | empty, or a need's systems |
| Rooms list → room | `roomID` (and `need`) |
| Search / equipment → "Show in 3D" | `elementID` |
| "What do you need?" chip | `need` |

- Android `ViewerFocus` (Viewer3DView.kt:67-76) has the same fields. Done.
- Navigation: a push with an inline title of the **building name** (`Text(verbatim: session.name)`, `.inline`, Viewer3DView.swift:77-78). The only way out is back; there is no close button.
- Android: `IvTopBar(session.name, onBack = navigator::back)` (Viewer3DView.kt:111). Done, apart from top-bar styling (A.3).

#### B.2 Screen layout (`Viewer3DView.body`, Viewer3DView.swift:47-113)

- A `ZStack(alignment: .bottom)` containing:
  1. **ModelViewer**, full screen and extended under the bottom safe area (`.ignoresSafeArea(edges: .bottom)`). Accessibility label **"3D model"** (one element; VoiceOver cannot pick parts).
  2. **Bottom overlay:** a `VStack(spacing: 8)`, centre-aligned, 12 vertical padding, on a `.regularMaterial` background (system blur, which also fills under the home indicator). In order:
     - LoadingBar (16 h padding), only while `phase` is `.connecting` or `.loading`;
     - the RoomFocusChip, only while `viewerFilters.roomID != nil`;
     - SystemChips (a ChipRow).
- **Toolbar** (trailing, left to right):

| Item | Accessibility label | SF Symbol | Action |
|---|---|---|---|
| See in AR | "See in AR" | `arkit` | full-screen AR |
| Level menu | "Level" | `square.3.layers.3d` | storey menu (B.5) |
| Fit to screen | "Fit to screen" | `arrow.up.left.and.arrow.down.right` | refit (B.6) |

- There are **no** subsystem chips, x-ray slider or See inside slider in the 3D viewer. Those are AR only (B.5).
- Android (Viewer3DView.kt:109-169):
  - **Done:** the toolbar's order, labels and actions, with icons `Outlined.ViewInAr`, `Outlined.Layers` and `Outlined.ZoomOutMap`; the overlay's order, conditions and spacing.
  - **Differs:**
    1. the overlay background is a flat `Palette.surface` at alpha 0.92 (:137), not a blur;
    2. Scaffold insets keep the model and the overlay above the system navigation bar, so the model does not extend under it and the overlay does not fill the gesture area (use `contentWindowInsets = WindowInsets(0)` and pad only the overlay content with the navigation-bar insets, with the overlay background drawn underneath);
    3. toolbar icon tint (A.1).

#### B.3 Loading sequence, as the user sees it

Pipeline: `BuildingSession.start` → `run` (BuildingSession.swift:226-311); Android `BuildingSession.kt:351-445`.

1. **Connecting** (`phase = .connecting`):
   - the visit is started (if none exists) and the manifest fetched, while the catalog loads in parallel;
   - on an error, `phase = .failed(GuestProblem)`; if the error ends access, `accessProblem` is set and the screen gives way to the access gate or problem state (other section).
2. **Filters seeded:**
   - viewer = every system in the manifest (architecture included);
   - AR = every system except architecture, then the See inside value is applied (:265-269).
3. **Loading** (`phase = .loading`):
   - Plan (`ChunkPlan.requests`, ChunkDownloader.swift:36-62; Android ChunkDownloader.kt:53-74):
     - the **architecture** chunk's meta and model first;
     - then **every other chunk's meta** (small; the object card, equipment and rooms need it);
     - then the other models, systems in `priority` order and then in manifest order.
     - iOS model files are `.usdz`; Android's are `.glb` (Android PLAN §6). Done.
     - Note: nothing passes `priority` today (GuestFlow.swift:82, 102 call `start()`), so in practice the order is the manifest's. The same on Android.
   - Cache first: every file whose sha256 is already cached is reported loaded **before** any download starts (ChunkDownloader.swift:176-183).
   - Downloads: **3 at a time** (`maxConcurrentDownloads = 3`, :131), each new one taking the next file in plan order.
   - Each file: **2 attempts** (`attempts = 2`, :132), retried immediately with no backoff. The sha256 must equal the manifest's (lower-case hex).
     - Bytes beginning `1F 8B` mean `notDecoded` (storage served gzip without `Content-Encoding`); any other mismatch is `checksumMismatch`.
     - A 403 marks the URL expired and re-signs (see expiry below).
     - Network loss is `offline`; other errors are `transport`.
     - A cache write error is `storage`, with no retry.
   - URL expiry (`URLBook`, :271-321): URLs are re-signed through a fresh manifest **30 s** before `urlsExpireAt`, and after a 403. There is one refresh at a time; a fresh manifest that fails with an access error ends the session (revoked or expired link, paused building).
4. **Each event** (`run` :287-307):
   - progress updates the bar;
   - a meta file is decoded into the `ElementIndex` and the chunk restyled and refiltered;
   - a model file is loaded into the scene (`addChunk`), the filters reapplied and `sceneRevision` bumped. **The first model to arrive (architecture) frames the camera** (B.6).
   - A model file the renderer cannot read is recorded as a `storage` failure; the rest carry on.
5. **End:**
   - `phase = .ready`, unless **no** model loaded and something failed: then `.failed(.offline)` if any failure was offline, else `.failed(.unavailable)` (:308-310; Android `BuildingSessionRules.finalPhase`, BuildingSessionRules.kt:25-32). Done.

Progress UI:

- **Fraction** = (bytes of finished files + bytes received so far in flight) / the sum of the manifest's `bytes` for every requested file. Cache hits count at once. It is 1 when there is nothing to load (`LoadProgress.fraction`, :81-83).
- An event goes out at most every **0.5 %** (`ProgressCounter`, :358).
- A failed attempt's bytes are taken back out. A file that fails for good never counts, so the bar can stop below 100 % before it disappears at `.ready`.
- **Captions** (exact English; xcstrings key → Android resource):

| Where | Caption | Android resource |
|---|---|---|
| Viewer (Viewer3DView.swift:116) | "Loading the model… 42%" | `loading_the_model_npercent` = `Loading the model… %d%%` |
| BuildingHome (BuildingHomeView.swift:85) | same, with the percentage | same |
| Landing preview (GuestLandingView.swift:133) | "Loading the model…" (no percentage) | `loading_the_model` |

  - The xcstrings key is `Loading the model… %lld%%` (pt-BR "Carregando o modelo… %lld%%", es "Cargando el modelo… %lld%%").
  - The percentage is `Int((fraction × 100).rounded())`; Kotlin uses `roundToInt()`.
- The bar shows only while `phase` is `.connecting` or `.loading`. During `.connecting` it shows 0 %.
- Android: Viewer3DView.kt:141-148 and GuestLandingView.kt:199-208. Done.

Failed chunks (never a crash):

- `failedSystems` = the chunk keys of every failed file (`ModelLoadState.failedChunks`, ModelLoadState.swift:25).
- In the viewer, that system's chip is drawn at **opacity 0.4**, with the accessibility hint **"Couldn't load"** (Viewer3DView.swift:197-198). It can still be toggled; nothing appears.
- On BuildingHome, the system row shows the text "Couldn't load".
- There is **no retry control**. A failed chunk loads again only when the session is restarted (reopening the building), or through the PIN-unlock reload, which re-runs the whole plan: loaded chunks stay in the scene and cached files come from the cache.
- Android: SystemChips (Viewer3DView.kt:226-234) uses alpha 0.4 and `stateDescription = "Couldn't load"`. Done (stateDescription versus hint is a platform equivalent).

Cache (`ChunkCache`, ChunkCache.swift:24-145; Android ChunkCache.kt):

- Files are named `{sha256}.{json|usdz|glb}`: in `Caches/Models` on iOS, in `cacheDir/models` on Android (AppDependencies.kt:152). The OS may purge both.
- Only verified files are stored, so a hit is trusted.
- "Last use" is the file's modification time, set on every hit and store.
- **Capacity 1,000,000,000 bytes (1 GB)**. After every store, the least recently used files are evicted (ties broken by name) until the total fits. The SHA-256s of the files in the current load are **pinned** and never evicted.
- No user-visible cache UI (no size display, no "clear"). Done.

Offline:

- The visit and the manifest are not cached. **A building cannot be opened offline, even when its chunks are cached**: the connecting step fails and the problem state is "offline".
- If the connection drops mid-load: cached files load; files not yet downloaded fail as `offline` after 2 attempts (their chips dim); `phase = .ready` if anything loaded, else `.failed(.offline)`.
- "Offline open of cached buildings" is an unticked iOS M5 item. Android: identical. Done.

#### B.4 Rendering the scene

Building root and chunks (BuildingScene.swift:20-125; Android BuildingScene.kt:47-235):

- One `BuildingRoot` holds one `chunk_{key}` container per chunk. Element nodes are named `e` + 32 lower-case hex; storey groups are `s{order}`.
- The root moves between the viewer and AR, so switching modes reloads nothing.
- Loading the same chunk twice does nothing.
- Android: one Filament engine (`SceneEngine`) and one `BuildingScene` whose root node moves between `SceneView`s. Done (Device check).

Materials (SceneMaterials.swift:9-41; Android SceneMaterials.kt):

| What | iOS | Android | Status |
|---|---|---|---|
| System elements (any system except `architecture` and `structure`) | Recoloured from the catalog: the subsystem colour if the element has a subsystem the catalog knows, else the system colour. PBR with **roughness 0.6, metallic 0**, cached per colour. Applied to every mesh in the element's subtree (meshes can sit on unnamed children); nested elements are left alone. | `createColorInstance(color, metallic 0, roughness 0.6)`, cached per (colour, opacity step) | Done (Device check: sRGB handling) |
| Architecture and structure | Keep the file's colours (IFC surface colours from the converter) | Keep the glTF materials | Done |
| No catalog (fetch failed) | Systems also keep their file colours | Same (`recolour` needs a catalog, BuildingScene.kt:254) | Done |
| Meta arrives after the model | Restyled and refiltered (`refresh`) | Same | Done |
| Selection | Every mesh in the element's subtree gets the highlight material; the previous materials are restored on deselect, or when a restyle happens while selected (the highlight is taken off first, then put back) | The `applyMaterials` priority is highlight > catalog > file | Done |
| Highlight look | base `#E30532`, **emissive `#FF3B4E` at intensity 0.8**, roughness 0.4, metallic 0: "glows a little", reads on any system colour and in a dark basement | Same values, but the emissive is set **only if SceneView's colour material exposes an `emissive` parameter** (SceneMaterials.kt:86-93) | Device check: without that parameter the selection is flat red, with no glow |
| Fading (AR only) | `OpacityComponent` on the chunk (See inside) and on elements (behind a wall) | Transparent material copies per 1/100 opacity step | Done for the viewer (the viewer never fades) |

Lighting and camera optics:

- iOS:
  - `ARView(cameraMode: .nonAR)` on a solid background (B.4 table in A.1);
  - motion blur off;
  - a `PerspectiveCamera` with a **55° vertical FOV**, **near 0.05 m**, **far 2000 m**;
  - one `DirectionalLight` of **intensity 2500** aimed at the origin from **(3, 8, 5)**, plus RealityKit's default image-based lighting (OrbitViewerView.swift:105-132).
- Android:
  - SceneView with the same FOV, near and far (`OrbitCameraNode`, OrbitViewerView.kt:257-271), default camera exposure;
  - SceneView's neutral IBL under a skybox of the background colour;
  - a main light pointing along `normalize(-3, -8, -5)` with no shadows; **its intensity is SceneView's default**, not tuned;
  - no fill light (OrbitViewerView.kt:361-410).
- Status: Device check. Compare against an iPhone side by side and tune the sun intensity and exposure until the catalog colours read the same.

Visibility filters (`ModelFilters`, ModelFilters.swift:23-125; Android ModelFilters.kt; applied by BuildingScene.swift:152-188 and Kotlin :273-326):

- A **chunk** shows if its system is in `filters.systems` and its opacity is above 0.
- **Storey:**
  - chunks with `s{order}` groups switch whole groups (all levels, or only the selected storey's order);
  - otherwise each element is checked: an element with a storey different from the selected one is hidden;
  - elements without meta show.
- **Context** chunks (architecture, structure) are never filtered by subsystem or room. They stay as context when a room is focused.
- **Subsystem:** a system element whose `SubsystemKey(system, subsystem)` is in `hiddenSubsystems` is hidden.
- **Room:** with `roomID` set, only system elements whose meta room equals it show; architecture stays.
- Viewer opacities are always 1. Status: Done.

Behind-wall fade (`BehindWallFade.swift`):

- AR only. The viewer calls nothing, so `proximityOn` stays false and every element is at opacity 1.
- AR constants, for reference: full opacity up to 8 m, gone at 12 m, hidden behind walls, recomputed every 0.30 m, 48 changes per frame, face tolerance 0.075 m.
- When AR hands the root back, `updateProximity(nil)` restores everything. Done.

Locate pulse:

- Shown in AR (Locate in AR), not drawn in the viewer: a sphere of radius **0.05 m**, unlit teal `#0A6E78` at 0.8 opacity, at the centre of the element's box, a child of the root.
- Scale `1 + 0.35 · (0.5 − 0.5 cos 2πt / 1.2 s)`: 1 → 1.35 → 1 every **1.2 s** (`LocateGuide.pulseScale`, LocateGuide.swift:85-91).
- Android: BuildingScene.kt:413-436 and LocateGuide.kt:102-107. Done.

#### B.5 Viewer controls

**System chips** (`SystemChips`, Viewer3DView.swift:181-202; Android Viewer3DView.kt:219-238)

- One chip per system in the manifest, **architecture included in the viewer**. AR uses `serviceSystems` (no architecture and no structure).
- Order: the catalog order (`catalog.order(ofSystem:)`); unknown systems last; manifest order if there is no catalog.
- Title: the catalog name in the app language, else the humanized key (`water_heater` → "Water heater").
- Dot: the system's catalog colour. Selected = the system is in `viewerFilters.systems`. Tap toggles it.
- Analytics on toggle: `system_toggled {system, on}`.
- Hidden systems (outside the manifest scope) never appear.
- No "all / none" control, no long-press. Done.

**Subsystem chips:** **not in the viewer.** In AR's expanded See inside panel, one chip per subsystem of each *on* service system, titled with the subsystem name and coloured with the subsystem colour (falling back to the system colour); selected = not hidden. Android: the same (`SubsystemChips`, used only by SeeInsidePanel). Done.

**Storey / level menu** (`StoreyMenu` and `StoreyPicker`, Viewer3DView.swift:231-265; Android :261-305)

- A toolbar button labelled "Level", **disabled when the building has fewer than 2 storeys** (viewer only).
- It opens a menu with an inline picker:
  - **"All levels"** (viewer only; it sets `storeyID = nil`);
  - then each storey **from the top down** (`storeysTopDown`), titled by its name, or "–" when it has none;
  - a check mark on the current level.
- Choosing a level:
  - sets the storey;
  - **clears the room focus if that room is on another level** (BuildingSession.swift:430-437);
  - does **not** move the camera (use Fit).
- Android: `DropdownMenu` with a trailing accent check. Done. Menu item font: see A.3.

**Room focus** (`focusRoom`, BuildingSession.swift:441-454; chip :153-177)

- Comes from the Rooms list (`ViewerFocus.roomID`). It applies to **both** the viewer and AR. It:
  - clears `focusedElementID`;
  - sets `roomID` and the room's storey;
  - bumps `focusRevision`, so the camera frames the room's elements (B.6).
- Chip: an HStack (spacing 6) of
  - the SF Symbol `door.left.hand.open`;
  - the room name (the manifest space's `longName`, else `name`, so "Kitchen" over "R1"), or **"Room"** when it has neither;
  - `xmark.circle.fill` in muted.
- Chip style: SF subheadline medium, 12 h padding, min height 36, `accentSoft` fill, square, plain button.
- Accessibility hint: **"Shows the whole level again"**.
- Tap clears the room focus in the viewer **and** in AR. The storey stays, and so does the camera.
- Android: `RoomFocusChip` (Viewer3DView.kt:197-213), with icons `Outlined.MeetingRoom` and `Filled.Cancel` (18 dp) and `onClickLabel` = the hint. Done, except the font (Barlow instead of system, A.3).

**Fit to screen** (`fitRequest += 1`, Viewer3DView.swift:91-95): see B.6. Android: Viewer3DView.kt:116. Done.

**See in AR** (toolbar):

- Opens AR. While AR is up, the viewer keeps its scene claim, and the root comes back to the viewer when AR closes.
- On iOS the viewer's `OrbitViewerView` survives under the full-screen cover, so **the orbit pose is kept** when you come back from AR (OrbitViewerView.swift:102-104: "the model and the orbit pose carry over").
- Android: AR is a navigation destination (`BuildingNavigator.showAR`, app/BuildingDestinations.kt:134), so the viewer composable leaves composition. `ModelViewer` creates its `OrbitViewerView` with `remember { }` (viewer/ModelViewer.kt:39), so **a new camera is made on return and the view refits from the default angle**. **Differs.** Fix: hoist the `OrbitViewerView` into a `ViewModel` or the `BuildingSession`, keyed by the viewer's `ownerID`, or keep it with `rememberSaveable` and a custom saver for target, yaw, pitch, distance and `hasFitted`. The KDoc claim at OrbitViewerView.kt:48-49 ("the orbit pose survives… AR") does not hold today.

**See inside opacity** (AR only; for completeness, since it uses DetentSlider):

- Three detents: **Reality 0**, **Reality + model 0.5**, **Model 1**. Default 0.5 (`SeeInside.defaultValue`). Snap distance **0.06**.
- Opacities for slider value `s`: systems `min(1, 2s)`; architecture `max(0, 2s − 1)`. Architecture is in the AR filters only while its opacity is above 0 (BuildingSession.swift:470-480).
- A tick fires on the detent nearest the finger when a fast drag crosses two (`detentCrossed`, ModelFilters.swift:170-179).
- Accessibility label "Model opacity".
- Panel (`AR/SeeInsidePanel.swift`):
  - handle 36×5 in line colour; title "See inside" (ivHeadline); a chevron (`chevron.up` / `.down`, SF subheadline semibold, muted);
  - toggle labels "More filters" / "Fewer filters";
  - the slider; system chips; when expanded, subsystem chips and the room chip;
  - 16 padding, spacing 10, `.ultraThinMaterial` background.
- Android `features/.../ar/SeeInsidePanel.kt`:
  - Done for content and order;
  - **Differs:** surface at alpha 0.9 instead of blur (:50); the room chip is start-aligned instead of centred (:97-99); the slider look (A.4).

#### B.6 Camera

`OrbitCamera` (IVModelKit/Geometry/OrbitCamera.swift; Android modelkit/geometry/OrbitCamera.kt, ported with its tests). Pure math. Status: Done.

Model:

- A target point, **yaw** (radians about +Y; 0 puts the camera on +Z looking toward −Z), **pitch** above the horizon and a **distance** in metres.
- Position = target + (cos p · sin y, sin p, cos p · cos y) · distance.

| Constant | Value | iOS |
|---|---|---|
| Default yaw | 35° | :22 |
| Default pitch | 30° (a three-quarter view from above) | :23 |
| Pitch range | **−10° … 89°** (clamped) | :30 |
| Distance range | **0.3 m … 500 m** by default; after a fit, `0.3 … max(500, 4 × fitted distance)` | :31, :95-97 |
| Fit margin | 1.1 | :91 |
| Vertical FOV | 55° | OrbitViewerView.swift:54 |

Fitting:

- Distance = `max(radius, 0.1) / sin(narrowest FOV / 2) × 1.1`, where radius is half the box diagonal and the narrowest FOV is the smaller of the vertical and horizontal FOV (horizontal = `2·atan(tan(v/2)·aspect)`).
- Aspect = view width / height, or 0.5 when the height is 0.
- **First fit** (`fitIfNeeded`):
  - runs on every `sceneRevision` change until something has been framed;
  - frames `focusBounds ?? visibleBounds`, which is the room or element if one was chosen before the model loaded, else everything visible (the architecture chunk, the first to arrive);
  - resets yaw and pitch to the defaults;
  - **not animated**;
  - later chunks never refit on their own.
- **Fit to screen** (`refit`):
  - **keeps the current yaw and pitch**, re-centres on the visible bounds and sets the fitted distance (raising the max if needed);
  - **animated 0.35 s, ease in and out**.
- **Focus** (a room chosen, or "Show in 3D"): the same refit to `focusBounds`, animated:
  - "Show in 3D" frames the element's box;
  - a room frames the union of its elements' boxes;
  - otherwise everything visible.
- Visible bounds = the union of the visual boxes of enabled chunks, excluding inactive (filtered-out) entities, so a storey or room filter changes what Fit frames.
- Android:
  - The animation interpolates eye and target linearly with a quadratic ease in-out (OrbitViewerView.kt:152-162).
  - iOS moves the camera transform (position lerp plus rotation slerp) with a cubic `.easeInOut`. Visually equivalent. Done.

Gestures (OrbitViewerView.swift:190-243; Android `OrbitTouch`, OrbitViewerView.kt:277-353):

| Gesture | Effect | Constants | Status |
|---|---|---|---|
| One-finger drag (after the system slop: about 10 pt / `scaledTouchSlop`) | Orbit. A full-width drag turns **π (half a circle)** of yaw; a full-height drag turns **π/2** of pitch. Dragging right turns the model right (yaw −= dx); dragging down looks from higher up (pitch += dy). | none | Done |
| Two-finger drag | Pan: the model follows the fingers' centroid at any distance. Metres per view height = `2·d·tan(FOV/2)`; target −= right·dx·m, target += up·dy·m, with dx and dy as fractions of the view **height**. No limit on the target. | none | Done |
| Pinch | Zoom: distance /= the incremental scale (above 1 moves closer), clamped to the distance range. Recognised **together with** the two-finger pan. | none | Done |
| Tap (no movement) | Picks an element (B.7); empty space returns nil | none | Done |
| Double-tap | **Nothing** on iOS (no recogniser) | none | Done |
| Double-tap-and-drag | **Nothing** on iOS | none | **Differs:** Android's `ScaleGestureDetector` turns on "quick scale" by default (targetSdk > 19), so double-tap-and-drag zooms on Android, and the single-finger orbit runs at the same time. Fix: `pinch.isQuickScaleEnabled = false` (OrbitViewerView.kt:290). |
| Inertia / fling | **None:** the camera stops the moment the fingers lift | none | Done |
| Long-press | Nothing | none | Done |

- Every gesture updates the camera immediately (not animated). The view renders on demand.
- Non-interactive mode (`isInteractive = false`, the landing preview): the gestures are off and touches pass through to the scroll view (iOS `.allowsHitTesting(false)`, GuestLandingView.swift:105-111). Android uses a touch-transparent sibling (OrbitViewerView.kt:411-422). Done.

#### B.7 Picking and selection

Picking:

- iOS uses RealityKit `entity(at:)` against collision shapes made per element after its chunk loads (`addCollisions`, BuildingScene.swift:343-368; `PickingShape`, IVModelKit/Geometry/Bounds.swift:37-64). Disabled (filtered-out) entities are not hit. The nearest hit wins, architecture walls included. The hit entity is walked up to the nearest `e{hex}` element.
- Box shape: the element's visual bounds, with each axis **grown to at least 0.06 m** (`minimumSize`) so 16 mm conduits stay tappable.
- **Convex hull** (`ShapeResource.generateConvex(from: mesh)`) for **system elements** whose two larger sides are both **≥ 0.6 m** (`diagonalSide`) and whose smallest side is **< ¼ of the middle one**, that is, long diagonal runs. Their bounding box would cover empty space and steal taps from everything behind.
- Android (`BuildingScene.pick`, :463-492; `ScenePicking`, modelkit/scene/ScenePicking.kt):
  - casts a ray from the orbit camera through the tap and runs a slab test against each shown element's grown box in its own frame; the nearest wins.
  - **Differs:** the convex-hull case falls back to the grown box (ScenePicking.kt:17-19, 33-40). Filament keeps no CPU copy of meshopt-compressed meshes.
  - User effect: a long diagonal pipe or cable tray (for example a 3 × 3 × 0.05 box) captures taps across its whole bounding rectangle, hiding the elements behind it.
  - Fix options: keep the decoded positions for system chunks when the GLB is loaded (decode the meshopt buffers on the CPU once) and run ray-triangle tests for the `ConvexHull` case; or approximate with an oriented box along the run's main diagonal.

Selection flow (Viewer3DView.swift:56 and :109; ObjectCard.swift:377-406):

- A tap sets `selection = elementID`, or `nil` for empty space, which closes the card.
- When the selection changes in the viewer context: `scene.select(id)` highlights it (A.1) and `object_opened {kind, system}` is tracked.
- The card is a sheet with `.medium` and `.large` detents. In the viewer, the model behind it **stays interactive up to the medium detent**, so you can orbit and tap another element, which swaps the card.
- When the viewer disappears, it calls `session.select(nil)`.
- Android:
  - `ObjectCardSheet` with `ObjectCardContext.VIEWER` → `NonModalCardPanel`: 50 % height, or 92 % expanded by dragging or tapping the handle; dragging down from 50 % closes it; Back closes it (ObjectCard.kt:380-386, 425-500).
  - The highlight goes through `LaunchedEffect(selection)`.
  - Status: Done (the card's details belong to the object-card section).

"Show in 3D" (`focusElement`, BuildingSession.swift:710-725):

- turns the element's system on (if it is in scope);
- clears a room focus that doesn't contain the element;
- switches the storey to the element's storey if another one was selected;
- sets `focusedElementID`, bumps `focusRevision` (animated refit to the element) and opens its card.
- Android: the same (BuildingSession.kt:890; Viewer3DView.kt:180-194). Done.

Element names on the card (`ElementRecord.displayName`, ElementIndex.swift:28-37):

- catalog kind name + " " + tag ("Outlet K-04");
- else a readable IFC name ("South wall");
- else the kind name ("Pipe").
- IFC terms never show. Done (ported with its tests).

#### B.8 Scene ownership between the landing preview, the viewer and AR

- Claims are a stack; the last claim wins (`claimScene` / `releaseScene`, BuildingSession.swift:358-378). The owner's mode picks which filters the scene applies (viewer or AR).
- A `ModelViewer` attaches the root only while it is the owner: at the origin, with an identity transform, enabled.
- The viewer claims on appear and releases on disappear, **except while AR is open** (Viewer3DView.swift:98-108).
- Landing preview:
  - 240 tall, 16 h inset, square;
  - shown only once the architecture model is ready;
  - before that, the building thumbnail (fill-cropped) or a surface with a 48 pt light `house` symbol in line colour;
  - the loading bar overlays it bottom-leading, padded 12 inside and 12 outside, on `.ultraThinMaterial`.
- Android (`GuestLandingView.kt:175-211`, `Viewer3DView.kt:97-107`): Done, except the overlay is surface at alpha 0.85 instead of a blur.

#### B.9 Empty and failed states inside the viewer

| State | What shows |
|---|---|
| Before the first model | The plain 3D background, the loading bar at 0–N %, chips once the manifest is in (`.loading`); during `.connecting` no chips, because `systems` is empty |
| Some chunks failed | Their chips at 40 %, hint "Couldn't load"; the rest work |
| Every model failed (`.failed`) | No loading bar; the chips (dimmed) over an empty background. The viewer is normally not reachable then: BuildingHome and the landing show the problem state |
| Access ended mid-session (`accessProblem`) | `.accessGate(session)` replaces the viewer with the problem screen (other section) |
| A storey with nothing on it | Empty background; Fit does nothing (visible bounds nil) |

Android: the same conditions (Viewer3DView.kt:123-157, AccessGate). Done.

#### B.10 Performance targets

| Platform | Source | Targets |
|---|---|---|
| iOS | `docs/PLAN.md:17`, `:262`, M5-03 | iPhone 12 at **60 fps** with every Duplex system; XS acceptable at **30 fps**; measure time to landing and to the first 3D frame on LTE and 3G (no number set) |
| Android | PLAN §1 | Pixel 7a-class at **60 fps**; low-end ARCore phone at **30 fps** |

- iOS does nothing about memory warnings or hidden-chunk unloading yet (M5-03 open), so Android needn't either.
- Android: never measured (Device check). The checks to run are in `docs/device-test.md`: meshopt GLBs in gltfio, transparency, picking of long runs.

#### B.11 Accessibility summary

| Element | iOS | Android | Status |
|---|---|---|---|
| 3D view | Label "3D model" (single element) | `contentDescription = "3D model"` | Done |
| Landing preview | Hidden from accessibility | `clearAndSetSemantics {}` | Done |
| Toolbar buttons | "See in AR", "Level", "Fit to screen" | Same `contentDescription`s | Done |
| System chip | Name, "Selected", hint "Couldn't load" when failed | Name, selectable state, `stateDescription` "Couldn't load" | Done |
| Room chip | Name (or "Room") + hint "Shows the whole level again" | Same, through `onClickLabel` | Done |
| DetentSlider | Label "Model opacity", value = the nearest detent label, swipe between detents | Label and value; adjust does not snap to detents | Differs (A.4) |
| Wordmark | "Insite View", header | Same | Done |
| Reduce Motion | The fit animation ignores it (M5-02 open) | The same | Done |
| Touch targets | Chips and room chip 36; buttons 46; toolbar 44 | Chips 36; buttons 46; `IconButton` 48 | Done |

---

### Parity gaps, most important first

1. **Differs: the orbit pose is lost after AR.** `remember { OrbitViewerView() }` (`viewer/ModelViewer.kt:39`) plus AR as a navigation destination means returning from AR refits the camera from the default angle. iOS keeps the pose.
2. **Differs: picking long diagonal runs.** There is no convex hull on Android, so the grown bounding box steals taps from what's behind it (`ScenePicking.kt:17-19, 33-40`).
3. **Differs: double-tap-and-drag zooms on Android.** `ScaleGestureDetector` quick scale is on (`OrbitViewerView.kt:290`); iOS has no such gesture. Set `isQuickScaleEnabled = false`.
4. **Missing: icons in primary and secondary buttons.** `ActionLabel` (`Components.kt:103`) is text only. iOS shows SF Symbols in "See in AR", "Explore in 3D", "View in AR", "3D model", "Save this building", "Continue with email" and "Undo".
5. **Differs: line heights.** Android uses 1.1 / 1.3 / 1.35 × (`Typography.kt:36, 40, 50`); iOS uses the fonts' own 1.505 (Anton), 1.2 (Barlow) and 1.32 (Mono).
6. **Differs / Missing: top bar.** The title is start-aligned (iOS centres it); there is no large Anton title on the tab roots; there is no blur or hairline; and the icons are ink where iOS uses the accent tint (`ListParts.kt:54-69`).
7. **Differs: the DetentSlider look and accessibility.** It uses the M3-Expressive bar thumb and 16 dp track (iOS: a white round thumb and a 4 pt track); TalkBack adjust does not step between detents; no snap animation (`DetentSlider.kt:44-77`).
8. **Differs: system-font text.** Android uses Barlow where iOS uses SF system styles: slider labels, the room chip, menus, and 92 other `.footnote` / `.subheadline` / … uses.
9. **Differs (cosmetic):**
   - blurred materials are replaced by translucent surface (the viewer overlay at 0.92, See inside at 0.9, the preview loading bar at 0.85);
   - the viewer does not extend under the navigation bar;
   - chips show a ripple where iOS dims on press;
   - the wordmark gap scales with the font size.
10. **Device check:**
    - highlight glow (emissive exists only if SceneView's colour material has an `emissive` parameter, `SceneMaterials.kt:86-93`);
    - light intensity and exposure (the sun is at SceneView's default, not tuned to RealityKit's 2500);
    - catalog sRGB colours;
    - 60 fps.

## 8. AR experience

What the user sees and does from tapping "See in AR" to closing the camera. The iOS code is the
spec (`insiteview-ios` at 8bff80b; the only AR-area change since `docs/ios-ar-reference.md` was written
from da1d05d is 7ac7e5f, which removed "Not now" from the camera pre-prompt). The math (solver, smoother,
matching, sites, corrections, fade) is ported to `:modelkit` with the iOS tests and isn't repeated here.
This section gives only the rules a user can perceive: states, strings, timers, thresholds and priorities.

### Conventions

**iOS files** (root `insiteview-ios/Packages/InsiteViewKit/Sources/`):
AEV `IVFeatures/AR/ARExperienceView.swift` · PA `IVFeatures/AR/PointsAlignment.swift` ·
PRF `IVFeatures/AR/PlateRegistrationFlow.swift` · SIP `IVFeatures/AR/SeeInsidePanel.swift` ·
PRE `IVFeatures/Onboarding/ARPreflightViews.swift` · MT `IVFeatures/Diagnostics/MarkingTestView.swift` ·
AAV `IVAR/ARAlignmentView.swift` · PAN `IVModelKit/Geometry/PlateAnchoring.swift` ·
REF `IVModelKit/Geometry/ReferenceAlignment.swift` · PAL `IVModelKit/Geometry/PlateAlignment.swift` ·
LG `IVModelKit/Geometry/LocateGuide.swift` · RC `IVModelKit/Geometry/RoomCorrections.swift` ·
BWF `IVModelKit/Geometry/BehindWallFade.swift` · PR `IVModelKit/Geometry/PlateRegistration.swift` · OC `IVFeatures/ObjectCard/ObjectCard.swift` ·
GO `IVCore/GuestOnboarding.swift`.

**Android files** (root `insiteview-android/`):
kAEV `features/src/main/kotlin/com/getinsiteview/features/ar/ARExperienceView.kt` ·
kRules `features/.../ar/ARExperienceRules.kt` · kPA `features/.../ar/PointsAlignment.kt` ·
kPRF `features/.../ar/PlateRegistrationFlow.kt` · kSIP `features/.../ar/SeeInsidePanel.kt` ·
kGate `features/.../ar/ARCoreGate.kt` · kPRE `features/.../onboarding/ARPreflightViews.kt` ·
kMT `features/.../diagnostics/MarkingTestView.kt` · kAAV `ar/src/main/kotlin/com/getinsiteview/ar/ARAlignmentView.kt` ·
kScene `ar/.../ARAlignmentScene.kt` · kLogic `modelkit/src/main/kotlin/com/getinsiteview/modelkit/ar/ARAlignmentLogic.kt` ·
kTypes `modelkit/.../ar/ARTypes.kt`.

**Parity:** **Done** = same behaviour and the same English text (checked string by string against
`features/src/main/res/values/strings*.xml`) · **Differs** = how it differs, at `file:line` · **Missing**.
"iPhone" becomes "phone" in Android strings (`strings_android.xml`), as decided in Android `docs/PLAN.md` §3.
No Android AR code has run on a device yet (Android PLAN, AND-M4-01/02).

**Visual tokens.** iOS AR overlays sit on blur materials (`.ultraThinMaterial` for the tag, chips,
coaching box and See inside; `.regularMaterial` for cards), with square corners and the blueprint palette
(accent `#E30532`/dark `#FF3B4E`, warn `#B45309`/`#F59E0B`, ink, muted, line). Android has no backdrop blur
for composables. It uses `Palette.surface` at 85 % (tag, chips, coaching box, buttons), 90 % (See inside)
and 95 % (cards). That substitution is accepted throughout (**Differs**, platform).
iOS AR body text uses **system** text styles (SF Pro: `.callout` 16, `.footnote` 13, `.caption` 12,
`.subheadline` 15), with `ivHeadline` (Barlow SemiBold 17) only for titles. Android uses Barlow at the same
sizes everywhere. This is **Differs** (typography), and the design owner should decide between Barlow and Roboto
for the iOS "system" styles. Sizes and weights match.

---

### 1. Entry points and exit

| # | Where | Control (exact) | What happens | iOS | Android | Parity |
|---|---|---|---|---|---|---|
| 1 | Guest landing | Primary button "See in AR" (the largest button). When the visit came from a plate, the footnote under it reads "Point your camera at plate %lld to line the model up with your room." | Opens AR | `Guest/GuestLandingView.swift:231-243`, full-screen cover 81-83 | `guest/GuestLandingView.kt:296-305` → `BuildingNavigator.showAR` | Done |
| 2 | Building home (members, saved) | Primary "View in AR" | Opens AR | `Building/BuildingHomeView.swift:61-65` | `building/BuildingHomeView.kt:144` | Done |
| 3 | Building home, Equipment row | Swipe action "Locate in AR" (`session.locate(id)`, then AR) | AR opens locating that element | `BuildingHomeView.swift:122-129` | An inline icon button (a11y "Locate in AR"), `BuildingHomeView.kt:189-195` | Differs (decided, PLAN §3 table) |
| 4 | 3D viewer toolbar | "See in AR" (icon + label) | Opens AR; the viewer keeps its scene claim until AR releases it | `Viewer/Viewer3DView.swift:80-85, 104-112` | Icon button, `viewer/Viewer3DView.kt:113` | Done |
| 5 | Object card (opened from a list or the viewer) | "Locate in AR" | The card closes first, then `locate(id)` and AR (`runPending`) | OC 196-209, 404-432 | `objectcard/ObjectCard.kt:204-217, 397, 412` | Done |
| 6 | Object card inside AR | "Locate" (scope icon) | Locates in place: pulse, arrow, chip | OC 405-407 | `ObjectCard.kt:210-212` (`ObjectCardContext.AR`) | Done |
| 7 | Profile tab (Debug builds) | Toolbar scope icon, a11y "AR marking test" | Marking device test (¶17) | `App/ProfileTab.swift:28-42` | `app/.../ProfileTab.kt:83-99` | Done |

**Presentation.** iOS uses a `fullScreenCover`. Android uses a navigation destination (`BuildingRoute.AR`,
`features/.../app/BuildingDestinations.kt:118-123, 179-182`; decided in PLAN §3). **Exit:** the ✕ button
(a11y "Close") calls `dismiss`. Android has two ways out: the ✕ button pops the destination, and so does the
**system Back** gesture, which iOS lacks (Back is not intercepted anywhere in AR). That is acceptable. On the
way out, AR ends any registration, points or fix, pauses the session, gives the building root back, clears
selection and locate (`session.select(nil)`, `session.locate(nil)`) and releases the scene (AEV 388-412;
kAEV 598-617). AR also closes by itself when `session.accessProblem` becomes non-nil (AEV 510-512;
kAEV 687-690), and the landing then shows why. iOS-only: closing AR in the App Clip offers the full app once
(`GuestLandingView.swift:93-98`). This is N/A on Android.

**"See in AR" on phones without ARCore.** Android shows the button anyway; tapping it gives
"AR isn't available on this phone" (¶2.1). This was decided in PLAN §6 ("An ARCore check first").

---

### 2. Preflight

The order on iOS (`ARPreflightStep.next`, GO 22-30) is: explainer if not seen → camera explainer if
`.notDetermined` → denied (with Settings if `.denied`, without if `.restricted`) → ready. The step is
re-read whenever the scene becomes active (AEV 506-508), for example after returning from Settings.
Android puts an **ARCore gate** in front of all of this (kAEV 691-693).

#### 2.1 ARCore gate (Android only, kGate)

| State | Screen | Strings | Buttons |
|---|---|---|---|
| Checking (up to 25 × 200 ms while ARCore reports a transient state, kGate 115-127) | Spinner on `Palette.background` | — | — |
| Supported, not installed or too old | Icon `ViewInAr` (warn, 72 dp), Anton 28 uppercase title, body 17 muted | "Get Google Play Services for AR" / "Insite View needs Google Play Services for AR to show the model in your room. It's free on Google Play." | [Install] (→ `ArCoreApk.requestInstall`, which shows Google's own install prompt and then Play), [Not now] |
| Install failed or declined | same | message "Google Play Services for AR couldn't be installed. Try again, or explore the model in 3D." | [Try again], [Not now] |
| Unsupported device | same | "AR isn't available on this phone" / "This phone can't show the model in your room. You can still explore it in 3D." | [Close] |
| Unknown error (e.g. offline on first check) | Goes on to the preflight; a real failure surfaces as the session-failed screen (¶3) | — | — |

After Play installs ARCore, returning to the app re-checks it (`ON_RESUME`, kGate 83-85) and AR continues.
Parity: no iOS counterpart. Decided in PLAN §6.

#### 2.2 First-use explainer ("Point · Align · Explore")

- Shown once per device (`hasSeenARExplainer`); "Skip" or "Start" sets the flag (AEV 69-72; kAEV 274-279).
- Layout (PRE 88-188): "Skip" top right (accent text), then a paging view with 3 frames and page dots,
  then a primary button "Next" ("Start" on the last frame). Each frame has an 88 pt light symbol in accent
  (140 pt box) that pulses repeatedly on the current frame, a title in Anton 34 uppercase (ink) and a body
  message (17, muted, centred, 32 pt side padding).
- The frames, in order:
  1. "Point": "Point your camera at the Insite View plate on the wall, or at the floor." (`qrcode.viewfinder`)
  2. "Align": "Hold still for a moment while the model lines up with your room." (`square.3.layers.3d.down.right`)
  3. "Explore": "See the pipes and wiring inside the walls. Tap one to find out what it is." (`eye`)
- Reduce Motion: no pulse, and "Next" jumps without animation.
- Accessibility: each page is one combined element. The symbols are hidden.
- **Android** (kPRE 101-205): HorizontalPager with custom dots, icons `QrCodeScanner`/`Layers`/`Visibility`.
  Reduced motion is read from the animator duration scale = 0. Each page has one content description,
  "Title. Message". **Done**. The pulse is an alpha pulse (1 → 0.35, 900 ms) instead of SF Symbols `.pulse`:
  acceptable.

#### 2.3 Camera pre-prompt and denied states

| Step | Icon | Title (Anton 28, uppercase) | Message (body, muted, centred) | Buttons |
|---|---|---|---|---|
| Camera explainer | `camera.viewfinder`, accent, 72 pt | "Allow the camera" | "Insite View shows the building's pipes and wiring over what your camera sees. Nothing is recorded or uploaded." | **[Continue] only** (primary). It triggers the system prompt; iOS's prompt text is `NSCameraUsageDescription` "Camera access is required for augmented reality." |
| Denied | `camera.badge.ellipsis`, warn | "Camera access is off" | "Turn on Camera for Insite View in Settings to see the model in your room. You can still explore it in 3D." | [Open Settings] (primary, the app's settings page), [Not now] (secondary, closes AR) |
| Restricted (iOS only) | same | same | "The camera is restricted on this iPhone. You can still explore the model in 3D." | [Not now] |

iOS PRE 190-270. "Continue" became the only action in 7ac7e5f: App Review guideline 5.1.1(iv) rejects a
pre-prompt that lets the user put off the request. The status bar is visible in the preflight and hidden once AR is ready (AEV 513).

**Android parity**

- **Differs (most visible preflight gap):** the pre-prompt still has "Not now" under "Continue", at
  kPRE 255-256. To match iOS, remove it; system Back still leaves. Google Play has no such rule, so this is a
  parity choice, but the spec is iOS.
- **Differs (intentional):** Android has no "restricted" state, so that string is unreachable. "Don't ask
  again" maps to Denied with Settings (kRules 401-406).
- **Differs (Android-only flow):** after a first denial that the system will still ask about (a rationale
  is shown), Android goes back to "Allow the camera" rather than "Camera access is off". The second denial
  shows Denied. On each *new* AR screen after a permanent denial, `askedThisScreen` is false, so the user first
  sees "Allow the camera". "Continue" then returns denied without a dialog, and only then does
  "Camera access is off" appear: one extra tap compared with iOS (kPRE 73-77, kAEV 706-711). Fix: persist
  "denied after asking" in preferences and pass it as `askedThisScreen`.
- Settings return: `ON_RESUME` re-reads the permission (kAEV 714). **Done**.

#### 2.4 Safety note (first AR use, and from ⋮)

- A card overlay (not a system sheet, so the object card can still open over the camera), centred, with 24 pt
  outer padding and 20 pt inner padding on `.regularMaterial` (AEV 553-557, 1173-1198).
- Content: a ⚠ `exclamationmark.triangle.fill` in warn, then "Before you drill or cut" (ivHeadline), then
  "The model shows the design. Always confirm before drilling or cutting." (body), then [Got it] (primary).
- Shown when AR starts if `hasSeenSafetyNote` is false. "Got it" sets the flag. ⋮ → "Safety note" shows it
  again. It fades in and out.
- VoiceOver: `.accessibilityAddTraits(.isModal)`, so focus stays in the card.
- **Android** (kAEV 1462-1480, 821-823): the same strings, layout and opacity animation. The flag is written
  on a process-wide scope, so closing AR right after "Got it" still saves it (kAEV 155-156, 652-657).
  **Differs (a11y):** only `isTraversalGroup`, not modal, so TalkBack can move to controls behind the card
  (kAEV 1470). Fix: `Modifier.semantics { paneTitle = … }` plus hiding the other layers from accessibility
  while the card shows.

---

### 3. Opening sequence and session lifecycle

1. **Scene claim** (AEV 114-136): AR takes the building scene (`claimScene(.ar)`), and the 3D viewers let go
   of their ARViews. Until they're gone, or **1 s** at most (polled every 50 ms), the screen is **black**.
   Reason: a living `.nonAR` ARView turns the camera feed black.
   - Android (kAEV 741-752, 316-331): the camera starts once this destination is `RESUMED` plus 2 frames
     (the previous SceneView has gone), with no fixed wait. **Differs (decided, PLAN §3).** Device check: no
     black feed.
2. **`start()`** when the camera view appears (AEV 138-155; kAEV 333-348): it records `openedAt` (the
   4 s offer), chooses the starting storey (¶10), sets the floor elevation, refreshes rooms, configures plates
   if the manifest is present, attaches the root (hidden), runs the session, points locate at its target,
   selects the element being located, tracks `ar_opened`, and shows the safety card if not seen. **Done**.
3. **The screen stays awake** while AR runs (`isIdleTimerDisabled`, AAV 362/369). Android uses
   `view.keepScreenOn` plus SceneView's lease (kAEV 835-843). **Done**.
4. **Status bar hidden** (AEV 513). Android hides only the status bar, not the navigation bar
   (kAEV 846-856). **Done**.
5. **Before the manifest arrives** the coaching line is "Loading the model…". The anchoring has no plates
   yet, so it is "manual", and the floor coaching overlay may show (¶6.7).
6. **Manifest arrives** → `configurePlatesIfNeeded` (once, AEV 157-170; kAEV 366-375): plates are configured
   (each placed plate's PNG downloads and detection starts as each one arrives), and if **no plate is placed
   and some room has an outline, marking points starts at once** (¶8). With no plates and no outlines: floor
   placement (¶7).
7. **Interruption** (phone call, app switch). iOS: `sessionWasInterrupted` → tag "Finding your place
   again…", and the alignment is marked **lost at once**. On return: relocalizing, then on normal tracking
   the alignment is restored where it was (AAV 1198-1224).
   - Android: activity pause → `sessionInterrupted` (lost at once); resume → relocalizing until `TRACKING`
     (kAAV 240-254, kLogic 1049-1060). **Done.** Note that on Android *anything* that pauses the activity
     interrupts AR. That includes opening a document from the object card (`ACTION_VIEW`), whereas iOS's
     QuickLook keeps ARKit running. Device check.
8. **Session failure (Android only)**, for example the camera is unavailable or ARCore throws a fatal error:
   a full-screen message "AR isn't available right now" / "AR couldn't start. Try again, or explore the model
   in 3D." with [Try again] (a new session, alignment starts over) and [Not now] (kAEV 772-779, 350-360).
9. **Rotation.** iOS declares no orientation restriction (no `UISupportedInterfaceOrientations` in
   `project.yml`, so the system default applies) and ARKit keeps the session through a rotation.
   **Missing on Android:** `MainActivity` neither locks the orientation nor handles `configChanges`
   (`app/src/main/AndroidManifest.xml:35-39`). A rotation recreates the activity, and the AR model is only
   `remember(session)` (kAEV 681). The ARCore session, the alignment, the marks and any registration are
   lost, AR restarts from scratch, and a second `ar_opened` fires. Fix: survive the configuration change in the
   AR destination, or lock AR to portrait and document that in PLAN §6. **This is the most important gap in
   this area.**

---

### 4. Screen layout (state "ready")

The z-order, bottom to top (AEV 518-577; kAEV 770-825):

1. Camera view (full screen, ignores safe areas).
2. A column inside the safe area, 16 pt horizontal and 8 pt vertical padding, 12 pt spacing: **top bar**,
   **locate chip** (when locating), spacer, **bottom panel**.
3. **Locate arrow** (edge of the screen, off-screen targets only).
4. **Crosshair** (centred on the *full* screen, not the safe area), only while aiming (¶8.3).
5. Android only: the **floor coaching card**, centred (iOS: Apple's `ARCoachingOverlayView`, a subview of the
   ARView, so *under* the SwiftUI layers). See ¶6.7.
6. **Safety card** (¶2.4).
7. The object card (a sheet, medium/large detents) when an element is tapped.

**Top bar** (AEV 581-606; kAEV 876-894): on the left a ✕ in a 44 pt circle (headline-size glyph, blur
material, a11y "Close"); the **status tag** centred between flexible spacers (footnote medium 13, centred,
12/8 padding, `.ultraThinMaterial`, square); on the right ⋮ (`ellipsis`, 44 pt circle, a11y "More").
Android: the same, but the tag is capped at `widthIn(max = 240.dp)` (kAEV 887), which iOS doesn't do, so long
tags such as "Point at a surface with more detail" or "Aligned at plate 12 · Electrical panel" wrap sooner.
**Differs (minor).**

**Bottom panel**, by priority, exactly one (AEV 747-765; kRules 298-304): registration panel → points panel →
Fix-here card → room-save card → standard panel. **Done**.

---

### 5. Status tag (first match wins)

AEV 608-638; kRules 160-184, kAEV 896-911. **Done** (all 12 strings match).

| # | Condition | English |
|---|---|---|
| 1 | tracking excessive motion | "Move more slowly" |
| 1 | tracking insufficient features (Android also: insufficient light) | "Point at a surface with more detail" |
| 1 | tracking relocalizing or interrupted | "Finding your place again…" |
| 1 | tracking not available (Android: ARCore STOPPED, bad state, camera unavailable) | "AR isn't available right now" |
| 2 | marking by points (`points != nil`) | "Aligning by points" |
| 3 | anchoring phase lost | "Alignment lost" |
| 4 | method points and phase manual | "Aligned by points" |
| 5 | aligned on a plate | "Aligned at plate %lld · %@" (number · label; an empty label leaves "· " at the end on both) |
| 6 | phase searching | "Looking for a plate" |
| 7 | placement finding the floor or ready to place | "Not placed yet" |
| 7 | placement adjusting | "Placed manually · adjusting" |
| 7 | placement locked | "Placed manually" |

Quirks copied on purpose (kRules 177-178): during ⋮ Re-align after an alignment by points, the phase is
realigning, so the tag falls through to "Placed manually". During an automatic re-anchor beyond 4 m the
tag keeps "Aligned at plate N · …". Tracking initializing shows no tracking message.
Android's mapping of ARCore states (kTypes 55-65) is: `TRACKING` → normal, `STOPPED` → not available, after a
resume → relocalizing, `PAUSED` with `EXCESSIVE_MOTION` → excessive motion, with `INSUFFICIENT_FEATURES` or
`INSUFFICIENT_LIGHT` → insufficient features, with `BAD_STATE` or `CAMERA_UNAVAILABLE` → not available, and
with no reason → initializing.

---

### 6. Standard panel

The layout is a vertical stack with 12 pt spacing (AEV 767-807; kAEV 1059-1107).

#### 6.1 Unplaced-plate card (admins)

Shown when `canManagePlates` holds, the scanned plate exists, and it isn't in the manifest. The card reads
"Plate %lld isn't in the model yet." (callout medium) with a primary [Register its position], which starts
registration preselecting that plate (¶14.1). It sits on 12 pt padding, `.ultraThinMaterial`, full width
(AEV 968-983; kAEV 1153-1166). **Done**.

#### 6.2 Coaching box

A full-width box with 12 pt padding: the coaching line (callout medium 16, centred), then a coaching action
link below it with 8 pt spacing (AEV 772-782).

Coaching line priority (AEV 985-1020; kRules 197-223, kAEV 1109-1122). **Done** (Android strings say
"phone" where iOS says "iPhone"):

| Order | Condition | English |
|---|---|---|
| 1 | manifest not loaded | "Loading the model…" |
| 2 | points, registration or Fix here active | (no box) |
| 3 | plates searched (not manual): coaching point at scanned plate | "Point your camera at the Insite View plate you scanned (plate %lld · %@)." |
| | point at any plate | "Point your camera at an Insite View plate." |
| | hold still (a plate's smoother has samples, while searching or a requested re-align) | "Hold still…" |
| | alignment lost | "Alignment lost. Point at a plate again, or move slowly so your iPhone finds its place." |
| | otherwise: requested re-align | "Point your camera at an Insite View plate." |
| | otherwise: aligned / not aligned | "Tap a pipe, cable or fixture to see what it is." / "Point your camera at an Insite View plate." |
| 4 | manual, phase lost | "Alignment lost. Move slowly so your iPhone finds its place, or place the model again." |
| 5 | manual, finding the floor | "Move your iPhone slowly to find the floor." |
| | ready to place | "Tap the floor where you're standing." |
| | adjusting | "Drag to move and twist to turn until the model matches the room." |
| | locked | "Tap a pipe, cable or fixture to see what it is." |

#### 6.3 Coaching action: "Model off?" and the 4 s "No plate?" offer

AEV 1022-1055; kRules 225-252, kAEV 1124-1151. The link is footnote semibold in accent:

- If `canFixHere` → **"Model off? Fix here"** (→ Fix here, ¶9).
- Otherwise, if plates are being searched for (not manual) and (not aligned, or lost), the condition is
  re-evaluated **every 1 s** (iOS `TimelineView`; Android a 1 s loop while the offer is pending). When more
  than **4 s** have passed since AR opened (`openedAt` = `start()`), or **at once when lost**:
  - **"No plate? Align by points"** (→ marking, ¶8) if any room has an outline;
  - else **"Place manually instead"** (→ manual placement, ¶7).
- **Done** (monotonic clock on Android, kAEV 152-153).

#### 6.4 Automatic fallback (no link needed)

If the phase is searching for **10 s** since the anchoring was configured (that is, since the manifest
arrived, not since AR opened) and no plate was detected in the last 1 s, it falls back to manual
(PAN `tick`, `fallbackDelay` 10). If points are possible and not already active, marking starts
automatically. Otherwise the placement state becomes "finding the floor" or "ready to place", unless the user
is already marking (AEV 442-446, AAV 550-554; kAEV 646-648, kLogic 495-498). Detection keeps running: a plate
seen later still aligns and replaces the fallback alignment. **Done** (`FALLBACK_DELAY = 10.0`).

#### 6.5 Adjusting controls (manual, adjusting)

[Fine-tune] (secondary, with the `slider.horizontal.3` icon on iOS; Android has text only) and [Done]
(primary): Done → `lock()` and hides the pad. Above them, the **Fine-tune pad** when toggled on (¶7.3)
(AEV 784-802; kAEV 1086-1102). **Done** (Android drops the button icon, which is minor).

#### 6.6 See inside

Shown otherwise, once aligned or once the manifest is loaded (AEV 803-805; kRules 309-311). See ¶12.

#### 6.7 Floor coaching (ARKit's coaching overlay)

- iOS: `ARCoachingOverlayView`, goal horizontal plane. It may activate only when the anchoring is manual,
  nothing is placed yet and the user isn't aiming (AAV 276-281, 563-571). This is Apple's own animated
  full-screen overlay with Apple's localized text; it hides itself once a plane is found and can return when
  tracking degrades. In practice it shows: before the manifest; in no-plate buildings without outlines before
  the first tap; after "Place manually" without an alignment; after "Place again".
- Android (kLogic 270-282, 508-519; kAEV 815-819, 1482-1500): a centred card (spinner + "Move the phone slowly
  over the floor", 15 sp medium, surface 85 %, 32 dp side margins) under the same gating. It shows while
  there is no horizontal plane yet or tracking isn't normal. **Differs (decided, PLAN §3).** A static
  card replaces Apple's animation. It is drawn *above* the panel layer while iOS's overlay sits under the
  SwiftUI chrome, and it is centred, so it can sit near the crosshair area. That's acceptable, since the
  crosshair never shows while it does.

---

### 7. Alignment states in order (plates → manual)

The user-visible state machine. The anchoring phases (PAN) are searching / manual / aligned /
realigning(requested) / lost; the placement states (AAV) are finding floor / ready to place / adjusting /
locked.

#### 7.1 Plate coaching and searching

- Phase searching (plates placed). The tag reads "Looking for a plate". The coaching line asks for the
  scanned plate ("…plate you scanned (plate 2 · Electrical panel).") if it is placed, else "Point your camera
  at an Insite View plate."
- Detection uses every placed plate's PNG at its printed width `sizeMm / 1000` m (quiet zone included).
  While marking points, placed plates are **not** detected (the marks decide); the plate being registered is
  always watched.
- **Hold still.** As soon as the plate's smoother has a sample, the line becomes "Hold still…". It aligns
  after 10 consistent detections (≥ 6 within 3 cm / 2°). Images are tracked every frame, so this takes a
  fraction of a second once the plate is held in view.

#### 7.2 Aligned on a plate

- The building **glides 0.5 s** (smoothstep) onto the plate, the tag reads "Aligned at plate N · label",
  the coaching line reads "Tap a pipe, cable or fixture to see what it is.", a **success haptic** fires,
  detection turns off (to save power), and `ar_aligned{method: plate, ms}` is sent if this is the first
  alignment of this anchoring. The placement state becomes locked, so taps open object cards right away.
- Android: the same rules. ARCore may track several images at once, and only `FULL_TRACKING` poses feed
  the smoother (kAAV 319-327). **Done (decided)**. Detection is turned off by rebuilding the
  `AugmentedImageDatabase` without the plates (kAAV 173-192). Device check: that rebuild must not stall the
  camera.

#### 7.3 Manual placement and fine-tune

Entered through the fallback with no outlines, "Place manually instead", ⋮ "Place manually", ⋮ "Adjust
placement" or ⋮ "Place again" (¶13).

- **Finding the floor**: "Move your iPhone slowly to find the floor." plus the floor coaching. The tag
  reads "Not placed yet".
- **Ready to place** (a horizontal plane was found): "Tap the floor where you're standing." A tap puts the
  current storey's floor on the hit, with the model's horizontal centre there, turned to face the camera.
  → **Adjusting**.
  - iOS raycasts existing plane geometry, then `estimatedPlane`. A tap can therefore place even while still
    "finding the floor" (AAV 577-586).
  - Android (kAAV 506-514): upward planes inside their outline, then beyond it, then a depth point facing up.
    Depth is only enabled on ToF phones. **Differs:** on ordinary phones a tap does nothing until ARCore
    has a plane, so there is no early placement. The text already asks the user to wait.
- **Adjusting**: "Drag to move and twist to turn until the model matches the room." The tag reads
  "Placed manually · adjusting".
  - One finger drags the building along its placed floor.
  - Two fingers twist it; clockwise on screen turns it clockwise seen from above, about the floor point
    between the fingers (AAV 1067-1090; kScene 79-150; the touch slop is the system's).
  - **Done.**
- **Fine-tune pad** (AEV 1130-1171; kAEV 1387-1460): a caption "Each tap moves %@ or turns %@." (caption 12,
  muted; the values are 1 cm and 0.5° in the user's units). Below it, two rows of four 52×44 pt square buttons
  on `surface` at 80 %:
  - top row: Turn left ↺ · Move away ↑ · Turn right ↻ · Raise ⌃;
  - bottom row: Move left ← · Move closer ↓ · Move right → · Lower ⌄.
  - Each tap moves 1 cm (relative to where the camera looks, flattened) or turns 0.5° about the floor point
    at the screen centre.
  - The buttons **repeat while held** (iOS `.buttonRepeatBehavior(.enabled)`, system timing; Android: one step
    on press, then every 80 ms after 400 ms). Accessibility labels are the eight names; TalkBack and VoiceOver
    activate one step.
  - **Done** (repeat timing approximated).
- **[Done]** → **Locked**. The tag reads "Placed manually" and the line "Tap a pipe, cable or fixture…". A
  success haptic fires, and `ar_aligned{manual}` is sent if this is the first alignment.

#### 7.4 Re-align and automatic re-anchoring

- ⋮ **Re-align** (when there are plates): detection comes back on, the building stays where it is, and the
  line reads "Point your camera at an Insite View plate." (or "Hold still…"). A converged plate replaces the
  whole alignment with a 0.5 s glide and a success haptic. After **15 s** with nothing seen, it goes back
  to the previous state.
  - iOS removes the image anchors so ARKit reports them again (AAV 509-513).
  - Android rebuilds the image database (`forgetImageDetections`, kAAV 194-200).
  - **Done (decided)**.
- **Automatic re-anchor**, plates only: once the camera is more than **4 m** from where it aligned,
  detection turns back on silently and a plate seen there adds a local alignment. Its only visible signs are
  the glide and the success haptic. After the 15 s timeout it immediately re-enters (a known iOS behaviour;
  copied).

#### 7.5 Lost and relocalizing

- Lost: on interruption at once; otherwise **2 s** after tracking becomes relocalizing or not available, if
  an alignment exists. Excessive motion and insufficient features never count as lost
  (AAV 240, 1155-1158, 1198-1224; kLogic 975-982, 1033-1060).
- While lost, the building stays drawn at its last transform. The tag reads "Alignment lost" (or the
  tracking message, which takes priority), and the coaching line is the "Alignment lost…" text for plates or
  for by-hand placement.
  - For plates, "No plate? Align by points" / "Place manually instead" appears **at once** (¶6.3).
  - When tracking is normal again the alignment is restored (aligned or manual), with no haptic.
- **Done.** Device check: ARCore usually reports `PAUSED/INSUFFICIENT_FEATURES` rather than a
  relocalizing state when the camera is covered, so, as on iOS, covering the camera does **not** lose the
  alignment. Only an activity pause does. The iOS device-test row "cover the camera … > 2 s: Alignment
  lost" contradicts the code on both platforms (reference §10.1).

---

### 8. Alignment by reference points

PA, AEV 188-212, AAV 657-682, 703-810; Android kPA, kAEV 395-422, kLogic 597-742.

#### 8.1 Start

Points start in any of these ways:

- automatically, when there are no placed plates (¶3.6);
- after the 10 s fallback (¶6.4);
- from "No plate? Align by points";
- from ⋮ "Align by points";
- from registration (¶14.1).

The room the camera is in under the current alignment is worked out *before* the building hides. Then
marking starts:

- The building **hides** and the floor coaching turns off.
- Session settings go lean: no environment texturing (no light estimation on Android) and no plate
  detection. Vertical planes are added with LiDAR / ToF.
- Chunk loading pauses while marking (`holdsSceneWork`) and comes back for the fit preview.
- The tag reads "Aligning by points".

#### 8.2 Room picker

Shown if no room was preselected. The preselection order is: the room the camera is in (if it has an
outline), else the room chosen in ⋮ Room, else the only room with an outline (PA 55-66; kRules 368-373).

- The card reads "Which room are you in?" (ivHeadline), then a scrollable list (max height **240 pt**) of
  secondary buttons with the room names, or "Unnamed room", then a "Cancel" link (footnote semibold,
  accent) (PA 418-446; kPA 496-509).
- Choosing a room switches AR to **that room's level** and starts marking. **Done**.

#### 8.3 Marking

The panel is `.regularMaterial`, 16 pt padding, 12 pt spacing (PA 488-524; kPA 571-605).

- **Room line**: a menu with the door icon and the room name (subheadline semibold, accent). Opening it lists
  the eligible rooms; choosing one restarts marking in that room. A11y hint "Change room".
- **Counter** on the right: "%lld of %lld" (marks so far, capped at the number needed, then the number
  needed; subheadline, monospaced digits, muted).
  - A long press copies the details (¶8.8) and shows "Details copied" for **2 s**.
  - Android uses JetBrains Mono 14 in place of the monospaced-digit system font, and a `combinedClickable` with
    a long-click action (kPA 555-567). **Done.**
- If the crosshair can aim (or a capture is running):
  - the **instruction** (callout, ¶8.4);
  - [Undo] (secondary, ↶; disabled with no marks or while capturing);
  - [Mark] (primary; it reads "Hold still…" while capturing, and is disabled unless the crosshair can mark).
- Otherwise the **finding row**: a spinner and "Finding the floor. Move the phone slowly, pointing at the
  floor." when there's no floor yet, else "Finding your place. Move the phone slowly around the room."
  (callout) (PA 370-389; kPA 452-468).
- A "Cancel" link restores the previous alignment, if any, else floor placement with the building hidden.
- **Mark** (PA 148-160; AAV 703-737): the crosshair is averaged for **500 ms** ("Hold still…" on the button
  and on the crosshair label).
  - The mark fails if the aim was lost in any frame, if there are fewer than 5 samples, or if fewer than 5
    samples stay within 2 cm of the median (1.5 cm / 2° for wall and corner aims). A failure shows the
    "unsteady" instruction.
  - A success drops a **green disc** (6 cm, unlit `systemGreen` `#34C759`, 3 mm above the floor or 3 mm out
    from the wall) and gives an **impact haptic**. The haptic fires on every change of the mark count, so undo
    and start over trigger it too.
- **Marks needed:** 3, or 2 once a wall mark is among them (LiDAR/ToF).

**Crosshair overlay** (PA 309-368; kPA 399-446): a 36 pt circle, 2 pt stroke, **accent when it can mark**,
else white, with a white "+" (18 pt semibold) and a black shadow at 50 % opacity, radius 2. It sits on the
exact centre of the full screen.

- The label hangs **64 pt below the reticle's top**: caption semibold 12, blur background, single line.
- It shows only while (marking, room chosen, step marking, and it can aim or is capturing) or (Fix here and it
  can aim or is capturing). It is hidden from accessibility, and so is the 3D floor marker (a faint 12 cm
  white circle at 35 % with a 1.2 cm dot, 2–3 mm above the aim point, standing on the wall for wall aims;
  AAV 905-916).
- **Done**; strings match. Device checks:
  - the Android reticle shadow is an elevation shadow on a transparent box (kPA 422) and may render as a dark
    disc;
  - the faint circle uses an unlit colour instance with alpha 0.35 (kAAV 592-602, 697) and may render opaque
    if the material isn't transparent.

| Crosshair state | Rule (perceivable) | Label |
|---|---|---|
| capturing | — | "Hold still…" |
| no floor | no floor plane yet (even for wall aims) | "Move the phone slowly over the floor" |
| not tracking | tracking not normal | "Move the phone slowly around the room" |
| ready | the centre ray is within **35°** of straight down and meets the floor | "On the floor" |
| too shallow | steeper than 35° and no wall aims (no LiDAR/ToF, or hot) | "Point the phone more straight down" |
| no surface | wall aims on, no wall under the centre | "Aim at a wall, a corner, or down at the floor" |
| on corner | two walls 45–135° apart within 10 cm, found with side rays ±18 cm | "On a corner" |
| on wall | a wall within 20° of vertical; doors and windows skipped on iOS (ARCore has no such classes) | "On a wall" |

"Can aim" excludes no floor and not tracking. "Can mark" means ready, on wall or on corner. The crosshair
updates at 15 Hz, and every frame while capturing.

#### 8.4 Instruction text (first match)

PA 526-553; kRules 348-361, kPA 610-633. **Done.**

| Case | No LiDAR / ToF | With LiDAR / ToF |
|---|---|---|
| unsteady mark | "Couldn't hold the mark steady. Keep the circle on the corner and try again." | "Couldn't hold the mark steady. Keep the circle on the same wall or corner and try again." |
| room has no corners | "This room has no corners in the model." | same |
| marks match nothing | "These corners don't match %@. Check the room, or undo the last corner." | "These marks don't match %@. Check the room, or undo the last mark." |
| need more | "Mark one more corner." | "Mark one more corner or wall." |
| collinear, too close (spread < 1 m) or parallel walls | "Mark corners further apart, on different walls." | "Mark a corner, or a wall that crosses the others." |
| default | "Stand by a floor corner, point the phone down at it with the circle on the corner, and tap Mark." | "Aim at a wall or a corner of the room, at any height, or down at a floor corner, and tap Mark." |

The text variant gates on `hasLiDAR` alone, not on heat, so a hot LiDAR phone invites wall aims that the
crosshair refuses (copied on Android). Android: `hasLiDAR` is a static set from the camera and confirmed when
the session starts (kAAV 676-694). It isn't Compose state, so a ToF phone whose ARCore lacks raw depth may
show the wall texts until the next recomposition. That's minor.

#### 8.5 Matching outcomes

Matching runs after each mark once the number needed is reached:

- **needs more** → stays in marking with a hint;
- **no match** → stays in marking with the message;
- **matched** → **Checking**, with the building **shown** at the fit (preview);
- **ambiguous** (the room fits up to 4 ways, for example a symmetric room) → **Choosing**, with option 1
  previewed.

#### 8.6 Checking and choosing

**Checking** (PA 557-587; kPA 635-647):

- the room line;
- "Does the model line up with the room?" (ivHeadline);
- the summary, subheadline:
  - "Matched %lld corners · average error %@", or "Matched %lld marks · average error %@" when a wall matched;
  - fair fit (rms between 3 and 6 cm): "Fair fit: check that the model lines up before relying on it."
    (footnote, warn);
  - approximate fit (rms > 6 cm): "Approximate fit: it may be off by about %@. Check that the model lines up
    before relying on it." (warn);
  - a dropped mark: "One corner was left out: it didn't match the project." (muted);
- [Start over] (clears the marks, discs and preview) and [It matches] (confirm);
- the link "Mark another corner" ("Mark another" with LiDAR/ToF), which returns to marking with the marks kept.
- **Done.**

**Choosing** (PA 589-618; kPA 649-671):

- "The room fits more than one way. Which one lines up?";
- a segmented control "Option 1"…"Option n" (iOS accessibility label "Fit"); each selection previews that fit;
- [Start over] and [This one].
- **Done** (Android `SingleChoiceSegmentedButtonRow`; it has no "Fit" label, which is minor).

#### 8.7 Confirm

The building stays where the fit put it. The tag reads "Aligned by points" and the line "Tap a pipe, cable
or fixture…". The success haptic fires, and `ar_aligned{points}` is sent if this is the first alignment.
Plate detection resumes, and a plate seen later replaces the points alignment and all its local alignments.
Chunk loading resumes (AEV 205-212; kAEV 413-422). **Done.**

#### 8.8 Floor changes while marking, and copy details

- When ARKit or ARCore refines the floor by **≥ 5 mm** (up to 15 cm), the discs follow it.
  - In Checking and Choosing the fit is refitted and the preview moves.
  - In Marking there is no re-solve.
  - Android fixes the iOS bug where wall discs lost their normal offset on a floor change
    (kLogic 670-683; decided).
- After confirming, the "floor glue" raises the alignment by points when the floor moves 0.5–15 cm (with a
  0.5 s glide).
- **Copy details** JSON (PA 259-306; kRules 415-503) holds:
  - `room` {id, name, hasOutline, floorY};
  - `worldFloorY`;
  - `floorPlanes` [{y, areaM2, isFloor}], sorted by height;
  - `cameraY`;
  - `references` [{id, kind, position}];
  - `marks` [{surface, position, marked, seenFrom}];
  - `outcome`.
  - Values are rounded to the millimetre, keys are sorted and the output is pretty-printed.
  - **Done.** The `outcome` string is Kotlin's `toString()` of the outcome rather than Swift's
    `String(describing:)`, so reports from the two apps differ in that one field. That's minor.

---

### 9. Fix here

AEV 214-280, 809-870; kAEV 424-493, 1168-1225.

- **Offered** when AR has started, nothing else is on screen (no points, registration or fix), and the
  anchoring can fix: phase manual with method points, or phase aligned with method plate. It is not offered
  while re-aligning or lost. The entries are ⋮ "Fix here" and the coaching link "Model off? Fix here".
- **Start**: the crosshair shows and the building stays visible. With LiDAR/ToF, vertical planes are added.
  Chunk loading pauses.
- **Card** (`.regularMaterial`, 16 pt):
  - If it can aim, the instruction (callout):
    - no problem, LiDAR: "Model off? Aim at the corner or wall nearest you, with the circle on it, and tap Fix.";
    - no problem, without: "Model off? Point the phone down at the floor corner nearest you, with the circle on
      it, and tap Fix.";
    - unsteady: "Couldn't hold the mark steady. Keep the circle on the corner and try again.";
    - not a corner: "That isn't near a corner of the model. Aim at the corner nearest you.";
    - not a wall: "That isn't near a wall of the model. Aim at the wall nearest you.";
    - then the footnote (secondary): LiDAR "A wall also straightens the model; so does a second corner of this
      room right after."; else "Fix a second corner of this room right after to straighten the model too.".
  - Otherwise the finding row.
  - Buttons: [Cancel] (disabled while capturing) and [Fix] ("Hold still…" while capturing, disabled unless
    it can mark). Fix is hidden while it can't aim.
- **Fix**: a 500 ms capture.
  - A corner within **0.75 m** (not ambiguous) moves the building horizontally onto it.
  - A wall (LiDAR/ToF) turns it by up to 5° and moves it across the wall.
  - A second corner of the same room within **120 s** and 2.5–6 m away also straightens the turn.
  - Then: a 0.5 s glide, a **success haptic**, and the card closes. No analytics event fires. A room
    observation is posted in the background (skipped for members' own access and read-only buildings). If the
    fix measured the room's position, the admin's "Save this room's adjustment" becomes available (¶14.2).
- **Done**; strings match.

---

### 10. Starting storey, levels and rooms

- **Starting storey** (AEV 172-179; kAEV 377-386): only when no level is chosen in AR and the model has more
  than one. It is the lowest storey with rooms, else the lowest. It runs at start and again when the manifest
  arrives. The floor elevation follows the chosen storey, so a manual placement keeps its world floor height
  when the user switches level. **Done**.
- ⋮ **Level** (more than one storey): the shared storey picker in AR mode.
- ⋮ **Room** (rooms on this level): a picker titled "Room", with "Whole level" plus each room's name or
  "Unnamed room" and a check on the current one. It sets the AR room focus, which also makes behind-wall
  fading ignore walls.
  - Android (kAEV 913-981): one dropdown that pages into Level or Room, with a disabled "Room" header row and
    trailing checks. **Done** (platform menu).
- Choosing a room for points switches AR to that room's level (PA 107-115). **Done**.

---

### 11. Locate (chip, arrow, marker)

AEV 1058-1128; kAEV 1322-1385.

- **Chip**, under the top bar, when an element is being located:
  - a scope icon in accent, the element's display name (subheadline semibold), and a second line (footnote,
    secondary):
    - not placed yet: "Line the model up with the room to find it.";
    - on screen: "%@ away";
    - off screen: "%@ away · follow the arrow";
    - distances are in the user's units, updated at **10 Hz**;
  - an ⓧ button (44 pt, a11y "Stop locating"). The name and distance are one combined accessibility element.
  - iOS: the chip hugs its content and is centred.
  - Android: **full width** (`fillMaxWidth`, kAEV 1329). **Differs (minor visual).**
- **Arrow** (off-screen only): `arrow.right.circle.fill` at 48 pt (white arrow on an accent disc, shadow
  radius 4), placed on the screen edge 48 pt in and rotated towards the target. It eases 0.15 s and is hidden
  from accessibility.
  - Android: an accent 48 dp circle with an `East` icon (30 dp), the same 150 ms ease-out rotation, **no
    shadow** (kAEV 1373-1381). **Differs (minor).**
- **Marker** in the scene: a 5 cm sphere, unlit teal (0.04, 0.43, 0.47) at 0.8 opacity, pulsing with scale
  1 → 1.35 over a period of **1.2 s**. It draws through walls. Android: `scene/.../BuildingScene.kt:413-435`.
  **Done.**
- Locating turns the element's system on in AR. When an object card closes, the located element stays
  highlighted.

---

### 12. See inside panel

SIP 1-65; kSIP 38-104.

- Layout:
  - a handle bar (36×5, `line` colour);
  - a row with "See inside" (ivHeadline) and a chevron (up when collapsed, down when expanded; muted).
    Tapping the row toggles expansion (iOS `.snappy` animation; Android `AnimatedVisibility`). The a11y label is
    "More filters" when collapsed and "Fewer filters" when expanded;
  - the **DetentSlider** with detents "Reality" (0), "Reality + model" (0.5, the default) and "Model" (1),
    a11y "Model opacity". It snaps within 0.06 on release, gives a selection haptic as it reaches a detent and
    a light impact on release;
  - the system chips (only systems in scope; padded out to the screen edges);
  - when expanded, the subsystem chips and, if a room is focused, the room focus chip (✕ clears the focus).
- The opacity rule a user can see: at "Reality" the camera only. At "Reality + model" systems are fully
  opaque over the camera and architecture and structure are hidden. At "Model" the building hides the camera.
  The systems draw through real walls (there is no occlusion), but elements behind a *model* wall, or more
  than 12 m away, are hidden; fading starts at 8 m.
- Android: **Done**, with these decided differences:
  - TalkBack adjusts the slider continuously (PLAN §3 table);
  - the release haptic is `Confirm` rather than a light impact (`design/.../DetentSlider.kt:57-94`).

---

### 13. ⋮ menu (in order; conditions exact)

AEV 640-743; kRules 268-296, kAEV 992-1027. **Done** (order, conditions, dividers). Icons are Material
equivalents.

| Item (exact) | Shown when | Action |
|---|---|---|
| "Fix here" | `canFixHere` (¶9) | Fix here |
| "Save this room's adjustment" | admin (`canManagePlates`), a fix this session that measured a room, and nothing else on screen (no points, registration, fix or save card) | Room-save card (¶14.2) |
| "Align by points" | no points, no registration, some room has an outline | Points (¶8) |
| "Register a plate here" | admin, no registration in progress | Registration with the plate list (¶14.1) |
| "Re-align" | the building has placed plates | ¶7.4 |
| "Adjust placement" | manual (incl. after points) and placement locked | Back to adjusting; converts a points alignment to manual, dropping its local alignments |
| "Place again" | manual and placed | Clears the placement; building hidden; floor coaching |
| "Place manually" | not manual | Manual from the current alignment (adjusting), or floor search |
| — divider — | | |
| "Level" ▸ | more than one storey | storey picker |
| "Room" ▸ | rooms on this level | room picker (¶10) |
| — divider — | | |
| "Safety note" | always | ¶2.4 |

Admin is `role.canManage` with a member building. iOS also requires "not the App Clip". On Android there is
no Clip, so admins always get these items (decided, PLAN §3 table).

---

### 14. Admin-only tools

#### 14.1 Plate registration and "Test now"

PRF; AEV 348-386; kPRF; kAEV 552-596.

- **Entry**:
  - [Register its position] on the unplaced-plate card (that plate preselected), or
  - ⋮ "Register a plate here" (choose from the list).
  - If the building was last aligned by points, that alignment is reused. Otherwise marking starts.
- **Loading**: a spinner and a "Cancel" link. On failure: "Couldn't load the plates. Check your
  connection." with [Close] and [Try again].
- **Plate list**:
  - "Which plate are you registering?" (ivHeadline);
  - a list (max **260 pt**) of rows, each with "Plate %lld" (subheadline semibold), the label (footnote), and
    "Has a position: registering replaces it" or "Not in the model yet" (caption, secondary);
  - "Cancel".
  - Android rows are plain `background`-coloured boxes rather than secondary buttons (kPRF 349-389). That's
    minor.
- **Collecting card** (shown while the points panel sits *under* it until the fit exists):
  - "Register plate %@" (number · label);
  - checklist row 1: ○ "Mark 3 floor corners" → ● "Corners marked";
  - checklist row 2: ○ "Point the phone at the plate from about 1 m" → "Hold still on the plate…" → ●
    "Plate seen". The plate is accepted from ≤ 1.5 m within 45°, and is ready after 8 of 15 samples;
  - [Mark corners] if no fit and no points panel;
  - "Cancel".
  - Done circles are in accent, open ones secondary.
- **Review**:
  - "Plate %@";
  - "Centre %@ above the floor";
  - "%@ from the left corner · %@ from the right corner";
  - "Alignment: good (average error %@)" or "Alignment: fair (average error %@). Marking more points helps."
    (warn);
  - on a save error: "Couldn't save the position. Check your connection and try again." (warn);
  - [Start over] (restarts the points) and [Save position] (a spinner while saving; "Try again" after an
    error), then "Cancel".
- **Not on a wall**: "The plate doesn't seem to be on a wall of this room. Check the room and mark the points
  again." with [Close] and [Start over].
- **Forbidden (403)**: "Only admins can register plates." with [Close].
- **Saved**: ✓ "Position saved. Guests now align on plate %@." with [Done] and **[Test now]**. Test now
  reconfigures the plates with the new one as the scanned plate and re-aligns, as a guest would. This new
  anchoring allows another `ar_aligned`.
- **Done**; strings match.

#### 14.2 Room-save card

AEV 282-338, 872-966; kAEV 495-550, 1227-1320.

- Review, saving and failed states:
  - "Save where %@ really is?" (ivHeadline);
  - "Everyone who opens this building in AR will start with this room where you fixed it." (callout);
  - "Moves %@ and turns %@ from the saved position." (footnote). The length is in cm below 1 m, else in m,
    with 1 decimal and always metric (`usage: .asProvided`); the angle is in degrees with 1 decimal;
  - "Measured from %@, %@ away." (room name), or "Measured from a plate, %@ away." (secondary);
  - if that distance is **> 8 m**: "Far from where you aligned: the phone may have lost precision on the way.
    Align closer for a better measurement." (orange on iOS; Android `Palette.warn` amber);
  - if no turn was measured: "To include its turn, fix a second corner of this room right after the first.";
  - when the save failed: "Couldn't save. Check your connection and try again." (red on iOS; Android accent
    red);
  - buttons: [Cancel] (disabled while saving) and [Save for everyone] ("Saving…" while saving).
- Saved: "Saved. Everyone will see this room where it really is." with [Done]. The building then glides to
  the saved correction.
- Forbidden: "Only admins can save room adjustments." with [Done].
- **Done** (colours mapped to tokens; `saveLength` uses `String.format` with the locale, kRules 408-413).

---

### 15. Object card in AR

Tapping in adjusting or locked state, when not aiming, picks the element under the finger.

- On iOS a tap on nothing clears the selection.
- The card is a sheet (medium and large detents) with **"Locate"** (in place) and no "Show in 3D". Guests never
  see technical properties.
- While the card shows, the selected element stays at full opacity even if it is behind a wall.
- Android: a `ModalBottomSheet` (`ObjectCard.kt`). **Done.** Device check: taps through the Compose overlay
  layers reach the AR view (kScene 41-44).

---

### 16. Haptics

| When | iOS | Android | Parity |
|---|---|---|---|
| Every alignment or fix: plate (including re-align, re-anchor, Test now), manual Done, points confirm, Fix | `.sensoryFeedback(.success, trigger: alignments)` AEV 576 | `HapticFeedbackType.Confirm` when `alignments` increases (kAEV 766-768) | Done |
| Each change of the mark count (mark, undo, start over) | `.impact`, PA 523 | `ContextClick` on change (kPA 573-580) | Done (ContextClick is weaker than a medium impact; check on device) |
| See inside reaches a detent / release | `.selection` / `.impact(.light)` | `SegmentTick` / `Confirm` | Done (mapping) |
| Marking test mark | `.impact` MT 273 | `ContextClick` (kMT 329-334) | Done |

---

### 17. Marking device test (Debug)

MT; kMT.

- A full-screen AR camera with the floor crosshair, floor corners only (wall aims off). The top bar has ✕ and
  "Tracking: <state>". The panel has:
  - a segmented control "Corner A" / "Corner B" / "A after 60 s";
  - the instruction plus " Point the phone down with the circle on the corner, and tap Mark.";
  - monospaced result lines ("<target>: N marks, M misses, max X mm, rms Y mm", "A↔B horizontal: X mm
    (compare with the tape)", "Drift on corner A: X mm");
  - [Undo], [Copy results]/"Copied", and [Mark]/"Hold still…" (enabled only when the crosshair says "On the
    floor").
- The text is English only, as a developer tool.
- Android **Done**. The report's `device` field is "MANUFACTURER MODEL". **Differs:** there is no ARCore gate,
  and it asks for the camera itself, with the fallback text "The marking test needs the camera." (kMT 263-285).

---

### 18. Analytics from AR

| Event | When | Props | iOS | Android |
|---|---|---|---|---|
| `ar_opened` | `start()`: the camera view appeared after the preflight | — | AEV 151 | kAEV 346 (**Done**). A rotation re-sends it (¶3.9). |
| `ar_aligned` | the first alignment of each anchoring (the first configure; again after "Test now") | `method`: `plate` / `manual` / `points`; `ms` = time since the anchoring was created (when the manifest was there), not since AR opened | AEV 425-441 | kAEV 632-645 (**Done**) |
| `system_toggled` | See inside chips | `system`, `on` | session | shared (**Done**) |
| `object_opened` | object card | `kind`, `system` | session | shared (**Done**) |

"Fix here", Re-align and lost produce no event. The room observation POST is not analytics.

---

### 19. Accessibility summary

- Labels: Close, More, Stop locating, the eight fine-tune names, Model opacity, More filters / Fewer filters,
  Change room (hint). The crosshair, locate arrow, explainer symbols and checklist icons are hidden. The locate
  chip's text and each explainer page are combined elements. The safety card is modal (iOS).
- Android: all present (content descriptions, `onClickLabel`, `clearAndSetSemantics`) except **modal
  safety card** (¶2.4, Differs).
- Nothing announces status-tag or coaching-line changes on iOS either. Android matches; a polite live region
  would improve both.
- Dynamic Type: iOS text styles scale. Android sizes are in `sp`. Device check at 200 % font scale: the top
  bar tag (capped at 240 dp) and the 52×44 fine-tune buttons.

---

### 20. Timers and thresholds the user can perceive

| What | Value | iOS | Android |
|---|---|---|---|
| Camera wait for 3D views | ≤ 1 s, polled every 50 ms (black screen) | AEV 122-131 | resumed + 2 frames (decided) |
| "No plate?" link | > 4 s after AR opened; at once when lost; re-checked every 1 s | AEV 1033-1034 | kRules 151, kAEV 1131-1145 |
| Automatic fallback | 10 s searching since configure, nothing seen in the last 1 s | PAN 24 | `FALLBACK_DELAY` |
| Plate convergence | 10 samples, ≥ 6 within 3 cm / 2° | PAL 96-164 | `WINDOW_SIZE`… |
| Glide on any alignment change | 0.5 s smoothstep | PAL 199-231 | `DURATION = 0.5` |
| Mark / Fix capture | 500 ms, ≥ 5 samples, 2 cm (walls 1.5 cm / 2°) | AAV 255-258 | `MarkCapture` |
| Aim gate | 35° from straight down | AAV 253 | `MAXIMUM_AIM_ANGLE` |
| Crosshair refresh | 15 Hz (every frame while capturing) | AAV 937-940 | `UPDATE_RATE` |
| Lost after | 2 s relocalizing or not available; interruption at once | AAV 240 | `LOST_AFTER` |
| Re-align timeout | 15 s | PAN 28 | `REALIGN_TIMEOUT` |
| Auto re-anchor | > 4 m from the alignment's camera (plates only) | PAN 26 | `REANCHOR_DISTANCE` |
| Two-corner turn window | 120 s, corners 2.5–6 m apart | PAN 36 | `TURN_WINDOW` |
| Fix reach | corner 0.75 m; wall 0.3 m | REF 396, 471 | ported |
| Points | 3 marks (2 with a wall); spread ≥ 1 m; good ≤ 3 cm, fair ≤ 6 cm rms; ≤ 4 options | REF 222-262, 356 | ported |
| Floor follow | ≥ 5 mm; ≤ 15 cm | AAV 1038, REF 144 | `FloorChangeNotifier` |
| "Details copied" | 2 s | PA 302-305 | `DETAILS_COPIED_MILLIS` |
| Locate | 10 Hz distance/arrow; arrow ease 0.15 s; pulse 1.2 s | AAV 1184, LG 85 | `LOCATE_INTERVAL` |
| Room switch | 0.30 m inside the next room, 0.5 s glide | RC 128 | `SWITCH_DEPTH` |
| Behind-wall fade | full to 8 m, gone at 12 m, recompute every 0.30 m | BWF 28-55 | `BehindWallFade` |
| Registration | ≤ 1.5 m, ≤ 45°, 8 of 15 samples | PR 10-58 | `PlateSampler` |
| Room-save warning | base distance > 8 m | AEV 902 | `FAR_BASE_DISTANCE` |
| Thermal | iOS `.serious`: no mesh, no texturing, ≤ 30 fps smallest format, no wall aims | AAV 462-467 | `THERMAL_STATUS_SEVERE` (API 29+), read at start too; the cool camera format applies only at session start (decided) |

---

### 21. Android translation and parity summary

The decided ARKit → ARCore mapping is in Android `docs/PLAN.md` §3 "AR" and ¶6. This section is the status of
each piece as implemented.

| Piece | Android implementation | Status |
|---|---|---|
| Session view | `ARSceneView` (SceneView 4.53), one Filament engine, plane renderer off, depth occlusion off, no post-processing or shadows (kScene 25-68) | Done (device check: no occlusion, fps) |
| Plate images | `AugmentedImageDatabase` rebuilt on each settings change; `FULL_TRACKING` only; `sizeMm/1000` width (kAAV 173-192, 319-327, 346-355) | Done (device check: QR detectability and rebuild stalls) |
| Floor | lowest upward plane ≥ 0.25 m² (no floor class) | Done (decided; device check: reflections) |
| LiDAR → ToF | `DEPTH_OUTPUT` back camera and `RAW_DEPTH_ONLY` supported → wall and corner aims; depth `AUTOMATIC` (kAAV 216-220, 707-715) | Done (decided). Most 2026 phones have no ToF, so they behave as iPhones without LiDAR. |
| Raycasts | `frame.hitTest` planes, then depth points; screen rays from the camera matrices | Differs: no `estimatedPlane`, so no tap-to-place before a plane (¶7.3) |
| Coaching overlay | Compose card with the same gating | Differs (decided, ¶6.7) |
| Tracking and interruption | `TrackingState`/`TrackingFailureReason` → `TrackingStatus`; activity pause and resume | Done (any pause interrupts, ¶3.7) |
| Thermal | `PowerManager` listener, read at start | Done (decided fix) |
| Keep awake, status bar | `keepScreenOn`, insets controller | Done |
| Gestures | `ARTouch`: tap, slop-gated drag, two-finger twist | Done |
| Site anchors | `session.createAnchor`, polled each frame | Done (device check: drift) |
| Mark discs and crosshair marker | `CylinderNode`s with unlit colours | Done (device check: 35 % alpha) |

**Gaps, most important first**

1. **Missing: surviving rotation** (¶3.9). AR restarts and the alignment is lost on any orientation change
   (`AndroidManifest.xml:35-39`, kAEV 681).
2. **Differs: "Not now" on the camera pre-prompt** (kPRE 255-256). iOS removed it in 7ac7e5f.
3. **Differs: an extra "Allow the camera" step after a permanent denial** on each new AR screen (kPRE 73-77,
   kRules 401-406).
4. **Differs: no tap-to-place before ARCore finds a plane** (kAAV 506-514). iOS uses `estimatedPlane`.
5. **Differs: the safety card isn't modal for TalkBack** (kAEV 1470).
6. **Differs: typography.** Barlow everywhere, where iOS uses system text styles for AR body text (whole
   screen; design decision).
7. **Differs: the locate chip is full width** (kAEV 1329) and the arrow has no shadow (kAEV 1373-1381);
   **the status tag is capped at 240 dp** (kAEV 887).
8. **Differs: the floor coaching** is a static card where iOS has Apple's animated overlay, and it is drawn
   above the panel layer (kAEV 815-819; decided).
9. **Differs: the marking test** has no ARCore gate (kMT 263-285). `hasLiDAR` isn't Compose state
   (kAAV 682-694). The copy-details `outcome` string format differs.
10. **Decided differences already in PLAN** (no action): ARCore install gate; navigation destination and
    system Back; inline Equipment "Locate in AR"; floor without classification; ToF-only wall marks; several
    tracked images; cool camera format only at start; thermal read at start; wall-disc offset fix; continuous
    TalkBack slider; admin tools outside a Clip.

**Doc fixes found while checking**

- Android `docs/device-test.md` §3: "Depth marks (walls) on A; hidden on B without Depth" contradicts PLAN
  (ToF only). Device A (Pixel 7a-class) has no ToF, and "hidden" is wrong: the crosshair says "Point the phone
  more straight down".
- The iOS device test's "cover the camera … > 2 s → Alignment lost" doesn't match the code
  (reference §10.1).

---

### 22. AR device-test checklist (Android, with a printed plate)

**Pass criteria** (master PLAN §15 M2 exit, `insiteview-api/docs/PLAN.md:996`; iOS
`docs/device-test.md` §1): AR **aligns from a plate in under 5 s** with **≤ 5 cm error and ≤ 2° at 3 m**.
Measure time from the camera feed appearing to the "Aligned at plate N · …" tag, with the plate in view at
1 m, and record `ar_aligned.ms` as well. Use the iOS protocol's buildings, plates (printed at 100 %, quiet
zone measured within ±0.5 mm of `sizeMm`), reference points and tables, so the two apps are compared on the
same ground.

**Devices:**

- A: mid-range ARCore phone (Pixel 7a-class);
- B: low-end ARCore phone;
- C: a phone without ARCore;
- D: if available, a ToF phone (e.g. Galaxy S20+/S20 Ultra/Note10+) for wall marks.

Record each phone's `hasLiDAR`.

| # | Check | Pass |
|---|---|---|
| 1 | C: "See in AR" | "AR isn't available on this phone", [Close] back to the landing |
| 2 | A with ARCore uninstalled or old | "Get Google Play Services for AR" → Install → Play → back: the explainer follows |
| 3 | First "See in AR" | Explainer 3 frames; Skip and Start work; not shown again; no pulse with animations off |
| 4 | Camera pre-prompt | Only [Continue] (after the parity fix); system dialog; deny once → asks again; deny twice → "Camera access is off"; Open Settings → allow → back → AR starts |
| 5 | Camera feed start | No black feed after leaving the 3D viewer; feed within 1 s |
| 6 | Safety card | On first AR only; ⋮ → Safety note shows it; TalkBack stays inside it (after the fix) |
| 7 | Plate invocation `/b/{code}/2` | Line "…plate you scanned (plate 2 · …)." |
| 8 | Plate alignment (×5 per plate, 1 m) | "Hold still…" then aligned < 5 s; one success haptic; 0.5 s glide, no jump |
| 9 | Accuracy at 1, 3, 5 m and angle at 3 m | ≤ 5 cm and ≤ 2° at 3 m (fill the iOS table) |
| 10 | QR detectability | `arcoreimg eval-img` score of the plate PNG ≥ 75; detection from 1–2 m at 30–45° |
| 11 | Detection off once aligned | Android Studio profiler: CPU drops after aligning; no camera stall when the image database is rebuilt (aligned, Re-align, registration) |
| 12 | Wall plate orientation | Model the right way round (not mirrored or 90° off); a plate tilted 10° keeps the building level |
| 13 | Re-align at plate 2 | ⋮ Re-align → glide onto plate 2; after 15 s with nothing, back to the previous state |
| 14 | Auto re-anchor | Walk > 4 m to plate 2: re-aligns by itself |
| 15 | "No plate?" offer | Appears 4–5 s after the camera starts; at once when lost |
| 16 | 10 s fallback | Room with outline → points start; without → "Move your phone slowly to find the floor." |
| 17 | Manual placement | No tap response before a plane is found (Android); tap places; drag and twist directions match iOS; Fine-tune repeat while held; Done → "Placed manually" + haptic |
| 18 | Floor on shiny tiles | The crosshair disc sits on the real floor, not 9 cm below (reflection) or on furniture |
| 19 | Points, no ToF | Crosshair states: steep tilt → "Point the phone more straight down"; "On the floor" turns the reticle accent; 3 corners → fit rms < 3 cm; Undo; Start over; symmetric room → Option 1/2; It matches → "Aligned by points" |
| 20 | Points, ToF (D) | "On a wall", "On a corner"; a wall plus a corner → "2 of 2" and a fit; hot phone → floor only |
| 21 | Copy details | Long press → "Details copied" for 2 s; valid JSON on the clipboard |
| 22 | Floor refine during checking | The preview follows a floor that rises by several cm |
| 23 | Fix here (iOS §1b) | Taken on the near corner; lands on the right side of a wall; second corner straightens; walking back keeps room A's alignment |
| 24 | Room save (admin, iOS §1c) | Card values; Save → dashboard "Saved by an admin"; a new visit starts corrected |
| 25 | Registration and Test now | Corners → plate seen → review distances plausible → Save → Test now aligns on it |
| 26 | Lost and interruption | Phone call, app switch or opening a document from the card → "Finding your place again…" → restored; covering the camera shows "Point at a surface with more detail" and does not lose the alignment |
| 27 | Rotation | After the fix: rotating keeps the alignment (or AR is locked to portrait, as decided) |
| 28 | Screen stays on | 3 min without touching: no sleep |
| 29 | See inside | Detent ticks; Reality / Reality + model / Model look as on iOS; chips filter |
| 30 | Behind wall | The next room's systems hidden; doorway swap within a step; fade 8–12 m; ⋮ Room shows that room behind walls |
| 31 | Locate | Chip distance at 10 Hz; arrow on the edge, pointing the right way, also when the target is behind |
| 32 | Overlays and taps | Taps reach the model through the overlays; the object card opens with "Locate" |
| 33 | Markers | Faint 12 cm circle translucent (not opaque white); green 6 cm discs; reticle shadow not a dark disc |
| 34 | Thermal (10 min on B) | Record thermal status, memory and fps at 0, 5 and 10 min; ≥ 30 fps; severe → light estimation off |
| 35 | TalkBack and font scale 200 % | Every control labelled; the tag and fine-tune pad usable |
| 36 | Analytics | `ar_opened` once per open (rotation included after the fix); `ar_aligned` with method and ms |

## 9. Parity backlog

### Status after the parity pass (2026-11-08)

Every item below was worked in one pass across the four repositories (commits name the IDs). Code that
needs a device to confirm is marked so; `docs/device-test.md` "Parity with the iPhone" lists the checks.

| IDs | Status |
|---|---|
| P0-01, P0-05 | Done in `insiteview-api` (`AndroidApp` visits, `Clients:AndroidMinVersion`; full API suite passes) |
| P0-02, P0-03 | Done in `insiteview-web` (`assetlinks.json`, Play referrer with `code`/`plate` or `token`; the app reads `token=` too) |
| P0-04 | Done (activity handles rotation); device check |
| P0-06 | Open: Play Console is the owner's (AND-M0-04) |
| P1-01, P1-02, P1-03, P1-04, P1-05, P1-06, P1-07, P1-08, P1-11, P1-12, P1-13, P1-14, P1-15, P1-16, P1-17, P1-18 | Done |
| P1-09, P1-10 | Done; device check (Instant Placement, TalkBack) |
| P2-01 | Done: exact convex hulls from the chunk GLB (meshopt decoder and quickhull in `:modelkit`, golden tests) |
| P2-02 | Emissive highlight done; the gain and lighting are tuned on a device |
| P2-03, P2-04, P2-06, P2-07, P2-08, P2-09 | Done. P2-07: the iOS 26 tab bar *has* a selection pill (screenshots), so Android keeps one, in iOS's glass style |
| P2-05 | Partly wrong: iOS also shows a spinner in the save button. The PIN haptic and full-height sheet are done |
| S-01 … S-05 | Fixed on both platforms (iOS IVAPI change tested on Linux; app and IVFeatures need Xcode Cloud) |
| Also fixed | API: NuGet audit blocked every restore (pinned crypto packages). Android: stale `BillingDetailsResponse` removed (the API dropped it) |

Corrections to the audit: P1-11's 14 dp padding applies to the need chips only (iOS `NeedLabel`); system
chips keep 12 dp, as iOS's `Chip`.


Every gap from §4–§8 is listed here once, with an ID to put in commit messages. The section tables give the
full context. Statuses are as of the header date; strike an item through when it ships.

- **P0** blocks any release.
- **P1** is a difference a user would notice next to an iPhone.
- **P2** is polish or a device-dependent check.
- **Shared** means iOS has the same problem. Fix both or neither, and report it to `insiteview-ios`.

### P0: blocks release

| ID | Gap | Fix | Where | Ref |
|---|---|---|---|---|
| P0-01 | The API rejects `platform: "AndroidApp"`, so every visit from the app fails | Add `AndroidApp` to `VisitPlatform`, regenerate and sync the spec everywhere | `insiteview-api/src/InsiteView.Core/Visits/Visit.cs:43` | §4.1 |
| P0-02 | No `/.well-known/assetlinks.json`, so App Links don't verify and plates open the browser | Route from `ANDROID_PACKAGE_NAME` + `ANDROID_SHA256_CERT_FINGERPRINTS` | `insiteview-web/src/app/.well-known/` | §4.2 |
| P0-03 | The Play link has no install referrer, so "Get the app" never reopens the building | `&referrer=code=…&plate=…` (or `token=…`) on guest pages | `insiteview-web/src/lib/guest/apps.ts:42-46`, `components/guest/app-promo.tsx` | §4.3 |
| P0-04 | Turning the phone recreates the activity: AR restarts (alignment, marks and the 3D camera lost, `ar_opened` fires again) | Declare `android:configChanges="orientation\|screenSize\|screenLayout\|smallestScreenSize"` on `MainActivity`, so Compose relayouts without recreating, and keep the AR model in the navigation entry's ViewModel scope as a backstop (process death, other config changes) | `app/src/main/AndroidManifest.xml:35-39`, `features/…/ar/ARExperienceView.kt:681` | §8 ¶3, ¶21 |
| P0-05 | Android can't be force-updated | `Clients:AndroidMinVersion` in the API | `insiteview-api/src/InsiteView.Api/Setup/ClientVersionCheck.cs` | §4.1 |
| P0-06 | No Play app, signing or tracks | AND-M0-04 | Play Console | §4.4 |

### P1: visible differences

| ID | Gap | Fix | Where | Ref |
|---|---|---|---|---|
| P1-01 | The camera pre-prompt still has "Not now"; iOS removed it in 7ac7e5f (App Review 5.1.1(iv)) | "Continue" only, both in the AR preflight and anywhere else the same pre-prompt appears; keep "Not now" on the *denied* state as iOS does | `features/…/onboarding/ARPreflightViews.kt:255-256` | §5 ¶17, §8 ¶2.3 |
| P1-02 | After a permanent camera denial, every new AR screen shows "Allow the camera" before "Camera access is off" | Remember the denial (DataStore) and use `shouldShowRequestPermissionRationale` so the denied state shows at once | `ARPreflightViews.kt:73-77`, `features/…/ar/ARExperienceRules.kt:401-406` | §8 ¶2.3 |
| P1-03 | Broken pt-BR/es plurals ("%d tentativa restantes", "%d dia restantes", "%d intento restantes", "%d día restantes") | Fix `scripts/xcstrings-to-android.py:35-43` to take each plural form from the catalog's own `plural` variations instead of inflecting; regenerate; add a parity test that every `<plurals>` item differs per quantity where the catalog does | `features/src/main/res/values-*/strings.xml:719-720, 771-772` | §6 ¶19 |
| P1-04 | Action buttons have no icons | Add an optional leading icon to `PrimaryActionButton`/`SecondaryActionButton` (`ActionLabel`), and pass the Material Symbols equivalents of the iOS SF Symbols for "See in AR", "Explore in 3D", "View in AR", "3D model", "Save this building", "Continue with email" and "Undo" | `design/…/Components.kt:49-115`; call sites in §6 ¶20 #5 | §7 A.4 |
| P1-05 | Line heights are tighter than iOS (1.1 / 1.3 / 1.35 ×) | Use the fonts' own metrics: Anton 1.505, Barlow 1.2, JetBrains Mono 1.32 (`LineHeightStyle` with `includeFontPadding = false`) | `design/…/Typography.kt:36, 40, 50` | §7 A.3 |
| P1-06 | Top bar: the tab roots have no large Anton title, the title is start-aligned, there is no hairline on scroll, and the icons are ink | `LargeTopAppBar`-style large Anton title collapsing into a centred small title; accent icon tint; hairline (`Palette.line`) once content scrolls under | `features/…/ui/ListParts.kt:48-71` | §5 ¶4–9, §7 A.4 |
| P1-07 | The 3D camera angle resets after returning from AR | Keep the orbit pose in `BuildingSession` (or hoist the `OrbitViewerView` with the building root), restore it on return | `features/…/viewer/ModelViewer.kt:39` | §7 B.8 |
| P1-08 | Double-tap-and-drag zooms (and orbits) | `isQuickScaleEnabled = false` on the `ScaleGestureDetector` | `scene/…/OrbitViewerView.kt:290` | §7 B.6 |
| P1-09 | No tap-to-place before ARCore finds a plane (iOS uses `estimatedPlane`) | ARCore Instant Placement (`InstantPlacementMode.LOCAL_Y_UP`) for the placement raycast only; upgrade to the plane when tracking turns `FULL_TRACKING` | `ar/…/ARAlignmentView.kt:506-514` | §8 ¶7.3 |
| P1-10 | The first-use safety card isn't modal for TalkBack | Make it a `Dialog`/`paneTitle` modal, or hide the screen behind it from semantics (`clearAndSetSemantics` on the background) | `features/…/ar/ARExperienceView.kt:1470` | §8 ¶2.4, ¶19 |
| P1-11 | The "What do you need?" chips announce as checkboxes; 12 dp padding vs 14 | `Role.Button` (they navigate), horizontal padding 14 dp | `features/…/guest/GuestLandingView.kt:322`, `design/…/Components.kt:141-142` | §6 ¶3.1 |
| P1-12 | See inside slider: Material Expressive bar thumb, 16 dp track, no snap animation | Round white thumb with shadow, 4 dp track, animated snap to detents | `design/…/DetentSlider.kt:44-77` | §7 A.4, §8 ¶12 |
| P1-13 | Small text uses Barlow where iOS uses the system font (slider labels, room chip, menus, ~92 `.footnote`/`.subheadline` uses, AR body text) | Per decision §12 #3: map iOS system text styles to the platform font (Roboto / `FontFamily.Default`) at the same sizes | `design/…/Typography.kt`, call sites listed in §7 A.3 | §7 A.3, §8 ¶21 #6 |
| P1-14 | "Couldn't open the document · Check your connection…" also appears when no app can open the file; the `*/*` fallback can fail | Separate Android-only message ("No app on this phone can open this file"), add it to all three string files; resolve the MIME type from the document's `contentType`, then from the extension | `features/…/documents/DocumentsView.kt:86, 101` | §6 ¶11 |
| P1-15 | "Show in 3D" uses the AR icon | Use the 3D cube symbol iOS uses | `features/…/objectcard/ObjectCard.kt:227` | §6 ¶12 |
| P1-16 | PIN shake ignores reduced motion | Reuse the onboarding `isReducedMotion` helper | `features/…/guest/PinEntryView.kt:130-140` | §6 ¶4 |
| P1-17 | Profile: Language/Help/Terms/Privacy in ink (iOS: accent); the email form submits with a red primary button (iOS: a plain accent row); "Continue with email" has no envelope icon | Match iOS | `app/…/android/ProfileTab.kt:263-277`, `SignInView.kt:159-163, 367-373` | §5 ¶9–10 |
| P1-18 | The landing's "Building details"/"Documents" are chevron rows (iOS: tinted links) | Match iOS | `GuestLandingView.kt:142-149, 341-347` | §6 ¶3 |

### P2: polish and device checks

| ID | Gap | Fix / check | Where | Ref |
|---|---|---|---|---|
| P2-01 | Long diagonal runs are picked by their bounding box, which steals taps | Device check; if it bites, test the triangles of runs whose box/volume ratio is high (keep a CPU copy of those meshes only) | `modelkit/…/scene/ScenePicking.kt:17-40` | §7 B.7 |
| P2-02 | Selection glow (emissive) and lighting not tuned | Device check side by side with an iPhone: emissive if the material allows it, sun intensity and exposure to match RealityKit's 2500 lx, sRGB catalog colours | `scene/…/SceneMaterials.kt:86-93`, `BuildingScene.kt` | §7 B.4 |
| P2-03 | The viewer stops at the navigation bar; blurred materials are flat translucent surfaces; chips ripple instead of dimming | Draw the model under the system bars; `RenderEffect` blur on API 31+ (translucent fallback below); pressed state dims to 0.6 alpha, no ripple | `features/…/viewer/Viewer3DView.kt`, `design/…/Components.kt` | §7 B.2, A.4 |
| P2-04 | Locate chip full width; arrow without shadow; status tag capped at 240 dp | Wrap the chip's content; add the shadow; remove the cap (iOS sizes the tag to its text) | `ARExperienceView.kt:887, 1329, 1373-1381` | §8 ¶5, ¶11 |
| P2-05 | PIN haptic fires per key press (iOS: when the digits change); PIN sheet wraps instead of filling; the save button turns into a spinner | Match iOS | `PinEntryView.kt:126, 151, 251-254`, `GuestLandingView.kt:371-374` | §6 ¶3.2, ¶4 |
| P2-06 | Problem screen title is the display code; an empty building name has no placeholder | Match iOS | `guest/GuestFlow.kt:167`, `GuestLandingView.kt:244` | §6 ¶5 |
| P2-07 | Bottom bar shows the M3 selection pill; the Version row isn't muted; re-tapping a tab doesn't scroll to top; the update cover doesn't fade in | Indicator colour transparent; muted label; scroll-to-top on re-select; fade | `app/…/android/RootTabView.kt:72, 126-143`, `ListParts.kt:146-152` | §5 ¶4, ¶5.1, ¶9 |
| P2-08 | Marking test: no ARCore gate; `hasLiDAR` isn't Compose state; the copy-details `outcome` string is formatted differently | Gate it; `mutableStateOf`; match iOS's JSON exactly | `features/…/diagnostics/MarkingTestView.kt:263-285`, `ar/…/ARAlignmentView.kt:682-694` | §8 ¶8.8, ¶17 |
| P2-09 | `docs/device-test.md` expects wall marks on device A (no ToF on a Pixel 7a) and says "hidden" on B | Fix the rows: wall marks only on a ToF phone; elsewhere the crosshair says "Point the phone more straight down" | `docs/device-test.md` §3 | §8 ¶21 |

### Shared with iOS (decide together, §12 #5)

| ID | Behaviour on both platforms | Ref |
|---|---|---|
| S-01 | No "session ended" message when a refresh fails; the user is silently signed out | §5 ¶11 |
| S-02 | Signed in but offline, Profile shows "Sign in" | §5 ¶11 |
| S-03 | The Profile units picker shows the account's units while the viewer uses the device's value | §5 ¶14 |
| S-04 | Signed out, Search still sends requests | §5 ¶7 |
| S-05 | After `emailTaken`, iOS clears the message when switching to Sign in (Android keeps it, which reads better) | §5 ¶10.3 |

## 10. Milestones to release

Each milestone ends with something checkable. The `AND-M*` items in `docs/PLAN.md` §7 stay the record; add
`AND-M7-*` items for the work below when it starts.

### M0 · Unblock (API, web, Play): 2–3 days of work across three repositories, plus Play Console review time

- P0-01, P0-05 in `insiteview-api` (one PR: enum, options, check, tests, spec), deployed.
- `npm run api:sync` in `insiteview-web`; `scripts/sync-openapi.sh` here (`ApiContractTest` must pass); `make
  openapi` in `insiteview-ios`.
- P0-02, P0-03 in `insiteview-web`, deployed with `ANDROID_PACKAGE_NAME` still empty in production (the route
  needs it, so set it on staging first, or ship the route reading the package independently of the promo).
- P0-06: Play Console, upload key, secrets, internal testing track.
- **Exit:** an internal-track build installed on a phone opens `https://getinsiteview.com/b/{code}/{plate}`
  straight into the app (`adb shell pm get-app-links com.getinsiteview.android` shows `verified`), creates a
  visit, and a fresh install from the web's Play link opens the same building on first launch.

### M1 · Parity fixes in code: about 1–2 weeks

- P0-04 and every P1 item. Logic changes get JVM tests in the module that owns them (`ARExperienceRules`,
  `BuildingSessionRules`, the converter script's parity test).
- Re-run `scripts/xcstrings-to-android.py` after P1-03; `StringsParityTest` and `AppStringsParityTest` pass.
- **Exit:** CI green; for each screen in the list below, a screenshot pair (iPhone and Android, light and dark,
  English and pt-BR) reviewed with no unexplained difference:
  Buildings (empty, list), Search (empty, results), Scan, Profile (signed out, signed in), Sign-in sheet,
  Email form (errors), Update required, Guest landing (with and without PIN banner), PIN sheet (error,
  lockout), each problem state, Building details, Rooms, Documents, Object card (guest, member), 3D viewer
  (loading, loaded, a selection, See inside), AR preflight (explainer, pre-prompt, denied), AR (searching,
  aligned, manual, points marking, matching choice, ⋮ menu, locate, safety card, registration).

### M2 · First run on phones: about 1 week

- `docs/device-test.md` non-AR sections on two phones (a Pixel 7a-class mid-range phone and a low-end ARCore
  phone, Android 8–10 if one is available for minSdk 26), plus a ToF phone if one can be found.
- Rendering: meshopt and quantized GLBs decode in Filament; architecture transparency sorts correctly;
  selection highlight and locate pulse visible; catalog colours match the iPhone (P2-02).
- Performance: 60 fps on the mid-range phone and 30 fps on the low-end one, on the largest test building;
  cold start to landing; chunk cache reuse offline.
- Sign-in round trips through Custom Tabs for every provider; App Links from Chrome, Google Lens, the camera
  app's QR reader and a messaging app; Install Referrer.
- **Exit:** the non-AR device-test rows all pass; crashes and ANRs found are fixed.

### M3 · AR field test with printed plates: about 1–2 weeks

- §8 ¶22 checklist (36 rows) on the two phones, on a real building with printed plates (plates printed from
  the web's plates PDF at 100 % scale; check them with ARCore's `arcoreimg eval-img` first, score ≥ 75).
- Pass criteria (master PLAN M2): **aligned from a plate in under 5 s, within 5 cm and 2° at 3 m.**
- Points alignment, Fix here, starting storey and floor change, locate, See inside, lost/relocalizing,
  thermal throttling (20 minutes in AR), rotation (P0-04), and the admin flows (register a plate, Test now,
  save a room).
- **Exit:** every row passes, or has a written decision in `docs/PLAN.md` §6.

### M4 · Localization, store and privacy: about 1 week, partly waiting on others

- Native review of pt-BR and es together with iOS's IOS-M5-01 (AND-M6-01).
- Play listing from §5 ¶16 (title, short and full description, screenshots from M1's set at Play's sizes,
  feature graphic), content rating questionnaire, target audience, the Data safety form from §5 ¶15 (device
  id, analytics events, account data, camera used on device only).
- Sentry with iOS's IOS-M5-04 (AND-M6-03): DSN in `BuildConfig`, mapping upload in the release workflow,
  no PII.
- **Exit:** listing and forms approved in Play Console; strings reviewed.

### M5 · Beta and release

- Closed testing with the pilot buildings' staff (Android users among the iPhone pilots), two weeks, feedback
  triaged into this backlog.
- Production on a `v*` tag (`.github/workflows/release.yml`), staged rollout 10 % → 50 % → 100 %.
- Set `ANDROID_PACKAGE_NAME` in the web's production environment so guest pages offer Google Play, and
  `Clients:AndroidMinVersion` to the first release's version.
- Merge the Android work to `main` before the first release (§12 #1).
- **Exit:** the app is live; a plate scanned with an Android phone without the app goes web → Play → app →
  the same building; with the app it opens directly.

## 11. Keeping parity after launch

- **The reference commit** in this file's header is the iOS commit the spec was read from. Before each
  Android release: `git -C ../insiteview-ios log --stat <reference>..origin/main -- Packages App Resources`,
  and for every user-visible change add a backlog row here (or port it), then move the reference.
- **Strings:** any change to `Resources/Localizable.xcstrings` → re-run `scripts/xcstrings-to-android.py`.
- **API:** any spec change → `scripts/sync-openapi.sh`; `ApiContractTest` fails on renamed or removed fields.
- **Catalog and asset contract** (master PLAN §9) changes → refresh `modelkit/src/test/resources` fixtures, as
  iOS does.
- **AR rules** live in `:modelkit`/`ARExperienceRules` with the iOS tests ported; port new iOS test cases with
  the same names.
- Suggest to `insiteview-ios` (its CLAUDE.md) a line asking user-visible iOS changes to note "Android: …" in the
  PR, so this list doesn't depend on archaeology.

## 12. Open decisions

| # | Question | Recommendation |
|---|---|---|
| 1 | Which branch becomes `main` for Android? The port lives on `claude/festive-hypatia-xhua3q` (the remote's default) and `claude/android-app-plan-st0bwi`, at the same commit | Create `main` from it now, protect it, and point CI and the release workflow at it |
| 2 | ~~Apple sign-in on Android~~ | **Decided:** no Apple sign-in on Android |
| 3 | ~~System font for small text~~ | **Done:** the platform font where iOS uses SF (P1-13) |
| 4 | ~~TalkBack slider~~ | **Done:** TalkBack steps between detents |
| 5 | ~~Shared behaviours S-01 to S-05~~ | **Done on both platforms;** S-03: the account's units decide when signed in |
| 6 | Back at a tab root | **Done:** leaves the app with its state kept (Android 8–11 too) |
| 7 | Test phones: there are few 2026 phones with a ToF sensor, so wall marks are rarely available | Test wall marks on one ToF phone if one can be borrowed; otherwise ship them untested behind the same ToF check |
