package com.techdelivery.r10.club

/**
 * The canonical golf club list (DESIGN §9, ROADMAP R5).
 *
 * Defined once, here, because two surfaces read it: the Settings "Clubs I own"
 * editor and the Shots-tab club picker. A second hardcoded copy is how the two
 * drift and the user ends up tagging a shot with a club the bag does not contain.
 *
 * **Coverage is the full bag, not the tour staples.** The first on-device pass
 * (2026-09-27) showed a list missing 2I, 3I, 4W, 6W, 7W and 9W — clubs that are in
 * most bags. So: the whole fairway-wood ladder, the short irons, half irons
 * (9.5I…6.5I, the low-lofted approach clubs between the numbered irons), hybrids
 * (2H…5H, the rescue clubs), the wedges, and the putter.
 *
 * **Labels are the golf abbreviation** — `D`, `3W`, `4I`, `GW`, `P`. The
 * abbreviation is the persisted `club_label` in the CSV, and it is what the picker
 * and the list row show. It is not only about width: a bag is read in this
 * notation, and "9.5I" is how the club is labelled on it.
 *
 * The letters were chosen not to collide across categories — irons take `I`,
 * hybrids `H`, woods `W`, wedges spelled out — because a hybrid between a 3-iron
 * and a 4-iron is `3H`, and reusing `3I` for it would make two different clubs
 * indistinguishable in the CSV forever.
 *
 * Order is bag order (driver → putter); both surfaces render in this order.
 *
 * This is an annotation, not device data. The R10 reports club *metrics* (speed,
 * face/path/attack angle) and never the club identity — see `ClubDisplay`. If a
 * club-type field ever turns up in the proto, it seeds the default selection and
 * does not become the source of truth.
 */
enum class GolfClub(val id: String) {
    DRIVER("D"),
    THREE_WOOD("3W"),
    FOUR_WOOD("4W"),
    FIVE_WOOD("5W"),
    SIX_WOOD("6W"),
    SEVEN_WOOD("7W"),
    NINE_WOOD("9W"),
    HYBRID_TWO("2H"),
    HYBRID_THREE("3H"),
    HYBRID_FOUR("4H"),
    HYBRID_FIVE("5H"),
    IRON_TWO("2I"),
    IRON_THREE("3I"),
    IRON_FOUR("4I"),
    IRON_FIVE("5I"),
    IRON_SIX("6I"),
    IRON_SEVEN("7I"),
    IRON_EIGHT("8I"),
    IRON_NINE("9I"),
    IRON_NINE_HALF("9.5I"),
    IRON_EIGHT_HALF("8.5I"),
    IRON_SEVEN_HALF("7.5I"),
    IRON_SIX_HALF("6.5I"),
    PITCHING_WEDGE("PW"),
    GAP_WEDGE("GW"),
    SAND_WEDGE("SW"),
    LOB_WEDGE("LW"),
    PUTTER("P"),
    ;

    companion object {
        /** Every club, in bag order. The Settings grid renders this list. */
        val ALL: List<GolfClub> = entries

        /** Default owned set: everything, so a fresh install has a usable picker. */
        val ALL_IDS: Set<String> = entries.mapTo(linkedSetOf()) { it.id }

        /** Longest persisted label — the CSV column and the setting are bounded by it. */
        const val ID_MAX_LEN = 20

        /**
         * Abbreviations only, before the legacy aliases are layered on. Built first
         * because the legacy table is written in long names and resolved through it.
         */
        private val currentById: Map<String, GolfClub> =
            entries.associateByTo(LinkedHashMap()) { it.id.lowercase() }

        /**
         * Labels written by the first build of this feature, before the list moved to
         * abbreviations. They must keep resolving forever: a `club_label` or an
         * `ownedClubs` entry from last week is data this app wrote, and the rule is
         * the same one the v1 CSV obeys — an older file never becomes invalid because
         * a later build renamed something.
         *
         * Each entry is the *long name*, resolved through [currentById], so an alias
         * can never drift away from the club it stands for. Spelled forms only:
         * "Gap Wedge" and "gap wedge" both resolve, "GAPW" would be guessing.
         */
        private val LEGACY_NAMES: Map<String, String> = mapOf(
            "Driver" to "D",
            "3 Wood" to "3W",
            "4 Wood" to "4W",
            "5 Wood" to "5W",
            "6 Wood" to "6W",
            "7 Wood" to "7W",
            "9 Wood" to "9W",
            "2 Hybrid" to "2H",
            "3 Hybrid" to "3H",
            "4 Hybrid" to "4H",
            "5 Hybrid" to "5H",
            "2 Iron" to "2I",
            "3 Iron" to "3I",
            "4 Iron" to "4I",
            "5 Iron" to "5I",
            "6 Iron" to "6I",
            "7 Iron" to "7I",
            "8 Iron" to "8I",
            "9 Iron" to "9I",
            "9.5 Iron" to "9.5I",
            "8.5 Iron" to "8.5I",
            "7.5 Iron" to "7.5I",
            "6.5 Iron" to "6.5I",
            "Pitching Wedge" to "PW",
            "Gap Wedge" to "GW",
            "Sand Wedge" to "SW",
            "Lob Wedge" to "LW",
            "Putter" to "P",
        )

        /** Abbreviations plus aliases, lowercase. */
        private val byId: Map<String, GolfClub> = buildMap {
            putAll(currentById)
            LEGACY_NAMES.forEach { (legacy, abbreviation) ->
                put(legacy.lowercase(), currentById.getValue(abbreviation.lowercase()))
            }
        }

        /**
         * Resolve a stored label to a known club, or null when it is not one.
         *
         * Case-insensitive, and never throws: stored data may be hand-edited or
         * predate a rename, and an unknown label must not lose the shot.
         */
        fun fromId(id: String?): GolfClub? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { byId[it.lowercase()] }

        /** True when [id] is a current abbreviation rather than a legacy long name. */
        fun isCurrentId(id: String?): Boolean {
            val trimmed = id?.trim() ?: return false
            return currentById.containsKey(trimmed.lowercase()) && trimmed in ALL_IDS
        }
    }
}
