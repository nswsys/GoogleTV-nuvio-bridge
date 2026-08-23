package com.nswsys.nuviobridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class GoogleTvDetail(
    val candidates: List<String>,
    val year: Int?,
    val typeHint: MediaType?,
    val primaryActionBounds: Rect?
)

object GoogleTvDetailDetector {
    private val actionMarker = Regex(
        """\b(?:watch now|watch on|ways to watch|buy|rent|play|resume|trailer|reproducir|ver ahora|d[oó]nde ver|comprar|alquilar)\b""",
        RegexOption.IGNORE_CASE
    )
    private val seriesMarker = Regex(
        """\b(?:season|seasons|episode|episodes|temporada|temporadas|episodio|episodios)\b""",
        RegexOption.IGNORE_CASE
    )
    private val movieDurationMarker = Regex(
        """\b\d+\s*(?:hr|hrs|hour|hours|h)\b""",
        RegexOption.IGNORE_CASE
    )
    private val navigationLabels = setOf(
        "home", "inicio", "for you", "para ti", "live", "en vivo", "apps",
        "library", "biblioteca", "search", "buscar", "shop", "tienda",
        "watchlist", "lista de seguimiento", "trailer", "trailers"
    )

    fun detect(root: AccessibilityNodeInfo?): GoogleTvDetail? {
        if (root == null) return null
        val nodes = ArrayList<NodeText>()
        // Google TV's current launcher nests entity details below several
        // FrameLayouts, a TabsViewPager and multiple grids. On some builds the
        // title row is 14-18 levels below the accessibility root, so the old
        // depth limit of 12 stopped immediately before reaching the metadata.
        collect(root, nodes, depth = 0, remaining = intArrayOf(500))

        // The title-row resource is unique to Google TV's entity detail page
        // and appears earlier than the playback buttons on slower devices.
        val titleRow = nodes.firstOrNull {
            it.resourceId.endsWith("/entity_details_title_row")
        } ?: return null
        val primaryAction = nodes.firstOrNull {
            it.className == "android.widget.Button" && actionMarker.containsMatchIn(it.value)
        }

        val allValues = nodes.map(NodeText::value)
        // Google TV normally exposes the release year as its own TextView in
        // the metadata row. Prefer it over years that may occur in a synopsis.
        val year = nodes.asSequence()
            .filter { it.className.endsWith("TextView") }
            .map(NodeText::value)
            .firstNotNullOfOrNull { value ->
                value.takeIf { it.trim().matches(Regex("(?:19|20)\\d{2}")) }
                    ?.toIntOrNull()
            }
            ?: allValues.firstNotNullOfOrNull(TitleNormalizer::extractYear)
        val typeHint = when {
            allValues.any(seriesMarker::containsMatchIn) -> MediaType.SERIES
            allValues.any(movieDurationMarker::containsMatchIn) -> MediaType.MOVIE
            else -> null
        }

        val titles = LinkedHashSet<String>()
        titleRow.value.takeIf(::looksLikeTitle)?.let(titles::add)
        nodes.asSequence()
            .filter { it.className.endsWith("TextView") || it.className == "android.view.View" }
            .filter { it.bounds.top < 760 }
            .map(NodeText::value)
            .filter(::looksLikeTitle)
            .forEach { value -> TitleNormalizer.candidates(listOf(value)).forEach(titles::add) }

        if (titles.isEmpty()) {
            nodes.asSequence()
                .map(NodeText::value)
                .filter(::looksLikeTitle)
                .forEach { value -> TitleNormalizer.candidates(listOf(value)).forEach(titles::add) }
        }
        if (titles.isEmpty()) return null
        return GoogleTvDetail(
            candidates = titles.take(5),
            year = year,
            typeHint = typeHint,
            primaryActionBounds = primaryAction?.bounds?.let(::Rect)
        )
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        output: MutableList<NodeText>,
        depth: Int,
        remaining: IntArray
    ) {
        if (depth > 24 || remaining[0] <= 0) return
        remaining[0]--
        val bounds = Rect().also(node::getBoundsInScreen)
        node.text?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
            output += NodeText(
                it,
                node.className?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(),
                bounds
            )
        }
        node.contentDescription?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
            output += NodeText(
                it,
                node.className?.toString().orEmpty(),
                node.viewIdResourceName.orEmpty(),
                bounds
            )
        }
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child -> collect(child, output, depth + 1, remaining) }
        }
    }

    private fun looksLikeTitle(value: String): Boolean {
        val clean = value.trim()
        val normalized = TitleNormalizer.normalized(clean)
        if (clean.length !in 2..90 || normalized in navigationLabels) return false
        if (actionMarker.containsMatchIn(clean)) return false
        if (clean.matches(Regex("(?:19|20)\\d{2}"))) return false
        if (clean.contains('•') || clean.count { it == '.' } >= 2) return false
        if (!clean.any(Char::isLetter)) return false
        return TitleNormalizer.candidates(listOf(clean)).isNotEmpty()
    }

    private data class NodeText(
        val value: String,
        val className: String,
        val resourceId: String,
        val bounds: Rect
    )
}
