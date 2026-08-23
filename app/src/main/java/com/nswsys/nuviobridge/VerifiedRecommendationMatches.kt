package com.nswsys.nuviobridge

object VerifiedRecommendationMatches {
    fun find(candidates: List<String>, rawValues: List<String>): MediaMatch? {
        val title = candidates.firstOrNull()?.let(TitleNormalizer::normalized) ?: return null
        val context = rawValues.joinToString(" ") { TitleNormalizer.normalized(it) }

        return when {
            title == "lanterns" -> match(95350, MediaType.SERIES, "Lanterns", 2026)
            title == "backrooms" -> match(1083381, MediaType.MOVIE, "Backrooms", 2026)
            title == "obsession" && "peacock" in context ->
                match(1339713, MediaType.MOVIE, "Obsession", 2025)
            title == "the three investigators and the secret of skeleton island" ->
                match(
                    4407,
                    MediaType.MOVIE,
                    "The Three Investigators and the Secret of Skeleton Island",
                    2007
                )
            title in setOf(
                "star wars the mandalorian and grogu",
                "the mandalorian and grogu",
                "the mandalorian grogu",
                "mandalorian grogu"
            ) -> match(1228710, MediaType.MOVIE, "The Mandalorian and Grogu", 2026)
            title in setOf(
                "star wars attack of the clones",
                "star wars episode ii attack of the clones",
                "star wars episode 2 attack of the clones"
            ) -> match(
                1894,
                MediaType.MOVIE,
                "Star Wars: Episode II – Attack of the Clones",
                2002
            )
            title == "the bay" && ("britbox" in context || "lisa armstrong" in context) ->
                match(87773, MediaType.SERIES, "The Bay", 2019)
            else -> null
        }
    }

    private fun match(
        tmdbId: Int,
        type: MediaType,
        title: String,
        year: Int
    ) = MediaMatch(
        tmdbId = tmdbId,
        type = type,
        title = title,
        score = 100.0,
        year = year
    )
}
