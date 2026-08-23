package com.nswsys.nuviobridge

object SponsoredSectionDetector {
    private val sponsoredMarkers = setOf(
        "sponsored",
        "patrocinado",
        "patrocinada"
    )
    private val sponsoredActions = setOf(
        "learn more",
        "scan qr code",
        "escanear codigo qr",
        "more info",
        "info",
        "mas informacion",
        "obtener mas informacion",
        "why this ad",
        "por que este anuncio"
    )

    fun isSponsoredContainer(labels: List<String>): Boolean {
        val normalized = labels.map(TitleNormalizer::normalized).filter(String::isNotBlank)
        return normalized.any(sponsoredMarkers::contains) &&
            normalized.any(sponsoredActions::contains)
    }
}
