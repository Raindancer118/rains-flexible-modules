package de.raindancer.modules.oneblock.model;

import java.util.Optional;

/**
 * One player's island as the sidebar shows it: the one they stand on, or their own while they are elsewhere.
 *
 * @param standingOn  whether they are on it now
 * @param yours       whether they are on its team
 * @param owner       the owner's name
 * @param rank        1000 for the owner, 500 for the team, 0 for a visitor
 * @param mostMembers below zero when there is no limit
 * @param dimension   the dimension they stand in; empty while they are not on it
 * @param broken      breaks of the block of that dimension, or of the overworld's while away
 */
public record IslandView(boolean standingOn, boolean yours, String owner, int rank, int members, int mostMembers,
                         int online, EconomyMode mode, long shards, Optional<IslandDimension> dimension,
                         long broken) {
}
