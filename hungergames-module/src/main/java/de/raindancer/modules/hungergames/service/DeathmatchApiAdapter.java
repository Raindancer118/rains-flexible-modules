package de.raindancer.modules.hungergames.service;

import java.util.Optional;

/**
 * {@link EventEndpoints.Deathmatch}, over the real {@link DeathmatchService}.
 *
 * <p>{@code DeathmatchService} leaves the countdown itself to its caller — see that class's note on
 * {@code WARNING} not surviving a restart — so there is no ticking number to report here. {@link #state()}
 * and {@link #statusLine()} report what the service actually knows: which of the three states it is in,
 * and why {@link #start} would currently be refused if it is idle.
 */
public final class DeathmatchApiAdapter implements EventEndpoints.Deathmatch {

    private final DeathmatchService deathmatch;

    public DeathmatchApiAdapter(DeathmatchService deathmatch) {
        this.deathmatch = deathmatch;
    }

    @Override
    public String state() {
        return deathmatch.state().name();
    }

    @Override
    public String statusLine() {
        return switch (deathmatch.state()) {
            case IDLE -> deathmatch.checkStart()
                    .map(reason -> "idle — " + reason)
                    .orElse("idle — ready to start");
            case WARNING -> "warning — a countdown to the deathmatch is running";
            case ACTIVE -> "active — the deathmatch is on";
        };
    }

    @Override
    public Optional<String> start(String actor) {
        return deathmatch.start();
    }

    @Override
    public Optional<String> cancel(String actor) {
        return deathmatch.cancel();
    }
}
