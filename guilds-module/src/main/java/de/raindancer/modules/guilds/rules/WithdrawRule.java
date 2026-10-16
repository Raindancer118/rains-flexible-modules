package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;

/**
 * How much a member may take out of the bank today: what their daily limit leaves, and what is there. Their own
 * limit, when they have one, replaces the rank's — higher or lower; the leader has none.
 */
public final class WithdrawRule implements IGuildsRule {

    @Override
    public String describe() {
        return "a member takes out at most what is in the bank and what their daily limit — their own, else their "
                + "rank's — has left today";
    }

    /** The daily limit that applies to this member, in minor units; negative for none. */
    public long limit(Rank rank, Member member) {
        if (rank.isLeader()) {
            return -1;
        }
        return member.hasOwnLimit() ? member.ownLimit() : rank.dailyLimit();
    }

    /** @param day the epoch day today is */
    public long mayTake(Rank rank, Member member, long day, long bank) {
        long available = Math.max(0, bank);
        long limit = limit(rank, member);
        if (limit < 0) {
            return available;
        }
        return Math.min(available, Math.max(0, limit - member.takenOn(day)));
    }
}
