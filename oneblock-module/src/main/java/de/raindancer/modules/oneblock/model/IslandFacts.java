package de.raindancer.modules.oneblock.model;

/**
 * What an invite is judged on, read in one go: the inviter's island and their standing on it.
 *
 * @param mostMembers below zero when the island has no limit
 * @param mode        the money the island plays with, so the invited player knows what they would join
 */
public record IslandFacts(String islandId, int inviterRank, int teamInviteRank, int members, int mostMembers,
                          boolean inviteeMember, boolean inviteeBanned, EconomyMode mode) {
}
