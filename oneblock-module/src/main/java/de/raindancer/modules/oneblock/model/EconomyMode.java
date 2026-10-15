package de.raindancer.modules.oneblock.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Which money an island plays with, chosen once by its owner and never changed again — everyone else sees it.
 *
 * <p>{@link #UNDECIDED} only for islands that came over from BentoBox: their owner chooses on the next visit, and
 * until then the island plays as {@link #PURE}, since letting money in is the one choice that cannot be taken back.
 */
public enum EconomyMode {

    /** OneBlock on its own: no SMP money on the island server, nothing bought, everything broken out of the block. */
    PURE("oneblock.mode.pure.badge", "oneblock.mode.pure.name"),
    /** The SMP's money comes along: {@code /shop}, {@code /pay}, {@code /balance} work on the island. */
    SMP("oneblock.mode.smp.badge", "oneblock.mode.smp.name"),
    /** Not chosen yet. Plays as {@link #PURE}. */
    UNDECIDED("oneblock.mode.undecided.badge", "oneblock.mode.undecided.name");

    // Written out whole rather than put together, so the test that every key has wording finds each of them.
    private final String badgeKey;
    private final String nameKey;

    EconomyMode(String badgeKey, String nameKey) {
        this.badgeKey = badgeKey;
        this.nameKey = nameKey;
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<EconomyMode> fromWire(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        for (EconomyMode mode : values()) {
            if (mode.wire().equalsIgnoreCase(raw.strip())) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public boolean allowsSmpMoney() {
        return this == SMP;
    }

    public boolean decided() {
        return this != UNDECIDED;
    }

    /** The message key of the badge others see. */
    public String badgeKey() {
        return badgeKey;
    }

    /** The message key of the words that name it. */
    public String nameKey() {
        return nameKey;
    }
}
