package com.nswsys.nuviobridge

/**
 * Classifies a Google TV container as an advertising slot.
 *
 * Google TV labels its ad rows inconsistently across builds and locales. Some
 * expose a "Sponsored" header TextView, some only a "Learn more" call to
 * action, and Compose-rendered rows frequently expose the badge as a content
 * description on a node several levels below the row container. Demanding a
 * label that is exactly "sponsored" plus a label that is exactly one of a
 * handful of actions matched almost nothing on real hardware, so the evidence
 * is scored instead: any unambiguous badge is enough on its own, while generic
 * calls to action only reinforce another signal.
 */
object SponsoredSectionDetector {
    /** Result of inspecting one container, with the reasons kept for logging. */
    data class Verdict(val score: Int, val reasons: List<String>) {
        val isSponsored: Boolean get() = score >= SPONSORED_SCORE

        override fun toString(): String =
            "score=$score" + reasons.joinToString(prefix = " [", postfix = "]")
    }

    private const val SPONSORED_SCORE = 2
    private const val DECISIVE_WEIGHT = 3
    private const val MARKER_WEIGHT = 2
    private const val RESOURCE_ID_WEIGHT = 2
    private const val ACTION_WEIGHT = 1
    private const val MAX_ACTION_SIGNALS = 2

    // Phrases that only ever appear inside an ad slot.
    private val decisiveMarkers = listOf(
        "why this ad",
        "why am i seeing this ad",
        "about this ad",
        "por que este anuncio",
        "por que veo este anuncio",
        "acerca de este anuncio",
        "sobre este anuncio",
        "sponsored content",
        "contenido patrocinado",
        "paid partnership",
        "colaboracion pagada",
        "publicidad pagada",
        "ad choices",
        "adchoices"
    )

    // Badges Google TV puts on the ad row itself. Enough on their own.
    private val markers = listOf(
        "sponsored",
        "sponsor",
        "patrocinado",
        "patrocinada",
        "patrocinados",
        "patrocinadas",
        "patrocinio",
        "publicidad",
        "anuncio",
        "anuncios",
        "advertisement",
        "advertising",
        "promoted",
        "promocionado",
        "promocionada"
    )

    // Generic calls to action. Ads use them, but so do ordinary info cards, so
    // these never classify a container on their own.
    private val actions = listOf(
        "learn more",
        "more info",
        "info",
        "ver mas",
        "mas informacion",
        "obtener mas informacion",
        "saber mas",
        "descubre mas",
        "scan qr code",
        "scan the qr code",
        "escanear codigo qr",
        "escanea el codigo qr",
        "shop now",
        "comprar ahora",
        "visit site",
        "visit website",
        "visitar el sitio",
        "get the app",
        "descargar la app",
        "install now",
        "instalar ahora",
        "get tickets",
        "comprar entradas"
    )

    // View ids such as ".../sponsored_row" or ".../ad_badge". The delimiters
    // keep "ad" from matching inside words like "shadow", "header" or "add".
    private val resourceIdMarker = Regex(
        """(?:^|[^a-z])(?:ads?|adverts?|advertisement|advertising|sponsored|sponsorship|sponsor)(?:$|[^a-z])"""
    )

    // Prose can mention advertising without being an ad. A badge is only
    // trusted on a short label, or when it opens the label.
    private const val MAX_MARKER_TOKENS = 12

    fun evaluate(labels: List<String>, resourceIds: List<String> = emptyList()): Verdict {
        var score = 0
        val reasons = ArrayList<String>(3)

        val normalizedLabels = labels.asSequence()
            .map(TitleNormalizer::normalized)
            .filter(String::isNotBlank)
            .distinct()
            .toList()

        normalizedLabels.firstOrNull { label -> decisiveMarkers.any { label.containsPhrase(it) } }
            ?.let {
                score += DECISIVE_WEIGHT
                reasons += "adDisclosure=\"$it\""
            }

        if (reasons.isEmpty()) {
            normalizedLabels.firstOrNull(::carriesBadge)?.let {
                score += MARKER_WEIGHT
                reasons += "badge=\"$it\""
            }
        }

        resourceIds.asSequence()
            .map { it.substringAfterLast('/').lowercase() }
            .firstOrNull(resourceIdMarker::containsMatchIn)
            ?.let {
                score += RESOURCE_ID_WEIGHT
                reasons += "viewId=\"$it\""
            }

        normalizedLabels.asSequence()
            .mapNotNull { label -> actions.firstOrNull { label.containsPhrase(it) } }
            .distinct()
            .take(MAX_ACTION_SIGNALS)
            .forEach {
                score += ACTION_WEIGHT
                reasons += "action=\"$it\""
            }

        return Verdict(score, reasons)
    }

    fun isSponsoredContainer(
        labels: List<String>,
        resourceIds: List<String> = emptyList()
    ): Boolean = evaluate(labels, resourceIds).isSponsored

    private fun carriesBadge(label: String): Boolean {
        val marker = markers.firstOrNull { label.containsPhrase(it) } ?: return false
        if (label.startsWith("$marker ") || label == marker) return true
        return label.count { it == ' ' } + 1 <= MAX_MARKER_TOKENS
    }

    /** Whole-token containment, so "anuncio" never matches "anunciante". */
    private fun String.containsPhrase(phrase: String): Boolean =
        " $this ".contains(" $phrase ")
}
