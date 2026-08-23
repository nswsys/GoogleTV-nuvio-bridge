package com.nswsys.nuviobridge

import android.content.Context

class MatchCache(context: Context) {
    // A new namespace prevents false matches learned by earlier permissive
    // classifiers from surviving an application update.
    private val preferences = context.getSharedPreferences("match_cache_v4", Context.MODE_PRIVATE)

    fun get(candidate: String): MediaMatch? {
        val key = TitleNormalizer.normalized(candidate)
        val parts = preferences.getString(key, null)?.split('|') ?: return null
        if (parts.size !in 4..5) return null

        val savedAt = parts[0].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - savedAt > MAX_AGE_MS) {
            preferences.edit().remove(key).apply()
            return null
        }

        val type = runCatching { MediaType.valueOf(parts[1]) }.getOrNull() ?: return null
        val id = parts[2].toIntOrNull() ?: return null
        val year = parts.getOrNull(4)?.toIntOrNull()
        return MediaMatch(id, type, parts[3], 100.0, year)
    }

    fun put(candidate: String, match: MediaMatch) {
        val value = listOf(
            System.currentTimeMillis().toString(),
            match.type.name,
            match.tmdbId.toString(),
            match.title.replace('|', ' '),
            match.year?.toString().orEmpty()
        ).joinToString("|")
        preferences.edit().putString(TitleNormalizer.normalized(candidate), value).apply()
    }

    companion object {
        private const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}
