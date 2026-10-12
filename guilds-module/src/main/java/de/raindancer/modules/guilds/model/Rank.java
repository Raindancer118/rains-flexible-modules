package de.raindancer.modules.guilds.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * One step of a guild's ladder.
 *
 * @param id         fixed for the rank's life; what members point at, so a rename keeps everybody in it
 * @param weight     higher is more senior. The leader's rank is {@link #LEADER_WEIGHT} and nothing else may be
 * @param dailyLimit most a member of this rank may take from the bank a day, in minor units; negative for no limit
 */
public record Rank(String id, String name, int weight, Set<GuildPermission> permissions, long dailyLimit) {

    public static final String LEADER = "leader";
    public static final String OFFICER = "officer";
    public static final String MEMBER = "member";
    public static final String RECRUIT = "recruit";
    public static final int LEADER_WEIGHT = 1000;

    public Rank {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        permissions = permissions == null || permissions.isEmpty()
                ? EnumSet.noneOf(GuildPermission.class) : EnumSet.copyOf(permissions);
        if (LEADER.equals(id)) {
            weight = LEADER_WEIGHT;
            permissions = EnumSet.allOf(GuildPermission.class);
            dailyLimit = -1;
        } else {
            weight = Math.max(1, Math.min(LEADER_WEIGHT - 1, weight));
        }
    }

    public boolean isLeader() {
        return LEADER.equals(id);
    }

    public boolean may(GuildPermission permission) {
        return isLeader() || permissions.contains(permission);
    }

    public boolean isUnlimited() {
        return dailyLimit < 0;
    }

    public Rank withName(String newName) {
        return new Rank(id, newName, weight, permissions, dailyLimit);
    }

    public Rank withWeight(int newWeight) {
        return new Rank(id, name, newWeight, permissions, dailyLimit);
    }

    public Rank withPermissions(Set<GuildPermission> newPermissions) {
        return new Rank(id, name, weight, newPermissions, dailyLimit);
    }

    public Rank toggled(GuildPermission permission) {
        Set<GuildPermission> next = permissions.isEmpty() ? EnumSet.noneOf(GuildPermission.class) : EnumSet.copyOf(permissions);
        if (!next.remove(permission)) {
            next.add(permission);
        }
        return withPermissions(next);
    }

    public Rank withDailyLimit(long minor) {
        return new Rank(id, name, weight, permissions, minor);
    }

    /** A rank id made from a name: lower case, letters, digits and dashes. */
    public static String idFrom(String name) {
        String id = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return id.isEmpty() ? "rank" : id;
    }

    /** The ladder every guild starts with. */
    public static List<Rank> defaults(long officerLimit, long memberLimit) {
        return List.of(
                new Rank(LEADER, "Leader", LEADER_WEIGHT, GuildPermission.all(), -1),
                new Rank(OFFICER, "Officer", 300, EnumSet.of(GuildPermission.INVITE, GuildPermission.KICK,
                        GuildPermission.PROMOTE, GuildPermission.WITHDRAW, GuildPermission.SET_HOME,
                        GuildPermission.USE_HOME, GuildPermission.EDIT_INFO, GuildPermission.RELATIONS,
                        GuildPermission.OFFICER_CHAT, GuildPermission.VIEW_LOG, GuildPermission.VAULT), officerLimit),
                new Rank(MEMBER, "Member", 200, EnumSet.of(GuildPermission.USE_HOME, GuildPermission.VIEW_LOG,
                        GuildPermission.WITHDRAW, GuildPermission.VAULT), memberLimit),
                new Rank(RECRUIT, "Recruit", 100, EnumSet.of(GuildPermission.USE_HOME), 0));
    }
}
