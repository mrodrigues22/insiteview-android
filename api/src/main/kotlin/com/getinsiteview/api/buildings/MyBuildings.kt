package com.getinsiteview.api.buildings

import com.getinsiteview.api.ApiClient
import com.getinsiteview.api.MyBuilding
import com.getinsiteview.api.Organization
import com.getinsiteview.api.myBuildings
import com.getinsiteview.api.organizations
import com.getinsiteview.api.saveBuilding
import com.getinsiteview.api.unsaveBuilding
import com.getinsiteview.core.BuildingCode
import com.getinsiteview.core.BuildingStatus
import com.getinsiteview.core.RecentBuildings
import java.text.Collator
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred

/**
 * The signed-in user's buildings (`GET /v1/me/buildings`) and organizations (`GET /v1/orgs`, for
 * the trial's end), fetched once and shared by the Buildings tab, search and the building screens
 * (which look up a building's id and role by code). Cleared on sign-out.
 */
class MyBuildingsRepository(private val api: ApiClient) {
    private val lock = Any()
    private var buildings: List<MyBuilding>? = null
    private var organizations: List<Organization>? = null
    private var loading: CompletableDeferred<List<MyBuilding>>? = null

    /** Bumped by [clear], so a load that started before a sign-out doesn't come back. */
    private var generation = 0

    /** The last list, without a call. */
    val cached: List<MyBuilding>? get() = synchronized(lock) { buildings }

    /** The list; fetched on the first call and when [refresh] is set (pull to refresh). */
    suspend fun list(refresh: Boolean = false): List<MyBuilding> {
        var owner = false
        val startGeneration: Int
        val deferred = synchronized(lock) {
            val current = buildings
            if (!refresh && current != null) return current
            startGeneration = generation
            loading ?: CompletableDeferred<List<MyBuilding>>().also {
                loading = it
                owner = true
            }
        }
        if (!owner) return deferred.await()
        try {
            val list = api.myBuildings()
            synchronized(lock) { if (startGeneration == generation) buildings = list }
            deferred.complete(list)
            return list
        } catch (e: Throwable) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            synchronized(lock) { if (loading === deferred) loading = null }
        }
    }

    /**
     * My building with this code, from the list (fetched once if needed); `null` when it isn't
     * mine or the list can't be loaded.
     */
    suspend fun building(code: BuildingCode): MyBuilding? {
        val list = cached ?: try {
            list()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return list?.firstOrNull { BuildingCode.parse(it.code) == code }
    }

    /**
     * When each of my organizations' trial ends, by organization id (lower-case UUID). Empty when
     * the user belongs to none or they can't be loaded.
     */
    suspend fun trialEnds(refresh: Boolean = false): Map<String, Instant> {
        val (needed, startGeneration) = synchronized(lock) { (refresh || organizations == null) to generation }
        if (needed) {
            val loaded = try {
                api.organizations()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (loaded != null) synchronized(lock) { if (startGeneration == generation) organizations = loaded }
        }
        val result = LinkedHashMap<String, Instant>()
        for (organization in synchronized(lock) { organizations } ?: emptyList()) {
            result.putIfAbsent(organization.id.lowercase(), organization.trialEndsAt)
        }
        return result
    }

    /** Saves a building to the account (IOS-M3-08); the list is fetched again next time. */
    suspend fun save(code: BuildingCode) {
        api.saveBuilding(code)
        synchronized(lock) { buildings = null }
    }

    suspend fun unsave(code: BuildingCode) {
        api.unsaveBuilding(code)
        synchronized(lock) { buildings = null }
    }

    /** Signed out: forget everything. */
    fun clear() {
        synchronized(lock) {
            generation += 1
            buildings = null
            organizations = null
            loading = null
        }
    }
}

/** The Buildings tab's segments (A-02): All · Active · Recent · Favorites. */
enum class BuildingListSegment(val raw: String) {
    ALL("all"),

    /** Live now: the QR codes work (trial or activated). */
    ACTIVE("active"),

    /** Opened on this device, newest first. */
    RECENT("recent"),

    /** Starred on this device (MVP: not synced). */
    FAVORITES("favorites"),
    ;

    val id: String get() = raw

    /** The rows of a segment. Codes compare in their canonical form. */
    fun rows(
        buildings: List<MyBuilding>,
        favorites: Set<String>,
        recents: List<RecentBuildings.Entry>,
        language: String? = null,
    ): List<MyBuilding> {
        val names = StandardComparator(language?.let(Locale::forLanguageTag) ?: Locale.getDefault())
        val byName = buildings.sortedWith { lhs, rhs ->
            val order = names.compare(lhs.name, rhs.name)
            if (order == 0) lhs.code.compareTo(rhs.code) else order
        }
        return when (this) {
            ALL -> byName
            ACTIVE -> byName.filter { it.status == BuildingStatus.TRIAL_LIVE || it.status == BuildingStatus.ACTIVE }
            FAVORITES -> byName.filter { building -> BuildingCode.parse(building.code)?.let { it.raw in favorites } ?: false }
            RECENT -> {
                val byCode = LinkedHashMap<String, MyBuilding>()
                for (building in buildings) {
                    val code = BuildingCode.parse(building.code) ?: continue
                    byCode.putIfAbsent(code.raw, building)
                }
                recents.mapNotNull { byCode[it.code.raw] }
            }
        }
    }
}

/**
 * Finder-style name order (iOS `localizedStandardCompare`): case- and accent-insensitive first,
 * runs of digits by value ("Unit 2" before "Unit 10").
 */
internal class StandardComparator(locale: Locale) : Comparator<String> {
    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        val left = chunks(a)
        val right = chunks(b)
        for (i in 0 until minOf(left.size, right.size)) {
            val l = left[i]
            val r = right[i]
            val order = if (l.first().isDigit() && r.first().isDigit()) {
                val byValue = l.trimStart('0').length.compareTo(r.trimStart('0').length)
                if (byValue != 0) byValue else l.trimStart('0').compareTo(r.trimStart('0'))
            } else {
                collator.compare(l, r)
            }
            if (order != 0) return order
        }
        val bySize = left.size.compareTo(right.size)
        return if (bySize != 0) bySize else Collator.getInstance().compare(a, b)
    }

    private fun chunks(string: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (character in string) {
            if (current.isNotEmpty() && current.last().isDigit() != character.isDigit()) {
                result.add(current.toString())
                current.clear()
            }
            current.append(character)
        }
        if (current.isNotEmpty()) result.add(current.toString())
        return result
    }
}
