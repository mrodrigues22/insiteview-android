package com.getinsiteview.modelkit.ar

import com.getinsiteview.modelkit.geometry.ReferenceMark
import com.getinsiteview.modelkit.geometry.Vec3
import com.getinsiteview.modelkit.geometry.YawTransform

// The value types iOS declares at the top of IVAR/ARAlignmentView.swift, here so the AR rules that
// use them are tested on the JVM. `:ar` (the ARCore view) and `:features` use them as they are.

/** Where manual alignment stands (iOS `PlacementState`). */
sealed interface PlacementState {
    /** Coaching: move the phone until the AR session finds the floor. */
    data object FindingFloor : PlacementState

    /** A horizontal plane is tracked: "Tap the floor where you're standing". */
    data object ReadyToPlace : PlacementState

    /** The model is on the floor; drag, twist and fine-tune until it matches the room. */
    data class Adjusting(val value: YawTransform) : PlacementState

    /** Done adjusting (or aligned on a plate); taps open the object card. */
    data class Locked(val value: YawTransform) : PlacementState

    val transform: YawTransform?
        get() = when (this) {
            is Adjusting -> value
            is Locked -> value
            FindingFloor, ReadyToPlace -> null
        }
}

/** Camera tracking quality, for the status line (iOS `TrackingStatus`). */
enum class TrackingStatus {
    NORMAL,
    INITIALIZING,
    EXCESSIVE_MOTION,
    INSUFFICIENT_FEATURES,
    RELOCALIZING,
    NOT_AVAILABLE,
    INTERRUPTED,
    ;

    companion object {
        /**
         * ARCore's camera tracking as a status (docs/PLAN.md §3 "AR": `TrackingState.PAUSED` +
         * `TrackingFailureReason` → the iOS values). ARCore has no relocalizing reason: after the
         * session resumes from an interruption ([relocalizing]), anything short of tracking reads
         * as relocalizing until tracking is back, as ARKit's `.limited(.relocalizing)` does.
         *
         * - tracking → normal; stopped → not available;
         * - paused: excessive motion → excessive motion; too few features or too little light →
         *   insufficient features (ARKit has no light reason, it reports features); bad state or
         *   camera unavailable → not available; no reason → initializing.
         */
        fun of(state: CameraTrackingState, failure: CameraTrackingFailure, relocalizing: Boolean = false): TrackingStatus {
            if (state == CameraTrackingState.TRACKING) return NORMAL
            if (state == CameraTrackingState.STOPPED) return NOT_AVAILABLE
            if (relocalizing) return RELOCALIZING
            return when (failure) {
                CameraTrackingFailure.EXCESSIVE_MOTION -> EXCESSIVE_MOTION
                CameraTrackingFailure.INSUFFICIENT_FEATURES, CameraTrackingFailure.INSUFFICIENT_LIGHT -> INSUFFICIENT_FEATURES
                CameraTrackingFailure.BAD_STATE, CameraTrackingFailure.CAMERA_UNAVAILABLE -> NOT_AVAILABLE
                CameraTrackingFailure.NONE -> INITIALIZING
            }
        }
    }
}

/** ARCore's `TrackingState`, without ARCore (`:ar` maps it). */
enum class CameraTrackingState { TRACKING, PAUSED, STOPPED }

/** ARCore's `TrackingFailureReason`, without ARCore (`:ar` maps it; unknown reasons → [NONE]). */
enum class CameraTrackingFailure { NONE, BAD_STATE, INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, CAMERA_UNAVAILABLE }

/** A horizontal plane the AR session found that may be the floor (see [FloorPlanes.worldFloorY]). */
data class FloorPlane(
    /** World metres. */
    val height: Double,
    /** Square metres. */
    val area: Double,
    /** The session classified it as floor (ARKit only; ARCore has no floor class, so always false here). */
    val isFloor: Boolean,
)

/**
 * What the crosshair is on: the point, and the camera it was seen from (its line of sight). On the
 * floor by default; with the depth sensor also a wall (with its direction) or a wall corner, put on
 * the floor.
 */
data class FloorAim(
    val point: Vec3,
    val eye: Vec3,
    val surface: ReferenceMark.Surface = ReferenceMark.Surface.FLOOR,
    /** For a wall: horizontal, out of the wall towards the camera. */
    val normal: Vec3? = null,
)

/** What the crosshair is on while aiming at a floor corner (marking, or "Fix here"). */
enum class CrosshairState {
    /** No floor found yet: move the phone slowly over the floor. */
    NO_FLOOR,

    /** Tracking isn't normal: starting up, moving fast, or seeing too little. */
    NOT_TRACKING,

    /**
     * The phone points too far from straight down: a small error in the floor's height would move
     * the mark sideways.
     */
    TOO_SHALLOW,

    /** On the floor: "Mark" can take it. */
    READY,

    /** On a wall (depth sensor): "Mark" takes the wall. */
    ON_WALL,

    /** On a wall corner, at any height (depth sensor): "Mark" takes the corner. */
    ON_CORNER,

    /** Not straight down and on no wall (depth sensor): aim at a wall, a corner or down at the floor. */
    NO_SURFACE,
    ;

    /**
     * The phone can aim at corners: the floor is found and tracking is normal (a shallow aim is
     * still aiming). Otherwise the screen offers nothing to mark yet.
     */
    val canAim: Boolean get() = this != NO_FLOOR && this != NOT_TRACKING

    /** "Mark" can take what the crosshair is on. */
    val canMark: Boolean get() = this == READY || this == ON_WALL || this == ON_CORNER
}

/** A ray in world (or model) coordinates; [direction] is unit length. */
data class Ray(val origin: Vec3, val direction: Vec3)
