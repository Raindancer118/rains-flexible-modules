package de.raindancer.e2e;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Waiting for something a server does in its own time — never a bare sleep, always a condition and a
 * deadline, and a failure that says what was waited for.
 */
public final class Await {

    private static final long POLL_MILLIS = 50;

    private Await() {
    }

    /** Until {@code condition} holds, or fails after {@code within} saying {@code what} never happened. */
    public static void until(String what, Duration within, BooleanSupplier condition) {
        until(() -> what, within, condition);
    }

    /** The same, its description read only when it fails — so it can say what was seen by then. */
    public static void until(Supplier<String> what, Duration within, BooleanSupplier condition) {
        long deadline = System.nanoTime() + within.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
                last = null;
            } catch (IllegalStateException fatal) {
                throw fatal;
            } catch (RuntimeException notYet) {
                last = notYet;
            }
            sleep(POLL_MILLIS);
        }
        AssertionError failure = new AssertionError("Waited " + within.toSeconds() + " s for: " + what.get());
        if (last != null) {
            failure.initCause(last);
        }
        throw failure;
    }

    /** Until {@code value} gives something other than null, and returns it. */
    public static <T> T value(String what, Duration within, Supplier<T> value) {
        return value(() -> what, within, value);
    }

    /** The same, its description read only when it fails. */
    public static <T> T value(Supplier<String> what, Duration within, Supplier<T> value) {
        Object[] found = new Object[1];
        until(what, within, () -> (found[0] = value.get()) != null);
        @SuppressWarnings("unchecked")
        T result = (T) found[0];
        return result;
    }

    /** That {@code condition} stays false for all of {@code during} — "nothing happens". */
    public static void never(String what, Duration during, BooleanSupplier condition) {
        long deadline = System.nanoTime() + during.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                throw new AssertionError("Happened, and must not: " + what);
            }
            sleep(POLL_MILLIS);
        }
    }

    /** Lets the server run for a while — for a "nothing happens" that needs no condition. */
    public static void ticks(int ticks) {
        sleep(ticks * 50L);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting", interrupted);
        }
    }
}
