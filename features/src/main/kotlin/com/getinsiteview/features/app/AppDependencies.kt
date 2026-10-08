package com.getinsiteview.features.app

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.os.ConfigurationCompat
import com.getinsiteview.api.AcceptLanguageInterceptor
import com.getinsiteview.api.AnalyticsUploader
import com.getinsiteview.api.ApiClient
import com.getinsiteview.api.ApiError
import com.getinsiteview.api.BearerInterceptor
import com.getinsiteview.api.CatalogRepository
import com.getinsiteview.api.ClientVersionInterceptor
import com.getinsiteview.api.DeviceIdInterceptor
import com.getinsiteview.api.DeviceIdentity
import com.getinsiteview.api.FileAnalyticsQueueStorage
import com.getinsiteview.api.KeyValueDeviceIdStore
import com.getinsiteview.api.TokenStore
import com.getinsiteview.api.UserCredentials
import com.getinsiteview.api.VisitPlatform
import com.getinsiteview.api.account.AccountService
import com.getinsiteview.api.buildings.DocumentDownloader
import com.getinsiteview.api.buildings.MyBuildingsRepository
import com.getinsiteview.api.refresh
import com.getinsiteview.core.AppConfiguration
import com.getinsiteview.core.BuildingHandoff
import com.getinsiteview.core.CatalogLanguage
import com.getinsiteview.core.FavoriteBuildings
import com.getinsiteview.core.GuestPreferences
import com.getinsiteview.core.KeyValueStore
import com.getinsiteview.core.RecentBuildings
import com.getinsiteview.core.Router
import com.getinsiteview.core.UnitFormatter
import com.getinsiteview.core.UnitSystem
import com.getinsiteview.core.WebLinks
import com.getinsiteview.features.account.AccountModel
import com.getinsiteview.features.account.SignInPrompt
import com.getinsiteview.features.guest.AppUpdate
import com.getinsiteview.features.storage.DataStoreKeyValueStore
import com.getinsiteview.features.storage.KeystoreTokenStore
import com.getinsiteview.modelkit.ChunkCache
import com.getinsiteview.modelkit.ChunkDownloader
import com.getinsiteview.modelkit.ChunkTransport
import com.getinsiteview.modelkit.OkHttpChunkTransport
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Everything screens need, built once per launch (docs/PLAN.md §2 "Code structure"): the API
 * client with its interceptors, credentials and the account, my buildings, the chunk downloader
 * and cache, documents, and the catalog. Screens read it from [LocalAppDependencies].
 *
 * Android has no App Clip (docs/PLAN.md §6), so there is no `target`: this is always the app
 * (iOS `.app`): sign-in is available, recents are recorded, visits are `AndroidApp`.
 *
 * Main-thread object: the observable values ([unitSystem], [ClientStatus.updateRequired]) are
 * Compose state.
 *
 * @param deviceID the random per-install id ([DeviceIdentity.current]); [live] reads it.
 * @param keyValueStore iOS `UserDefaults`: guest flags, favourites, recents, units, the handoff.
 * @param initialUnitSystem the unit system stored on the device, if any ([live] reads it).
 * @param preferredLanguages the app's locales, most preferred first (`Accept-Language`, catalog names).
 * @param applicationScope process-wide work (analytics timer, flushes, the account's updates).
 */
class AppDependencies(
    val configuration: AppConfiguration,
    val deviceID: String,
    tokenStore: TokenStore,
    private val keyValueStore: KeyValueStore,
    cacheDirectory: File,
    documentsDirectory: File,
    catalogCacheFile: File?,
    analyticsFile: File?,
    deviceName: String?,
    private val preferredLanguages: () -> List<String>,
    initialUnitSystem: UnitSystem? = null,
    transport: OkHttpClient = ApiClient.defaultTransport,
    chunkTransport: ChunkTransport = OkHttpChunkTransport(),
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    val router: Router = Router(configuration.webBaseURL)

    /**
     * Set when the API turns this version away (426 `client.outdated`): the app covers everything
     * with "Update required" (master PLAN §9 "Schema and client compatibility").
     */
    val clientStatus = ClientStatus()

    val credentials: UserCredentials

    /** Device id, client version, `Accept-Language` and bearer interceptors. */
    val api: ApiClient
    val downloader: ChunkDownloader
    val catalog: CatalogRepository

    /** Analytics batches (PLAN §12), flushed every 30 s and when the app goes to the background. */
    val analytics: AnalyticsUploader

    /** Once-per-device guest flags (AR explainer, safety note). */
    val preferences = GuestPreferences(keyValueStore)

    /** Sign-in and the signed-in user (IOS-M3-01). */
    val account: AccountModel

    /** Lets shared screens ask the app to show its sign-in sheet. */
    val signInPrompt = SignInPrompt(isAvailable = true)

    /** `GET /v1/me/buildings` and `GET /v1/orgs`, shared by the tabs and the building screens. */
    val myBuildings: MyBuildingsRepository

    /** Documents as files for `ACTION_VIEW` (IOS-M3-06). */
    val documents: DocumentDownloader

    /** Starred and recently opened buildings on this device (the Buildings tab's segments). */
    val favorites = FavoriteBuildings(keyValueStore)
    val recents = RecentBuildings(keyValueStore)

    /** The web → app handoff through the Play Install Referrer (Android's App Clip handoff). */
    val handoff = BuildingHandoff(keyValueStore)

    /** Pages on the web: forgot password, add building, terms, privacy, help. */
    val webLinks = WebLinks(configuration.webBaseURL)

    init {
        val onClientOutdated: () -> Unit = {
            // Called on OkHttp's thread.
            applicationScope.launch { clientStatus.markOutdated() }
        }
        val base = listOf(
            DeviceIdInterceptor(deviceID),
            ClientVersionInterceptor(configuration.appVersion),
            AcceptLanguageInterceptor(preferredLanguages),
        )
        // Refreshing goes through a client without the bearer interceptor.
        val authAPI = ApiClient(configuration.apiBaseURL, transport, base, onClientOutdated)
        credentials = UserCredentials(tokenStore) { refreshToken ->
            authAPI.refresh(refreshToken).tokens
                ?: throw ApiError(code = ApiError.Code.AUTH_INVALID_CREDENTIALS, status = 401)
        }
        api = ApiClient(configuration.apiBaseURL, transport, base + BearerInterceptor(credentials), onClientOutdated)
        downloader = ChunkDownloader(transport = chunkTransport, cache = ChunkCache(cacheDirectory))
        catalog = CatalogRepository(api, catalogCacheFile)
        analytics = AnalyticsUploader(api, analyticsFile?.let(::FileAnalyticsQueueStorage))
        myBuildings = MyBuildingsRepository(api)
        documents = DocumentDownloader(chunkTransport, documentsDirectory)
        val service = AccountService(
            api = api, credentials = credentials, deviceName = deviceName,
            locale = { CatalogLanguage.preferred(preferredLanguages()).raw },
        )
        account = AccountModel(service, myBuildings, applicationScope)
        account.start()
        analytics.start(applicationScope)
    }

    /** What "Update" does on ProblemView's "Update required": the Google Play page. */
    val appUpdate: AppUpdate get() = AppUpdate(configuration.playStore)

    /** Sent with every visit (analytics, PLAN §12). */
    val platform: VisitPlatform get() = VisitPlatform.ANDROID_APP

    /** Catalog names follow the app's language (en, pt-BR or es; English otherwise). */
    val language: CatalogLanguage get() = CatalogLanguage.preferred(preferredLanguages())

    private var storedUnitSystem by mutableStateOf(initialUnitSystem)

    /**
     * From the locale unless the user picked one in Profile (kept on the device and, signed in,
     * on the account).
     */
    var unitSystem: UnitSystem
        get() = storedUnitSystem ?: UnitSystem.default(Locale.getDefault())
        set(value) {
            storedUnitSystem = value
            applicationScope.launch { keyValueStore.putString(UNIT_SYSTEM_KEY, value.raw) }
        }

    val unitFormatter: UnitFormatter get() = UnitFormatter(unitSystem, Locale.getDefault())

    companion object {
        const val UNIT_SYSTEM_KEY = "com.getinsiteview.units"

        /**
         * The app's dependencies (call once, from the Application): the device id and tokens in
         * DataStore (tokens sealed with an Android Keystore key), the chunk cache in
         * `cacheDir/models`, documents in `cacheDir/documents` (the FileProvider's `documents/`
         * cache path), the catalog in `cacheDir/catalog.json` and the analytics queue in
         * `filesDir/analytics-queue.json`.
         *
         * Reads the device id and the stored unit system from DataStore before returning: a
         * single small read, blocking the calling thread once at launch (iOS reads both
         * synchronously from the Keychain and `UserDefaults`).
         */
        fun live(context: Context, configuration: AppConfiguration): AppDependencies {
            val app = context.applicationContext
            val store = DataStoreKeyValueStore.of(app)
            val (deviceID, units) = runBlocking(Dispatchers.IO) {
                DeviceIdentity.current(KeyValueDeviceIdStore(store)) to
                    store.getString(UNIT_SYSTEM_KEY)?.let(UnitSystem::fromRaw)
            }
            return AppDependencies(
                configuration = configuration,
                deviceID = deviceID,
                tokenStore = KeystoreTokenStore(store),
                keyValueStore = store,
                cacheDirectory = File(app.cacheDir, "models"),
                documentsDirectory = File(app.cacheDir, "documents"),
                catalogCacheFile = File(app.cacheDir, "catalog.json"),
                analyticsFile = File(app.filesDir, "analytics-queue.json"),
                deviceName = Build.MODEL,
                preferredLanguages = { preferredLanguages(app) },
                initialUnitSystem = units,
            )
        }

        /** The app's locales (per-app language on Android 13+, else the system's), as BCP 47 tags. */
        private fun preferredLanguages(context: Context): List<String> {
            val locales = ConfigurationCompat.getLocales(context.resources.configuration)
            return (0 until locales.size()).mapNotNull { locales.get(it)?.toLanguageTag() }
        }
    }
}

/**
 * Whether the API still serves this app version. Any client's 426 `client.outdated` sets
 * [updateRequired]; it stays set until the app is updated (the next launch is a new version).
 */
class ClientStatus {
    var updateRequired by mutableStateOf(false)
        private set

    fun markOutdated() {
        if (!updateRequired) updateRequired = true
    }
}

/** The app's [AppDependencies], provided at the root by the app (iOS passes them to each screen). */
val LocalAppDependencies = staticCompositionLocalOf<AppDependencies> {
    error("LocalAppDependencies isn't provided: wrap the app's content in CompositionLocalProvider.")
}
