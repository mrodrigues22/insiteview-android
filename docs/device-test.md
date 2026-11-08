# Device test protocol

> Run before the first Play release (AND-M6-05). Status: **not run yet**. Everything in `:design`,
> `:scene`, `:ar`, `:features` and `:app` was written in a Claude Code session where Android modules
> only build in CI; this run is their first check on a phone. Use the same buildings, codes, printed
> plates and links as `insiteview-ios/docs/device-test.md` (Setup), so the two apps are compared on
> the same ground.

## Setup

| Item | Value |
|---|---|
| Build | `staging` from CI (or `./gradlew installStaging`) |
| Commit | |
| Devices | A: mid-range ARCore phone (Pixel 7a-class; no time-of-flight sensor) · B: low-end ARCore phone · C: a phone without ARCore · D (if one can be borrowed): a phone with a time-of-flight depth sensor |
| Android versions | |
| Buildings, codes, plates, links | as in the iOS protocol |

## 1. Entry

| Check | A | B | C |
|---|---|---|---|
| Camera app scan of a plate opens the app on the building (App Link verified: `adb shell pm get-app-links com.getinsiteview.android.staging`) | | | |
| App not installed: the plate opens the web viewer; "Get the app" → Play → first launch opens the same building and plate (Install Referrer) | | | |
| `/a/{token}` valid, expired, revoked | | | |
| In-app Scan tab: building code, a foreign QR ("This isn't an Insite View code") | | | |

## 2. 3D

| Check | A | B | C |
|---|---|---|---|
| Duplex: landing → first 3D frame (s) | | | |
| GLB chunks render with catalog colours (meshopt decoded, no missing meshes) | | | |
| Tap picks the right element; object card | | | |
| Level picker, system and subsystem chips, room focus, Fit | | | |
| fps while orbiting (Android GPU inspector or `dumpsys gfxinfo`) | | | |

## 3. AR (A and B)

Fill the iOS protocol's accuracy tables (plate alignment error at 1, 3 and 6 m; drift after walking 10 m;
re-anchoring; reference points; Fix here; room corrections; locate; behind-wall) with the same plates.

| Check | A | B |
|---|---|---|
| Plate 1 detected and aligned (time from AR open) | | |
| Alignment error at the far wall (cm) | | |
| Align by points in a room without a plate | | |
| Wall and corner marks on D (ToF); on A and B floor corners only, the crosshair saying "Point the phone more straight down" at a wall | | |
| Plate registration (admin) → "Test now" | | |

## 4. Signed-in app

| Check | Result |
|---|---|
| Google and Microsoft sign-in through Custom Tabs (PKCE; no Apple button); email sign-in and register | |
| Buildings tab segments, favourites, pull to refresh | |
| Search tab; in-building search offline | |
| Profile: units, sign out, delete account (sole-owner alert) | |
| Update required at `Clients__AndroidMinVersion` above the build's version | |

## Parity with the iPhone (AND-M7-10)

Side by side with an iPhone on the same building and plates (`docs/IOS-PARITY-PLAN.md` §10):

| Check | Result |
|---|---|
| Glass: tab bar, navigation bar buttons, search field and chip bar look like the iPhone's; blur over lists (Android 12+) | |
| Large titles collapse on scroll; re-tapping a tab scrolls to the top | |
| Swipe a building right: Favorite / Unfavorite; a full swipe toggles. Swipe an equipment row left: Locate in AR | |
| Selection highlight glows as on the iPhone (tune `SceneMaterials.EMISSIVE_GAIN`); lighting and colours match | |
| Tapping beside a long diagonal pipe selects what's behind it | |
| Turning the phone in AR keeps the alignment and marks | |
| Tap-to-place before a plane is found (Instant Placement) | |
| Two plates in view: only one drives the alignment | |
| Hot phone (20 min of AR): lighter rendering at once; camera format at once only before aligning | |
| Floor coaching animation, and its static frame with animations off | |
| Plurals in pt-BR and es ("2 tentativas restantes", "1 dia restante") | |

