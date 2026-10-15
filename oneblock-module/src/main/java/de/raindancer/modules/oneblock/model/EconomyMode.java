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
    PURE,
    /** The SMP's money comes along: {@code /shop}, {@code /pay}, {@code /balance} work on the island. */
    SMP,
    /** Not chosen yet. Plays as {@link #PURE}. */
    UNDECIDED;

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
        return "oneblock.mode." + wire() + ".badge";
    }

    /** The message key of the sentence that names it. */
    public String nameKey() {
        return "oneblock.mode." + wire() + ".name";
    }
}
