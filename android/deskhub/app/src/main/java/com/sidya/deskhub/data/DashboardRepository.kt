package com.sidya.deskhub.data

import com.sidya.deskhub.core.Clock
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

/** Everything the screens render. Immutable; a new instance is published on each change. */
data class DashState(
    val paired: Boolean = false,
    val online: Boolean = false,
    val connecting: Boolean = false,
    /** Last good /api/snapshot from the companion (possibly from the offline cache). */
    val snapshot: JSONObject? = null,
    val snapshotAt: Long = 0,
    val fromCache: Boolean = false,
    val error: String? = null,
    val errorKind: ErrKind? = null,
    val latencyMs: Long = -1,
    val failures: Int = 0,
    val nextRetryAt: Long = 0,
    val weather: JSONObject? = null,
    val weatherAt: Long = 0,
    val weatherError: String? = null,
    val github: JSONObject? = null,
    val githubAt: Long = 0,
    val githubError: String? = null,
) {
    fun section(name: String): JSONObject? = snapshot?.optJSONObject(name)
}

/**
 * Polls the companion only while the dashboard is visible ([start]/[stop]), backs off
 * exponentially when it is unreachable, and keeps the last good snapshot on disk so
 * the app opens with useful (clearly marked) data while offline.
 */
class DashboardRepository(
    private val settings: Settings,
    private val secrets: SecretStore,
    val companion: CompanionLink,
    private val api: ApiClient,
    private val cacheDir: File,
    private val clock: Clock,
    private val deliver: Executor,
    private val pcExec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deskhub-pc").apply { isDaemon = true } },
    private val netExec: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deskhub-net").apply { isDaemon = true } },
) {
    @Volatile var state = DashState()
        private set
    private val listeners = LinkedHashSet<(DashState) -> Unit>()
    private var pcTask: ScheduledFuture<*>? = null
    private var netTask: ScheduledFuture<*>? = null
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var lastCacheWrite = 0L

    init {
        loadCache()
    }

    fun addListener(l: (DashState) -> Unit) { synchronized(listeners) { listeners.add(l) }; l(state) }
    fun removeListener(l: (DashState) -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        val gen = ++generation
        update { it.copy(paired = companion.paired) }
        pcTask = pcExec.schedule({ pollPc(gen) }, 0, TimeUnit.MILLISECONDS)
        netTask = netExec.schedule({ pollInternet(gen) }, 300, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    fun stop() {
        running = false
        generation++
        pcTask?.cancel(false); pcTask = null
        netTask?.cancel(false); netTask = null
    }

    /** Re-poll now (after settings changed, pairing, or a manual refresh). */
    @Synchronized
    fun refreshNow() {
        if (!running) return
        val gen = ++generation
        pcTask?.cancel(false)
        update { it.copy(failures = 0, paired = companion.paired) }
        pcTask = pcExec.schedule({ pollPc(gen) }, 0, TimeUnit.MILLISECONDS)
    }

    fun refreshInternet() {
        if (!running) return
        val gen = generation
        netExec.execute { fetchWeather(); fetchGitHub(); if (gen == generation) publish() }
    }

    private fun pollPc(gen: Int) {
        if (!running || gen != generation) return
        val delayMs: Long = try {
            if (!companion.paired) {
                update { it.copy(paired = false, online = false, connecting = false, error = "Not paired with the PC companion. Pair in Settings.", errorKind = ErrKind.NOT_PAIRED) }
                // Nothing to poll; check again rarely in case pairing happens elsewhere.
                30_000
            } else {
                update { it.copy(connecting = true, paired = true) }
                when (val r = companion.get("/api/snapshot")) {
                    is ApiResult.Ok -> {
                        val now = clock.now()
                        update {
                            it.copy(online = true, connecting = false, snapshot = r.json, snapshotAt = now, fromCache = false,
                                error = null, errorKind = null, latencyMs = r.latencyMs, failures = 0, nextRetryAt = 0)
                        }
                        maybeWriteCache(r.json, now)
                        settings.refreshSec * 1000L
                    }
                    is ApiResult.Err -> {
                        val fails = state.failures + 1
                        val backoff = backoffMs(settings.refreshSec, fails, r.kind)
                        update {
                            it.copy(online = false, connecting = false, error = r.describe(), errorKind = r.kind,
                                failures = fails, latencyMs = -1, nextRetryAt = clock.now() + backoff,
                                fromCache = it.snapshot != null)
                        }
                        backoff
                    }
                }
            }
        } catch (e: Exception) {
            // Never let a bad payload kill the poller.
            update { it.copy(online = false, connecting = false, error = "Unexpected error: ${e.javaClass.simpleName}", errorKind = ErrKind.BAD_RESPONSE) }
            30_000
        }
        synchronized(this) {
            if (running && gen == generation) pcTask = pcExec.schedule({ pollPc(gen) }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun pollInternet(gen: Int) {
        if (!running || gen != generation) return
        try {
            if (clock.now() - state.weatherAt > WEATHER_EVERY_MS || state.weather == null) fetchWeather()
            if (clock.now() - state.githubAt > GITHUB_EVERY_MS || state.github == null) fetchGitHub()
        } catch (e: Exception) {
            update { it.copy(weatherError = "Weather unavailable.") }
        }
        synchronized(this) {
            if (running && gen == generation) netTask = netExec.schedule({ pollInternet(gen) }, 60_000, TimeUnit.MILLISECONDS)
        }
    }

    private fun fetchWeather() {
        val lat = settings.weatherLat
        val lon = settings.weatherLon
        if (lat == null || lon == null) {
            update { it.copy(weatherError = "Set a weather location in Settings.") }
            return
        }
        val unit = if (settings.fahrenheit) "&temperature_unit=fahrenheit" else ""
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6&timezone=auto$unit"
        when (val r = api.request(url)) {
            is ApiResult.Ok -> {
                val now = clock.now()
                update { it.copy(weather = r.json, weatherAt = now, weatherError = null) }
                writeFile("weather.json", JSONObject().put("at", now).put("data", r.json))
            }
            is ApiResult.Err -> update {
                it.copy(weatherError = if (it.weather != null) "Weather offline - showing last forecast." else "Weather unavailable (no internet?).")
            }
        }
    }

    private fun fetchGitHub() {
        val repo = settings.githubRepo
        if (!Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$").matches(repo)) {
            update { it.copy(githubError = "Set a repository (owner/name) in Settings.") }
            return
        }
        val token = secrets.get(GITHUB_TOKEN)
        val headers = mapOf("Accept" to "application/vnd.github+json")
        when (val r = api.request("https://api.github.com/repos/$repo", bearer = token, headers = headers)) {
            is ApiResult.Ok -> update { it.copy(github = r.json, githubAt = clock.now(), githubError = null) }
            is ApiResult.Err -> update {
                it.copy(githubError = when {
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

    /** Run a companion command off the main thread; the result comes back on the main thread. */
    fun command(path: String, body: JSONObject, cb: (ApiResult) -> Unit) {
        pcExec.execute {
            val r = try { companion.post(path, body) } catch (e: Exception) { ApiResult.Err(ErrKind.BAD_RESPONSE, "error") }
            deliver.execute { cb(r) }
            if (r is ApiResult.Ok) refreshNow()
        }
    }

    /** Arbitrary background job on the PC thread (connection test, pairing). */
    fun background(job: () -> ApiResult, cb: (ApiResult) -> Unit) {
        pcExec.execute {
            val r = try { job() } catch (e: Exception) { ApiResult.Err(ErrKind.BAD_RESPONSE, "error") }
            deliver.execute { cb(r) }
        }
    }

    fun clearCache() {
        File(cacheDir, "snapshot.json").delete()
        update { it.copy(snapshot = null, snapshotAt = 0, fromCache = false) }
    }

    private fun update(f: (DashState) -> DashState) {
        synchronized(this) { state = f(state) }
        publish()
    }

    private fun publish() {
        val s = state
        deliver.execute {
            val ls = synchronized(listeners) { listeners.toList() }
            ls.forEach { it(s) }
        }
    }

    private fun maybeWriteCache(json: JSONObject, now: Long) {
        if (now - lastCacheWrite < 30_000) return
        lastCacheWrite = now
        writeFile("snapshot.json", JSONObject().put("at", now).put("data", json))
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

    private fun loadCache() {
        val snap = readFile("snapshot.json")
        val weather = readFile("weather.json")
        state = state.copy(
            paired = companion.paired,
            snapshot = snap?.optJSONObject("data"),
            snapshotAt = snap?.optLong("at") ?: 0,
            fromCache = snap != null,
            weather = weather?.optJSONObject("data"),
            // Cached weather counts as stale so it is refreshed as soon as we can.
            weatherAt = 0,
        )
    }

    companion object {
        const val GITHUB_TOKEN = "github.token"
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
