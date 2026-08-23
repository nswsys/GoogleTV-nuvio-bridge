package com.nswsys.nuviobridge

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

class TmdbClient(private val apiKeyProvider: () -> String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    fun search(candidate: String): MediaMatch? {
        return searchAll(candidate).firstOrNull()
    }

    fun searchAll(candidate: String, typeHint: MediaType? = null): List<MediaMatch> {
        val apiKey = apiKeyProvider().trim()
        if (apiKey.isBlank()) return emptyList()

        val language = Locale.getDefault().toLanguageTag().ifBlank { "en-US" }
        val url = "https://api.themoviedb.org/3/search/multi".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            // Year is used for scoring/filtering, not as part of TMDB's text
            // query. Sending "Title 2019" can produce zero search results.
            .addQueryParameter("query", TitleNormalizer.withoutYear(candidate))
            .addQueryParameter("language", language)
            .addQueryParameter("include_adult", "false")
            .build()

        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "TMDB search failed: ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                matches(candidate, JSONObject(body), typeHint)
            }
        } catch (exception: Exception) {
            Log.e(TAG, "TMDB search error", exception)
            emptyList()
        }
    }

    internal fun bestMatch(candidate: String, json: JSONObject): MediaMatch? {
        return matches(candidate, json).firstOrNull()
    }

    internal fun matches(
        candidate: String,
        json: JSONObject,
        typeHint: MediaType? = null
    ): List<MediaMatch> {
        val expectedYear = TitleNormalizer.extractYear(candidate)
        val titleQuery = TitleNormalizer.withoutYear(candidate)
        val results = json.optJSONArray("results") ?: return emptyList()
        val matches = ArrayList<MediaMatch>()

        // TMDB returns up to 20 items per search page. Inspecting the complete
        // page is important for short titles such as "Alone" or "Godzilla",
        // where another exact movie/series match may be below the first 12.
        for (index in 0 until minOf(results.length(), 20)) {
            val item = results.optJSONObject(index) ?: continue
            val type = when (item.optString("media_type")) {
                "movie" -> MediaType.MOVIE
                "tv" -> MediaType.SERIES
                else -> continue
            }
            if (typeHint != null && type != typeHint) continue
            val title = if (type == MediaType.MOVIE) item.optString("title") else item.optString("name")
            val originalTitle = if (type == MediaType.MOVIE) {
                item.optString("original_title")
            } else {
                item.optString("original_name")
            }
            if (title.isBlank() && originalTitle.isBlank()) continue

            val titleScore = maxOf(
                TitleNormalizer.similarity(titleQuery, title),
                TitleNormalizer.similarity(titleQuery, originalTitle)
            )
            val resultYear = (if (type == MediaType.MOVIE) {
                item.optString("release_date")
            } else {
                item.optString("first_air_date")
            }).take(4).toIntOrNull()
            val yearBonus = when {
                expectedYear == null || resultYear == null -> 0.0
                expectedYear == resultYear -> 8.0
                else -> -8.0
            }
            val popularityBonus = (item.optDouble("popularity", 0.0) / 100.0).coerceIn(0.0, 4.0)
            val score = titleScore + yearBonus + popularityBonus

            val match = MediaMatch(
                tmdbId = item.optInt("id"),
                type = type,
                title = title.ifBlank { originalTitle },
                score = score,
                year = resultYear
            )
            if (match.tmdbId > 0 && match.score >= MINIMUM_SCORE) matches += match
        }

        return matches.sortedByDescending(MediaMatch::score)
    }

    companion object {
        private const val TAG = "NuvioBridgeTmdb"
        private const val MINIMUM_SCORE = 52.0
    }
}
