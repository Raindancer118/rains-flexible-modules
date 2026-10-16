package de.raindancer.modules.guilds.model;

import java.util.Objects;
import java.util.UUID;

/**
 * What a guild knows about one of its members, beyond being in it.
 *
 * @param deposited   everything they ever paid into the bank, in minor units — what a disband shares by
 * @param withdrawn   everything they ever took out
 * @param xp          guild experience they earned for it
 * @param takenToday  what they took out on {@code takenOn}, for the daily limit
 * @param takenOn     the day (epoch day) {@code takenToday} counts
 * @param seen        when they were last on any server of the network, epoch millis; 0 for not known — what a
 *                    guild's succession reads, since a server only knows when somebody was last on <em>it</em>
 * @param ownLimit    their own daily bank limit in minor units, replacing the rank's: negative for none,
 *                    {@link #RANK_LIMIT} for the rank's
 */
public record Member(UUID player, String rank, long joined, long deposited, long withdrawn, long xp,
                     long takenToday, long takenOn, long seen, long ownLimit) {

    /** No limit of their own: the rank's applies. */
    public static final long RANK_LIMIT = Long.MIN_VALUE;

    public Member(UUID player, String rank, long joined, long deposited, long withdrawn, long xp, long takenToday,
                  long takenOn, long seen) {
        this(player, rank, joined, deposited, withdrawn, xp, takenToday, takenOn, seen, RANK_LIMIT);
    }

    public Member(UUID player, String rank, long joined, long deposited, long withdrawn, long xp, long takenToday,
                  long takenOn) {
        this(player, rank, joined, deposited, withdrawn, xp, takenToday, takenOn, 0);
    }

    public Member {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(rank, "rank");
        if (ownLimit < 0 && ownLimit != RANK_LIMIT) {
            ownLimit = -1;
        }
    }

    public static Member joining(UUID player, String rank, long now) {
        return new Member(player, rank, now, 0, 0, 0, 0, 0, now);
    }

    public Member withRank(String newRank) {
        return new Member(player, newRank, joined, deposited, withdrawn, xp, takenToday, takenOn, seen, ownLimit);
    }

    public Member depositing(long minor) {
        return new Member(player, rank, joined, Math.addExact(deposited, minor), withdrawn, xp, takenToday, takenOn,
                seen, ownLimit);
    }

    /** Taking out on {@code day}: the day's count starts again when the day is a new one. */
    public Member withdrawing(long minor, long day) {
        long today = day == takenOn ? takenToday : 0;
        return new Member(player, rank, joined, deposited, Math.addExact(withdrawn, minor), xp,
                Math.addExact(today, minor), day, seen, ownLimit);
    }

    /** A withdrawal on {@code day} that did not go through, taken back off both counts. */
    public Member unwithdrawing(long minor, long day) {
        return new Member(player, rank, joined, deposited, withdrawn - minor, xp,
                Math.max(0, takenOn(day) - minor), day, seen, ownLimit);
    }

    public Member withSeen(long when) {
        return new Member(player, rank, joined, deposited, withdrawn, xp, takenToday, takenOn, Math.max(seen, when),
                ownLimit);
    }

    /** @param minor their own daily limit; negative for none, {@link #RANK_LIMIT} for the rank's again */
    public Member withOwnLimit(long minor) {
        return new Member(player, rank, joined, deposited, withdrawn, xp, takenToday, takenOn, seen, minor);
    }

    public boolean hasOwnLimit() {
        return ownLimit != RANK_LIMIT;
    }

    public long takenOn(long day) {
        return day == takenOn ? takenToday : 0;
    }

    public Member earning(long gained) {
        return new Member(player, rank, joined, deposited, withdrawn, Math.addExact(xp, gained), takenToday, takenOn,
                seen, ownLimit);
    }

    /** What they put in and did not take back out; never below zero. */
    public long netContribution() {
        return Math.max(0, deposited - withdrawn);
    }
}
