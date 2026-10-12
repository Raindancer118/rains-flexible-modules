package de.raindancer.modules.guilds.rules;

import de.raindancer.core.world.combat.Verdict;
import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.Relation;

import java.util.Optional;

/**
 * Whether one player may hurt another because of their guilds.
 *
 * <p>Only ever says no, or nothing: a guild cannot make PvP happen where the world forbids it, it can only keep
 * its own members (and, if it wants, its allies) from hurting each other. An alliance counts only when both
 * guilds hold it — it is agreed, so this is always true of a real one, but a half-written relation after a crash
 * must not protect anybody.
 */
public final class FriendlyFireRule implements IGuildsRule {

    @Override
    public String describe() {
        return "members of one guild cannot hurt each other unless their guild allows it, and allies cannot "
                + "unless the attacker's guild allows hurting allies";
    }

    /** @return {@link Verdict#PROTECTED} to refuse, or null for no opinion */
    public Verdict judge(Optional<Guild> attacker, Optional<Guild> victim) {
        if (attacker.isEmpty() || victim.isEmpty()) {
            return null;
        }
        Guild own = attacker.get();
        Guild theirs = victim.get();
        if (own.id().equals(theirs.id())) {
            return own.friendlyFire() ? null : Verdict.PROTECTED;
        }
        boolean allied = own.relationTo(theirs.id()) == Relation.ALLY && theirs.relationTo(own.id()) == Relation.ALLY;
        if (allied && !own.allyFriendlyFire()) {
            return Verdict.PROTECTED;
        }
        return null;
    }
}
