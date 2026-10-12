package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.GuildOutcome;
import de.raindancer.modules.guilds.model.GuildPermission;
import de.raindancer.modules.guilds.model.Rank;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Who may do what to whom inside a guild.
 *
 * <p>Two rules carry all of it. A permission says <em>what</em> a rank may do; seniority says <em>to whom</em>:
 * nobody acts on somebody of their own rank or above, and nobody hands out a rank or a permission they do not
 * hold themselves. Without the second, an officer allowed to promote could promote a friend to officer, who
 * could promote another, and the leader's say over who leads would be gone in three clicks.
 */
public final class RankRule implements IGuildsRule {

    @Override
    public String describe() {
        return "a member acts only on members of a lower rank, with a permission their rank holds, and never "
                + "hands out a rank or permission they do not hold themselves";
    }

    /** Whether {@code actor} holds {@code permission} at all. */
    public GuildOutcome may(Guild guild, UUID actor, GuildPermission permission) {
        if (!guild.isMember(actor)) {
            return GuildOutcome.NOT_A_MEMBER;
        }
        return guild.may(actor, permission) ? GuildOutcome.SUCCESS : GuildOutcome.NOT_ALLOWED;
    }

    /** Whether {@code actor} may use {@code permission} on {@code target} — the target must rank below them. */
    public GuildOutcome mayActOn(Guild guild, UUID actor, UUID target, GuildPermission permission) {
        GuildOutcome held = may(guild, actor, permission);
        if (!held.isSuccess()) {
            return held;
        }
        if (actor.equals(target)) {
            return GuildOutcome.YOURSELF;
        }
        if (!guild.isMember(target)) {
            return GuildOutcome.NOT_A_MEMBER;
        }
        return outranks(guild, actor, target) ? GuildOutcome.SUCCESS : GuildOutcome.OUTRANKED;
    }

    public boolean outranks(Guild guild, UUID actor, UUID target) {
        int mine = guild.rankOf(actor).map(Rank::weight).orElse(0);
        int theirs = guild.rankOf(target).map(Rank::weight).orElse(0);
        return mine > theirs;
    }

    /** Whether {@code actor} may put {@code target} in {@code rank}. The leader's rank is only ever handed over. */
    public GuildOutcome mayAssign(Guild guild, UUID actor, UUID target, Rank rank) {
        GuildOutcome onTarget = mayActOn(guild, actor, target, GuildPermission.PROMOTE);
        if (!onTarget.isSuccess()) {
            return onTarget;
        }
        if (rank.isLeader()) {
            return GuildOutcome.LEADER_RANK;
        }
        int mine = guild.rankOf(actor).map(Rank::weight).orElse(0);
        return rank.weight() < mine ? GuildOutcome.SUCCESS : GuildOutcome.OUTRANKED;
    }

    /** Whether {@code actor} may change {@code rank} — its name, permissions, limit — or delete it. */
    public GuildOutcome mayEdit(Guild guild, UUID actor, Rank rank) {
        GuildOutcome held = may(guild, actor, GuildPermission.MANAGE_RANKS);
        if (!held.isSuccess()) {
            return held;
        }
        if (rank.isLeader()) {
            return GuildOutcome.LEADER_RANK;
        }
        int mine = guild.rankOf(actor).map(Rank::weight).orElse(0);
        return rank.weight() < mine ? GuildOutcome.SUCCESS : GuildOutcome.OUTRANKED;
    }

    /** Whether {@code actor} may give or take {@code permission} on a rank: only one they hold. */
    public GuildOutcome mayGrant(Guild guild, UUID actor, Rank rank, GuildPermission permission) {
        GuildOutcome editable = mayEdit(guild, actor, rank);
        if (!editable.isSuccess()) {
            return editable;
        }
        return guild.may(actor, permission) ? GuildOutcome.SUCCESS : GuildOutcome.PERMISSION_NOT_HELD;
    }

    /** The rank one step above ({@code up}) or below the member's, never the leader's; empty at either end. */
    public Optional<Rank> step(Guild guild, UUID member, boolean up) {
        Optional<Rank> current = guild.rankOf(member);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        List<Rank> ladder = guild.ranks().stream().filter(rank -> !rank.isLeader()).toList();
        int at = ladder.indexOf(current.get());
        if (at < 0) {
            return Optional.empty();
        }
        // The ladder is sorted most senior first, so "up" is towards the front.
        int next = up ? at - 1 : at + 1;
        return next < 0 || next >= ladder.size() ? Optional.empty() : Optional.of(ladder.get(next));
    }
}
