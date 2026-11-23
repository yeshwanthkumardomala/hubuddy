package com.sidya.deskhub.data

import com.sidya.deskhub.core.Clock
import com.sidya.deskhub.core.PcProfile
import com.sidya.deskhub.core.PcRegistry
import com.sidya.deskhub.core.SecretStore
import com.sidya.deskhub.core.Settings
import com.sidya.deskhub.net.ApiClient
import com.sidya.deskhub.net.ApiResult
import com.sidya.deskhub.net.CompanionLink
import com.sidya.deskhub.net.ErrKind
import com.sidya.deskhub.net.describe
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Live state of one PC. */
data class PcLive(
    val pc: PcProfile,
    val paired: Boolean = false,
    val online: Boolean = false,
    val connecting: Boolean = false,
    val snapshot: JSONObject? = null,
    val snapshotAt: Long = 0,
    val fromCache: Boolean = false,
    val error: String? = null,
    val errorKind: ErrKind? = null,
    val latencyMs: Long = -1,
    val failures: Int = 0,
    val nextRetryAt: Long = 0,
    /** CPU % of recent successful polls, oldest first (for charts). */
    val cpuHistory: List<Double> = emptyList(),
) {
    fun section(name: String): JSONObject? = snapshot?.optJSONObject(name)
    fun num(section: String, key: String): Double? =
        section(section)?.let { if (it.has(key) && !it.isNull(key)) it.optDouble(key).takeIf { v -> !v.isNaN() } else null }
}

/**
 * Everything the screens render. The plain fields mirror the **selected PC**, so single-PC screens
 * (Home, AI, Controls) stay simple; [pcs] holds every PC for the overview screens.
 */
data class DashState(
    val paired: Boolean = false,
    val online: Boolean = false,
    val connecting: Boolean = false,
    val snapshot: JSONObject? = null,
    val snapshotAt: Long = 0,
    val fromCache: Boolean = false,
    val error: String? = null,
    val errorKind: ErrKind? = null,
    val latencyMs: Long = -1,
    val failures: Int = 0,
    val nextRetryAt: Long = 0,
    val cpuHistory: List<Double> = emptyList(),
    val weather: JSONObject? = null,
    val weatherAt: Long = 0,
    val weatherError: String? = null,
    val github: JSONObject? = null,
    val githubAt: Long = 0,
    val githubError: String? = null,
    val pcs: List<PcLive> = emptyList(),
    val activeId: String = "",
) {
    fun section(name: String): JSONObject? = snapshot?.optJSONObject(name)
    val activePc: PcLive? get() = pcs.firstOrNull { it.pc.id == activeId }
    val onlineCount: Int get() = pcs.count { it.online }
}

/**
 * Polls every paired PC only while the dashboard is visible ([start]/[stop]); each PC backs off
 * exponentially on its own when unreachable and keeps its last good snapshot on disk, so the app
 * opens with useful (clearly marked) data while offline.
 */
class DashboardRepository(
    private val settings: Settings,
    private val secrets: SecretStore,
    val companion: CompanionLink,
    private val api: ApiClient,
    private val cacheDir: File,
    private val clock: Clock,
    private val deliver: Executor,
    val registry: PcRegistry,
    private val pcPool: ScheduledExecutorService = Executors.newScheduledThreadPool(4) { r -> Thread(r, "deskhub-pc").apply { isDaemon = true } },
    private val netExec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deskhub-net").apply { isDaemon = true } },
) {
    @Volatile var state = DashState()
        private set

    private class Net(
        val weather: JSONObject? = null, val weatherAt: Long = 0, val weatherError: String? = null,
        val github: JSONObject? = null, val githubAt: Long = 0, val githubError: String? = null,
    )

    private var net = Net()
    private val live = LinkedHashMap<String, PcLive>()
    private val tasks = HashMap<String, ScheduledFuture<*>>()
    private val lastCacheWrite = HashMap<String, Long>()
    private val listeners = LinkedHashSet<(DashState) -> Unit>()
    private var netTask: ScheduledFuture<*>? = null
    @Volatile private var running = false
    @Volatile private var generation = 0

    init {
        syncPcs()
        loadCache()
        recompute()
    }

    fun addListener(l: (DashState) -> Unit) { synchronized(listeners) { listeners.add(l) }; l(state) }
    fun removeListener(l: (DashState) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    // ---- lifecycle -----------------------------------------------------------
    @Synchronized
    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        syncPcs()
        live.keys.forEach { schedulePc(it, gen, 0) }
        netTask = netExec.schedule({ pollInternet(gen) }, 300, TimeUnit.MILLISECONDS)
        recomputeAndPublish()
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        tasks.values.forEach { it.cancel(false) }; tasks.clear()
        netTask?.cancel(false); netTask = null
    }

    /** Re-poll everything now (settings, pairing or the PC list changed, or a manual refresh). */
    @Synchronized
    fun refreshNow() {
        syncPcs()
        if (!running) { recomputeAndPublish(); return }
        val gen = ++generation
        tasks.values.forEach { it.cancel(false) }; tasks.clear()
        live.keys.forEach { id -> live[id] = live[id]!!.copy(failures = 0, paired = registry.isPaired(id)); schedulePc(id, gen, 0) }
        recomputeAndPublish()
    }

    /** Switch which PC the single-PC screens show. */
    fun selectPc(id: String) {
        registry.activeId = id
        synchronized(this) { recompute() }
        publish()
    }

    fun refreshInternet() {
        if (!running) return
        val gen = generation
        netExec.execute { fetchWeather(); fetchGitHub(); if (gen == generation) publish() }
    }

    // ---- per-PC polling --------------------------------------------------------
    /** Make [live] match the registry: add new PCs, drop removed ones, refresh names and addresses. */
    @Synchronized
    private fun syncPcs() {
        val profiles = registry.list()
        val ids = profiles.map { it.id }.toSet()
        (live.keys - ids).toList().forEach { id ->
            live.remove(id); tasks.remove(id)?.cancel(false); File(cacheDir, "snapshot_$id.json").delete()
        }
        profiles.forEach { p ->
            val cur = live[p.id]
            live[p.id] = cur?.copy(pc = p, paired = registry.isPaired(p.id)) ?: loadSnapshot(p.id).let { cached ->
                PcLive(p, paired = registry.isPaired(p.id), snapshot = cached?.second, snapshotAt = cached?.first ?: 0, fromCache = cached != null)
            }
        }
        // Keep registry order.
        val ordered = LinkedHashMap<String, PcLive>()
        profiles.forEach { ordered[it.id] = live[it.id]!! }
        live.clear(); live.putAll(ordered)
        if (running) {
            val gen = generation
            profiles.forEach { if (tasks[it.id] == null) schedulePc(it.id, gen, 0) }
        }
    }

    @Synchronized
    private fun schedulePc(id: String, gen: Int, delayMs: Long) {
        if (!running || gen != generation) return
        tasks[id]?.cancel(false)
        tasks[id] = pcPool.schedule({ pollPc(id, gen) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun mutate(id: String, f: (PcLive) -> PcLive) {
        synchronized(this) {
            val cur = live[id] ?: return
            live[id] = f(cur)
            recompute()
        }
        publish()
    }

    private fun pollPc(id: String, gen: Int) {
        if (!running || gen != generation) return
        val profile = registry.get(id) ?: return
        val delayMs: Long = try {
            if (!registry.isPaired(id)) {
                mutate(id) { it.copy(pc = profile, paired = false, online = false, connecting = false, error = "Not paired with this PC. Pair in Settings.", errorKind = ErrKind.NOT_PAIRED) }
                30_000
            } else {
                mutate(id) { it.copy(pc = profile, paired = true, connecting = true) }
                when (val r = companion.get("/api/snapshot", id)) {
                    is ApiResult.Ok -> {
                        val now = clock.now()
                        val cpu = r.json.optJSONObject("stats")?.let { if (it.has("cpu") && !it.isNull("cpu")) it.optDouble("cpu") else Double.NaN }?.takeIf { v -> !v.isNaN() }
                        mutate(id) {
                            it.copy(online = true, connecting = false, snapshot = r.json, snapshotAt = now, fromCache = false, error = null, errorKind = null,
                                latencyMs = r.latencyMs, failures = 0, nextRetryAt = 0,
                                cpuHistory = if (cpu == null) it.cpuHistory else (it.cpuHistory + cpu).takeLast(HISTORY))
                        }
                        maybeWriteCache(id, r.json, now)
                        autoName(profile, r.json)
                        settings.refreshSec * 1000L
                    }
                    is ApiResult.Err -> {
                        val fails = (live[id]?.failures ?: 0) + 1
                        val backoff = backoffMs(settings.refreshSec, fails, r.kind)
                        mutate(id) {
                            it.copy(online = false, connecting = false, error = r.describe(), errorKind = r.kind, failures = fails,
                                latencyMs = -1, nextRetryAt = clock.now() + backoff, fromCache = it.snapshot != null)
                        }
                        backoff
                    }
                }
            }
        } catch (e: Exception) {
            // Never let a bad payload kill the poller.
            mutate(id) { it.copy(online = false, connecting = false, error = "Unexpected error: ${e.javaClass.simpleName}", errorKind = ErrKind.BAD_RESPONSE) }
            30_000
        }
        schedulePc(id, gen, delayMs)
    }

    /** A PC still called "My PC" / "PC 2" takes the computer name it reports. */
    private fun autoName(p: PcProfile, snapshot: JSONObject) {
        val host = snapshot.optJSONObject("pc")?.optString("hostname")?.trim().orEmpty()
        if (host.isNotEmpty() && Regex("^(My PC|PC \\d+)$").matches(p.name) && registry.list().none { it.name.equals(host, true) }) {
            registry.update(p.copy(name = host))
            synchronized(this) { live[p.id]?.let { live[p.id] = it.copy(pc = p.copy(name = host.take(24))) }; recompute() }
            publish()
        }
    }

    /** Run a command on a PC (the selected one by default); the result comes back on the main thread. */
    fun command(path: String, body: JSONObject, pcId: String? = null, cb: (ApiResult) -> Unit) {
        val id = pcId ?: registry.activeId
        pcPool.execute {
            val r = try { companion.post(path, body, id) } catch (e: Exception) { ApiResult.Err(ErrKind.BAD_RESPONSE, "error") }
            deliver.execute { cb(r) }
            if (r is ApiResult.Ok) synchronized(this) { schedulePc(id, generation, 0) }
        }
    }

    /** Arbitrary background job (connection test, pairing). */
    fun background(job: () -> ApiResult, cb: (ApiResult) -> Unit) {
        pcPool.execute {
            val r = try { job() } catch (e: Exception) { ApiResult.Err(ErrKind.BAD_RESPONSE, "error") }
            deliver.execute { cb(r) }
        }
    }

    // ---- internet (weather, GitHub) ----------------------------------------------
    private fun pollInternet(gen: Int) {
        if (!running || gen != generation) return
        try {
            if (clock.now() - net.weatherAt > WEATHER_EVERY_MS || net.weather == null) fetchWeather()
            if (clock.now() - net.githubAt > GITHUB_EVERY_MS || net.github == null) fetchGitHub()
        } catch (e: Exception) {
            setNet { Net(it.weather, it.weatherAt, "Weather unavailable.", it.github, it.githubAt, it.githubError) }
        }
        synchronized(this) {
            if (running && gen == generation) netTask = netExec.schedule({ pollInternet(gen) }, 60_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun setNet(f: (Net) -> Net) { synchronized(this) { net = f(net); recompute() }; publish() }

    private fun fetchWeather() {
        val lat = settings.weatherLat
        val lon = settings.weatherLon
        if (lat == null || lon == null) {
            setNet { Net(it.weather, it.weatherAt, "Set a weather location in Settings.", it.github, it.githubAt, it.githubError) }
            return
        }
        val unit = if (settings.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6&timezone=auto$unit"
        when (val r = api.request(url)) {
            is ApiResult.Ok -> {
                val now = clock.now()
                setNet { Net(r.json, now, null, it.github, it.githubAt, it.githubError) }
                writeFile("weather.json", JSONObject().put("at", now).put("data", r.json))
            }
            is ApiResult.Err -> setNet {
                Net(it.weather, it.weatherAt, if (it.weather != null) "Weather offline - showing last forecast." else "Weather unavailable (no internet?).",
                    it.github, it.githubAt, it.githubError)
            }
        }
    }

    private fun fetchGitHub() {
        val repo = settings.githubRepo
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) {
            setNet { Net(it.weather, it.weatherAt, it.weatherError, it.github, it.githubAt, "Set a repository (owner/name) in Settings.") }
            return
        }
        val token = secrets.get(GITHUB_TOKEN)
        val headers = mapOf("Accept" to "application/vnd.github+json")
        when (val r = api.request("https://api.github.com/repos/$repo", bearer = token, headers = headers)) {
            is ApiResult.Ok -> setNet { Net(it.weather, it.weatherAt, it.weatherError, r.json, clock.now(), null) }
            is ApiResult.Err -> setNet {
                Net(it.weather, it.weatherAt, it.weatherError, it.github, it.githubAt, when {
                    r.kind == ErrKind.HTTP && r.code == 404 -> "Repository not found (private repos need a token)."
                    r.kind == ErrKind.UNAUTHORIZED -> "GitHub token rejected."
                    else -> "GitHub unavailable (no internet?)."
                })
            }
        }
    }

    /** Geocode a city name with Open-Meteo (no key). Result delivered on the main thread. */
    fun geocode(name: String, cb: (String?, Double?, Double?, String?) -> Unit) {
        netExec.execute {
            val q = URLEncoder.encode(name.trim(), "UTF-8")
            val r = api.request("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&format=json")
            val res = (r as? ApiResult.Ok)?.json?.optJSONArray("results")?.optJSONObject(0)
            deliver.execute {
                if (res == null) cb(null, null, null, if (r is ApiResult.Err) "No internet or service unavailable." else "Place not found.")
                else cb("${res.optString("name")}, ${res.optString("country_code")}", res.optDouble("latitude"), res.optDouble("longitude"), null)
            }
        }
    }

    /** Test/screenshot hook: pretend weather and GitHub data arrived (tests have no internet). */
    internal fun seedInternet(weather: JSONObject?, github: JSONObject?) {
        setNet { Net(weather, clock.now(), null, github, clock.now(), null) }
    }

    // ---- state -----------------------------------------------------------------------
    fun clearCache() {
        cacheDir.listFiles { f -> f.name.startsWith("snapshot") }?.forEach { it.delete() }
        synchronized(this) { live.keys.toList().forEach { id -> live[id] = live[id]!!.copy(snapshot = null, snapshotAt = 0, fromCache = false) }; recompute() }
        publish()
    }

    /** Build the published state from the live map, the selected PC and the internet data. Call holding the lock. */
    private fun recompute() {
        val pcs = live.values.toList()
        val activeId = registry.activeId
        val a = live[activeId] ?: pcs.firstOrNull()
        state = DashState(
            paired = a?.paired ?: false, online = a?.online ?: false, connecting = a?.connecting ?: false,
            snapshot = a?.snapshot, snapshotAt = a?.snapshotAt ?: 0, fromCache = a?.fromCache ?: false,
            error = a?.error, errorKind = a?.errorKind, latencyMs = a?.latencyMs ?: -1, failures = a?.failures ?: 0,
            nextRetryAt = a?.nextRetryAt ?: 0, cpuHistory = a?.cpuHistory ?: emptyList(),
            weather = net.weather, weatherAt = net.weatherAt, weatherError = net.weatherError,
            github = net.github, githubAt = net.githubAt, githubError = net.githubError,
            pcs = pcs, activeId = a?.pc?.id ?: activeId,
        )
    }

    private fun recomputeAndPublish() { synchronized(this) { recompute() }; publish() }

    private fun publish() {
        val s = state
        deliver.execute {
            val ls = synchronized(listeners) { listeners.toList() }
            ls.forEach { it(s) }
        }
    }

    // ---- disk cache --------------------------------------------------------------------
    private fun maybeWriteCache(id: String, json: JSONObject, now: Long) {
        if (now - (lastCacheWrite[id] ?: 0L) < 30_000) return
        lastCacheWrite[id] = now
        writeFile("snapshot_$id.json", JSONObject().put("at", now).put("data", json))
    }

    private fun writeFile(name: String, obj: JSONObject) {
        try {
            val tmp = File(cacheDir, "$name.tmp")
            tmp.writeText(obj.toString())
            tmp.renameTo(File(cacheDir, name))
        } catch (e: Exception) { /* cache is best effort */ }
    }

    private fun readFile(name: String): JSONObject? = try {
        File(cacheDir, name).takeIf { it.isFile }?.readText()?.let { JSONObject(it) }
    } catch (e: Exception) { null }

    private fun loadSnapshot(id: String): Pair<Long, JSONObject>? {
        val o = readFile("snapshot_$id.json") ?: (if (id == "pc1") readFile("snapshot.json") else null) ?: return null
        val data = o.optJSONObject("data") ?: return null
        return o.optLong("at") to data
    }

    private fun loadCache() {
        val weather = readFile("weather.json")
        // Cached weather counts as stale so it is refreshed as soon as we can.
        net = Net(weather = weather?.optJSONObject("data"), weatherAt = 0)
    }

    companion object {
        const val GITHUB_TOKEN = "github.token"
        const val HISTORY = 60
        const val WEATHER_EVERY_MS = 30 * 60_000L
        const val GITHUB_EVERY_MS = 30 * 60_000L

        /** Exponential backoff: refresh, 2x, 4x ... capped at 60 s (auth errors wait longer). */
        fun backoffMs(refreshSec: Int, failures: Int, kind: ErrKind): Long {
            if (kind == ErrKind.UNAUTHORIZED || kind == ErrKind.BAD_ADDRESS) return 60_000
            if (kind == ErrKind.RATE_LIMITED) return 60_000
            val base = refreshSec.coerceAtLeast(2) * 1000L
            val exp = (failures - 1).coerceIn(0, 5)
            return (base shl exp).coerceAtMost(60_000)
        }
    }
}
