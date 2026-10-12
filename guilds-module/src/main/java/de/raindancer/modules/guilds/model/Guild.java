package de.raindancer.modules.guilds.model;

import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamId;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A guild as it is right now. Immutable; every change is a new one, made in {@code GuildBook} under its lock.
 *
 * <p>Membership, the name and the leader are also held by Core's {@code Teams} roster, which is what refuses a
 * second guild of the same name and a player in two guilds at once. This record is the rest: ranks, bank, level,
 * relations — what only a guild has.
 *
 * @param bank      the guild's money, in minor units
 * @param relations other guilds by id value, and how this one stands to them. Neutral is never stored
 */
public record Guild(
        TeamId id,
        String name,
        String tag,
        TeamColour colour,
        TeamEmblem emblem,
        String motd,
        long founded,
        UUID leader,
        JoinMode joinMode,
        long xp,
        long bank,
        Optional<HomeSpot> home,
        boolean friendlyFire,
        boolean allyFriendlyFire,
        Map<UUID, Member> members,
        List<Rank> ranks,
        Map<String, Relation> relations,
        NameStyle tagStyle,
        NameStyle nameStyle) {

    public Guild {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(colour, "colour");
        emblem = emblem == null ? TeamEmblem.NONE : emblem;
        motd = motd == null ? "" : motd;
        Objects.requireNonNull(leader, "leader");
        joinMode = joinMode == null ? JoinMode.INVITE : joinMode;
        home = home == null ? Optional.empty() : home;
        members = Map.copyOf(members);
        ranks = ranks.stream().sorted(Comparator.comparingInt(Rank::weight).reversed()).toList();
        relations = Map.copyOf(relations);
        tagStyle = tagStyle == null ? NameStyle.NONE : tagStyle;
        nameStyle = nameStyle == null ? NameStyle.NONE : nameStyle;
        if (!members.containsKey(leader)) {
            throw new IllegalArgumentException("the leader of " + name + " is not a member");
        }
        if (ranks.stream().noneMatch(Rank::isLeader)) {
            throw new IllegalArgumentException(name + " has no leader rank");
        }
    }

    /** Without styles — how every guild started, and what older callers still build. */
    public Guild(TeamId id, String name, String tag, TeamColour colour, TeamEmblem emblem, String motd, long founded,
                 UUID leader, JoinMode joinMode, long xp, long bank, Optional<HomeSpot> home, boolean friendlyFire,
                 boolean allyFriendlyFire, Map<UUID, Member> members, List<Rank> ranks, Map<String, Relation> relations) {
        this(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home, friendlyFire,
                allyFriendlyFire, members, ranks, relations, NameStyle.NONE, NameStyle.NONE);
    }

    public Guild withStyles(NameStyle newTagStyle, NameStyle newNameStyle) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, newTagStyle, newNameStyle);
    }

    public boolean isMember(UUID player) {
        return members.containsKey(player);
    }

    public Optional<Member> member(UUID player) {
        return Optional.ofNullable(members.get(player));
    }

    public Optional<Rank> rank(String rankId) {
        return ranks.stream().filter(rank -> rank.id().equals(rankId)).findFirst();
    }

    /** The rank a member holds; a member pointing at a rank that is gone counts as the lowest. */
    public Optional<Rank> rankOf(UUID player) {
        Member member = members.get(player);
        if (member == null) {
            return Optional.empty();
        }
        return rank(member.rank()).or(() -> Optional.of(lowestRank()));
    }

    public Rank lowestRank() {
        return ranks.getLast();
    }

    public boolean may(UUID player, GuildPermission permission) {
        return rankOf(player).map(rank -> rank.may(permission)).orElse(false);
    }

    public int size() {
        return members.size();
    }

    public Relation relationTo(TeamId other) {
        return relations.getOrDefault(other.value(), Relation.NEUTRAL);
    }

    public long allies() {
        return relations.values().stream().filter(relation -> relation == Relation.ALLY).count();
    }

    // ------------------------------------------------------------------ one change at a time

    public Guild withName(String newName) {
        return new Guild(id, newName, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withTag(String newTag) {
        return new Guild(id, name, newTag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withLook(TeamColour newColour, TeamEmblem newEmblem) {
        return new Guild(id, name, tag, newColour, newEmblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withMotd(String newMotd) {
        return new Guild(id, name, tag, colour, emblem, newMotd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withLeader(UUID newLeader) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, newLeader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withJoinMode(JoinMode mode) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, mode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withXp(long newXp) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, newXp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withBank(long minor) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, minor, home,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withHome(Optional<HomeSpot> newHome) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, newHome,
                friendlyFire, allyFriendlyFire, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withFriendlyFire(boolean own, boolean allies) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                own, allies, members, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withMember(Member member) {
        Map<UUID, Member> next = new LinkedHashMap<>(members);
        next.put(member.player(), member);
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, next, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withoutMember(UUID player) {
        Map<UUID, Member> next = new LinkedHashMap<>(members);
        next.remove(player);
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, next, ranks, relations, tagStyle, nameStyle);
    }

    public Guild withRanks(List<Rank> newRanks) {
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, newRanks, relations, tagStyle, nameStyle);
    }

    public Guild withRank(Rank changed) {
        List<Rank> next = new java.util.ArrayList<>(ranks.stream().filter(rank -> !rank.id().equals(changed.id())).toList());
        next.add(changed);
        return withRanks(next);
    }

    public Guild withRelation(TeamId other, Relation relation) {
        Map<String, Relation> next = new LinkedHashMap<>(relations);
        if (relation == null || relation == Relation.NEUTRAL) {
            next.remove(other.value());
        } else {
            next.put(other.value(), relation);
        }
        return new Guild(id, name, tag, colour, emblem, motd, founded, leader, joinMode, xp, bank, home,
                friendlyFire, allyFriendlyFire, members, ranks, next, tagStyle, nameStyle);
    }
}
