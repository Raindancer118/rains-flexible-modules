package de.raindancer.modules.moderation.model;

import de.raindancer.modules.moderation.rules.ConfidenceRule;

import java.util.Optional;

/**
 * What a staff report is about: a movement hack, a combat hack or a world hack — one open report each per
 * player. Combat and world flags only come while somebody fights or builds, so they are judged over a
 * longer window with fewer flags than movement, which flags every tick it is on.
 */
public enum ReportKind {

    MOVEMENT("movement"), COMBAT("combat"), WORLD("world");

    private final String key;

    ReportKind(String key) {
        this.key = key;
    }

    /** The word the report's text starts with, and the queue keeps one open of per player. */
    public String key() {
        return key;
    }

    /** Which kind a family's flags count towards, if any. */
    public static Optional<ReportKind> of(CheckType check) {
        return switch (check.category()) {
            case MOVEMENT -> Optional.of(MOVEMENT);
            case COMBAT -> Optional.of(COMBAT);
            case WORLD -> Optional.of(WORLD);
            default -> Optional.empty();
        };
    }

    /** The owner's movement policy as it stands, and the same thinking scaled to fights and building. */
    public ConfidenceRule.Policy policy(ConfidenceRule.Policy movement) {
        if (this == MOVEMENT) {
            return movement;
        }
        return new ConfidenceRule.Policy(Math.max(movement.windowMillis(), 300_000), Math.max(4, movement.minFlags() / 3),
                Math.max(4, movement.minLevel() / 2), movement.maxPing(), movement.minTps(), 2, 10_000);
    }
}
