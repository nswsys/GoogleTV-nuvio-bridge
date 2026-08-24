package com.nswsys.nuviobridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SponsoredSectionDetectorTest {
    @Test
    fun badgeAloneIsEnough() {
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(listOf("Sponsored", "Wicked: For Good"))
        )
    }

    @Test
    fun spanishBadgeAloneIsEnough() {
        assertTrue(SponsoredSectionDetector.isSponsoredContainer(listOf("Patrocinado")))
    }

    @Test
    fun badgeInsideACompoundContentDescriptionIsDetected() {
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(
                listOf("Sponsored, Wicked: For Good, In theaters now, Learn more")
            )
        )
    }

    @Test
    fun separatorFormattedBadgeIsDetected() {
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(listOf("Sponsored · Universal Pictures"))
        )
    }

    @Test
    fun adDisclosureIsDetected() {
        assertTrue(SponsoredSectionDetector.isSponsoredContainer(listOf("Why this ad?")))
        assertTrue(SponsoredSectionDetector.isSponsoredContainer(listOf("¿Por qué este anuncio?")))
    }

    @Test
    fun adViewIdIsEnough() {
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(
                labels = listOf("Wicked: For Good"),
                resourceIds = listOf("com.google.android.apps.tv.launcherx:id/sponsored_row")
            )
        )
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(
                labels = listOf("Wicked: For Good"),
                resourceIds = listOf("com.google.android.apps.tv.launcherx:id/ad_badge")
            )
        )
    }

    @Test
    fun ordinaryViewIdsAreNotAdMarkers() {
        assertFalse(
            SponsoredSectionDetector.isSponsoredContainer(
                labels = listOf("The Bear, 2022"),
                resourceIds = listOf(
                    "com.google.android.apps.tv.launcherx:id/header",
                    "com.google.android.apps.tv.launcherx:id/card_shadow",
                    "com.google.android.apps.tv.launcherx:id/add_to_watchlist",
                    "com.google.android.apps.tv.launcherx:id/gradient_overlay"
                )
            )
        )
    }

    @Test
    fun aGenericCallToActionIsNotEnoughOnItsOwn() {
        assertFalse(
            SponsoredSectionDetector.isSponsoredContainer(listOf("The Bear, 2022", "Learn more"))
        )
    }

    @Test
    fun twoIndependentAdActionsAreEnough() {
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(listOf("Ver más", "Info"))
        )
    }

    @Test
    fun badgePlusActionStillMatchesTheOriginalBehaviour() {
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(listOf("Sponsored", "Learn more"))
        )
        assertTrue(
            SponsoredSectionDetector.isSponsoredContainer(listOf("Patrocinado", "Escanear código QR"))
        )
    }

    @Test
    fun ordinaryRecommendationsAreNotSponsored() {
        assertFalse(
            SponsoredSectionDetector.isSponsoredContainer(
                listOf("Top picks for you", "Monsters, Inc., 2001", "Dune: Part Two, 2024")
            )
        )
    }

    @Test
    fun aTitleThatMerelyContainsTheWordIsNotABadge() {
        assertFalse(SponsoredSectionDetector.isSponsoredContainer(listOf("Ad Astra, 2019")))
        assertFalse(SponsoredSectionDetector.isSponsoredContainer(listOf("Los anunciantes, 2019")))
    }

    @Test
    fun prosePassingByTheWordIsNotABadge() {
        assertFalse(
            SponsoredSectionDetector.isSponsoredContainer(
                listOf(
                    "A washed-up creative director returns to the agency he walked out on and " +
                        "discovers that the campaign he abandoned is now the most awarded piece of " +
                        "publicidad of the decade, which forces him to confront his own past"
                )
            )
        )
    }

    @Test
    fun theVerdictExplainsItself() {
        val verdict = SponsoredSectionDetector.evaluate(
            labels = listOf("Sponsored", "Learn more"),
            resourceIds = listOf("com.google.android.apps.tv.launcherx:id/ad_container")
        )
        assertTrue(verdict.isSponsored)
        assertTrue(verdict.toString().contains("badge=\"sponsored\""))
        assertTrue(verdict.toString().contains("viewId=\"ad_container\""))
        assertTrue(verdict.toString().contains("action=\"learn more\""))
    }
}
