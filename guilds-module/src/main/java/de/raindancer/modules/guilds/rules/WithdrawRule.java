package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;

/** How much a member may take out of the bank today: what the rank's daily limit leaves, and what is there. */
public final class WithdrawRule implements IGuildsRule {

    @Override
    public String describe() {
        return "a member takes out at most what is in the bank and what their rank's daily limit has left today";
    }

    /** @param day the epoch day today is */
    public long mayTake(Rank rank, Member member, long day, long bank) {
        long available = Math.max(0, bank);
        if (rank.isUnlimited()) {
            return available;
        }
        long left = Math.max(0, rank.dailyLimit() - member.takenOn(day));
        return Math.min(available, left);
    }
}
