package com.getinsiteview.api

import com.getinsiteview.core.BuildingStatus
import com.getinsiteview.core.Catalog
import com.getinsiteview.core.UnitSystem
import com.getinsiteview.modelkit.Manifest
import java.io.File
import kotlin.test.assertTrue
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The hand-written models against `openapi/openapi.json` (iOS gets this from code generation: a
 * renamed field is a compile error there, a failure here). Run after every spec sync.
 */
@DisplayName("API contract")
class ApiContractTest {
    /** The operations the app calls (docs/PLAN.md §3; iOS `openapi-generator-config.yaml` minus `auth_apple_native`). */
    private val operations = setOf(
        "health_ready", "public_building", "public_visit_by_code", "public_visit_by_link", "visit_manifest",
        "visit_element", "visit_unlock", "visit_documents", "visit_search", "events_create", "model_manifest",
        "model_element", "auth_login", "auth_register", "auth_refresh", "auth_logout", "auth_oauth_providers",
        "auth_oauth_exchange", "auth_verify_email_send", "me_get", "me_update", "me_delete", "me_buildings",
        "me_save_building", "me_unsave_building", "search_mine", "search_building", "documents_list", "buildings_get",
        "orgs_list", "plates_list", "plates_update", "visit_room_observations_create", "room_corrections_set", "catalog_get",
    )

    /** `:api`'s models: names, requiredness and nullability must match the schema exactly. */
    private val models: List<Pair<KSerializer<*>, String>> = listOf(
        HealthResponse.serializer() to "HealthResponse",
        HealthCheckResponse.serializer() to "HealthCheckResponse",
        PublicBuilding.serializer() to "PublicBuildingResponse",
        PublicBuilder.serializer() to "PublicBuilder",
        CreateVisitRequest.serializer() to "CreateVisitRequest",
        CreateLinkVisitRequest.serializer() to "CreateLinkVisitRequest",
        UnlockVisitRequest.serializer() to "UnlockVisitRequest",
        Visit.serializer() to "VisitResponse",
        NamedRef.serializer() to "NamedRef",
        SpaceRef.serializer() to "SpaceRef",
        DocumentRef.serializer() to "DocumentRef",
        ElementDetail.serializer() to "ElementResponse",
        BuildingDocument.serializer() to "DocumentResponse",
        SearchResults.serializer() to "SearchResponse",
        SearchHit.serializer() to "SearchHit",
        EventInput.serializer() to "EventInput",
        EventsRequest.serializer() to "EventsRequest",
        EventsResponse.serializer() to "EventsResponse",
        LoginRequest.serializer() to "LoginRequest",
        RegisterRequest.serializer() to "RegisterRequest",
        RefreshRequest.serializer() to "RefreshRequest",
        OAuthExchangeRequest.serializer() to "OAuthExchangeRequest",
        OAuthProvidersResponse.serializer() to "OAuthProvidersResponse",
        AuthResponse.serializer() to "AuthResponse",
        Me.serializer() to "MeResponse",
        MyOrganization.serializer() to "MyOrganization",
        UpdateMeRequest.serializer() to "UpdateMeRequest",
        MyBuilding.serializer() to "MyBuildingResponse",
        MySearchResults.serializer() to "MySearchResponse",
        MySearchBuilding.serializer() to "MySearchBuilding",
        MySearchHit.serializer() to "MySearchHit",
        Organization.serializer() to "OrganizationResponse",
        BillingDetailsResponse.serializer() to "BillingDetailsResponse",
        BuildingDetail.serializer() to "BuildingResponse",
        BuildingPlate.serializer() to "PlateResponse",
        UpdatePlateRequest.serializer() to "UpdatePlateRequest",
        PlatePlacementRequest.serializer() to "PlatePlacementRequest",
        RoomObservationRequest.serializer() to "RoomObservationRequest",
        SetRoomCorrectionRequest.serializer() to "SetRoomCorrectionRequest",
        RoomCorrectionSummary.serializer() to "RoomCorrectionResponse",
        RoomAdjustment.serializer() to "RoomAdjustment",
        ProblemDetails.serializer() to "ProblemDetails",
    )

    /**
     * `:modelkit`'s and `:core`'s types the API's responses decode into. They also read files (the
     * asset contract, the catalog cache), so defaults may differ; names and the required set must match.
     */
    private val shared: List<Pair<KSerializer<*>, String>> = listOf(
        Manifest.serializer() to "ManifestResponse",
        Manifest.Building.serializer() to "ManifestBuilding",
        Manifest.Origin.serializer() to "ManifestOrigin",
        Manifest.BoundsDTO.serializer() to "ManifestBounds",
        Manifest.Storey.serializer() to "ManifestStoreyResponse",
        Manifest.Space.serializer() to "ManifestSpaceResponse",
        Manifest.Space.Correction.serializer() to "ManifestRoomCorrection",
        Manifest.SystemSummary.serializer() to "ManifestSystemResponse",
        Manifest.Chunk.serializer() to "ManifestChunk",
        Manifest.File.serializer() to "ManifestFile",
        Manifest.Plate.serializer() to "ManifestPlate",
        Manifest.Stats.serializer() to "ManifestStatsResponse",
        Catalog.serializer() to "Catalog",
        Catalog.System.serializer() to "CatalogSystem",
        Catalog.Subsystem.serializer() to "CatalogSubsystem",
        Catalog.Kind.serializer() to "CatalogKind",
        Catalog.Property.serializer() to "CatalogProperty",
        Catalog.LocalizedNames.serializer() to "LocalizedNames",
    )

    /** Open enums and the values the app names (`VisitPlatform.ANDROID_APP` is being added to the API). */
    private val enums: Map<String, List<String>> = mapOf(
        "PublicBuildingStatus" to listOf(PublicBuildingStatus.LIVE, PublicBuildingStatus.NOT_LIVE, PublicBuildingStatus.PAUSED, PublicBuildingStatus.EXPIRED).map { it.raw },
        "VisitVia" to listOf(VisitVia.PLATE, VisitVia.LINK, VisitVia.MEMBER, VisitVia.GRANT, VisitVia.SAVED).map { it.raw },
        "VisitPlatform" to listOf(VisitPlatform.IOS_APP, VisitPlatform.IOS_CLIP).map { it.raw },
        "OrganizationRole" to listOf(OrganizationRole.OWNER, OrganizationRole.ADMIN, OrganizationRole.MEMBER).map { it.raw },
        "RoomCorrectionSource" to listOf(RoomCorrectionSource.ADMIN, RoomCorrectionSource.VISITORS).map { it.raw },
        "DocumentKind" to listOf(
            DocumentKind.MANUAL, DocumentKind.WARRANTY, DocumentKind.CERTIFICATE, DocumentKind.DRAWING, DocumentKind.PHOTO,
            DocumentKind.INVOICE, DocumentKind.OTHER,
        ).map { it.raw },
        "SearchHitType" to listOf(SearchHitType.ELEMENT, SearchHitType.ROOM).map { it.raw },
        "MyBuildingVia" to listOf(MyBuildingVia.MEMBER, MyBuildingVia.GRANT, MyBuildingVia.SAVED).map { it.raw },
        "Tier" to emptyList(),
        "OrganizationType" to emptyList(),
        "Currency" to emptyList(),
        "UnitSystem" to UnitSystem.entries.map { it.raw },
        "BuildingStatus" to listOf(
            BuildingStatus.DRAFT, BuildingStatus.READY, BuildingStatus.TRIAL_LIVE, BuildingStatus.ACTIVE, BuildingStatus.PAUSED,
            BuildingStatus.EXPIRED,
        ).map { it.raw },
    )

    /** Reachable schemas with no model: free-form JSON. */
    private val unmodelled = setOf("JsonElement")

    private val spec: JsonObject by lazy {
        val path = System.getProperty("iv.openapi") ?: "../openapi/openapi.json"
        Json.parseToJsonElement(File(path).readText()).jsonObject
    }

    private val schemas: JsonObject by lazy { spec["components"]!!.jsonObject["schemas"]!!.jsonObject }

    private fun schema(name: String): JsonObject = requireNotNull(schemas[name]?.jsonObject) { "No schema $name in the spec" }

    private fun JsonObject.properties(): JsonObject = this["properties"]?.jsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.required(): Set<String> = this["required"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()

    private fun refName(property: JsonObject): String? = property["\$ref"]?.jsonPrimitive?.content?.substringAfterLast('/')

    /** `type: ["null", …]`, directly or on the referenced schema. */
    private fun isNullable(property: JsonObject): Boolean {
        val type = property["type"]
        if (type is JsonArray && type.any { it.jsonPrimitive.content == "null" }) return true
        val ref = refName(property) ?: return false
        val target = schemas[ref]?.jsonObject?.get("type")
        return target is JsonArray && target.any { it.jsonPrimitive.content == "null" }
    }

    private fun check(descriptor: SerialDescriptor, schemaName: String, strict: Boolean): List<String> {
        val problems = mutableListOf<String>()
        val schema = schema(schemaName)
        val properties = schema.properties()
        val required = schema.required()
        val names = (0 until descriptor.elementsCount).map(descriptor::getElementName)
        for ((index, name) in names.withIndex()) {
            val property = properties[name]?.jsonObject
            if (property == null) {
                problems += "$schemaName.$name: not in the spec"
                continue
            }
            if (!strict) continue
            val optional = descriptor.isElementOptional(index)
            if (optional == (name in required)) {
                problems += "$schemaName.$name: ${if (name in required) "required in the spec but has a default" else "optional in the spec but has no default"}"
            }
            val nullable = descriptor.getElementDescriptor(index).isNullable
            val expected = isNullable(property) || name !in required
            if (nullable != expected) {
                problems += "$schemaName.$name: ${if (expected) "nullable" else "non-null"} in the spec, ${if (nullable) "nullable" else "non-null"} here"
            }
        }
        for (name in required) {
            if (name !in names) problems += "$schemaName.$name: required in the spec, missing here"
        }
        return problems
    }

    @Test
    fun `Every model's properties match its schema`() {
        val problems = models.flatMap { (serializer, schema) -> check(serializer.descriptor, schema, strict = true) }
        assertTrue(problems.isEmpty(), "Models out of step with openapi/openapi.json:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `The manifest and the catalog decode the spec's schemas`() {
        val problems = shared.flatMap { (serializer, schema) -> check(serializer.descriptor, schema, strict = false) }
        assertTrue(problems.isEmpty(), "Shared types out of step with openapi/openapi.json:\n" + problems.joinToString("\n"))
    }

    @Test
    fun `Open enums - every value the app names is in the spec`() {
        val problems = mutableListOf<String>()
        for ((name, values) in enums) {
            val schema = schemas[name]?.jsonObject
            if (schema == null) {
                problems += "$name: not in the spec"
                continue
            }
            val known = schema["enum"]?.jsonArray?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }?.toSet() ?: emptySet()
            for (value in values) if (value !in known) problems += "$name.$value: not in the spec"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `Every operation exists and every schema it reaches has a model`() {
        val found = mutableSetOf<String>()
        val reached = mutableSetOf<String>()
        fun collect(element: JsonElement) {
            when (element) {
                is JsonObject -> for ((key, value) in element) {
                    if (key == "\$ref") reached += value.jsonPrimitive.content.substringAfterLast('/') else collect(value)
                }
                is JsonArray -> element.forEach(::collect)
                else -> Unit
            }
        }
        for ((_, item) in spec["paths"]!!.jsonObject) {
            for ((_, operation) in item.jsonObject) {
                val id = (operation as? JsonObject)?.get("operationId")?.jsonPrimitive?.content ?: continue
                if (id in operations) {
                    found += id
                    collect(operation)
                }
            }
        }
        val queue = ArrayDeque(reached)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            val before = reached.toSet()
            collect(schema(name))
            queue.addAll(reached - before)
        }
        val covered = (models + shared).map { it.second }.toSet() + enums.keys + unmodelled
        val problems = (operations - found).map { "operation $it: not in the spec" } +
            (reached - covered).map { "schema $it: reached by the app's operations but has no model in ApiContractTest" }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
