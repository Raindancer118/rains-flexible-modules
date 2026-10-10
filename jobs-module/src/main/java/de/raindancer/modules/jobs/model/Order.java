package de.raindancer.modules.jobs.model;

import de.raindancer.core.social.economy.Money;

/**
 * Work a player asked for: so many of something by a deadline, for the amount they named. Immutable.
 *
 * @param work  the orders.yml entry; what counts is read from there
 * @param state OPEN while it runs, PAID when done in time, OWED done but refused by the treasury, FAILED when
 *              time ran out
 */
public record Order(String work, String says, int units, Money pay, long startedAt, long endsAt, int progress,
                    State state) {

    public enum State { OPEN, PAID, OWED, FAILED }

    public Order {
        units = Math.max(1, units);
        progress = Math.clamp(progress, 0, units);
    }

    public boolean done() {
        return progress >= units;
    }

    public Order plus(int count) {
        return new Order(work, says, units, pay, startedAt, endsAt, progress + Math.max(0, count), state);
    }

    public Order in(State next) {
        return new Order(work, says, units, pay, startedAt, endsAt, progress, next);
    }
}
