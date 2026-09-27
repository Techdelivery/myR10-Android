package com.techdelivery.r10.club

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canonical club list (ROADMAP R5, widened after the first on-device pass).
 *
 * Two things matter here and neither is cosmetic:
 *  - **coverage** — the list missed clubs that are in a normal bag, which showed up
 *    the moment the feature was used for real;
 *  - **back-compat** — the first build persisted long labels, and renaming a value
 *    that already exists in users' CSVs is how you lose their history.
 */
class GolfClubTest {

    @Test
    fun everyBagClubTheFirstListMissedIsPresent() {
        val ids = GolfClub.ALL_IDS
        listOf("2I", "3I", "4W", "6W", "7W", "9W").forEach {
            assertTrue("$it missing from the list", it in ids)
        }
    }

    @Test
    fun hybridsAndHalfIronsArePresent() {
        val ids = GolfClub.ALL_IDS
        listOf("2H", "3H", "4H", "5H", "9.5I", "8.5I", "7.5I", "6.5I").forEach {
            assertTrue("$it missing from the list", it in ids)
        }
    }

    /** A hybrid and an iron must never share a label, or the CSV cannot tell them apart. */
    @Test
    fun labelsAreUnique() {
        assertEquals(GolfClub.ALL.size, GolfClub.ALL_IDS.size)
    }

    @Test
    fun theListIsInBagOrder() {
        val ids = GolfClub.ALL.map { it.id }
        assertEquals("D", ids.first())
        assertEquals("P", ids.last())
        assertTrue("woods before irons", ids.indexOf("3W") < ids.indexOf("4I"))
        assertTrue("irons before wedges", ids.indexOf("9I") < ids.indexOf("PW"))
        assertTrue("wedges before putter", ids.indexOf("LW") < ids.lastIndex)
    }

    @Test
    fun anAbbreviationResolvesToItsClub() {
        assertEquals(GolfClub.IRON_SEVEN, GolfClub.fromId("7I"))
        assertEquals(GolfClub.SAND_WEDGE, GolfClub.fromId("SW"))
        assertEquals(GolfClub.PUTTER, GolfClub.fromId("P"))
    }

    @Test
    fun lookupIsCaseInsensitiveAndTrims() {
        assertEquals(GolfClub.DRIVER, GolfClub.fromId(" d "))
        assertEquals(GolfClub.GAP_WEDGE, GolfClub.fromId("gw"))
    }

    @Test
    fun anUnknownOrBlankLabelResolvesToNull() {
        assertNull(GolfClub.fromId(null))
        assertNull(GolfClub.fromId(""))
        assertNull(GolfClub.fromId("   "))
        assertNull(GolfClub.fromId("Sand Wedge Deluxe"))
    }

    // --- the labels the first build wrote ---

    @Test
    fun legacyLongNamesStillResolve() {
        assertEquals(GolfClub.IRON_SEVEN, GolfClub.fromId("7 Iron"))
        assertEquals(GolfClub.SAND_WEDGE, GolfClub.fromId("Sand Wedge"))
        assertEquals(GolfClub.PITCHING_WEDGE, GolfClub.fromId("Pitching Wedge"))
        assertEquals(GolfClub.PUTTER, GolfClub.fromId("Putter"))
    }

    @Test
    fun aLegacyNameIsNotMistakenForACurrentId() {
        assertFalse(GolfClub.isCurrentId("7 Iron"))
        assertTrue(GolfClub.isCurrentId("7I"))
    }

    @Test
    fun everyClubHasALegacyAlias() {
        // A rename with no alias is a silent loss, so assert none slipped through.
        GolfClub.ALL.forEach { club ->
            assertTrue("${club.id} has no legacy alias", GolfClub.fromId(legacyFor(club)) == club)
        }
    }

    private fun legacyFor(club: GolfClub): String = when (club) {
        GolfClub.DRIVER -> "Driver"
        GolfClub.PUTTER -> "Putter"
        GolfClub.IRON_SEVEN -> "7 Iron"
        GolfClub.GAP_WEDGE -> "Gap Wedge"
        GolfClub.SAND_WEDGE -> "Sand Wedge"
        GolfClub.LOB_WEDGE -> "Lob Wedge"
        GolfClub.PITCHING_WEDGE -> "Pitching Wedge"
        GolfClub.IRON_NINE_HALF -> "9.5 Iron"
        else -> club.id
    }
}
