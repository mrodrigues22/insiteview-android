package com.getinsiteview.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

// The spec's string enums, as open value classes (iOS: the `typeOverrides` in
// openapi-generator-config.yaml point at structs in OpenEnums.swift). A value the server adds
// later decodes as its raw string instead of failing the whole response, and `when` on them
// needs an `else`. `UnitSystem` and `BuildingStatus` are :core's.

/** Serializes an open enum as its raw string, keeping values this app doesn't know. */
abstract class OpenEnumSerializer<T>(name: String, private val make: (String) -> T, private val raw: (T) -> String) :
    KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("com.getinsiteview.api.$name", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): T = make(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: T) {
        encoder.encodeString(raw(value))
    }
}

/** A building's state as guests see it (`PublicBuildingResponse.status`). */
@JvmInline
@Serializable(with = PublicBuildingStatus.Serializer::class)
value class PublicBuildingStatus(val raw: String) {
    /** Whether guests can open the twin. */
    val isViewable: Boolean get() = this == LIVE || this == EXPIRED

    override fun toString(): String = raw

    companion object {
        /** The QR opens the twin. */
        val LIVE = PublicBuildingStatus("Live")

        /** "This building isn't live yet". */
        val NOT_LIVE = PublicBuildingStatus("NotLive")

        /** "This digital twin is paused". */
        val PAUSED = PublicBuildingStatus("Paused")

        /** The activation ended: a read-only archive, still viewable. */
        val EXPIRED = PublicBuildingStatus("Expired")
    }

    object Serializer : OpenEnumSerializer<PublicBuildingStatus>("PublicBuildingStatus", ::PublicBuildingStatus, { it.raw })
}

/** How a visit got its access (`VisitResponse.via`). */
@JvmInline
@Serializable(with = VisitVia.Serializer::class)
value class VisitVia(val raw: String) {
    override fun toString(): String = raw

    companion object {
        val PLATE = VisitVia("Plate")
        val LINK = VisitVia("Link")
        val MEMBER = VisitVia("Member")
        val GRANT = VisitVia("Grant")
        val SAVED = VisitVia("Saved")
    }

    object Serializer : OpenEnumSerializer<VisitVia>("VisitVia", ::VisitVia, { it.raw })
}

/** Which app a visit comes from (analytics, master PLAN §12). */
@JvmInline
@Serializable(with = VisitPlatform.Serializer::class)
value class VisitPlatform(val raw: String) {
    override fun toString(): String = raw

    companion object {
        /** This app. */
        val ANDROID_APP = VisitPlatform("AndroidApp")
        val IOS_APP = VisitPlatform("IosApp")
        val IOS_CLIP = VisitPlatform("IosClip")
    }

    object Serializer : OpenEnumSerializer<VisitPlatform>("VisitPlatform", ::VisitPlatform, { it.raw })
}

/** A member's role in an organization (`MyOrganization.role`). */
@JvmInline
@Serializable(with = OrganizationRole.Serializer::class)
value class OrganizationRole(val raw: String) {
    /** Admins and owners manage the building: plates, access, versions. */
    val canManage: Boolean get() = this == OWNER || this == ADMIN

    override fun toString(): String = raw

    companion object {
        val OWNER = OrganizationRole("Owner")
        val ADMIN = OrganizationRole("Admin")
        val MEMBER = OrganizationRole("Member")
    }

    object Serializer : OpenEnumSerializer<OrganizationRole>("OrganizationRole", ::OrganizationRole, { it.raw })
}

/** Whose correction of a room is in use (`RoomCorrectionResponse.source`). */
@JvmInline
@Serializable(with = RoomCorrectionSource.Serializer::class)
value class RoomCorrectionSource(val raw: String) {
    override fun toString(): String = raw

    companion object {
        val ADMIN = RoomCorrectionSource("Admin")
        val VISITORS = RoomCorrectionSource("Visitors")
    }

    object Serializer : OpenEnumSerializer<RoomCorrectionSource>("RoomCorrectionSource", ::RoomCorrectionSource, { it.raw })
}

/** What a building document is (`DocumentResponse.kind`). */
@JvmInline
@Serializable(with = DocumentKind.Serializer::class)
value class DocumentKind(val raw: String) {
    override fun toString(): String = raw

    companion object {
        val MANUAL = DocumentKind("Manual")
        val WARRANTY = DocumentKind("Warranty")
        val CERTIFICATE = DocumentKind("Certificate")
        val DRAWING = DocumentKind("Drawing")
        val PHOTO = DocumentKind("Photo")
        val INVOICE = DocumentKind("Invoice")
        val OTHER = DocumentKind("Other")
    }

    object Serializer : OpenEnumSerializer<DocumentKind>("DocumentKind", ::DocumentKind, { it.raw })
}

/** A search result's type (`SearchHit.type`). */
@JvmInline
@Serializable(with = SearchHitType.Serializer::class)
value class SearchHitType(val raw: String) {
    override fun toString(): String = raw

    companion object {
        val ELEMENT = SearchHitType("Element")
        val ROOM = SearchHitType("Room")
    }

    object Serializer : OpenEnumSerializer<SearchHitType>("SearchHitType", ::SearchHitType, { it.raw })
}

/** How a building in "my buildings" is mine (`MyBuildingResponse.via`). */
@JvmInline
@Serializable(with = MyBuildingVia.Serializer::class)
value class MyBuildingVia(val raw: String) {
    override fun toString(): String = raw

    companion object {
        /** A building of one of my organizations. */
        val MEMBER = MyBuildingVia("Member")

        /** Shared with me ("people with access"). */
        val GRANT = MyBuildingVia("Grant")

        /** Saved from a QR visit with a free account (IOS-M3-08). */
        val SAVED = MyBuildingVia("Saved")
    }

    object Serializer : OpenEnumSerializer<MyBuildingVia>("MyBuildingVia", ::MyBuildingVia, { it.raw })
}

/**
 * A building's price tier (`BuildingResponse.tier`). Billing stays on the web (master PLAN §11);
 * the app only decodes it.
 */
@JvmInline
@Serializable(with = Tier.Serializer::class)
value class Tier(val raw: String) {
    override fun toString(): String = raw

    object Serializer : OpenEnumSerializer<Tier>("Tier", ::Tier, { it.raw })
}

/** What kind of company an organization is (`OrganizationResponse.type`). */
@JvmInline
@Serializable(with = OrganizationType.Serializer::class)
value class OrganizationType(val raw: String) {
    override fun toString(): String = raw

    object Serializer : OpenEnumSerializer<OrganizationType>("OrganizationType", ::OrganizationType, { it.raw })
}

/**
 * An organization's billing currency (`OrganizationResponse.currency`). Decoded, never shown: the
 * app has no prices (master PLAN §11).
 */
@JvmInline
@Serializable(with = Currency.Serializer::class)
value class Currency(val raw: String) {
    override fun toString(): String = raw

    object Serializer : OpenEnumSerializer<Currency>("Currency", ::Currency, { it.raw })
}
