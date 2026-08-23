package com.nswsys.nuviobridge

enum class MediaType(val tmdbPath: String, val nuvioPath: String) {
    MOVIE("movie", "movie"),
    SERIES("tv", "tv")
}

data class MediaMatch(
    val tmdbId: Int,
    val type: MediaType,
    val title: String,
    val score: Double,
    val year: Int? = null
)

data class MediaResolution(
    val query: String,
    val options: List<MediaMatch>
) {
    val preferred: MediaMatch get() = options.first()
    val isAmbiguous: Boolean get() = options.size > 1
}
