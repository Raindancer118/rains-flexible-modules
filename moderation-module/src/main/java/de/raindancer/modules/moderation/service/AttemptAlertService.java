package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.punishment.BlockedAttempts;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.rules.AttemptAlertRule;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Tells staff when a punished player tries anyway, throttled per player by {@link AttemptAlertRule}. Who exactly
 * is told — staff online who may see attempts and have not switched alerts off — is the sink's business.
 *
 * <p>Called on whichever thread the attempt was made on (the login thread, the chat thread), so it keeps only
 * concurrent state.
 */
public final class AttemptAlertService implements IModerationService {

    /** An attempt to tell staff about, and how many by the same player were held back since the last alert. */
    public record Alert(BlockedAttempts.Attempt attempt, int heldBack) {
    }

    private record Told(Instant at, int heldBack) {
    }

    private final AttemptAlertRule rule;
    private final Consumer<Alert> sink;
    private final LongSupplier clock;
    private final Map<UUID, Told> told = new ConcurrentHashMap<>();
    private volatile ModerationSettings settings;

    public AttemptAlertService(AttemptAlertRule rule, Consumer<Alert> sink, LongSupplier clock,
                               ModerationSettings settings) {
        this.rule = rule;
        this.sink = sink;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings;
    }

    public void onAttempt(BlockedAttempts.Attempt attempt) {
        ModerationSettings now = settings;
        if (attempt == null || attempt.player() == null || !now.attemptAlerts()) {
            return;
        }
        Instant at = Instant.ofEpochMilli(clock.getAsLong());
        int[] heldBack = {-1};
        told.compute(attempt.player(), (player, last) -> {
            if (rule.due(last == null ? null : last.at(), at, now.attemptAlertQuiet())) {
                heldBack[0] = last == null ? 0 : last.heldBack();
                return new Told(at, 0);
            }
            return new Told(last.at(), last.heldBack() + 1);
        });
        if (heldBack[0] >= 0) {
            sink.accept(new Alert(attempt, heldBack[0]));
        }
    }

    /** Somebody's punishment ended or they were forgotten: the next attempt is told at once again. */
    public void forget(UUID player) {
        told.remove(player);
    }
}
