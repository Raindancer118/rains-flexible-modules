package de.raindancer.modules.twitch.model;

import java.time.Instant;

/** A running stream as Twitch describes it. */
public record StreamInfo(String id, String channel, String title, String game, Instant startedAt, int viewers) {

    public StreamInfo {
        title = title == null ? "" : title;
        game = game == null ? "" : game;
    }
}
