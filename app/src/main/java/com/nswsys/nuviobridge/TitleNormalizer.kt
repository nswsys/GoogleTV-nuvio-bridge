package com.nswsys.nuviobridge

import java.text.Normalizer
import java.util.Locale

object TitleNormalizer {
    private val metadataMarker = Regex(
        pattern = """\s*,?\s*(?:cuesta|costs?|precio|price|puntuaci[oó]n|calificaci[oó]n|(?:fresh|rotten|audience|critic)\s+rating|rating|tomatometer|audience\s+score|se necesita una suscripci[oó]n(?:\s+a)?|requiere una suscripci[oó]n(?:\s+a)?|requires?\s+(?:(?:an?\s+)?subscription(?:\s+to)?|[\p{L}\p{N}+&.' -]{2,40}\s+subscription)|available\s+on|disponible\s+en)\s*:?\s*""",
        option = RegexOption.IGNORE_CASE
    )
    private val trailingRole = Regex(
        pattern = """\s*,?\s*(?:button|bot[oó]n|selected|seleccionado)\s*$""",
        option = RegexOption.IGNORE_CASE
    )
    private val yearPattern = Regex("(?:19|20)\\d{2}")
    private val placeholderPattern = Regex(
        """^(?:column|columna)\s+\d+$|^\d+\s*(?:min|mins|minute|minutes|minuto|minutos)\s+(?:left|remaining|restantes?)$""",
        RegexOption.IGNORE_CASE
    )
    private val playbackActionPattern = Regex(
        """^(?:watch now|watch on|play|resume|trailer|subscribe|reproducir|ver ahora|continuar|ver en|suscribirse|suscr[ií]bete)(?:\s+.+)?$""",
        RegexOption.IGNORE_CASE
    )
    private val nonTitleContentPattern = Regex(
        """(?:\breleased\s+.+?\s+ago\s+in\s+channel\b|\bwith\s+[\d.]+\s*[kmb]?\s+views\b|\bduration\s+is\s+\d+\s+(?:hours?|minutes?|seconds?)\b|\bofficial\s+(?:final\s+)?trailer\b|,\s*youtube\s*[•|]|\bshop\s+(?:now|the\s+collection|on-trend)\b|\btry\s+on\s+(?:the\s+)?collection\b|\bmust-have\s+pieces\b|\bromantic\s+tops\s+and\s+cardigans\b|\bfall\s+rebrand\b|^\s*(?:buy|rent)\s+\$\s*\d|(?:…|\.\.\.)\s*more\s*$)""",
        RegexOption.IGNORE_CASE
    )
    private val spaces = Regex("\\s+")
    private val invisibleFormatting = Regex("\\p{Cf}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}]+")
    private val trailingRomanNumerals = mapOf(
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10"
    )
    private val mediaNodeClasses = setOf(
        "android.view.View",
        "android.view.ViewGroup",
        "android.widget.FrameLayout",
        "android.widget.ImageView"
    )
    private val nonMediaLabels = setOf(
        "action", "accion", "adventure", "aventura", "animation", "animacion",
        "apps", "your apps", "mis aplicaciones", "browse by genre", "buscar por genero",
        "children", "kids", "comedy", "comedia", "crime", "crimen", "documentary", "documental",
        "drama", "family", "familia", "fantasy", "fantasia", "home", "inicio",
        "horror", "terror", "live", "en vivo", "movies", "peliculas", "music", "musica",
        "musicals", "musicales", "mystery", "misterio", "news", "noticias", "profile", "perfil", "profiles", "perfiles",
        "reality tv", "recommendations", "recomendaciones", "romance", "science fiction", "ciencia ficcion",
        "search", "buscar", "settings", "configuracion", "shows", "series", "sports", "deportes",
        "thriller", "western", "watchlist", "lista de seguimiento", "see all", "ver todo",
        "netflix", "youtube", "prime video", "amazon prime video", "disney plus", "hulu",
        "max", "apple tv", "nuvio", "what it s about", "what people are saying",
        "de que se trata", "que dice la gente", "cast crew", "cast and crew", "reparto",
        "seasons", "temporadas", "ways to watch", "formas de ver", "watched it",
        "ya la viste", "subscribe", "suscribirse", "prime video subscribe", "screensaver",
        "protector de pantalla", "new season new looks", "new season",
        "movies and shows across services", "peliculas y series de todos tus servicios",
        "top match animated", "top match"
    )
    private val providerLabels = setOf(
        "amazon prime video", "apple tv", "apple tv+", "crackle", "disney+", "disney plus", "freevee",
        "google tv freeplay", "hbo max", "hulu", "max", "netflix", "paramount+", "paramount plus",
        "amc+", "amc plus", "britbox", "peacock", "philo", "plex", "pluto", "pluto tv",
        "prime video", "roku", "roku channel", "sling tv", "the cw", "cw", "tubi",
        "the roku channel", "tubi tv", "vudu", "youtube", "youtube tv"
    ).map(::normalized).toSet()
    private val launcherControlPrefixes = setOf(
        "settings", "device settings", "system settings", "configuration", "configuracion", "ajustes",
        "search", "buscar", "apps", "applications", "aplicaciones", "home", "inicio",
        "profile", "profiles", "perfil", "perfiles", "notifications", "notificaciones",
        "account", "accounts", "cuenta", "cuentas", "accessibility", "accesibilidad",
        "network", "network and internet", "red", "wifi", "bluetooth", "inputs", "entradas",
        "display", "display and sound", "pantalla", "sound", "sonido", "system", "sistema",
        "privacy", "privacidad", "help", "ayuda"
    )
    private val launcherControlTokens = setOf(
        "settings", "setting", "configuration", "configuracion", "ajustes",
        "notifications", "notification", "notificaciones", "notificacion",
        "accessibility", "accesibilidad", "bluetooth", "wifi"
    )

    fun isLauncherControl(rawValues: List<String>): Boolean = rawValues.any { raw ->
        val value = normalized(raw)
        val tokens = value.split(' ').filter(String::isNotBlank).toSet()
        tokens.any(launcherControlTokens::contains) ||
            launcherControlPrefixes.any { prefix ->
                value == prefix || value.startsWith("$prefix ")
            }
    }

    fun looksLikeRecommendation(
        className: String,
        directDescription: String?,
        directText: List<String>,
        nearbyText: List<String> = emptyList(),
        excludedLabels: Set<String> = emptySet()
    ): Boolean {
        val description = directDescription.orEmpty().trim()
        if (isProviderPlaybackAction(description)) return false
        if (metadataMarker.containsMatchIn(description)) return true

        // Standard recommendation row cards usually expose a View with a
        // compound content description. App tiles generally expose only the
        // app name, so requiring the separator prevents hijacking those clicks.
        if (className == "android.view.View" && description.contains(',')) return true

        // The large hero card exposes several text fields in a ViewGroup:
        // title, subtitle/synopsis and call to action. A single text field is
        // intentionally rejected because app tiles and navigation buttons use it.
        val usefulText = directText.map(String::trim).filter { isUseful(it) }
        if (className == "android.view.ViewGroup" &&
            usefulText.size >= 2 &&
            usefulText.drop(1).any { it.length >= 12 }
        ) return true

        // Some Google TV rows expose only the title of a movie/series. This is
        // common in Browse by genre and Top free picks. Accept those cards, but
        // exclude launcher navigation, genre tiles and installed application
        // labels so ordinary launcher clicks keep their normal behavior.
        if (className !in mediaNodeClasses) return false
        val possibleTitles = candidates(
            buildList {
                directDescription?.let(::add)
                addAll(directText)
                addAll(nearbyText)
            }
        )
        return possibleTitles.any { title ->
            val value = normalized(title)
            value !in nonMediaLabels &&
                value !in excludedLabels &&
                value.any(Char::isLetter) &&
                value.length >= 2
        }
    }

    fun candidates(rawValues: List<String>): List<String> {
        val output = LinkedHashSet<String>()

        rawValues.forEach { raw ->
            val clean = invisibleFormatting.replace(raw, "")
                .replace(spaces, " ").trim().trim('|', '•')
            if (!isUseful(clean)) return@forEach

            val markerMatch = metadataMarker.find(clean)
            if (markerMatch != null) {
                addCandidate(output, removeTrailingProvider(clean.substring(0, markerMatch.range.first)))
            } else {
                val withoutRole = trailingRole.replace(clean, "")
                val withoutProvider = removeTrailingProvider(withoutRole)
                addCandidate(output, withoutProvider)
                if (withoutProvider == withoutRole) addCandidate(output, withoutRole)

                // A card may be "Title, provider/synopsis". Walk commas from
                // right to left so titles containing commas remain intact.
                clean.indices
                    .filter { clean[it] == ',' }
                    .asReversed()
                    .take(3)
                    .forEach { comma -> addCandidate(output, clean.substring(0, comma)) }
            }
        }

        return output.take(6)
    }

    private fun removeTrailingProvider(value: String): String {
        val parts = value.split(',').map(String::trim)
        if (parts.size < 2 || normalized(parts.last()) !in providerLabels) return value
        return parts.dropLast(1).joinToString(", ")
    }

    fun normalized(value: String): String {
        val decomposed = Normalizer.normalize(invisibleFormatting.replace(value, ""), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        val clean = punctuation.replace(decomposed.lowercase(Locale.ROOT), " ")
            .replace(spaces, " ")
            .trim()
        val tokens = clean.split(' ').filter(String::isNotBlank).toMutableList()
        if (tokens.size >= 2) {
            trailingRomanNumerals[tokens.last()]?.let { tokens[tokens.lastIndex] = it }
        }
        return tokens.joinToString(" ")
    }

    fun extractYear(value: String): Int? =
        yearPattern.find(value)?.value?.toIntOrNull()

    fun withoutYear(value: String): String =
        yearPattern.replace(invisibleFormatting.replace(value, ""), " ")
            .replace(spaces, " ").trim()

    fun similarity(query: String, result: String): Double {
        val left = normalized(query)
        val right = normalized(result)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 100.0

        val leftTokens = left.split(' ').filter(String::isNotBlank).toSet()
        val rightTokens = right.split(' ').filter(String::isNotBlank).toSet()
        val union = leftTokens union rightTokens
        val overlap = if (union.isEmpty()) 0.0 else (leftTokens intersect rightTokens).size.toDouble() / union.size

        val prefixBonus = when {
            left.startsWith(right) || right.startsWith(left) -> 18.0
            else -> 0.0
        }
        val containmentBonus = when {
            left.contains(right) || right.contains(left) -> 12.0
            else -> 0.0
        }
        return (overlap * 70.0 + prefixBonus + containmentBonus).coerceAtMost(99.0)
    }

    private fun addCandidate(output: MutableSet<String>, value: String) {
        val candidate = value.trim().trim(',', '-', ':', ';').trim()
        if (isProviderPlaybackAction(candidate)) return
        if (normalized(candidate) in providerLabels) return
        if (isUseful(candidate)) output += candidate
    }

    fun isProviderPlaybackAction(value: String): Boolean {
        val parts = value.split(',').map(String::trim).filter(String::isNotBlank)
        return parts.size == 2 &&
            normalized(parts.first()) in providerLabels &&
            playbackActionPattern.matches(parts.last())
    }

    private fun isUseful(value: String): Boolean {
        if (value.length !in 2..180) return false
        if (nonTitleContentPattern.containsMatchIn(value)) return false
        val normalized = normalized(value)
        if (normalized.length < 2) return false
        if (placeholderPattern.matches(value.trim())) return false
        if (normalized in nonMediaLabels) return false
        return normalized !in setOf(
            "ver ahora", "watch now", "reproducir", "play", "inicio", "home",
            "patrocinado", "sponsored", "mis aplicaciones", "your apps"
        )
    }
}
