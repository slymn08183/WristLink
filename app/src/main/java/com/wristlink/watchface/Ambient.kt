package com.wristlink.watchface

import java.time.ZonedDateTime

/**
 * Rules both WristLink faces follow so the AMOLED screen doesn't keep the same pixels lit for
 * hours, which leaves a ghost image behind:
 * - on Always On the time is an outline and everything else small and dim, with no arcs, fills
 *   or icons;
 * - the whole face moves a few pixels every minute through a 3 × 3 grid of positions, with the
 *   screen on too (too little to notice).
 */
object Ambient {
    /** Outline time and the few texts left on screen. */
    const val TEXT = 0xFF8A8A8A.toInt()
    const val DIM = 0xFF5E5E5E.toInt()

    /** How far the face may move each way, in face units (the 450-unit square). */
    private const val SHIFT = 6f

    /** The offset for this minute, in face units. */
    fun offset(now: ZonedDateTime): Pair<Float, Float> {
        val step = now.minute % 9
        return ((step % 3) - 1) * SHIFT to ((step / 3) - 1) * SHIFT
    }
}
