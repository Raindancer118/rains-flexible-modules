package de.raindancer.modules.oneblock.model;

/**
 * What an invite is judged on, read from BentoBox in one go: the inviter's island and their standing on it.
 *
 * @param mostMembers below zero when the island has no limit
 */
public record IslandFacts(String islandId, int inviterRank, int teamInviteRank, int members, int mostMembers,
                          boolean inviteeMember, boolean inviteeBanned) {
}
