package de.raindancer.modules.oneblock.model;

import java.util.Optional;

/**
 * One player's island as the sidebar shows it: the one they stand on, or their own while they are elsewhere.
 *
 * @param standingOn  whether they are on it now
 * @param yours       whether they are on its team
 * @param owner       the owner's name; null for an island nobody owns
 * @param rank        their BentoBox rank on it
 * @param mostMembers below zero when there is no limit
 */
public record IslandView(boolean standingOn, boolean yours, String owner, int rank, int members, int mostMembers,
                         int online, Optional<Progress> progress) {
}
