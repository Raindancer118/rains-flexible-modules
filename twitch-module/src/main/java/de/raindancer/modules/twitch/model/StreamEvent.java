package de.raindancer.modules.twitch.model;

import java.time.Duration;

/** What is worth telling the network. */
public sealed interface StreamEvent {

    record WentLive(StreamInfo stream) implements StreamEvent {
    }

    record Ended(StreamInfo stream, Duration lasted) implements StreamEvent {
    }
}
