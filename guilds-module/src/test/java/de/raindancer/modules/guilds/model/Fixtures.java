package de.raindancer.modules.guilds.model;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Guilds for tests, built the long way so a test reads what it sets. */
public final class Fixtures {

    public static final UUID LEADER = player("leader");
    public static final UUID OFFICER = player("officer");
    public static final UUID MEMBER = player("member");
    public static final UUID RECRUIT = player("recruit");
    public static final UUID STRANGER = player("stranger");

    private Fixtures() {
    }

    public static UUID player(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }

    /** A guild with one member of each default rank, joined in rank order. */
    public static Guild guild(String name) {
        Map<UUID, Member> members = new LinkedHashMap<>();
        members.put(LEADER, Member.joining(LEADER, Rank.LEADER, 1));
        members.put(OFFICER, Member.joining(OFFICER, Rank.OFFICER, 2));
        members.put(MEMBER, Member.joining(MEMBER, Rank.MEMBER, 3));
        members.put(RECRUIT, Member.joining(RECRUIT, Rank.RECRUIT, 4));
        return new Guild(TeamId.fromName(name), name, name.substring(0, Math.min(4, name.length())).toUpperCase(),
                TeamColour.values()[0], TeamEmblem.NONE, "", 0, LEADER, JoinMode.INVITE, 0, 0, Optional.empty(),
                false, false, members, Rank.defaults(1000, 100), Map.of());
    }

    /** A guild led by {@code leader} alone. */
    public static Guild solo(String name, UUID leader) {
        return new Guild(TeamId.fromName(name), name, "T" + Math.abs(name.hashCode() % 1000), TeamColour.values()[1],
                TeamEmblem.NONE, "", 0, leader, JoinMode.INVITE, 0, 0, Optional.empty(), false, false,
                Map.of(leader, Member.joining(leader, Rank.LEADER, 0)), Rank.defaults(1000, 100), Map.of());
    }
}
