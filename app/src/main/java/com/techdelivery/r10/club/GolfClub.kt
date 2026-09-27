package com.techdelivery.r10.club

/**
 * The canonical golf club list (DESIGN §9, ROADMAP R5).
 *
 * Defined once, here, because two surfaces read it: the Settings "Clubs I own"
 * editor and the Shots-tab club picker. A second hardcoded copy is how the two
 * drift and the user ends up tagging a shot with a club the bag does not contain.
 *
 * Order is the bag order a golfer reads top-down (driver → putter) and both
 * surfaces render in this order. The **name** (`id`) is what is persisted, in the
 * CSV `club_label` column and in the `ownedClubs` setting, so the stable string
 * matters more than the enum constant: renaming an entry would orphan the labels
 * already on disk.
 *
 * This is an annotation, not device data. The R10 reports club *metrics* (speed,
 * face/path/attack angle) and never the club identity — see `ClubDisplay`. If a
 * club-type field ever turns up in the proto, it seeds the default selection and
 * does not become the source of truth.
 */
enum class GolfClub(val id: String) {
    DRIVER("Driver"),
    THREE_WOOD("3 Wood"),
    FIVE_WOOD("5 Wood"),
    FOUR_IRON("4 Iron"),
    FIVE_IRON("5 Iron"),
    SIX_IRON("6 Iron"),
    SEVEN_IRON("7 Iron"),
    EIGHT_IRON("8 Iron"),
    NINE_IRON("9 Iron"),
    PITCHING_WEDGE("Pitching Wedge"),
    GAP_WEDGE("Gap Wedge"),
    SAND_WEDGE("Sand Wedge"),
    LOB_WEDGE("Lob Wedge"),
    PUTTER("Putter"),
    ;

    companion object {
        /** Every club, in bag order. The Settings grid renders this list. */
        val ALL: List<GolfClub> = entries

        /** Default owned set: everything, so a fresh install has a usable picker. */
        val ALL_IDS: Set<String> = entries.mapTo(linkedSetOf()) { it.id }

        /** Longest persisted label — the CSV column and the setting are bounded by it. */
        const val ID_MAX_LEN = 20

        private val byId = entries.associateBy { it.id.lowercase() }

        /**
         * Resolve a persisted label to a known club, or null when it is not one.
         * Case-insensitive, and never throws: stored data may be hand-edited or
         * predate a rename, and an unknown label must not lose the shot.
         */
        fun fromId(id: String?): GolfClub? = id?.trim()?.takeIf { it.isNotEmpty() }?.let { byId[it.lowercase()] }
    }
}
