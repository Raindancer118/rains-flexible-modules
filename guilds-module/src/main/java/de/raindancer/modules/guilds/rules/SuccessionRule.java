package de.raindancer.modules.guilds.rules;

import de.raindancer.modules.guilds.model.Guild;
import de.raindancer.modules.guilds.model.Member;
import de.raindancer.modules.guilds.model.Rank;

import java.time.Duration;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

/**
 * Who leads a guild whose leader has stopped playing.
 *
 * <p>The most senior member who has been seen within the same window, then whoever has been in the guild
 * longest, then — so the answer never depends on map order — the lower UUID. A leader the server knows nothing
 * about is never replaced: "never seen" is what a broken lookup says too, and a guild must not change hands
 * because a file could not be read.
 */
public final class SuccessionRule implements IGuildsRule {

    @Override
    public String describe() {
        return "a leader unseen for the configured time is replaced by the most senior member seen within it";
    }

    /**
     * @param lastSeen when a player was last on, in epoch millis; empty when unknown
     * @param after    how long a leader may be gone; zero or less never replaces anybody
     */
    public Optional<UUID> heir(Guild guild, Function<UUID, OptionalLong> lastSeen, long now, Duration after) {
        if (after == null || after.isZero() || after.isNegative()) {
            return Optional.empty();
        }
        long cutoff = now - after.toMillis();
        OptionalLong leaderSeen = lastSeen.apply(guild.leader());
        if (leaderSeen.isEmpty() || leaderSeen.getAsLong() >= cutoff) {
            return Optional.empty();
        }
        return guild.members().values().stream()
                .filter(member -> !member.player().equals(guild.leader()))
                .filter(member -> {
                    OptionalLong seen = lastSeen.apply(member.player());
                    return seen.isPresent() && seen.getAsLong() >= cutoff;
                })
                .min(Comparator.<Member>comparingInt(member -> -guild.rankOf(member.player()).map(Rank::weight).orElse(0))
                        .thenComparingLong(Member::joined)
                        .thenComparing(Member::player))
                .map(Member::player);
    }
}
