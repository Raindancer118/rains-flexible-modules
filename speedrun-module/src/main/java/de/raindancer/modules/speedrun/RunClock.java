package de.raindancer.modules.speedrun;

import de.raindancer.core.platform.util.Times;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A run time typed by an admin: {@code 42:05}, {@code 1:02:03}, or Core's {@code 1h30m}. */
public final class RunClock {

    private static final Pattern CLOCK = Pattern.compile("(?:(\\d+):)?(\\d+):(\\d{2})");

    /** Far beyond any run, and far below where a clock's arithmetic would overflow. */
    static final Duration LONGEST = Duration.ofHours(10_000);

    private RunClock() {
    }

    /** Empty for anything that is not a clock reading between zero and {@link #LONGEST}. */
    public static Optional<Duration> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String text = typed.trim();
        if (text.equals("0")) {
            return Optional.of(Duration.ZERO);
        }
        try {
            return read(text).filter(reading -> !reading.isNegative() && reading.compareTo(LONGEST) <= 0);
        } catch (NumberFormatException | ArithmeticException tooBig) {
            // Digits too many for a long, or hours too many for a Duration: not a clock reading.
            return Optional.empty();
        }
    }

    private static Optional<Duration> read(String text) {
        Matcher clock = CLOCK.matcher(text);
        if (clock.matches()) {
            long hours = clock.group(1) == null ? 0 : Long.parseLong(clock.group(1));
            long minutes = Long.parseLong(clock.group(2));
            long seconds = Long.parseLong(clock.group(3));
            if (seconds >= 60 || (clock.group(1) != null && minutes >= 60)) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofHours(hours).plusMinutes(minutes).plusSeconds(seconds));
        }
        return Times.parseLenient(text);
    }
}
