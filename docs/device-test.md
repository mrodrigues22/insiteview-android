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
| Devices | A: mid-range ARCore phone with Depth (Pixel 7a-class) · B: low-end ARCore phone · C: a phone without ARCore |
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
| Depth marks (walls) on A; hidden on B without Depth | | |
| Plate registration (admin) → "Test now" | | |

## 4. Signed-in app

| Check | Result |
|---|---|
| Google, Microsoft, Apple sign-in through Custom Tabs; email sign-in and register | |
| Buildings tab segments, favourites, pull to refresh | |
| Search tab; in-building search offline | |
| Profile: units, sign out, delete account (sole-owner alert) | |
| Update required at `Clients__AndroidMinVersion` above the build's version | |
