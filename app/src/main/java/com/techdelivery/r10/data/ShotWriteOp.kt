package com.techdelivery.r10.data

import com.techdelivery.r10.club.GolfClub
import com.techdelivery.r10.protocol.shot.Shot

/**
 * One unit of work for [ShotWriteQueue].
 *
 * Every change to the shot history is one of these, because the queue is the only
 * thing that writes the file (DESIGN §8). When the store could also be called
 * directly, two writers shared its mutex and that serialised them without ordering
 * them: a delete could be undone by an append still sitting in the queue, and a club
 * pick on a shot not yet on disk matched nothing and was never retried.
 *
 * Ordering is now submission order, and nothing reaches the UI before it is
 * submitted.
 */
sealed interface ShotWriteOp {
    /** A shot arriving from the device. */
    data class Append(val shot: Shot) : ShotWriteOp

    /** Set ([club] non-null) or clear ([club] null) the annotation on one shot. */
    data class SetClub(val shotId: Int, val receivedAtMs: Long, val club: GolfClub?) : ShotWriteOp

    /** Remove one shot. Irreversible in-app. */
    data class DeleteShot(val shotId: Int, val receivedAtMs: Long) : ShotWriteOp
}

/**
 * The three store calls [ShotWriteQueue] makes. Enough surface, no more.
 *
 * Declared here rather than inside the queue so the store can satisfy it without
 * the queue depending on the store: the dependency runs queue -> [ShotWriter], never
 * the other way round.
 */
interface ShotWriter {
    suspend fun append(shot: Shot): Boolean
    suspend fun updateClub(shotId: Int, receivedAtMs: Long, club: GolfClub?): WriteOutcome
    suspend fun deleteShot(shotId: Int, receivedAtMs: Long): WriteOutcome
}
