package de.raindancer.modules.manhunt.stats;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** A finished hunt, as it is kept: who played, who won, and everything that happened. */
public record HuntRecord(int number, long startedAt, long durationMillis, String reason, Winner winner,
                         List<PlayerResult> players, List<TimelineEvent> events) {

    public enum Winner { RUNNERS, HUNTERS, NOBODY }

    public HuntRecord {
        players = List.copyOf(players);
        events = List.copyOf(events);
    }

    public Optional<PlayerResult> player(UUID id) {
        return players.stream().filter(result -> result.id().equals(id)).findFirst();
    }
}
