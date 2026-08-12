package de.raindancer.modules.moderation.model;

/**
 * One ore block, and how directly the digging immediately before it seems to have gone.
 *
 * @param ore                  where it was, and what it was
 * @param pathLength           how many mined blocks immediately before it were counted as part of the
 *                             same dig — see {@link MiningTrail#MAX_STEP_DISTANCE}
 * @param straightLineDistance how far the ore actually is, in a straight line, from wherever that
 *                             stretch of digging started
 * @param directnessPercent    {@code straightLineDistance / pathLength} as a whole percentage. A
 *                             winding tunnel covers little net distance for a lot of digging and reads
 *                             low; digging that went almost straight at the ore reads close to a
 *                             hundred. Clamped there rather than let past it — mining is not the only
 *                             way to close the last block of distance, and this is not the place to
 *                             guess at why the arithmetic came out slightly ahead of itself.
 */
public record ApproachReading(MinedBlock ore, int pathLength, double straightLineDistance,
                              int directnessPercent) {
}
