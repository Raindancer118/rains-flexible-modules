package de.raindancer.modules.essentials.util;

import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * When somebody was here, and for how long — read straight off vanilla, not kept a second time.
 *
 * <h2>Why this is not a copy of moderation-module's {@code PlayerStats}</h2>
 * Because it is one {@code Statistic} call and two {@code OfflinePlayer} getters, and a dependency
 * between two modules that otherwise have nothing to do with each other is a worse trade than
 * writing three lines twice. Both read the same vanilla facts; neither invents a second copy of
 * them to keep.
 */
public final class SeenService {

    private SeenService() {
    }

    /** What {@code /seen} has to say about somebody, worked out once rather than field by field. */
    public record Seen(boolean everJoined, Duration playtime, Instant firstJoined,
                       boolean online, Optional<Instant> lastSeen) {
    }

    public static Seen of(OfflinePlayer who) {
        if (!who.hasPlayedBefore()) {
            return new Seen(false, Duration.ZERO, Instant.EPOCH, who.isOnline(), Optional.empty());
        }
        Duration playtime = Duration.ofSeconds(who.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20L);
        Instant firstJoined = Instant.ofEpochMilli(who.getFirstPlayed());
        boolean online = who.isOnline();
        Optional<Instant> lastSeen = online
                ? Optional.empty()
                : Optional.of(Instant.ofEpochMilli(who.getLastLogin()));
        return new Seen(true, playtime, firstJoined, online, lastSeen);
    }
}
