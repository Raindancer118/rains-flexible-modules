package de.raindancer.modules.jobs.model;

import de.raindancer.core.social.economy.Money;

/**
 * One quest a player was given today, at the size and pay their tier gave it. Immutable.
 *
 * @param template the quests.yml entry it came from; its title and what counts are read from there
 * @param state    {@link State#OWED} when it is done but the payment was refused and is tried again
 */
public record Quest(String template, int amount, Money pay, int progress, State state) {

    public enum State { OPEN, PAID, OWED }

    public Quest {
        amount = Math.max(1, amount);
        progress = Math.clamp(progress, 0, amount);
    }

    public boolean done() {
        return progress >= amount;
    }

    public boolean open() {
        return state == State.OPEN;
    }

    public Quest plus(int count) {
        return new Quest(template, amount, pay, progress + Math.max(0, count), state);
    }

    public Quest in(State next) {
        return new Quest(template, amount, pay, progress, next);
    }
}
