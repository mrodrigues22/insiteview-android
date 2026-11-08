# Google Play listing and Data safety (AND-M6, mirrors iOS `docs/app-store-listing.md`)

The listing to enter in Play Console for `com.getinsiteview.android`. English (United States) is the
default language; pt-BR and es come with the native translation review (AND-M6-01). The wording
follows the App Store listing; differences are Android facts (no App Clip, Google Play, phone instead
of iPhone, no Apple sign-in).

## Store listing, English (United States)

- **App name** (30): Insite View
- **Short description** (80): See inside the walls: a building's pipes, wiring and ducts in 3D and AR.
- **App category:** Productivity. **Tags:** Productivity, Business, Augmented reality.
- **Contact:** support e-mail as on the App Store; website https://getinsiteview.com
- **Privacy policy:** https://getinsiteview.com/privacy

### Full description (4000)

```
Insite View shows what's inside a building's walls. Scan a numbered QR code mounted in the building and its design model opens on your phone in 3D and augmented reality, aligned to the QR code, so you see the pipes, wiring, ducts and structure right where they are.

FOR OWNERS, RESIDENTS AND TRADES
Scan a QR code with the phone camera. With the app installed, the building opens straight away; without it, it opens in the browser and brings you back to the same building once you get the app. No account needed to look around; a free account keeps your buildings in one place.

FOR BUILDERS, ARCHITECTS AND FACILITY MANAGERS
Upload the project's IFC models at getinsiteview.com. Insite View combines architecture, structure, plumbing, electrical and HVAC into one model organized by system, floor and room. Print the numbered QR code plates, mount them, and register each QR code's position on site with this app.

FEATURES
• AR aligned to the QR code: the hidden systems behind the wall, at real scale
• A 3D model you can orbit and filter by system, floor and room, with "See inside"
• Tap any element to see its type, system and properties
• Search the model and find an element in AR with a guide arrow
• Documents attached to the building
• An optional PIN and temporary links control what each visitor sees
• On-site QR code registration for company admins

SAFETY
Insite View shows the design model, not a survey of what was built. Always confirm on site with a detector before drilling or cutting.

Visitors don't need an account. Companies manage buildings, QR codes and access on the web at getinsiteview.com. There are no purchases in the app.

AR needs Google Play Services for AR (ARCore). On phones without it, the 3D model still works.
```

### Graphics

- **App icon:** `app/src/main/ic_launcher-playstore.png` (512×512, from `insiteview-api/brand/render.py --android`).
- **Feature graphic** (1024×500): the mark and wordmark on the brand red, from the same renderer.
- **Phone screenshots:** the same four scenes as the App Store set (`insiteview-ios/docs/app-store-screenshots`):
  the landing of the demo building, the 3D model with the system chips, the model in AR at a plate,
  an object card. Taken on a 1080×2400 phone with the demo building.

## App content

- **Content rating** (IARC questionnaire): no violence, sexuality, language, controlled substances or
  gambling; no user-to-user communication; no location sharing with other users → Everyone / PEGI 3.
- **Target audience:** 18 and over (a work tool); not designed for children.
- **Ads:** none.
- **App access:** the demo account (`review@getinsiteview.com`, from the API's `seed-demo`; password typed
  into Play Console, never committed) and the demo building link `https://getinsiteview.com/b/MRRMYZ`.
  Instructions: as the App Review notes in the iOS listing, with "the app" for "the App Clip".
- **Government app / financial features / health:** no.

## Data safety

The answers match the iOS App Privacy declaration (`PrivacyInfo.xcprivacy`) and what the app sends
(master PLAN §12): no tracking, nothing shared with third parties, all traffic over HTTPS, account and
data deletion in the app (Profile → Delete account) and on the web.

| Data type (Play) | Collected | Shared | Optional | Purposes | Notes |
|---|---|---|---|---|---|
| Personal info → Name | yes | no | yes (only with an account) | App functionality, Account management | The account's display name |
| Personal info → Email address | yes | no | yes (only with an account) | App functionality, Account management | Sign-in |
| Personal info → User IDs | yes | no | yes (only with an account) | App functionality, Account management | The account id |
| Device or other IDs | yes | no | no | App functionality, Analytics | A random id made by the app (`X-Device-Id`), not the advertising id; linked to the account when signed in |
| App activity → App interactions | yes | no | no | Analytics | Visit events (3D opened, AR aligned, object opened, …), linked to the visit and, signed in, to the account |
| Location | no | | | | Coarse country is derived on the server from the IP address for building statistics, not collected from the device |
| Photos and videos / camera | no | | | | The camera is used on the device only (AR, QR scanning); no images leave the phone |
| Files and docs | no | | | | Documents are downloaded for viewing, not uploaded |

- **Is data encrypted in transit?** Yes.
- **Can users request deletion?** Yes: Profile → Delete account (also https://getinsiteview.com/app/settings).
- **Independent security review:** no.

## Release notes (first release)

```
Insite View for Android: open a building from its QR code, explore it in 3D and see its hidden systems in AR.
```
