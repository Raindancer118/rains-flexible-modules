package de.raindancer.modules.guilds.model;

import java.util.Objects;
import java.util.UUID;

/**
 * What a guild knows about one of its members, beyond being in it.
 *
 * @param deposited   everything they ever paid into the bank, in minor units — what a disband shares by
 * @param withdrawn   everything they ever took out
 * @param xp          guild experience they earned for it
 * @param takenToday  what they took out on {@code takenOn}, for the rank's daily limit
 * @param takenOn     the day (epoch day) {@code takenToday} counts
 */
public record Member(UUID player, String rank, long joined, long deposited, long withdrawn, long xp,
                     long takenToday, long takenOn) {

    public Member {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(rank, "rank");
    }

    public static Member joining(UUID player, String rank, long now) {
        return new Member(player, rank, now, 0, 0, 0, 0, 0);
    }

    public Member withRank(String newRank) {
        return new Member(player, newRank, joined, deposited, withdrawn, xp, takenToday, takenOn);
    }

    public Member depositing(long minor) {
        return new Member(player, rank, joined, Math.addExact(deposited, minor), withdrawn, xp, takenToday, takenOn);
    }

    /** Taking out on {@code day}: the day's count starts again when the day is a new one. */
    public Member withdrawing(long minor, long day) {
        long today = day == takenOn ? takenToday : 0;
        return new Member(player, rank, joined, deposited, Math.addExact(withdrawn, minor), xp,
                Math.addExact(today, minor), day);
    }

    public long takenOn(long day) {
        return day == takenOn ? takenToday : 0;
    }

    public Member earning(long gained) {
        return new Member(player, rank, joined, deposited, withdrawn, Math.addExact(xp, gained), takenToday, takenOn);
    }

    /** What they put in and did not take back out; never below zero. */
    public long netContribution() {
        return Math.max(0, deposited - withdrawn);
    }
}
