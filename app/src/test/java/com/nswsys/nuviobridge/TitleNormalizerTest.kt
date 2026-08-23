package com.nswsys.nuviobridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleNormalizerTest {
    @Test
    fun preservesCommaInsideTitleWhenMetadataMarkerExists() {
        val candidates = TitleNormalizer.candidates(
            listOf("Monstruos, S.A., puntuación: 8.1")
        )
        assertEquals("Monstruos, S.A.", candidates.first())
    }

    @Test
    fun supportsEnglishSubscriptionMarker() {
        val candidates = TitleNormalizer.candidates(
            listOf("Game of Thrones, requires a subscription to Max")
        )
        assertEquals("Game of Thrones", candidates.first())
    }

    @Test
    fun removesProviderNamedSubscriptionRequirement() {
        val candidates = TitleNormalizer.candidates(
            listOf("Obsession, requires Peacock subscription")
        )
        assertEquals("Obsession", candidates.first())
    }

    @Test
    fun createsRightToLeftCommaFallbacks() {
        val candidates = TitleNormalizer.candidates(
            listOf("Once Upon a Time in America, Netflix")
        )
        assertTrue(candidates.contains("Once Upon a Time in America"))
    }

    @Test
    fun normalizationIgnoresAccentsAndPunctuation() {
        assertEquals("monstruos s a", TitleNormalizer.normalized("Monstruos, S.A."))
        assertEquals("pokemon", TitleNormalizer.normalized("Pokémon"))
    }

    @Test
    fun exactTitlesScoreOneHundred() {
        assertEquals(100.0, TitleNormalizer.similarity("The Bear", "The Bear"), 0.0)
    }

    @Test
    fun rejectsSingleTextAppTile() {
        assertTrue(
            !TitleNormalizer.looksLikeRecommendation(
                className = "android.view.ViewGroup",
                directDescription = "Netflix",
                directText = listOf("Netflix")
            )
        )
    }

    @Test
    fun acceptsMultiTextHeroCard() {
        assertTrue(
            TitleNormalizer.looksLikeRecommendation(
                className = "android.view.ViewGroup",
                directDescription = null,
                directText = listOf("The Bear", "A young chef returns home to Chicago", "Watch now")
            )
        )
    }

    @Test
    fun acceptsSingleTitleRecommendationCard() {
        assertTrue(
            TitleNormalizer.looksLikeRecommendation(
                className = "android.view.View",
                directDescription = "Dune: Part Two",
                directText = emptyList()
            )
        )
    }

    @Test
    fun rejectsInstalledApplicationTile() {
        assertTrue(
            !TitleNormalizer.looksLikeRecommendation(
                className = "android.view.View",
                directDescription = "YouTube",
                directText = emptyList(),
                excludedLabels = setOf("youtube")
            )
        )
    }

    @Test
    fun rejectsGenreTile() {
        assertTrue(
            !TitleNormalizer.looksLikeRecommendation(
                className = "android.view.View",
                directDescription = "Comedy",
                directText = emptyList()
            )
        )
    }

    @Test
    fun rejectsLauncherNavigationControls() {
        listOf("Settings", "Apps", "Search", "Home").forEach { label ->
            assertTrue(
                !TitleNormalizer.looksLikeRecommendation(
                    className = "android.view.View",
                    directDescription = label,
                    directText = emptyList()
                )
            )
        }
    }

    @Test
    fun recognizesCompoundGoogleTvSettingsControls() {
        listOf(
            "Settings, notifications",
            "Quick Settings, There are new notifications, click to view, There are new notifications",
            "Open Settings",
            "Device settings",
            "Configuración, botón",
            "Accessibility",
            "Network and Internet"
        ).forEach { label ->
            assertTrue(TitleNormalizer.isLauncherControl(listOf(label)))
        }
    }

    @Test
    fun removesDisneyProviderBeforeFreshRating() {
        val candidates = TitleNormalizer.candidates(
            listOf("Lady Bird, Disney+, fresh rating: 99% on Rotten Tomatoes")
        )
        assertEquals("Lady Bird", candidates.first())
    }

    @Test
    fun removesHboProviderBeforeFreshRating() {
        val candidates = TitleNormalizer.candidates(
            listOf("Lanterns, HBO Max, fresh rating: 90% on Rotten Tomatoes")
        )
        assertEquals("Lanterns", candidates.first())
    }

    @Test
    fun preservesCommaTitleWhenProviderIsPresent() {
        val candidates = TitleNormalizer.candidates(
            listOf("Monsters, Inc., Disney+, fresh rating: 96% on Rotten Tomatoes")
        )
        assertEquals("Monsters, Inc.", candidates.first())
    }

    @Test
    fun removesProviderEvenWithoutRating() {
        assertEquals(
            "Next Level",
            TitleNormalizer.candidates(listOf("Next Level, Tubi TV")).first()
        )
        assertEquals(
            "Rush Hour",
            TitleNormalizer.candidates(listOf("Rush Hour, The Roku Channel")).first()
        )
    }

    @Test
    fun removesPlutoTvBeforeRatingMetadata() {
        assertEquals(
            "Marshals",
            TitleNormalizer.candidates(
                listOf("Marshals, Pluto TV, rotten rating: 48% on Rotten Tomatoes")
            ).first()
        )
    }

    @Test
    fun removesBidirectionalUnicodeFormattingFromTitles() {
        val contaminated = "Jumanji: Welcome to the Jungl\u202Ae\u202C"
        assertEquals(
            "Jumanji: Welcome to the Jungle",
            TitleNormalizer.candidates(listOf(contaminated)).first()
        )
        assertEquals("Jumanji: Welcome to the Jungle", TitleNormalizer.withoutYear(contaminated))
    }

    @Test
    fun rejectsObfuscatedFreePickPlaceholders() {
        assertTrue(TitleNormalizer.candidates(listOf("Column 1", "16 min left")).isEmpty())
    }

    @Test
    fun removesYearBeforeComparingTmdbTitles() {
        assertEquals("Mr. Peabody & Sherman", TitleNormalizer.withoutYear("Mr. Peabody & Sherman 2014"))
    }

    @Test
    fun rejectsProviderPlaybackButtonsAsTitles() {
        assertTrue(TitleNormalizer.candidates(listOf("Netflix, Watch now")).isEmpty())
        assertTrue(TitleNormalizer.candidates(listOf("The Roku Channel, Watch now")).isEmpty())
    }

    @Test
    fun treatsTrailingRomanNumeralsAsSequelNumbers() {
        assertEquals("frozen 2", TitleNormalizer.normalized("Frozen II"))
        assertEquals(100.0, TitleNormalizer.similarity("Frozen 2", "Frozen II"), 0.0)
        assertEquals(100.0, TitleNormalizer.similarity("Rocky 4", "Rocky IV"), 0.0)
    }

    @Test
    fun rejectsGoogleTvDetailControlsAndSubscriptionButtons() {
        listOf(
            "What it's about",
            "What people are saying",
            "Prime Video, Subscribe",
            "Screensaver"
        ).forEach { value ->
            assertTrue(TitleNormalizer.candidates(listOf(value)).isEmpty())
        }
        assertTrue(TitleNormalizer.isProviderPlaybackAction("Prime Video, Subscribe"))
    }

    @Test
    fun rejectsYouTubeVideoDescriptionsAndShoppingPromotions() {
        listOf(
            "Buried Official Trailer (2010) - Ryan Reynolds Movie HD, released 13 years ago in channel Rotten Tomatoes Trailers, with 2.8M views",
            "The True King: Legendary MonsterVerse Film [4K HDR], released 10 months ago in channel Action Movie T3, with 133K views",
            "New Season, New Looks. Try on must-have pieces from our fall collection. Shop the collection",
            "INTENTANDO armar un Motor con piezas CHINAS, Duration is 45 minutes 19 seconds",
            "La Hummer más exagerada que ha construido Martín | Mexicánicos, YouTube • Mexicánicos",
            "Shop on-trend jeans, romantic tops and cardigans, plus jackets for your fall rebrand"
        ).forEach { value ->
            assertTrue(TitleNormalizer.candidates(listOf(value)).isEmpty())
        }
    }

    @Test
    fun rejectsSynopsisPricesGenresAndAdditionalSubscriptionButtons() {
        listOf(
            "Peter Quill must rally his team to defend the universe and protect one of their own… More",
            "Buy $19.99 $29.99",
            "Crime",
            "Musicals",
            "Reality TV",
            "Movies and shows across services",
            "TOP MATCH, Animated",
            "BritBox, Subscribe",
            "AMC+, Subscribe"
        ).forEach { value ->
            assertTrue("Expected to reject: $value", TitleNormalizer.candidates(listOf(value)).isEmpty())
        }
    }

    @Test
    fun resolvesNewVerifiedProblemTitles() {
        assertEquals(
            4407,
            VerifiedRecommendationMatches.find(
                listOf("The Three Investigators and the Secret of Skeleton Island"),
                listOf("The Three Investigators and the Secret of Skeleton Island, Tubi TV")
            )?.tmdbId
        )
        assertEquals(
            MediaType.MOVIE,
            VerifiedRecommendationMatches.find(
                listOf("Star Wars: The Mandalorian and Grogu"),
                emptyList()
            )?.type
        )
        assertEquals(
            1894,
            VerifiedRecommendationMatches.find(
                listOf("Star Wars: Attack of the Clones"),
                emptyList()
            )?.tmdbId
        )
        assertEquals(
            87773,
            VerifiedRecommendationMatches.find(
                listOf("The Bay"),
                listOf("The Bay, requires BritBox subscription")
            )?.tmdbId
        )
    }

    @Test
    fun doesNotForceTheBayWithoutBritboxSeriesContext() {
        assertEquals(
            null,
            VerifiedRecommendationMatches.find(listOf("The Bay"), emptyList())
        )
    }

    @Test
    fun keepsExactShortTitleMatchesAmbiguousUntilTheUserChooses() {
        val options = MediaResolver.selectOptions(
            "Alone",
            listOf(
                MediaMatch(1, MediaType.SERIES, "Alone", 103.0, 2015),
                MediaMatch(2, MediaType.MOVIE, "Alone", 101.0, 2020),
                MediaMatch(3, MediaType.SERIES, "Alone Together", 96.0, 2018)
            )
        )
        assertEquals(listOf(1, 2), options.map(MediaMatch::tmdbId))
    }

    @Test
    fun yearReducesAnExactShortTitleToTheCorrectOption() {
        val options = MediaResolver.selectOptions(
            "Alone (2020)",
            listOf(
                MediaMatch(1, MediaType.SERIES, "Alone", 95.0, 2015),
                MediaMatch(2, MediaType.MOVIE, "Alone", 108.0, 2020)
            )
        )
        assertEquals(listOf(2), options.map(MediaMatch::tmdbId))
    }

    @Test
    fun identifiesEnglishAndSpanishSponsoredContainers() {
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(
                listOf("Sponsored", "Meet AutoCurl", "Learn more", "Info")
            )
        )
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(
                listOf("Patrocinado", "Oferta especial", "Más información")
            )
        )
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(
                listOf("Sponsored", "Hugh Jackman Thinks AG1 Is a No Brainer", "Scan QR code", "Info")
            )
        )
    }

    @Test
    fun treatsAStandaloneSponsoredBadgeAsAnAdContainer() {
        // Demanding a badge *and* a call to action matched almost no real ad
        // row, so a bare badge is now enough. Nothing Google TV recommends is
        // actually named "Sponsored".
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(
                listOf("Sponsored", "2025", "Watch now")
            )
        )
    }

    @Test
    fun keepsGenreAndProviderWordsThatAreAlsoRealTitles() {
        // "Max" (2015), "Drama" and "Family" are genuine titles. They used to be
        // discarded as genre or provider tiles, so those cards never opened.
        assertTrue(
            TitleNormalizer.candidates(listOf("Max, 2015")).contains("Max")
        )
        assertTrue(
            TitleNormalizer.candidates(listOf("Max, requires a subscription to Netflix"))
                .contains("Max")
        )
        assertTrue(
            TitleNormalizer.candidates(listOf("Drama, 2019")).contains("Drama")
        )
    }

    @Test
    fun stillRejectsBareGenreAndProviderTiles() {
        listOf("Max", "Drama", "Family", "Reality TV", "Netflix", "Prime Video")
            .forEach { value ->
                assertTrue(
                    "Expected to reject: $value",
                    TitleNormalizer.candidates(listOf(value)).isEmpty()
                )
            }
    }

    @Test
    fun launcherChromeStaysRejectedEvenWithCardMetadata() {
        listOf("Watchlist, 2024", "Cast & crew, 2024", "Ways to watch, 2024")
            .forEach { value ->
                assertTrue(
                    "Expected to reject: $value",
                    TitleNormalizer.candidates(listOf(value)).none {
                        TitleNormalizer.normalized(it) in setOf(
                            "watchlist", "cast crew", "ways to watch"
                        )
                    }
                )
            }
    }
}
