package com.openminis.app.plugins.muse

import android.content.Context
import com.openminis.app.tools.FetchUrlGuard
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Fetch the Muse-Plugins-Store index.json with last-good caching, following
 * the PluginRegistry fetch pattern (guard checks, same-origin redirects,
 * SharedPreferences cache).
 *
 * [T-muse-store-mirror] GitHub raw is unreachable from some networks, so
 * every raw.githubusercontent URL transparently falls back to the jsDelivr
 * CDN copy of the same path — deterministic same-content transform, no
 * third-party rewrite service involved.
 */
object MuseIndexClient {

    const val DEFAULT_INDEX_URL =
        "https://raw.githubusercontent.com/xzyy03/Muse-Plugins-Store/main/index.json"

    private const val PREFS = "muse_store"
    private const val KEY_CACHE = "index_cache"
    private const val KEY_OVERRIDE = "index_url_override"

    /** Seconds of connect/read timeout, matching PluginRegistry. */
    private const val TIMEOUT_SECONDS = 15L

    data class Result(val entries: List<MuseIndexEntry>, val live: Boolean)

    fun overrideUrl(context: Context): String? =
        prefs(context).getString(KEY_OVERRIDE, null)?.trim()?.ifEmpty { null }

    fun setOverrideUrl(context: Context, url: String?) {
        prefs(context).edit().putString(KEY_OVERRIDE, url?.trim()?.ifEmpty { null }).apply()
        prefs(context).edit().remove(KEY_CACHE).apply()
    }

    /** Try override → default raw → jsDelivr mirror; on total failure return cache. */
    fun fetch(context: Context): Result {
        val override = overrideUrl(context)
        val candidates = buildList {
            if (override != null) add(override)
            add(DEFAULT_INDEX_URL)
            mirrorFor(DEFAULT_INDEX_URL)?.let { add(it) }
        }
        for (url in candidates) {
            val text = fetchText(context, url) ?: continue
            val entries = MuseIndexParser.parseIndex(text)
            if (entries.isEmpty()) continue
            prefs(context).edit().putString(KEY_CACHE, text).apply()
            return Result(entries, live = true)
        }
        val cached = prefs(context).getString(KEY_CACHE, null)
        return Result(MuseIndexParser.parseIndex(cached.orEmpty()), live = false)
    }

    fun cached(context: Context): List<MuseIndexEntry> =
        MuseIndexParser.parseIndex(prefs(context).getString(KEY_CACHE, null).orEmpty())

    /**
     * Download an arbitrary Muse asset (index or package zip) with mirror
     * fallback. Returns bytes or null.
     */
    fun fetchBytes(context: Context, url: String): ByteArray? {
        val candidates = buildList {
            add(url)
            mirrorFor(url)?.let { add(it) }
        }
        for (candidate in candidates) {
            val bytes = fetchBytesOnce(context, candidate) ?: continue
            if (bytes.isNotEmpty()) return bytes
        }
        return null
    }

    /**
     * Deterministic same-path CDN transform for raw.githubusercontent URLs:
     * https://raw.githubusercontent.com/<user>/<repo>/<ref>/<path>
     * → https://cdn.jsdelivr.net/gh/<user>/<repo>@<ref>/<path>
     */
    fun mirrorFor(url: String): String? {
        val marker = "https://raw.githubusercontent.com/"
        if (!url.startsWith(marker)) return null
        val rest = url.removePrefix(marker)
        val parts = rest.split('/', limit = 4)
        if (parts.size < 4) return null
        val (user, repo, ref, path) = parts
        if (user.isEmpty() || repo.isEmpty() || ref.isEmpty() || path.isEmpty()) return null
        return "https://cdn.jsdelivr.net/gh/$user/$repo@$ref/$path"
    }

    private fun fetchText(context: Context, url: String): String? {
        FetchUrlGuard.blockedReason(url)?.let { return null }
        val client = client()
        var current = url
        val origin = URI(url)
        var redirects = 0
        repeat(3) {
            FetchUrlGuard.blockedReason(current)?.let { return null }
            val response = client.newCall(
                Request.Builder().url(current).header("Accept", "application/json").build(),
            ).execute()
            response.use {
                when {
                    it.code in 300..399 -> {
                        val location = it.header("Location") ?: return null
                        if (redirects++ >= 2) return null
                        val next = URI(current).resolve(location)
                        if (!next.scheme.equals(origin.scheme, true) ||
                            !next.host.equals(origin.host, true)
                        ) return null
                        current = next.toString()
                    }
                    it.isSuccessful -> return it.body?.string()
                    else -> return null
                }
            }
        }
        return null
    }

    private fun fetchBytesOnce(context: Context, url: String): ByteArray? {
        FetchUrlGuard.blockedReason(url)?.let { return null }
        val client = client()
        return runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.bytes()
            }
        }.getOrNull()
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .dns(FetchUrlGuard.publicInternetDns())
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
