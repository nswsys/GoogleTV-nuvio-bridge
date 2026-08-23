package com.nswsys.nuviobridge

import android.content.Context

class MediaResolver(context: Context) {
    private val cache = MatchCache(context)
    private val tmdb = TmdbClient { AppSettings.tmdbApiKey(context) }

    fun resolve(candidates: List<String>, isCurrent: () -> Boolean): MediaMatch? {
        return resolveOptions(candidates, isCurrent)?.preferred
    }

    fun resolveOptions(
        candidates: List<String>,
        isCurrent: () -> Boolean,
        typeHint: MediaType? = null
    ): MediaResolution? {
        val requestedYear = candidates.firstNotNullOfOrNull(TitleNormalizer::extractYear)
        candidates.forEach { candidate ->
            if (!isCurrent()) return null
            cache.get(candidate)?.let { cached ->
                if (requestedYear == null || cached.year == requestedYear) {
                    return MediaResolution(candidate, listOf(cached))
                }
            }
        }

        var fallback: Pair<String, List<MediaMatch>>? = null
        candidates.take(4).forEach { candidate ->
            if (!isCurrent()) return null
            val matches = tmdb.searchAll(candidate, typeHint)
            val match = matches.firstOrNull() ?: return@forEach
            val strong = selectOptions(candidate, matches)
            val previous = fallback
            if (previous == null || match.score > previous.second.first().score) {
                fallback = candidate to (strong.ifEmpty { listOf(match) })
            }
            if (match.score >= 92.0) {
                val options = strong.ifEmpty { listOf(match) }
                if (options.size == 1) cache.put(candidate, match)
                return MediaResolution(candidate, options)
            }
        }

        return fallback?.let { (candidate, options) ->
            if (options.size == 1) cache.put(candidate, options.first())
            MediaResolution(candidate, options)
        }
    }

    fun remember(query: String, match: MediaMatch) = cache.put(query, match)

    companion object {
        private const val AMBIGUITY_SCORE = 92.0
        private const val MAX_OPTIONS = 5

        fun selectOptions(
            candidate: String,
            matches: List<MediaMatch>
        ): List<MediaMatch> {
            val expectedYear = TitleNormalizer.extractYear(candidate)
            val eligible = matches
                .filter { expectedYear == null || it.year == null || it.year == expectedYear }
                .distinctBy { it.type to it.tmdbId }
            val title = TitleNormalizer.withoutYear(candidate)
            val exact = eligible.filter {
                TitleNormalizer.similarity(title, it.title) == 100.0
            }
            return (if (exact.size > 1) exact else eligible.filter { it.score >= AMBIGUITY_SCORE })
                .take(MAX_OPTIONS)
        }
    }
}
