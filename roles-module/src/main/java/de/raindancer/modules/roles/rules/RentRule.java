package de.raindancer.modules.roles.rules;

import java.time.Duration;

/** When rent is due, what the next due date is, and when to warn. Real time, so it runs while nobody is online. */
public final class RentRule implements IRolesRule {

    public static final Duration PERIOD = Duration.ofDays(30);
    public static final Duration WARN = Duration.ofDays(3);

    public boolean due(long dueAt, long now) {
        return dueAt <= now;
    }

    /** One period on from the due date; time a player spent away is not billed, so never earlier than a period from now. */
    public long next(long dueAt, long now) {
        long next = dueAt + PERIOD.toMillis();
        return next > now ? next : now + PERIOD.toMillis();
    }

    public boolean warn(long dueAt, long now) {
        return dueAt > now && dueAt - now <= WARN.toMillis();
    }

    @Override
    public String describe() {
        return "when role rent is due, and when to warn about it";
    }
}
