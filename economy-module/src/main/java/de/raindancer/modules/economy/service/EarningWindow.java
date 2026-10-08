package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.rules.EarningCapRule;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * What each player has earned this hour, so the hourly cap holds across mobs, mining, salary and
 * advancements together. In memory: a restart resetting an hourly window costs nothing anybody could farm.
 */
public final class EarningWindow {

    private static final long HOUR = 3_600_000L;

    private record Window(long started, Money earned) {
    }

    private final EarningCapRule rule = new EarningCapRule();
    private final LongSupplier clock;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

    public EarningWindow(LongSupplier clock) {
        this.clock = clock;
    }

    /** How much of this reward may be paid; what is allowed is counted against the hour at once. */
    public Money take(UUID who, Money reward, Money cap) {
        long now = clock.getAsLong();
        Money[] allowed = {Money.ZERO};
        windows.compute(who, (id, window) -> {
            Window current = window == null || now - window.started() >= HOUR ? new Window(now, Money.ZERO) : window;
            allowed[0] = rule.allowed(current.earned(), cap, reward);
            return new Window(current.started(), current.earned().plus(allowed[0]));
        });
        return allowed[0];
    }

    /** Gives back what was counted but could not be paid — a full account, a frozen one. */
    public void refund(UUID who, Money amount) {
        windows.computeIfPresent(who, (id, window) -> new Window(window.started(),
                window.earned().isAtLeast(amount) ? window.earned().minus(amount) : Money.ZERO));
    }

    /** Drops windows that have run out. */
    public void sweep() {
        long now = clock.getAsLong();
        windows.values().removeIf(window -> now - window.started() >= HOUR);
    }
}
