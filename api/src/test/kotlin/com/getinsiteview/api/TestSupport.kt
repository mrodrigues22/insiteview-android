package com.getinsiteview.api

import java.net.URI
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.Headers.Companion.toHeaders
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/**
 * A transport that answers with a handler and records every request, body included (iOS
 * `RoutingTransport`): an OkHttp interceptor at the end of the chain, so nothing touches the network.
 * A handler that throws an `IOException` (e.g. `UnknownHostException`) is a transport failure.
 */
class RoutingTransport(private val handler: (Sent) -> Reply) : Interceptor {
    class Sent(val request: Request, val body: ByteArray?, val operationId: String) {
        /** The encoded path and query, as the API sees them. */
        val path: String get() = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val method: String get() = request.method
        val authorization: String? get() = request.header("Authorization")

        fun header(name: String): String? = request.header(name)

        fun json(): JsonObject = Json.parseToJsonElement(requireNotNull(body).decodeToString()).jsonObject

        fun jsonOrNull(): JsonObject? = try {
            json()
        } catch (_: Exception) {
            null
        }
    }

    data class Reply(val status: Int, val headers: Map<String, String> = mapOf("Content-Type" to "application/json"), val body: String) {
        companion object {
            fun json(body: String, status: Int = 200) = Reply(status = status, body = body)

            fun problem(status: Int, code: String, extra: String = "") = Reply(
                status = status,
                headers = mapOf("Content-Type" to "application/problem+json"),
                body = """{"type":"https://tools.ietf.org/html/rfc9110","title":"Problem","status":$status,"code":"$code"$extra,"traceId":"00-abc"}""",
            )

            fun empty(status: Int) = Reply(status = status, headers = emptyMap(), body = "")
        }
    }

    private val sent = mutableListOf<Sent>()

    val requests: List<Sent> get() = synchronized(sent) { sent.toList() }

    /** A client whose calls end here. */
    val client: OkHttpClient by lazy { OkHttpClient.Builder().addInterceptor(this).build() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = request.body?.let { Buffer().also(it::writeTo).readByteArray() }
        val record = Sent(request, body, request.operationId)
        synchronized(sent) { sent.add(record) }
        val reply = handler(record)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(reply.status)
            .message("")
            .headers(reply.headers.toHeaders())
            .body(reply.body.toResponseBody(reply.headers["Content-Type"]?.toMediaTypeOrNull()))
            .build()
    }
}

/** A client on a [RoutingTransport]. */
fun RoutingTransport.api(
    interceptors: List<okhttp3.Interceptor> = emptyList(),
    onClientOutdated: (() -> Unit)? = null,
): ApiClient = ApiClient(APIFixtures.baseURL, client, interceptors, onClientOutdated)

/** Runs a suspending test on real threads (the stub transport runs on `Dispatchers.IO`). */
fun test(block: suspend CoroutineScope.() -> Unit) {
    runBlocking { block() }
}

/** `Date(timeIntervalSince1970: 1_790_856_900)`: 2026-10-01T12:15:00Z. */
val fixtureNow: Instant = Instant.ofEpochSecond(1_790_856_900)

/** Counts calls from any thread. */
class CallCounter {
    @Volatile
    var count = 0
        private set

    @Synchronized
    fun increment() {
        count += 1
    }
}

object APIFixtures {
    val baseURL: URI = URI("https://api.staging.getinsiteview.com")

    fun string(name: String): String =
        requireNotNull(APIFixtures::class.java.getResource("/$name.json")) { "Missing fixture $name.json" }.readText()

    /** `GET /v1/public/buildings/TEST01`, as the API writes it (.NET dates, string enums). */
    val publicBuilding = """
        {"code":"TEST01","displayCode":"IV-TEST-01","name":"Test room","addressLine":"1 Test Street","city":"Palmas",
         "builder":{"name":"Construtora Exemplo","logoUrl":null},"verifiedAt":"2026-09-28T14:03:11.5170000+00:00",
         "status":"Live","pinRequired":false,"thumbnailUrl":null}
    """.trimIndent()

    fun visit(
        token: String = "visit-token-1",
        expiresAt: String = "2026-10-01T23:00:00+00:00",
        systems: String = "null",
        pinLocked: Boolean = false,
    ): String =
        """{"visitId":"01926f3a-0000-7000-8000-0000000000aa","visitToken":"$token","expiresAt":"$expiresAt","via":"Plate","systems":$systems,"pinLocked":$pinLocked,"readOnly":false,"building":$publicBuilding}"""

    val element = """
        {"id":"e22dff25b5a264301813afd2f07c0ff68","globalId":"0YtvxbrQHA6Q5rY2wfM0VQ","ifcClass":"IfcOutlet","kind":"outlet",
         "name":"Outlet K-01","tag":"K-01","typeName":null,"system":"electrical","subsystem":"power",
         "storey":{"id":"e614d613568044166b4fe08af3b18d142","name":"Level 1"},
         "space":{"id":"ed3083b6225a94e56856253148965de2f","name":"R1","longName":"Test room"},
         "keyProps":{"circuit":"K-04","voltage":"120 V"},
         "props":{"Pset_Common":{"Reference":"K-01","IsExternal":false,"Height":0.3}},"documents":[]}
    """.trimIndent()

    fun auth(access: String, refresh: String? = "refresh-2"): String {
        val refreshJSON = refresh?.let { "\"$it\"" } ?: "null"
        return """
            {"accessToken":"$access","accessTokenExpiresAt":"2026-10-01T12:15:00+00:00","refreshToken":$refreshJSON,
             "refreshTokenExpiresAt":"2026-10-31T12:00:00+00:00",
             "user":{"id":"01926f3a-0000-7000-8000-00000000000a","email":"ana@example.com","emailConfirmed":true,
                     "displayName":"Ana","locale":"pt-BR","units":"Metric","marketingEmails":true,
                     "organizations":[{"id":"01926f3a-0000-7000-8000-00000000000b","name":"Construtora Exemplo","role":"Owner"}]}}
        """.trimIndent()
    }

    const val ORGANIZATION_ID = "01926f3a-0000-7000-8000-00000000000b"

    /** `GET /v1/me/buildings`: one of each kind, as the API writes them. */
    val myBuildings = """
        [{"id":"01926f3a-0000-7000-8000-000000000001","code":"TEST01","displayCode":"IV-TEST-01","name":"Test room","city":"Palmas",
          "status":"TrialLive","isLive":true,"organizationId":"01926f3a-0000-7000-8000-00000000000b","organizationName":"Construtora Exemplo",
          "via":"Member","role":"Owner","systems":null,"accessExpiresAt":null,"verifiedAt":"2026-09-28T14:03:11.517+00:00"},
         {"id":"01926f3a-0000-7000-8000-000000000002","code":"8K29X7","displayCode":"IV-8K29-X7","name":"Casa Azul","city":null,
          "status":"Active","isLive":true,"organizationId":"01926f3a-0000-7000-8000-00000000000c","organizationName":"Outra",
          "via":"Grant","role":"Contractor","systems":["plumbing"],"accessExpiresAt":"2026-12-01T00:00:00+00:00","verifiedAt":null},
         {"id":"01926f3a-0000-7000-8000-000000000003","code":"ABCDEF","displayCode":"IV-ABCD-EF","name":"apartamento 12","city":"São Paulo",
          "status":"Paused","isLive":false,"organizationId":"01926f3a-0000-7000-8000-00000000000d","organizationName":null,
          "via":"Saved","role":"Visitor","systems":null,"accessExpiresAt":null,"verifiedAt":null},
         {"id":"01926f3a-0000-7000-8000-000000000004","code":"XYZ123","displayCode":"IV-XYZ1-23","name":"Draft house","city":null,
          "status":"Draft","isLive":false,"organizationId":"01926f3a-0000-7000-8000-00000000000b","organizationName":"Construtora Exemplo",
          "via":"Member","role":"Owner","systems":null,"accessExpiresAt":null,"verifiedAt":null}]
    """.trimIndent()

    /** `GET /v1/orgs`. */
    val organizations = """
        [{"id":"01926F3A-0000-7000-8000-00000000000B","name":"Construtora Exemplo","type":"Builder","country":"BR","timeZone":"America/Sao_Paulo","currency":"Brl",
          "trialEndsAt":"2026-10-09T12:00:00+00:00","inTrial":true,"showBranding":true,"logoUrl":null,"role":"Owner",
          "createdAt":"2026-09-25T12:00:00+00:00"}]
    """.trimIndent()

    /** `GET /v1/buildings/{id}` for a member. */
    val buildingDetail = """
        {"id":"01926f3a-0000-7000-8000-000000000001","organizationId":"01926f3a-0000-7000-8000-00000000000b","code":"TEST01",
         "displayCode":"IV-TEST-01","name":"Test room","addressLine":null,"city":"Palmas","region":null,"country":"BR","postalCode":null,
         "status":"Active","isLive":true,"floorAreaM2":42.5,"floorAreaMethod":"spaces","units":1,
         "currentVersionId":"01926f3a-0000-7000-8000-0000000000f1","verifiedAt":null,"trialLiveAt":"2026-09-26T12:00:00+00:00",
         "activatedAt":"2026-09-29T12:00:00+00:00","expiresAt":"2036-09-29T12:00:00+00:00","pinEnabled":false,
         "createdAt":"2026-09-25T12:00:00+00:00","archivedAt":null,"role":"Admin","tier":"Home","activationCredits":1}
    """.trimIndent()

    /** `GET /v1/me/search?q=outlet`. */
    val mySearch = """
        {"query":"outlet","buildings":[{"id":"01926f3a-0000-7000-8000-000000000002","code":"8K29X7","name":"Casa Azul","addressLine":null,"city":null}],
         "hits":[
          {"buildingId":"01926F3A-0000-7000-8000-000000000001","buildingName":"Test room","hit":{"type":"Element","id":"e1","title":"Outlet K-01","kind":"outlet","kindName":"Outlet","system":"electrical","tag":"K-01","room":"Kitchen","storey":"Level 1"}},
          {"buildingId":"01926f3a-0000-7000-8000-000000000001","buildingName":"Test room","hit":{"type":"Element","id":"e2","title":"Outlet","kind":"outlet","kindName":"Outlet","system":"electrical","tag":null,"room":null,"storey":"Level 1"}},
          {"buildingId":"01926f3a-0000-7000-8000-000000000001","buildingName":"Test room","hit":{"type":"Element","id":"e3","title":"Outlet K-02","kind":"outlet","kindName":"Outlet","system":"electrical","tag":"K-02","room":"Kitchen","storey":"Level 1"}},
          {"buildingId":"01926f3a-0000-7000-8000-000000000002","buildingName":"Casa Azul","hit":{"type":"Element","id":"e4","title":"Outlet B-01","kind":"outlet","kindName":"Outlet","system":"electrical","tag":"B-01","room":"Bath","storey":null}},
          {"buildingId":"01926f3a-0000-7000-8000-000000000001","buildingName":"Test room","hit":{"type":"Element","id":"e5","title":"Outlet L-01","kind":"outlet","kindName":"Outlet","system":"electrical","tag":"L-01","room":"Living","storey":"Level 1"}}]}
    """.trimIndent()

    /** `GET /v1/buildings/{id}/documents`: one confirmed, one still uploading. */
    val documents = """
        [{"id":"01926F3A-0000-7000-8000-0000000000d1","title":"Panel manual","kind":"Manual","fileName":"panel-manual.pdf","mime":"application/pdf",
          "sizeBytes":120400,"elementId":"e1","visibleToGuests":false,"uploaded":true,
          "url":"https://files.example.com/d1?X-Amz-Signature=abc","createdAt":"2026-09-29T12:00:00+00:00"},
         {"id":"01926f3a-0000-7000-8000-0000000000d2","title":"Warranty","kind":"Warranty","fileName":"warranty.png","mime":"image/png",
          "sizeBytes":1000,"elementId":null,"visibleToGuests":true,"uploaded":false,"url":null,"createdAt":"2026-09-29T12:00:00+00:00"}]
    """.trimIndent()
}
