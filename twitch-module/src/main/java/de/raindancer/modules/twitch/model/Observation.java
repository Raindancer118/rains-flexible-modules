package de.raindancer.modules.twitch.model;

/** What one look at Twitch found. {@link #UNKNOWN} is a failed look — it says nothing about whether anybody is live. */
public sealed interface Observation {

    record Live(StreamInfo stream) implements Observation {
    }

    enum State implements Observation {
        OFFLINE, UNKNOWN
    }

    Observation OFFLINE = State.OFFLINE;
    Observation UNKNOWN = State.UNKNOWN;
}
