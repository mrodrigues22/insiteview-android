<!-- Written from the iOS code at insiteview-ios da1d05d (2026-10-08). The iOS code is the spec: when this file and the code disagree, read the code. Android decisions are in docs/PLAN.md §3 "AR"; section 11 here is the audit of an earlier draft of it. -->

# iOS AR experience as implemented in code (origin/main da1d05d), plus an audit of the Android PLAN

The code is the reference throughout. Where the iOS `docs/PLAN.md` §3 disagrees with it, that is noted in §10. All the AR UI strings I extracted (165 `Text`/`Label`/`Button` literals from the AR, Points, Registration, SeeInside and Preflight files) exist as keys in `/home/user/insiteview-ios/Resources/Localizable.xcstrings`. Interpolations appear there as `%@` and `%lld`.

**File abbreviations** (root `/home/user/insiteview-ios/Packages/InsiteViewKit/Sources/`):
- **AAV** = `IVAR/ARAlignmentView.swift`
- **AM** = `IVAR/AnchorMath.swift`
- **AEV** = `IVFeatures/AR/ARExperienceView.swift`
- **PA** = `IVFeatures/AR/PointsAlignment.swift`
- **PRF** = `IVFeatures/AR/PlateRegistrationFlow.swift`
- **SIP** = `IVFeatures/AR/SeeInsidePanel.swift`
- **PRE** = `IVFeatures/Onboarding/ARPreflightViews.swift`
- **MT** = `IVFeatures/Diagnostics/MarkingTestView.swift`
- **BS** = `IVFeatures/Building/BuildingSession.swift`
- **SCN** = `IVScene/BuildingScene.swift`
- **PAN** = `IVModelKit/Geometry/PlateAnchoring.swift`
- **PAL** = `IVModelKit/Geometry/PlateAlignment.swift`
- **SITES** = `IVModelKit/Geometry/AlignmentSites.swift`
- **REF** = `IVModelKit/Geometry/ReferenceAlignment.swift`
- **RC** = `IVModelKit/Geometry/RoomCorrections.swift`
- **LA** = `IVModelKit/Geometry/LidarAim.swift`
- **PR** = `IVModelKit/Geometry/PlateRegistration.swift`
- **MA** = `IVModelKit/Geometry/ManualAlignment.swift`
- **BWF** = `IVModelKit/Geometry/BehindWallFade.swift`
- **LG** = `IVModelKit/Geometry/LocateGuide.swift`
- **RO** = `IVModelKit/Geometry/RoomOutline.swift`
- **MF** = `IVModelKit/ModelFilters.swift`
- **NN** = `IVModelKit/NodeNames.swift` (holds `ManualAlignment.intersectFloor` / `floorHeight`)
- **MAN** = `IVModelKit/Manifest.swift`

## 0. Recent AR history (git log -15)

| Commit | Date | What changed |
|---|---|---|
| 37e6325 | 10-08 | Visual restyle only (ink/paper/red, square corners). |
| 8730eb4 | 10-07 | Starting-storey selection: `Manifest.startingStorey` + `chooseStartingStorey()`. |
| 7030e24 | 10-07 | Room corrections (RC), LidarAim, wall marks, observations, admin room save; large PlateAnchoring/ReferenceAlignment growth. |
| 73317c3 | 10-07 | Local alignments (AlignmentSites), "Fix here", line-of-sight corner disambiguation, site ARAnchors. |
| 34c91fa, 43a4518, 4ee9824 | 10-07 | Behind-wall fade (ProximityFade / WallOcclusion / ProximityTracker), hide rather than 35 %. |
| e66ee1c, dff0c16 | 10-06 | `worldFloorY` rule: lowest floor-classified plane, else lowest plane ≥ 0.25 m². |
| 130347d | 10-06 | `FindingFloorRow`. |
| f6d3ffd | 10-06 | `lastOutcome` for copied details. |
| 7b2d4f3 | 10-06 | Floor change tracking (`onFloorChange`, 5 mm) + `ReferenceAlignment.refit`. |
| 5a53469 | 10-06 | `FloorAim` struct (point + eye) and line-of-sight marks. |
| 825a609 | 10-06 | Floor plane detection and crosshair. |
| 683c643 | 10-06 | `SessionRelay` threading, lean marking config, render options. |

---

## 1. ARKit session

**View setup** (AAV 260-289)
- `ARView(cameraMode: .ar, automaticallyConfigureSession: false)`.
- `arView.environment.sceneUnderstanding.options = []`: no scene-understanding occlusion, no physics, no receivesLighting.
- Render options off: motion blur, depth of field, camera grain, HDR, grounding shadows (AAV 270).
- No `frameSemantics` is ever set: no people occlusion, no sceneDepth, no smoothedSceneDepth. Systems draw over the camera without any occlusion.
- One `AnchorEntity(world: .zero)` (`worldAnchor`) holds the building root, the crosshair marker and the mark discs.

**Coaching overlay** (AAV 276-281, 563-571)
- `ARCoachingOverlayView`, `goal = .horizontalPlane`.
- `activatesAutomatically` is true only when `anchoring.isManual && state.transform == nil && !isAiming`. Otherwise it is forced off with `setActive(false)`.
- In practice it shows only while placing by hand before the first placement. Plates have their own coaching text, and the crosshair has its own row.

**Configuration** (`makeConfiguration`, AAV 405-428), derived from `Settings` (AAV 380-401). The session is re-run only when `Settings` changes, or with `force`.

| Field | Rule |
|---|---|
| `planeDetection` | `[.horizontal]`; `[.horizontal, .vertical]` when `walls = isAiming && aimsAtWalls`. `aimsAtWalls = hasLiDAR && !thermalLimited && allowsWallAims` (AAV 237). |
| `environmentTexturing` | `.none` when `thermalLimited \|\| lean` (`lean = isMarking`; not during Fix here); else `.automatic`. |
| `sceneReconstruction = .mesh` | When `!thermalLimited && !isMarking && anchoring.isManual && supportsSceneReconstruction(.mesh)` (AAV 399). `anchoring.isManual` is also true after an alignment by points and before plates are configured (see §10). No raycast in the code asks for mesh geometry. |
| `videoFormat` | When hot: of the formats with fps ≤ 30, the smallest by width × height (AAV 431-435). |
| `detectionImages` | Union of (a) placed plates' reference images when `anchoring.wantsImageDetection && !isMarking` (`detectionSet`, AAV 375-377), and (b) the plate being registered (`registrationImage`, always included, even while marking). Only set when non-empty. |
| `maximumNumberOfTrackedImages` | 1 whenever images are set. Images are therefore tracked continuously: the anchor updates every frame, which is how the 10-sample smoothing window fills quickly. |
| `automaticImageScaleEstimationEnabled` | false |

**Reference images** (AAV 315-355)
- `configure(plates:scannedPlate:corrections:)` creates a new `PlateAnchoring` (resetting all alignment state). It then downloads every placed plate's `imageUrl` concurrently (a task group over `URLSession.shared`, accepting only 2xx).
- Each image becomes `ARReferenceImage(cgImage, orientation: .up, physicalWidth: plate.physicalWidth)`, where `physicalWidth = sizeMm / 1000` (MAN 245; `sizeMm` is the printed width including the quiet zone, MAN 226-228).
- Names are `"plate-<number>"`. As each image arrives, `reconfigureIfNeeded()` runs.
- Registration image: `watchPlate(number:imageURL:physicalWidth:)` downloads it, names it `"register-<number>"` and reconfigures (AAV 1016-1024). Its width is `BuildingPlate.sizeMm / 1000` (AEV 353).

**Running and re-running**
- `run()` (AAV 359-365): `isRunning = true`, `UIApplication.isIdleTimerDisabled = true` (screen never sleeps), `session.run(config)` with no options (never reset), then `reconcileSiteAnchors()`.
- `pause()` (AAV 367-371): idle timer back on, `session.pause()`.
- `reconfigureIfNeeded(force:)` (AAV 440-453) runs again without reset options. Before running, if the detection set grew, or the registration image changed to a new one, it removes all `ARImageAnchor`s, because ARKit doesn't re-report an image it already anchored (AAV 445-450, 455-459).
- Re-runs are triggered by: phase changes, alignment events, marking or fixing start and stop, the thermal change, a reference image arriving, registration watch and stop, `realign()` (force, after removing image anchors), and session resume (force).

**Thermal** (AAV 284-288, 462-467)
- An observer on `ProcessInfo.thermalStateDidChangeNotification`; `limited = thermalState >= .serious`; reconfigure on change.
- `thermalLimited` starts `false` and is never read at start. A phone already hot when AR opens is treated as cool until the next change.
- When hot: no mesh, no environment texturing, the ≤ 30 fps smallest format, and no wall or corner aims (`aimsAtWalls` false).

**Interruption and relocalization**
- `sessionShouldAttemptRelocalization` returns true (AAV 1378).
- `sessionWasInterrupted`: `tracking = .interrupted` and `anchoring.trackingLost(now)` immediately (AAV 1214-1217).
- `sessionInterruptionEnded`: `tracking = .relocalizing`, `lostSince = now`, `reconfigureIfNeeded(force: true)` (AAV 1219-1224).
- Tracking state mapping (AAV 47-57):

| ARKit state | `TrackingStatus` |
|---|---|
| `.normal` | `.normal` |
| `.notAvailable` | `.notAvailable` |
| `.limited(.excessiveMotion)` | `.excessiveMotion` |
| `.limited(.insufficientFeatures)` | `.insufficientFeatures` |
| `.limited(.relocalizing)` | `.relocalizing` |
| other `.limited` (initializing) | `.initializing` |

- `trackingChanged` (AAV 1198-1212):
  - `.normal`: `lostSince = nil`; if the phase is `.lost`, `trackingRestored` and reconfigure.
  - `.relocalizing` or `.notAvailable`: start `lostSince` if unset.
  - Other states don't start or clear it.
- In `frameUpdated` (AAV 1155-1158): if `now - lostSince >= lostAfter (2 s, AAV 240)`, an alignment exists, and the phase isn't `.lost`, then `trackingLost` and reconfigure.

**SessionRelay threading model** (AAV 1303-1381)
- The `ARSessionDelegate` lives on its own serial queue `"com.getinsiteview.ar-session"`, QoS userInteractive (`session.delegateQueue = relay.queue`, AAV 273-274).
- `didUpdate frame`, on the AR queue:
  - Reduces the frame to a `FrameSample`: `timestamp`, camera position (`camera.transform.columns.3`), `viewMatrix(for: interfaceOrientation)`, and focal lengths `projectionMatrix(for: orientation, viewportSize, zNear 0.01, zFar 1000)` [0][0] and [1][1] (only when the viewport is non-empty).
  - Puts it into a one-slot `Mutex` mailbox. If the slot was empty it dispatches `main.async`, which takes the newest sample and calls `frameUpdated`. Otherwise it overwrites the slot and returns.
  - Result: the main thread always gets the newest frame, older ones are skipped, and an `ARFrame` is never retained.
- Anchor add, update and remove each become an `AnchorChange` value object (AAV 1241-1295), dispatched in order to main (`anchorsChanged`). Empty changes are dropped. Each change carries:
  - horizontal `ARPlaneAnchor`s → `hasHorizontalPlane`; and as floor candidates if classification is `.floor` or `planeExtent.width * height >= 0.25` (AAV 1278), with height = `transform.columns.3.y`;
  - `ARImageAnchor`s with `isTracked == true` → `(name, PlateFrame(anchorTransform:))`;
  - plain anchors named `"insiteview-site-<UUID>"` → `(id, YawTransform(anchorTransform:))`;
  - removals as identifiers.
- Tracking state, interruption and resume are dispatched to main.
- The viewport (interface orientation + `arView.bounds.size`) is written by `layoutSubviews` into a `Mutex` (AAV 297-302).

**Frame loop on main** (`frameUpdated`, AAV 1133-1164), every delivered frame:
1. `anchoring.tick(t)` then `cameraMoved(to: camera, t)`; handle any event and reconfigure on a phase change.
2. If `!isMarking` and the building follows the anchoring (`!isManual || alignment.method == .points`), apply `anchoring.transform(at: t)` when it changed or the root is disabled.
3. The lost-after-2 s check.
4. While aiming: `updateCrosshair`.
5. `updateLocate`.
6. `updateProximity`.

**When the session runs** (AEV)
- `ARExperienceModel.start()` (AEV 138-155) is called from `ARContainer.onAppear` (AEV 523-528), which only appears once `canShowCamera`.
- `canShowCamera = started || readyForCamera` (AEV 134-136). `readyForCamera` turns true once `OrbitViewers.live == 0`, polled every 50 ms, or after 1 s at most (AEV 114-132). Until then the screen is black. Reason: a living `.nonAR` ARView turns the AR camera feed black. `claimScene()` also calls `session.claimScene(claimID, .ar)` and `OrbitViewers.releaseForAR()`.
- `stop()` (AEV 388-412) on `onDisappear`: end registration, cancel points, end fix, `holdsSceneWork = false`, `view.pause()`, `view.detach()`, `OrbitViewers.restoreAfterAR()`, `session.select(nil)`, `session.locate(nil)`, `releaseScene`.
- There is no explicit pause on backgrounding. ARKit interrupts itself, and `scenePhase .active` only refreshes the preflight (AEV 506-508).
- `accessProblem != nil` dismisses AR (AEV 510-512).

---

## 2. Raycasts, hit tests and the floor

**Raycasts in code**

| # | Where | Query | From | Used for |
|---|---|---|---|---|
| 1 | `place(at:)` AAV 577-586 | `arView.raycast(from: point, .existingPlaneGeometry, .horizontal)` + `(.estimatedPlane, .horizontal)`, first hit | Tap location, only when the state is `.findingFloor`/`.readyToPlace` and `anchoring.isManual` | First manual placement: `MA.placingFloor(elevation: floorElevation, at: hit, cameraForward)`. The storey floor goes on the hit, the model origin's horizontal centre at the hit, yaw facing the camera (`atan2(-fwd.x, -fwd.z)`), MA 94-108. |
| 2 | `wallHit(at:eye:)` AAV 988-1003 | `.existingPlaneGeometry, .vertical` + `.estimatedPlane, .vertical` | Screen centre, plus two side points (below) | LiDAR wall/corner aims. Skips hits whose anchor is an `ARPlaneAnchor` classified `.door` or `.window`. Hit normal = `worldTransform.columns.1`. `LA.Plane(point:normal:eye:)` rejects tilt > 20° (LA 15-24, 38) and flips the normal towards the eye. |
| 3 | Side points of #2 (AAV 972-975) | Project `middle.point ± tangent * 0.18 m` (`LA.sideReach`, LA 41) to screen with `arView.project`, then #2 again | — | Corner detection: `LA.target(centre:left:right:)` / `LA.corner` = two walls whose normals' 2-D determinant ≥ sin 45° (45–135°), meeting within 0.10 m of the middle hit horizontally (LA 43-45, 52-72). |
| 4 | `arView.entity(at: point)` AAV 1063 | RealityKit collision hit test | Tap, when the state is `.adjusting`/`.locked` and not aiming | Object card. The scene walks up to the `e<32 hex>` element (SCN 325-335). Collisions are per-element boxes (SCN 343-368, `PickingShape`). |

No raycast uses mesh geometry.

**Analytic ray–plane intersections** (not ARKit raycasts): `MA.intersectFloor(origin, direction, height)`. Valid when `|dir.y| > 1e-6` and `0 < t < 100` (NN 31-38). Used for:
- **The crosshair**: the ray through the screen centre (`arView.ray(through: centre)`) against `worldFloorY` (AAV 960-968).
- **Drag**: `floorPoint` = the ray through the touch against the placed storey floor `transform.translation.y + floorElevation` (AAV 645-650, NN 41-43).
- **Twist pivot** and **nudge pivot** (the screen centre's floor point, falling back to `transform.apply((0, floorElevation, 0))`).
- **`ReferenceMark.onFloor`**: re-intersects a mark's line of sight with a new floor (REF 150-164).

**Floor height: `worldFloorY`** (AAV 184-188)
- Candidates are the `floorPlanes[UUID]`: horizontal planes that are classified `.floor` or have an area of at least 0.25 m² (AAV 1273-1281).
- If any candidate is classified floor, take the minimum height among those. Otherwise take the minimum height among all candidates.
- Planes are removed when ARKit removes them (AAV 1098-1100).

**Floor change tracking** (`noteFloorPlanes`, AAV 1033-1042, on every anchor change)
1. Run the floor glue.
2. Then, if `worldFloorY` moved by 5 mm or more since last reported (or never reported), call `onFloorChange(floorY)`.

**Floor refitting** (PA 232-249, `floorMoved`)
- The mark discs move to `marks.map { $0.onFloor(at: worldFloorY) }`:
  - floor marks re-intersect their line of sight (`seenFrom → position`) with the new height;
  - `.edge` marks just take the new y;
  - wall marks are unchanged;
  - no change if the floor moved more than `maximumFloorChange = 0.15` m (REF 144).
- In `.checking(fit)`: `REF.refit(fit, marks:, worldFloorY:)` keeps the same matches and refits the transform, residuals and rms (REF 641-657), then updates the preview.
- In `.choosing`: refit every option.
- In `.marking`: no re-solve; only the discs move.

**FloorAim** (AAV 72-78): `point`, `eye` (camera), `surface` (`.floor` / `.edge` / `.wallPlane`), and a horizontal `normal` for walls.

**Crosshair state machine** (`crosshairTarget`, AAV 958-984; `CrosshairState` AAV 81-104)
1. `worldFloorY == nil` → `.noFloor`. This applies even to LiDAR wall aims, which also need a floor.
2. `tracking != .normal`, zero width, or no ray → `.notTracking`.
3. Ray direction with `-dir.y >= cos(35°)` (`maximumAimAngle`, AAV 253) and a floor intersection → `.ready`, `FloorAim(point, eye)`.
4. If `!aimsAtWalls` → `.tooShallow`.
5. No wall hit at the centre → `.noSurface`.
6. Two side hits forming a corner → `.onCorner`, point = `(corner.x, worldFloorY, corner.z)`, surface `.edge`.
7. Otherwise → `.onWall`, `FloorAim(plane.point, eye, .wallPlane, normal)`.

- `canAim = state ∉ {noFloor, notTracking}`; `canMark = state ∈ {ready, onWall, onCorner}`.
- Update rate: every frame while capturing, otherwise 15 Hz (AAV 938-952).
- The marker is enabled only when an aim exists, positioned at the aim point and oriented to the wall normal (quaternion from +Y to the normal), else flat (AAV 943-950).

**Crosshair marker** (AAV 905-916)
- A 0.12 m circular plane, unlit white at 35 % opacity, 2 mm up.
- A 0.012 m dot, unlit white, 3 mm up.
- Shown only while aiming (marking or fixing).

**Capture ("Mark" or "Fix")** (`captureFloorAim`, AAV 703-737)
- Guards: `isAiming`, not already capturing, `crosshair.canMark`.
- Collects every frame's aim for 500 ms (`captureDuration`, AAV 255; a wall-clock `Task.sleep`). Any frame with no aim sets `captureBroken`.
- Fails (nil) if broken or there are fewer than 5 samples (`minimumCaptureSamples`, AAV 256).
- If any sample is non-floor, all samples must convert to LidarAim targets (`.edge` → corner, `.wallPlane` → wall). A `.floor` sample mixed in fails.
  - `LA.average(targets, minimum: 5)`: all walls or all corners. Walls are kept within 1.5 cm of the median point and 2° of the circular-median heading. Corners are kept within 1.5 cm (LA 47-48, 77-99).
  - The eye is the mean of all eyes.
- Floor samples: the component-wise median point; keep samples within 0.02 m of it (`captureTolerance`, AAV 258); need ≥ 5 kept; average point and eye.

**Mark discs** (AAV 742-783)
- A 0.06 m circular plane (cornerRadius 0.03), unlit `systemGreen`.
- Floor marks: the disc sits 3 mm above the mark.
- Wall marks: oriented to the normal, 3 mm out along it.
- `moveMarkDiscs` always applies the +3 mm y offset (AAV 763-771). A wall mark's disc therefore loses its normal offset when the floor changes. This is a minor bug; don't copy it.

**FindingFloorRow** (PA 372-389)
- A `ProgressView` plus:
  - `.noFloor`: "Finding the floor. Move the phone slowly, pointing at the floor."
  - otherwise: "Finding your place. Move the phone slowly around the room."
- It replaces the Undo/Mark buttons (PA 492-518) and the Fix card's instruction and Fix button (AEV 813-843) whenever `!(crosshair.canAim || capturing)`.
- The screen crosshair is hidden in that case too (AEV 547-551).

---

## 3. Alignment state machine, from AR open to aligned and afterwards

### 3.1 States

`PlacementState`, the view-level state (AAV 19-35):
- `findingFloor`
- `readyToPlace` (a horizontal plane has been seen; `anchorsChanged` promotes from `findingFloor` only when `anchoring.isManual`, AAV 1103-1108)
- `adjusting(T)`
- `locked(T)`

`PlateAnchoring.Phase` (PAN 60-71):
- `searching`
- `manual`
- `aligned`
- `realigning(requested: Bool)`
- `lost`

PlateAnchoring constants:

| Constant | Value | Where |
|---|---|---|
| `fallbackDelay` | 10 s | PAN 24 |
| `reanchorDistance` | 4.0 m | PAN 26 |
| `realignTimeout` | 15 s | PAN 28 |
| `followDistance` | 0.02 m | PAN 31 |
| `followAngle` | 0.25° | PAN 32 |
| `turnWindow` | 120 s | PAN 36 |
| `turnReach` | 0.5 m | PAN 39 |
| `isDetecting` | a detection within the last 1 s | PAN 567-570 |

Derived values:
- `wantsImageDetection` (PAN 149-155) = plates non-empty and phase ≠ `aligned`. This includes `manual`, `realigning` and `lost`.
- `isManual` (PAN 186-192):
  - `.manual` → `alignedPlate == nil` (so true after an alignment by points);
  - `.lost` → `alignment.method.isByHand`;
  - otherwise false.
- `canFix` (PAN 223-229): `(phase .manual, method .points)` or `(phase .aligned, method .plate)`. False while realigning or lost.

### 3.2 Opening

1. **Preflight** (`ARPreflightStep.next`, `IVCore/GuestOnboarding.swift` 22-30):
   - explainer not seen → `.explainer`;
   - camera `.notDetermined` → `.cameraExplainer`;
   - `.denied` → `.cameraDenied(canOpenSettings: true)`;
   - `.restricted` → `.cameraDenied(false)`;
   - authorized → `.ready`.
   - The step is re-evaluated when `scenePhase` becomes active (after returning from Settings).
2. **Claim the scene** (AEV 559, 114-132).
3. **`start()`** (AEV 138-155):
   - `openedAt = Date()`, `claimScene`, `chooseStartingStorey()`, `updateFloorElevation()`, `refreshRooms()`, `configurePlatesIfNeeded()`;
   - `view.attach(scene)`: the root moves under `worldAnchor`, disabled; if a transform already exists, apply it (AAV 473-481);
   - `view.run()`, `updateLocateTarget()`, `session.select(session.locating)`;
   - `track(.arOpened)`;
   - show the safety note if not seen.
4. **Before the manifest arrives**:
   - The view's `anchoring` is `PlateAnchoring(plates: [], …)`, so phase `.manual` (PAN 143; AAV 127).
   - `model.anchoring` is nil until `onAnchoringChange` fires. With no plates, `configure()` produces the same phase, coaching and alignment, so `onAnchoringChange` never fires and `model.anchoring` stays nil.
   - The coaching line says "Loading the model…".
5. **`configurePlatesIfNeeded()`** (AEV 159-170), once, when the manifest is present (also called on `manifest != nil` change, AEV 564-566):
   - `chooseStartingStorey`, `updateFloorElevation`, `refreshRooms`;
   - `view.configure(plates: session.plates, scannedPlate: session.scannedPlate, corrections: session.roomCorrections)`;
   - if there are no placed plates and `canAlignByPoints`, call `startPoints()` immediately.
   - With no plates and no outlines, the phase stays `.manual`: floor placement with the ARKit coaching overlay.

**Starting storey** (AEV 172-179; MAN 354-360)
- Only if `session.arFilters.storeyID == nil` and the manifest has more than one storey.
- `Manifest.startingStorey` = the lowest storey by `order` that has a space with `storeyId`; otherwise the lowest storey.
- Applied with `session.setStorey(id, in: .ar)`.
- `updateFloorElevation` (AEV 414-419) sets `view.floorElevation` to the storey's elevation, or 0 when no storey is chosen.
- The `floorElevation` setter (AAV 130-142) keeps the same world floor height when switching storeys, but only for a manual placement (not marking, not points).
- `PointsAlignment` also switches AR to the selected room's storey (`showRoomLevel`, PA 110-115).
- `onChange(arFilters.storeyID)` → `updateFloorElevation` (AEV 561-563).

### 3.3 Plate detection, smoothing and aligned

- `observeImages` (AAV 1116-1129):
  - `"register-"` images go to `onRegistrationDetection(frame, cameraPosition)` and nothing else;
  - `"plate-N"` images are ignored while marking;
  - otherwise `anchoring.observe(plate: N, world: PlateFrame, camera:, at: lastFrameTime)`.
- `PlateFrame(anchorTransform:)` (AM 10-14, PAL 24-26): right = x, **up = −z**, **normal = y**, position = column 3.
- `observe` (PAN 235-245) requires `wantsImageDetection` and the plate to be in `plates` (placed, corrected by room). It records `lastDetection` and feeds `AlignmentSmoother(plate:, model: plate.frame)`.
- **Solver** (`PlateAlignment.solve`, PAL 62-70):
  - Surface: `|normal.y| > sin 45°` is horizontal, else wall (PAL 32-37).
  - The world surface must equal the model's.
  - Heading = the normal for walls, `up` for horizontal plates, flattened. It must keep ≥ 0.5 of its length (`minimumHorizontalLength`, PAL 56).
  - yaw = `atan2(x, z)` difference; `t = p_w − RotY(yaw)·p_m`.
- **Smoother** (PAL 96-164):
  - window 10, `positionTolerance` 0.03 m, `angleTolerance` 2°, `minimumInliers` 6;
  - nothing until 10 samples are in the window;
  - then: median position, circular-median yaw; inliers within 3 cm and 2°; need ≥ 6; median of the inlier positions and circular mean of their yaws.
  - Rejected detections (wrong surface) count `rejected` and are not added.
- **Converged** → `align(on:)` (PAN 483-505):
  - the plate's room (`corrections.room(ofPlate:)`) sets the camera room;
  - blend from the current as-built transform over **0.5 s**, smoothstep, pivot = the plate's model position (PAL 199-231);
  - alignment = `.plate(N)` with the camera position;
  - if the phase was `realigning(requested: false)`, `sites.add(site)`; else `sites.startOver(site)`;
  - phase becomes `.aligned` (detection off), and the event `.alignedOnPlate(N, firstIn)` fires.
- **View** `handle(.alignedOnPlate)` (AAV 542-561): `state = .locked(transform)`, coaching off, reconfigure (detection off), `onAlignmentEvent`.
- **Model**: `alignments += 1` (success haptic); `ar_aligned{method: plate, ms}` if it's the first alignment (AEV 425-448).
- **Coaching while searching** (PAN 157-175):
  - `.holdStill(n)` when the last-detected plate's smoother has samples and the phase is `searching` or `realigning(true)`;
  - else `.pointAtScannedPlate(n, label)` if the scanned plate is placed;
  - else `.pointAtAnyPlate`.

### 3.4 Fallback

- **"No plate? Align by points"** (AEV 1022-1055), in the coaching actions:
  - Shown only when `anchoring != nil`, `!anchoring.isManual`, and (`!isAligned` or phase `.lost`).
  - A `TimelineView` re-evaluates every 1 s. The button appears when `now - openedAt > 4 s` (AEV 1034; `openedAt` = AR start, a Date), or immediately when the phase is `.lost`.
  - Label "No plate? Align by points" (→ `startPoints()`) if `canAlignByPoints`, else "Place manually instead" (→ `view.placeManually()`).
  - If `canFixHere`, "Model off? Fix here" replaces it.
- **Automatic fallback** (`tick`, PAN 433-435): in `.searching`, at least `fallbackDelay` 10 s since `phaseSince` and not detecting in the last 1 s → phase `.manual`, event `.fellBackToManual`.
  - `phaseSince` is the `PlateAnchoring` creation time (configure, i.e. when the manifest arrived), not AR open.
  - View (AAV 550-554): if not marking, `state = readyToPlace` or `findingFloor`.
  - Model (AEV 442-446): if `points == nil && canAlignByPoints`, `startPoints()`.
  - Detection keeps running in `.manual`, so a plate seen later still aligns, and `sites.startOver` replaces the manual or points alignment.
- **`canAlignByPoints`** = `PointsAlignment.rooms(in:)` is non-empty. That is the session's `rooms()` (every manifest space, ElementIndex.roomList) filtered to those with a valid `RoomOutline(space:)`: an outline of ≥ 3 finite 2-D points plus a finite `floorY` (RO 42-52). Refreshed on start, configure, storey change and `sceneRevision`.

### 3.5 Points alignment, step by step (PA, AAV)

1. **`startPoints()`** (AEV 188-195):
   - guarded by `started && points == nil`;
   - `endFixHere()`;
   - `located = PointsAlignment.room(around: view.cameraInModel)` (the camera's room under the current alignment, computed before marking hides the building);
   - `view.startMarking()`;
   - `points = PointsAlignment(...)`.
2. **`view.startMarking()`** (AAV 657-665): `isMarking`; state `readyToPlace`/`findingFloor`; root disabled (building hidden); crosshair shown; coaching overlay off; reconfigure (lean: no texturing, no plate detection; vertical planes with LiDAR).
3. **`PointsAlignment.init`** (PA 55-71):
   - Room preselection: the located room, if it's eligible; else `arFilters.roomID` if eligible; else the only eligible room; else none, and the room picker shows.
   - `showRoomLevel()`; set `onCrosshairChange` and `onFloorChange`; `session.holdsSceneWork = true`.
   - `step.didSet` keeps `holdsSceneWork = (step == .marking)` (PA 37-39). Chunk application waits in a 200 ms polling loop while it's set (BS 288-290).
4. **Room picker** (PA 418-446): "Which room are you in?", a scrollable list (max height 240), names or "Unnamed room", and "Cancel".
   - Selecting a room (`selectRoom`, PA 101-105) switches to that room's storey and calls `restart()`.
5. **Marking** (`step .marking`, PA 488-524):
   - Room line: a menu to change room (with "Change room" accessibility hint), and a counter `"%lld of %lld"` showing `min(marks, needed)` of `needed`.
   - Long-pressing the counter copies details as JSON (PA 263-306): room, `worldFloorY`, `floorPlanes`, `cameraY`, references, marks (marked vs on-floor, `seenFrom`), outcome. "Details copied" shows for 2 s.
   - If `crosshair.canAim || capturing`: the instruction (§4) plus [Undo] (disabled with no marks or while capturing) and [Mark] / "Hold still…" (disabled unless `canMark`). Otherwise `FindingFloorRow`. Then "Cancel".
   - Impact haptic on each new mark.
   - `marksNeeded` (PA 137-139) = 3, or 2 if any mark is a `.wallPlane` (REF 222-224).
   - `references` = `ReferencePoints.make(outline:, objects: [], floorY:, walls: hasLiDAR)` (PA 131-134): corners `corner-i`, plus walls of length ≥ 0.4 m (REF 74) as `wall-i` when on LiDAR. Objects (outlets) are never passed.
6. **`mark()`** (PA 149-160): `view.markCorner()` → capture (§2) → a disc → `ReferenceMark(position, surface, seenFrom: eye, normal)`. A nil result sets `markProblem = .unsteady`.
7. **`evaluate()`** (PA 198-228): `marksOnFloor`; move the discs; if count < needed → `.marking`, preview hidden. Else `REF.solve(marks:, references:, worldFloorY:, outline:)` (REF 305-353):
   - Marks needed: 3, or 2 with a wall. Otherwise `.needsMore(.morePoints)`.
   - Without walls: horizontal spread must be ≥ 1.0 m (`.spreadOut`). Collinear within 0.2 m and spread < 2.5 m gives `.anotherWall`. Walls only, none crossing (within 15° of parallel), gives `.anotherWall`.
   - Corner or wall marks but no corner references → `.noMatch(.noCorners)`.
   - Strict pass, then relaxed:
     - `pair` 0.15 / 0.40 m, `height` 0.10 / 0.25, `maxRMS` 0.06 / 0.20, `wallAngle` 3° / 6° (REF 229-245).
     - Backtracking assignment, one skip allowed when marks > needed, search limit 200 000.
     - Each wall turned onto its mark within `wallAngle`, and the mark within its wall's half-length + 0.3 m (REF 668-674).
     - Fits whose mark cameras are more than 0.3 m outside the outline are dropped (REF 797-804).
     - `distinct()` merges identical fits.
   - No fits → `.noMatch(.tooFar)`.
   - Rivals: a later fit is not a rival when `score >= 2·best && score − best >= 0.04` (`ambiguityMargin`; score = rms, +0.02 if a mark was dropped), or when it doesn't explain every mark.
   - No rivals → `.matched(best)`.
   - Rivals that are all the room's symmetry (every corner lands on some corner within `pair`, and no outlet marks): `.ambiguous([best] + rivals).prefix(4)`.
   - Rivals that aren't symmetric with marks == needed → `.needsMore(.morePoints)`.
   - Rivals with more marks than needed → `.ambiguous`.
   - Quality (REF 204-217): rms ≤ 0.03 good; ≤ 0.06 (the strict max) fair; else approximate.
   - Outcome mapping: `needsMore` → hint, stay marking. `matched` → `.checking(fit)` with a preview (`view.previewAlignment(T)`: state `.adjusting`, root enabled at T, AAV 787-795). `ambiguous` → `.choosing(fits, shown: 0)` with a preview. `noMatch` → stay marking with a message.
8. **Checking** (PA 557-587): roomLine, "Does the model line up with the room?", the summary (§4), [Start over] (`restart`: clear marks, discs and preview), [It matches] (`confirm(fit)`), and the link "Mark another corner" (or "Mark another" with LiDAR), which goes back to `.marking` with the marks kept.
9. **Choosing** (PA 589-618): "The room fits more than one way. Which one lines up?", a segmented picker "Option 1…n" (each change previews that fit), [Start over], [This one].
10. **Confirm** (AEV 205-212 → AAV 801-810):
    - `view.confirmPoints(fit.transform, around: fit.centre, modelFloorY: room.floorY ?? 0, room: id)`;
    - `anchoring.alignedByPoints` (PAN 265-278):
      - as-built = `corrections.transform(of: room).inverse.then(fit)`;
      - alignment `.points`;
      - `sites.startOver(site at fit.centre)` (the matched references' world middle, or the marks' middle when walls are among them);
      - camera room = this room; phase `.manual`; event `.alignedByPoints(firstIn)`.
    - `pointsFloorY` is stored; `stopMarking()` (discs cleared, crosshair hidden, `state .locked(T)`, apply); `handle(event)`; reconfigure (plate detection resumes because phase `.manual` has `wantsImageDetection`).
    - Model: `ar_aligned{points}` if first; success haptic; `lastPoints` stored for registration; `holdsSceneWork = false`.
11. **Cancel** (AEV 198-203; AAV 669-682): `stopMarking` restores the previous alignment if `anchoring.transform` exists (`state .locked`), else floor-placement state with the building hidden.

### 3.6 Manual placement and fine-tune

- Entered by:
  - fallback with no outlines;
  - ⋮ "Place manually" (any time `!isManual`) → `view.placeManually()` (AAV 516-526): `switchToManual`, which starts from the drawn transform, clears sites and the camera room, and sets phase `.manual`. State becomes `.adjusting(current)` if there was an alignment, else `readyToPlace`/`findingFloor`;
  - ⋮ "Adjust placement" from `.locked` → `unlock()` (AAV 607-616). It uses `lastApplied`; if the alignment isn't manual, or is points, it `switchToManual` (this discards the points alignment and its sites).
- Tap the floor → raycast #1 → `.adjusting`.
- One-finger pan (AAV 1067-1080): the horizontal delta between floor intersections on the placed floor (`MA.dragged`).
- Rotation gesture (AAV 1082-1090): `angle = -recognizer.rotation` (clockwise on screen = negative yaw), pivot = the floor point under the gesture.
- **Fine-tune** (`FineTunePad`, AEV 1131-1171):
  - eight repeatable buttons: Turn left / Move away / Turn right / Raise, then Move left / Move closer / Move right / Lower;
  - ±0.01 m (`nudgeDistance`, MA 74) and ±0.5° (MA 76);
  - forward = the camera forward flattened; turns are about the pivot = the floor point at the screen centre (AAV 589-594; MA 136-157).
- **[Done]** → `lock()` (AAV 597-604): `state .locked`, `anchoring.placedManually` (method manual, sites cleared, camera room nil, phase manual), event `alignedManually` → `ar_aligned{manual}` if first. `showsFineTune = false`.
- **⋮ "Place again"** (when `placement.transform != nil` and manual) → `reset()` (AAV 619-624): `clearedManualPlacement`, state `readyToPlace`/`findingFloor`, root hidden.
- While manual and not points, the building follows `state.transform`, not `anchoring`. `apply()` is called directly by `setManualTransform`.

### 3.7 Sites, local alignments and following

- **AlignmentSites** (SITES 85-178):
  - `switchMargin` 1.0 m, `replaceRadius` 1.5 m, `maximumCount` 16, `storeyBand` −1.5…+2.5 m (camera y − site y).
  - `select(near:)`: the nearest on-storey site takes over when it's 1 m nearer than the current one, or when the current one is off-storey.
  - Site transform = `base.then(anchorAtCreation⁻¹.then(anchorNow))`: the anchor yaw comes from its z axis (AM 21-26).
- **`followSites`** (PAN 510-527, called from `cameraMoved` every frame and after anchor moves; not while lost):
  - choose a site; if the current site's transform differs at the camera's model point by ≥ 2 cm or ≥ 0.25°, blend 0.5 s about that point and adopt it.
- **`followCameraRoom`** (PAN 532-536): for non-manual methods with corrections present, `room(containing: alignment⁻¹(camera), previous:)` (RC 169-175). Another room takes over once the camera is ≥ 0.30 m inside it (`switchDepth`, RC 128); outside every room the previous one stays. The room correction glides over 0.5 s.
- **Site anchors** (AAV 884-897): one `ARAnchor(name: "insiteview-site-<uuid>", transform: site.anchor.matrix)` per site, added and removed to match `anchoring.sites` (on `run()` and whenever the site ids change). Anchor updates → `siteAnchorsMoved` (AAV 1110-1113). Controlled by `followsSiteAnchors = true` (AAV 247).
- **Auto re-anchor** (PAN 454-463): only in phase `.aligned`, which means plate alignments only. A camera more than 4 m from `alignment.cameraPosition` → `realigning(requested: false)` (detection on, smoothers cleared).
  - A converged plate → `sites.add`.
  - After 15 s with no detection → back to `aligned`. The next frame is still more than 4 m away, so it re-enters realigning straight away. In practice detection stays on beyond 4 m.
- **Floor glue** (AAV 868-880; MA 115-125):
  - Runs on every floor-plane change, when not aiming, with `pointsFloorY` set and method `.points`.
  - `change = worldFloorY − (T.y + modelFloorY)`, applied only if 0.005 ≤ |change| ≤ 0.15.
  - Then `raisedPointsAlignment(dy)`: every site raised, a 0.5 s blend about the origin, `state .locked(glued)` (PAN 379-389).
- **Corrections update** (PAN 348-373, from an admin save): plates re-corrected, smoothers cleared, each site retargeted so its room stays put; the building blends with the camera's model point as pivot.

### 3.8 Fix here

- **Entry**: ⋮ "Fix here" or "Model off? Fix here".
- **`canFixHere`** (AEV 235-238): started, and no points, registration or fix in progress, and `anchoring.canFix`.
- **`startFixHere`** (AEV 240-246): `view.startFixing()` (crosshair shown, `isFixing`, coaching off, reconfigure; with LiDAR, vertical planes; texturing and detection unchanged; building stays visible). `onCrosshairChange` drives the card; `holdsSceneWork = true`.
- **`fix()`** (AEV 248-280):
  1. Capture the aim (§2). If there's no aim, or `fixableAlignment` is nil → `.unsteady`.
  2. `current = view.fixableAlignment` = the as-built `anchoring.alignment.transform` (AAV 817-819).
  3. Wall aim with a normal → `corrections.reanchor(current, wall:, normal:, seenFrom: eye)`. Otherwise → `reanchor(current, mark:, seenFrom:)`. Both run against the corrected outlines of all rooms (RC 206-231). Rules:
     - **Corners** (REF 427-467):
       - rooms whose floor is within 0.5 m of the mark's model y;
       - corners from `RO.corner(at:)` (outline turn ≥ 10° and ≤ 170°, RO 87-113) within 0.75 m;
       - facing margin ≥ −0.05 m from the line of sight;
       - across-wall pairs (different rooms, corners 0.03–0.6 m apart): the one the camera faces 0.02 m better wins;
       - the nearest wins, but fail if the next different corner is less than 2× as far, or an across-wall rival remains;
       - result: translate horizontally so the corner lands on the mark.
     - **Walls** (REF 486-519):
       - rooms with mark y − floorY in −0.5…3.0 m;
       - walls ≥ 0.4 m whose inward normal is within 10° of the mark's normal;
       - the mark within 0.3 m of the wall and within its ends ± 0.3 m;
       - the eye on the room side;
       - the nearest wins; fail if the runner-up is less than 2 × max(d, 0.01);
       - turn ≤ 5° about the mark, then move across the wall only.
  4. No fix → `.notAWall` for a wall aim, `.notACorner` otherwise.
  5. `view.fixAlignment(fix)` → `anchoring.fixedAlignment` (PAN 291-343):
     - **Two corners in a row**: corner kind, previous fix also a corner, created within 120 s → `REF.turned`. That needs the model distance in 2.5–6 m, the marks' distance within 0.04 m of it, and a turn change ≤ 3° (REF 523-546). The straightened turn retargets the sites within half the distance + 0.5 m of the middle.
     - **Observation**: when the fix's room differs from the current site's room, or carries an inherited base, `corrections.observation(...)` (RC 241-260) sets `lastObservation`. Turn is measured for a wall, or for two corners of the same room.
     - Blend 0.5 s about the fixed corner; `sites.add(fix site)`; alignment updated. View `state .locked`.
  6. A new observation → `session.recordRoomObservation(obs, lidar: hasLiDAR)`. This posts `POST /v1/visit/room-observations` in the background (skipped for `.member` access and for read-only buildings; BS 636-640, `IVAPI/BuildingConnection.swift` 189-196). If `obs.hasTranslation` (a corner fix), `roomFix = obs`, which enables the admin save.
  7. `alignments += 1` (haptic); `endFixHere()`. No analytics event fires for a fix.

### 3.9 Re-align, lost and other transitions

- **⋮ "Re-align"** (when `hasPlates`) → `view.realign()` (AAV 509-513): `requestRealign` clears smoothers and `lastDetection`; phase `searching` if there's no alignment, else `realigning(requested: true)`. Image anchors are removed and the session re-runs (force). On convergence, `sites.startOver`. After 15 s with no detection → back to `manual` (if by hand) or `aligned`.
- **Alignment lost**: phase `.lost` (detection on, smoothers cleared). The building stays drawn at its last transform. On `.normal` → `trackingRestored` → `manual` (by hand) or `aligned`, then `followSites`.
- **"Test now"** (registration) → a new `PlateAnchoring` via `configure` (the alignment is discarded; `firstIn` is reset, so a second `ar_aligned` can fire) + `realign()` (AEV 381-386).

---

## 4. What the user sees (exact English)

**Preflight** (PRE)
- Explainer: "Skip"; pages "Point" / "Align" / "Explore" with:
  - "Point your camera at the Insite View plate on the wall, or at the floor."
  - "Hold still for a moment while the model lines up with your room."
  - "See the pipes and wiring inside the walls. Tap one to find out what it is."
  - Buttons "Next" / "Start".
- Camera:
  - Title "Allow the camera" / "Camera access is off".
  - Messages: "Insite View shows the building's pipes and wiring over what your camera sees. Nothing is recorded or uploaded." / "Turn on Camera for Insite View in Settings to see the model in your room. You can still explore it in 3D." / "The camera is restricted on this iPhone. You can still explore the model in 3D."
  - Buttons "Continue", "Open Settings", "Not now".

**Top bar** (AEV 581-606): an "xmark" close button (a11y "Close"), the status tag, and the ⋮ menu (a11y "More"). The status bar is hidden while AR is ready.

**Status tag**, first match wins (AEV 608-638):

| # | Condition | Text |
|---|---|---|
| 1 | tracking `excessiveMotion` | "Move more slowly" |
| 1 | tracking `insufficientFeatures` | "Point at a surface with more detail" |
| 1 | tracking `relocalizing` / `interrupted` | "Finding your place again…" |
| 1 | tracking `notAvailable` | "AR isn't available right now" |
| 2 | `points != nil` | "Aligning by points" |
| 3 | phase `lost` | "Alignment lost" |
| 4 | method `points` and phase `manual` | "Aligned by points" |
| 5 | `alignedPlate` | "Aligned at plate %lld · %@" |
| 6 | phase `searching` | "Looking for a plate" |
| 7 | placement `findingFloor` / `readyToPlace` | "Not placed yet" |
| 7 | placement `adjusting` | "Placed manually · adjusting" |
| 7 | placement `locked` | "Placed manually" |

Quirk: during "Re-align" after an alignment by points, the phase is `realigning`, so the tag falls through to "Placed manually".

**⋮ menu**, in order (AEV 641-743):

| Item | Condition |
|---|---|
| "Fix here" | `canFixHere` |
| "Save this room's adjustment" | `canSaveRoom` = admin (`canManagePlates`: not the Clip, `role.canManage`, `memberBuilding.buildingID` present) + `roomFix` + nothing else on screen |
| "Align by points" | no points, no registration, `canAlignByPoints` |
| "Register a plate here" | `canManagePlates`, no registration |
| "Re-align" | `hasPlates` |
| "Adjust placement" | `isManual` and placement `locked` |
| "Place again" | `isManual` and placement has a transform |
| "Place manually" | not `isManual` |
| — | divider |
| "Level" submenu (StoreyPicker, mode .ar) | more than one storey |
| "Room" submenu: picker "Room" with "Whole level", each room name or "Unnamed room" | rooms non-empty; sets `arFilters.roomID` via `focusRoom` |
| — | divider |
| "Safety note" | always |

**Bottom panel**, by priority (AEV 747-765): registration panel > points panel > Fix card > room-save card > standard panel.

**Standard panel** (AEV 767-807)
- Admins whose scanned plate is unplaced: "Plate %lld isn't in the model yet." + [Register its position].
- **Coaching line** (AEV 986-1020):
  - Manifest nil: "Loading the model…".
  - Nothing while points, registration or fix is active.
  - Non-manual anchoring:
    - scanned plate: "Point your camera at the Insite View plate you scanned (plate %lld · %@)."
    - any plate: "Point your camera at an Insite View plate."
    - holdStill: "Hold still…"
    - lost: "Alignment lost. Point at a plate again, or move slowly so your iPhone finds its place."
    - otherwise (`realigning(true)` or other): "Point your camera at an Insite View plate." until aligned, then "Tap a pipe, cable or fixture to see what it is."
  - Manual and lost: "Alignment lost. Move slowly so your iPhone finds its place, or place the model again."
  - Manual by placement:
    - `findingFloor`: "Move your iPhone slowly to find the floor."
    - `readyToPlace`: "Tap the floor where you're standing."
    - `adjusting`: "Drag to move and twist to turn until the model matches the room."
    - `locked`: "Tap a pipe, cable or fixture to see what it is."
- **Coaching actions**: "Model off? Fix here", or after 4 s / when lost, "No plate? Align by points" or "Place manually instead".
- **Manual adjusting**: [Fine-tune] [Done]. The pad caption is "Each tap moves %@ or turns %@." (`units.nudge(0.01)`, `units.angle(0.5°)`). Accessibility labels: "Turn left", "Move away", "Turn right", "Raise", "Move left", "Move closer", "Move right", "Lower".
- Otherwise, when aligned or the manifest is loaded: See inside panel.

**Crosshair overlay** (PA 311-368)
- A 36 pt circle stroke (accent when `canMark`, else white) with a "plus" icon, centred on the full screen with safe areas ignored.
- The label sits 64 pt below the reticle top: "Hold still…" while capturing; otherwise "Move the phone slowly over the floor" / "Move the phone slowly around the room" / "Point the phone more straight down" / "On the floor" / "On a wall" / "On a corner" / "Aim at a wall, a corner, or down at the floor".
- Shown only when (points with a room selected, step `.marking`, and `canAim || capturing`) or (fix active and `canAim || capturing`).

**Points panel instruction** (PA 526-553). Without LiDAR / with LiDAR:
- Unsteady: "Couldn't hold the mark steady. Keep the circle on the corner and try again." / "…Keep the circle on the same wall or corner and try again."
- No corners: "This room has no corners in the model."
- Too far: "These corners don't match %@. Check the room, or undo the last corner." / "These marks don't match %@. Check the room, or undo the last mark."
- More points: "Mark one more corner." / "Mark one more corner or wall."
- Another wall or spread out: "Mark corners further apart, on different walls." / "Mark a corner, or a wall that crosses the others."
- Default: "Stand by a floor corner, point the phone down at it with the circle on the corner, and tap Mark." / "Aim at a wall or a corner of the room, at any height, or down at a floor corner, and tap Mark."

**Fit summary** (PA 620-649)
- "Matched %lld corners · average error %@" (or "…marks…" when a wall is matched).
- Fair: "Fair fit: check that the model lines up before relying on it."
- Approximate: "Approximate fit: it may be off by about %@. Check that the model lines up before relying on it."
- Dropped mark: "One corner was left out: it didn't match the project."

**Fix-here card** (AEV 810-870)
- Problems:
  - "Couldn't hold the mark steady. Keep the circle on the corner and try again."
  - "That isn't near a corner of the model. Aim at the corner nearest you."
  - "That isn't near a wall of the model. Aim at the wall nearest you."
- Default instruction: LiDAR "Model off? Aim at the corner or wall nearest you, with the circle on it, and tap Fix."; else "Model off? Point the phone down at the floor corner nearest you, with the circle on it, and tap Fix."
- Footnote: LiDAR "A wall also straightens the model; so does a second corner of this room right after."; else "Fix a second corner of this room right after to straighten the model too."
- [Cancel], and [Fix] / "Hold still…". `FindingFloorRow` shows when the phone can't aim.

**Room-save card** (AEV 873-954)
- "Save where %@ really is?"
- "Everyone who opens this building in AR will start with this room where you fixed it."
- "Moves %@ and turns %@ from the saved position." (length in cm below 1 m else m, 1 decimal; angle in degrees, 1 decimal; from `RC.change`)
- "Measured from %@, %@ away." or "Measured from a plate, %@ away."
- If `baseDistance > 8` (AEV 902), in orange: "Far from where you aligned: the phone may have lost precision on the way. Align closer for a better measurement."
- If `turn == nil`: "To include its turn, fix a second corner of this room right after the first."
- Failed: "Couldn't save. Check your connection and try again."
- Saved: "Saved. Everyone will see this room where it really is."
- Forbidden: "Only admins can save room adjustments."
- Buttons: [Cancel] [Save for everyone] / "Saving…", or [Done].

**Safety note** (AEV 1174-1198): an overlay card with 24 pt padding, not a system sheet.
- "Before you drill or cut" / "The model shows the design. Always confirm before drilling or cutting." / [Got it].
- Shown at start if `hasSeenSafetyNote` is false. Dismissing sets the flag. Also reachable from ⋮.

**Locate** (AEV 1059-1128)
- Chip (when `session.locating`): the element's name; "%@ away", or "%@ away · follow the arrow" off screen, or "Line the model up with the room to find it." before placement; an × button (a11y "Stop locating").
- Arrow: "arrow.right.circle.fill" at 48 pt, placed at `LG.edgePosition(angle, inset 48)`, rotated `-angle`, eased 0.15 s; only when off screen.

**See inside** (SIP): a handle and "See inside" (a11y "More filters" / "Fewer filters"); a `DetentSlider` "Reality" / "Reality + model" / "Model" (a11y "Model opacity"); system chips; when expanded, subsystem chips and the room focus chip.

**Haptics**: `.success` on `alignments` (any alignment or fix, AEV 576); `.impact` per mark (PA 523).

---

## 5. Rendering in AR

**Scene ownership** (BS 358-378): the last claim wins. While AR owns the scene, `arFilters` apply.

**AR filters** (BS 267, 470-480; MF 131-183)
- `arFilters.systems` starts as every in-scope system minus architecture.
- See inside default `s = 0.5`: systems opacity `min(1, 2s)`, architecture opacity `max(0, 2s − 1)`. Architecture is added to the systems only while its opacity is > 0.
- "structure" is a context system too: its opacity is the architecture opacity, so it is hidden by default (`showsChunk` needs opacity > 0, MF 58-60).
- At `s = 0` the systems are hidden too. Snap within 0.06 of a detent (MF 141).
- Opacity is an `OpacityComponent` on each chunk container (SCN 161-171).
- The storey shows via the converter's storey groups `s{order}` (SCN 173-179), or per element.

**Root transform**
- `root.transform = Transform(matrix: YawTransform.matrix)`: `RotY(yaw)` with the translation in column 3 (AM 29-34; AAV 493-498).
- The drawn transform = `roomCorrection(cameraRoom).then(asBuilt alignment)`, each possibly mid-blend (PAN 196-215).
- The root is disabled until aligned, while marking (except during a fit preview), and after "Place again".
- `detach()` restores the identity transform and re-enables the root (AAV 484-491).

**Behind-wall and distance fade** (BWF; SCN 192-252; AAV 1169-1176)
- Active when the root is enabled, `lastApplied` is set, and not marking. It stays active during Fix here.
- The camera in model coordinates = `lastApplied.inverseApply(camera)`, every frame. Recomputed only after 0.30 m of movement (BWF 36). At most 48 changes are applied per frame (BWF 39).
- Opacity: 1 up to 8 m; 0 behind a wall or at 12 m and beyond; linear in between; stepped to 0.05 (BWF 28-55). Distances are on the plan (x/z), to the nearest point of the element's box.
- Walls are architecture elements' `meta.footprint` polygons, grouped by storey. An element is tested only against walls of its own storey.
  - A wall blocks on a proper segment crossing more than 0.075 m from either end (BWF 42, 122-138).
  - Walls whose footprint contains the camera are ignored.
  - The grid cell is 2 m (BWF 73).
- `ignoresWalls` when `filters.roomID != nil` (SCN 155); distance still fades.
- The selected element (card open) is always at full opacity (SCN 211-214).
- Elements at opacity ≤ 0.01 are disabled, so they can't be tapped (SCN 183, 221).
- Only system (recoloured) chunks are faded.

**Locate marker** (SCN 295-320)
- A sphere of radius 0.05 m, unlit RGB (0.04, 0.43, 0.47), 0.8 opacity, a child of the root at the element box's centre (it follows the alignment and is not occluded).
- Pulse scale `1 + 0.35·(0.5 − 0.5·cos 2πt/1.2)` every frame (LG 85-91).
- The indicator updates at 10 Hz (AAV 1184), and is nil when not placed.
- `LG.indicator`: on screen if |ndc| ≤ 0.9 (margin 0.1); behind the camera the arrow points left or right (LG 36-53). Distance is from the camera to the box (model coordinates).
- `session.locate(id)` turns the element's system on in AR (BS 694-702).

**Occlusion**: none, neither scene nor people (AAV 266-270).

---

## 6. LiDAR-specific behaviour and gating

- `hasLiDAR = ARWorldTrackingConfiguration.supportsSceneReconstruction(.mesh)` (AAV 243).
- **Wall and corner aims** need `hasLiDAR && !thermalLimited && allowsWallAims` (AAV 237). `allowsWallAims` is false only in the marking device test (MT 81).
- Effects of `aimsAtWalls`: vertical plane detection while aiming; the crosshair's `.tooShallow` becomes a wall raycast; `.onWall`/`.onCorner`/`.noSurface`.
- **UI and matching gate on `hasLiDAR` alone**, not thermal:
  - `PointsAlignment.marksWalls` (PA 128) → wall references in matching and the LiDAR instruction strings;
  - Fix card texts (AEV 820, 832);
  - `lidar:` in the observation (AEV 272).
  - On a hot LiDAR phone the text invites wall aims that the crosshair refuses.
- **Mesh reconstruction** on LiDAR when manual (§1).
- **Without LiDAR**: floor corners only, by steep aim (≤ 35° from straight down); 3 marks always; Fix by corners only (and turn from two corners).

---

## 7. Plate registration (admins) and room-correction save

**Registration** (AEV 349-386, PRF)
1. Entry:
   - [Register its position] on the unplaced-plate card (`plateNumber` = the scanned plate), or ⋮ "Register a plate here" (`nil`).
   - Requires `canManagePlates` and no registration in progress.
2. A `PlateRegistrationFlow` is created; `onRegistrationDetection` is wired. Then either:
   - if `lastPoints` exists and the current method is `.points`: `flow.aligned(lastPoints.fit, transform: view.drawnAlignment, room: located ?? lastPoints.room)` (reuse);
   - otherwise `startPoints()`.
   - `flow.load()` runs either way.
3. `load()` (PRF 55-67): `GET plates_list`, sorted by number.
   - The preselected plate is chosen automatically.
   - Otherwise a list: "Which plate are you registering?", each row "Plate %lld" + label + "Has a position: registering replaces it" or "Not in the model yet" (max height 260) + "Cancel".
   - Failure → "Couldn't load the plates. Check your connection." [Close] [Try again].
4. `choose()`: sampler reset, `.collecting`, and the view watches the "register-N" image (always in `detectionImages`, even while marking).
5. **Collecting** card:
   - "Register plate %@"
   - Checklist: "Mark 3 floor corners" → "Corners marked".
   - "Point the phone at the plate from about 1 m" → "Hold still on the plate…" → "Plate seen".
   - [Mark corners] if no fit and no points (→ `startPoints`), and "Cancel".
   - The points panel is embedded below while the fit is nil.
6. **PlateSampler** (PR 10-58): keep a detection when the distance is ≤ 1.5 m and `normal·toCamera ≥ cos 45°`; window 15; ready at 8; pose = component-wise median position and normal; up orthogonalised.
7. Confirming points → `registration.aligned(fit, room: points.room)` with transform = `fit.transform` (AEV 211). `alignmentMoved` (on any anchoring change while the method is points) updates the transform and room while collecting (AEV 375-378; PRF 96-103).
8. **`reviewIfReady`** → `PlateRegistration.pose` (PR 92-124):
   - into model coordinates;
   - horizontal plates: normal ±Y, horizontal up;
   - wall plates: horizontal normal, up = +Y; choose walls with `inward·normal ≥ cos 15°`, signed distance −0.08…+0.5 m, along the wall ±0.1 m; nearest wins;
   - snap onto the wall plus 0.003 m within 0.08 m;
   - none found → `.failed(.notOnAWall)`: "The plate doesn't seem to be on a wall of this room. Check the room and mark the points again." [Close] [Start over].
9. **Review**:
   - "Plate %@"
   - "Centre %@ above the floor"
   - "%@ from the left corner · %@ from the right corner"
   - "Alignment: good (average error %@)" or "Alignment: fair (average error %@). Marking more points helps."
   - [Start over] (`flow.startOver` + `startPoints`), [Save position] / "Try again" / spinner, "Cancel".
   - Save error: "Couldn't save the position. Check your connection and try again."
10. **Save** → `PATCH /v1/plates/{id}` with `versionId`, position, normal and up, then `refreshPlates()` re-fetches the manifest's plates (BS 612-622).
    - 403 → "Only admins can register plates."
    - Saved → "Position saved. Guests now align on plate %@." [Done] [Test now].
11. **End** → stop watching, cancel points.

**Room-correction save**: §3.8 step 6 and the card in §4. `saveRoom` → `PUT /v1/buildings/{id}/room-corrections/{spaceId}` (BS 643-650) with the version, pivot (the room's outline centroid), offset and yaw. Then the manifest's space correction is updated locally and `view.updateCorrections(session.roomCorrections)` makes the building glide. 403 → forbidden; other errors → failed.

---

## 8. Analytics from AR

| Event | When | Props | Where |
|---|---|---|---|
| `ar_opened` | `start()`: after preflight, when the camera view appears | none | AEV 151 |
| `ar_aligned` | First alignment of a `PlateAnchoring` instance | `method`: `plate`/`manual`/`points`; `ms` = rounded `max(0, t − PlateAnchoring.openedAt)` | AEV 425-441; PAN 555-559; `IVCore/AnalyticsEvent.swift` 52-53 |

Notes on `ar_aligned`:
- `ms` is measured from `PlateAnchoring` creation (configure, i.e. when the manifest was present), not from AR open.
- A new `PlateAnchoring` ("Test now") allows another `ar_aligned`.
- "Fix here" fires nothing.

Indirect events:
- `system_toggled` from See inside chips (BS 396-401).
- `object_opened` from the object card.
- Not an analytics event: the `POST room-observations` call.

---

## 9. Logic in the views that has no IVModelKit counterpart (must be ported)

**ARAlignmentView**
- `worldFloorY` choice and the 0.25 m² candidate filter (AAV 184-188, 1278).
- The 5 mm floor-change notifier (AAV 1038).
- Crosshair targeting: the 35° gate, the requirement for `worldFloorY`, the `.notTracking` rule, ±0.18 m side projection, door/window skipping, putting a corner on the floor (AAV 958-1003).
- Capture: 500 ms, every-frame sampling, the broken flag, 5-sample minimum, 2 cm median filter, mixed-kind rejection (AAV 703-737).
- `PlacementState` and every transition (place, lock, unlock, reset, preview, startMarking, stopMarking, placeManually, handle).
- Gestures: drag on the placed floor plane, twist sign and pivot, nudge pivot (AAV 589-594, 1046-1090).
- The `floorElevation` storey-switch preservation (AAV 130-142).
- The floor-glue trigger conditions (AAV 868-880).
- Lost detection: which tracking states start the 2 s timer; interruption is immediate (AAV 1155-1158, 1198-1224).
- Session settings derivation, re-run and image-anchor removal rules, thermal handling and video format (AAV 375-467).
- Coaching-overlay gating (AAV 563-571).
- Site-anchor reconciliation (AAV 884-897).
- When the building follows the anchoring vs the manual state (AAV 1149-1154).
- Proximity gating (AAV 1169-1176); locate 10 Hz and the projection (AAV 1181-1196).
- Image-name routing (`plate-`/`register-`) (AAV 1116-1129).
- Mark disc and crosshair visuals.

**ARExperienceModel and View**
- Starting-storey trigger.
- Auto `startPoints` (no plates, or fallback).
- `canFixHere`, `canSaveRoom`, and the `fix()` orchestration (wall vs corner reanchor, problem mapping, observation upload and `roomFix`).
- Registration orchestration (`lastPoints` reuse, `alignmentMoved`, Test now = reconfigure + realign).
- Status-tag and coaching-text priority tables.
- The 4 s "No plate?" offer.
- Menu conditions.
- Scene claim and the 1 s camera wait.
- Safety-note first use.
- `isAligned`, `isManual` and `hasPlates` derivations.

**PointsAlignment**
- Room preselection order.
- `showRoomLevel`.
- `marksNeeded`.
- `evaluate` / `floorMoved` / `restart` / `markAnother`.
- `holdsSceneWork` coupling.
- The copy-details JSON schema.

**PlateRegistrationFlow** — the phase machine. Its pure parts (PlateSampler, PlateRegistration) are in IVModelKit.

---

## 10. iOS PLAN §3 vs code (code wins)

1. **"Tracking limited for more than 2 s after aligning counts as alignment lost"**: only `.relocalizing` and `.notAvailable` start the timer (AAV 1207-1208); excessive motion and insufficient features never count. A session interruption marks the alignment lost immediately (AAV 1216).
2. **"Mark … fails … if the samples spread more than 2 cm"**: samples more than 2 cm from the median are dropped; it fails only if fewer than 5 remain or any frame lost the aim (AAV 730-732). For walls and corners the tolerances are 1.5 cm and 2° (LA 47-48).
3. **"plane detection is horizontal only throughout" (while marking)**: false with LiDAR (when not hot); `.vertical` is added while aiming (AAV 400, 409).
4. **"sceneReconstruction = .mesh … only for floor placement by hand"**: the condition is `anchoring.isManual`, which is also true after an alignment by points, and in the initial no-plates `PlateAnchoring` (AAV 127, 399; PAN 186-192). No raycast requests mesh.
5. **Robustness "on .serious turn off scene reconstruction and lower the frame rate"**: the code also turns off environment texturing and LiDAR wall aims. It only reacts to a change notification and never reads the initial thermal state (AAV 284-288, 462-467).
6. **"Manual fallback (last resort, when the room has no outline)"**: ⋮ "Place manually" is offered whenever `!isManual`, even when points are possible (AEV 693-699). "Adjust placement" and "Place again" are offered after an alignment by points, and "Adjust placement" converts it to manual, dropping its sites (AAV 611-613).
7. **"No plate? Align by points" is offered after 4 s**: also immediately when the phase is `.lost` (AEV 1034). The 10 s fallback is timed from `PlateAnchoring` creation (manifest available), not from AR open.
8. **`ar_aligned` ms "from opening AR"** (AnalyticsEvent doc): it is from `PlateAnchoring` creation, and it repeats after "Test now".
9. **"Finding the floor…" / "Finding your place…"**: the real strings are longer (PA 380-382).
10. **Automatic 4 m re-anchoring**: only from phase `.aligned` (plates), never after points. After the 15 s timeout it immediately re-enters realigning while the camera stays more than 4 m away.
11. **"A plate seen afterwards still re-anchors"** (after points): it replaces the points alignment and all its sites (`sites.startOver`, since the phase is `.manual`, PAN 496-500).
12. **Status tag during Re-align after points** reads "Placed manually" (AEV 623-637). This is a quirk; Android should decide deliberately.
13. **Wall mark discs** lose their normal offset on a floor change (AAV 763-771). This is a bug.

---

## 11. Android `docs/PLAN.md` §3 "AR" and §6: wrong or incomplete statements

File: `/home/user/insiteview-android/docs/PLAN.md`. The relevant lines are 76-85 (§3 AR) and 109-120 (§6); line 15 is §1.

1. **"Plates: ARCore Augmented Images built from each plate's PNG (`imageUrl`) with its physical width (`sizeMm`)."** Incomplete:
   - The database must also include the registration image (`register-N`).
   - Placed plates must be removed from detection while marking, and after a plate aligns (`wantsImageDetection` is false in `aligned`). This means reconfiguring the ARCore session's `AugmentedImageDatabase` at runtime; iOS re-runs the configuration without reset.
   - The width is `sizeMm / 1000` m, including the quiet zone (MAN 226-245).
   - iOS sets `maximumNumberOfTrackedImages = 1` and turns scale estimation off. ARCore has neither: it tracks several images. Only `TrackingMethod.FULL_TRACKING` updates should feed the smoother. `LAST_KNOWN_POSE` updates would poison the 10-sample window, which assumes continuous per-frame tracking.
   - iOS removes image anchors to force re-detection on Re-align (AAV 455-459, 509-513). The Android equivalent (resetting the smoother, ignoring stale poses) isn't mentioned.
2. **"ARCore's image pose has +Y along the image normal, +Z down the image; AnchorMath converts it …"**: correct, and identical to ARKit, so the conversion is the same: right = x, up = −z, normal = y. Incomplete: AnchorMath also has `YawTransform(anchorTransform:)` (AM 17-35), which site anchors need (yaw from the z axis, falling back to x), and `YawTransform.matrix` for the root.
3. **"The solver, smoothing, re-anchoring, reference points, sites, room corrections, locate and the behind-wall rule are the iOS code ported to :modelkit"**: incomplete and misleading. A large share of the behaviour lives in `ARAlignmentView`, `ARExperienceModel` and `PointsAlignment` and must be ported into `:ar` and `:features`; the list is in §9. Also missing from the list: `LidarAim`, `PlateRegistration`, `ManualAlignment`, `RoomOutline`, `ModelFilters`/`SeeInside`, `Manifest.startingStorey`.
4. **"LiDAR marks (walls, wall corners) use ARCore Depth hit tests on devices that support depth, and are hidden elsewhere (iOS hides them without LiDAR)."** Wrong or incomplete in several ways:
   - iOS gating is `hasLiDAR && !thermalLimited`, plus `allowsWallAims`. When hot, wall aims are off even on LiDAR.
   - iOS hit-tests ARKit vertical planes first (`existingPlaneGeometry`), then the depth-based `estimatedPlane`. It skips door and window plane classifications, which ARCore lacks.
   - Corners need two extra side raycasts at ±0.18 m.
   - The text variants (instructions, Fix card, `marksNeeded = 2` with a wall, wall references in matching) gate on `hasLiDAR`.
   - ARCore Depth on phones without a ToF sensor is motion-stereo depth, far noisier than LiDAR. The 1.5 cm / 2° capture tolerances and the 10 cm corner reach were tuned for LiDAR. Gating on "supports Depth" alone will likely give unusable wall marks. It needs a device-test decision; one option is ToF only.
   - "Hidden" is inaccurate: without wall aims the crosshair just reports `.tooShallow` ("Point the phone more straight down").
   - The `lidar` flag in room observations must be defined.
5. **§1 "The ARCore Depth API stands in for LiDAR where supported"**: incomplete. LiDAR on iOS also enables `sceneReconstruction = .mesh` in manual mode; ARCore has no mesh, though it's effectively unused. The plan needs to say what `hasLiDAR` maps to, since it drives UI texts.
6. **§6 row "ARKit image detection, LiDAR → ARCore Augmented Images, Depth API — Platform equivalents"**: incomplete. Missing differences that need an explicit decision:
   - **Floor classification.** `ARPlaneAnchor.classification == .floor` drives `worldFloorY`. ARCore has only `HORIZONTAL_UPWARD_FACING`, so Android always falls to "lowest plane ≥ 0.25 m²", the reflection trap the iOS comment warns about (9 cm low).
   - **`ARCoachingOverlayView`.** ARCore has none; a custom floor coaching UI is needed.
   - **`estimatedPlane` raycasts.** No exact ARCore equivalent. Use `hitTest` with planes, `DepthPoint`, or Instant Placement.
   - **`arView.ray(through:)` / `project`.** Need the Filament/ARCore camera matrices.
   - **Delegate threading.** `SessionRelay`'s queue model doesn't apply: ARCore frames are pulled with `session.update()` on the render thread. The "newest frame only, values out" rule still applies.
   - **Interruption and relocalization.** ARCore has no `sessionWasInterrupted` or `sessionShouldAttemptRelocalization`. Map `TrackingState.PAUSED` with `TrackingFailureReason` to the status texts, and `onPause`/`onResume` to interrupt and resume.
   - **Thermal.** `PowerManager` thermal status (API 29+, while minSdk is 26).
   - **Video format.** Choose a `CameraConfig` with a 30 fps target and the smallest size.
   - **Environment texturing.** Environmental HDR / `LightEstimationMode`.
   - **Render options.** Filament post-processing.
   - **Site anchors.** `ARAnchor` add and remove with delegate updates becomes `session.createAnchor(pose)` with per-frame polling of `anchor.pose`. Detach on removal.
   - **No occlusion.** Disable Depth-based occlusion in SceneView.
   - **Keep the screen on.** `FLAG_KEEP_SCREEN_ON` (iOS `isIdleTimerDisabled`).
   - **Camera permission.** Android has no "restricted" state but does have "don't ask again". It maps to `cameraDenied(canOpenSettings: true)`.
   - **3D-viewer conflict.** The iOS `OrbitViewers` release and 1 s camera wait is an iOS workaround; Android needs its own rule (one Filament engine / one SceneView).
   - **Picking.** RealityKit `entity(at:)` with collision boxes becomes ray–box picking in `:scene`.
   - **Opacity.** `OpacityComponent` multiplication becomes material alpha on the chunk times the element.
7. **§6 has no row for admin AR features in the Clip.** iOS disables plate registration and room-correction save in the App Clip (`canManagePlates` requires `!isClip`, BS 593-595). On Android everything runs in the full app, so admins always get them when the role allows. Worth stating explicitly.
8. **AND-M4-02 checklist mentions a "safety sheet".** iOS shows a card overlay, not a sheet, so the object card can still open over the camera (AEV 553-557, 1174-1198).
9. **AND-M4-02 omits pieces the code has:** the `FindingFloorRow` UX, the crosshair overlay states, the starting-storey logic, the floor-change refit, the floor glue, copy details (long-press JSON), the Level and Room menus, "Adjust placement" / "Place again" / "Place manually", locate chip and arrow, haptics, unplaced-plate card, "Test now", and the room-save card. They're listed in §3-§7 above.
10. **The plan says nothing about known iOS quirks** that the "iOS is the spec" rule would copy (§10 items 4, 5, 10, 12, 13, and the `ar_aligned` timing in 8). Each needs a fix-or-copy decision.
