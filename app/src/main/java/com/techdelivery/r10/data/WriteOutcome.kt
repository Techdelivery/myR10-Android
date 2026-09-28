package com.techdelivery.r10.data

/**
 * What a user-initiated write to the shot history actually did.
 *
 * The store's rewrite operations used to answer with a `Boolean` that was true for
 * "wrote it" and false for both "that shot is not there" and "the file is damaged".
 * The UI could not tell those apart, so it re-read and re-validated the whole file
 * after every refused edit just to decide whether to apologise. The writer is holding
 * the store when it finds out, so it reports the difference instead.
 */
enum class WriteOutcome {
    /** The file was changed. */
    WRITTEN,

    /** No such shot. A no-op, not a fault, so nothing is put on screen about it. */
    NOT_FOUND,

    /**
     * The store refused the rewrite because the file holds damage one would paper
     * over. The UI says so and points at the export.
     */
    DAMAGED,

    /** The queue will not run this at all. */
    REJECTED,
}
