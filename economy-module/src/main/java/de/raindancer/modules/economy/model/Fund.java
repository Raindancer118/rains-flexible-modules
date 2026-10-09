package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

import java.util.UUID;

/**
 * A community fund: everybody donates toward a goal, and when it is full its effect happens. Donations are
 * destroyed — that is the point of it.
 *
 * @param effect what happens when it is full, as staff wrote it: {@code boost 25 24} or {@code command say hi}
 * @param doneAt when it filled up; 0 while it is still collecting
 */
public record Fund(UUID id, String name, Money target, Money raised, String effect, long created, long doneAt) {

    public Fund {
        raised = raised == null ? Money.ZERO : raised;
        effect = effect == null ? "" : effect;
    }

    public boolean done() {
        return doneAt > 0;
    }

    public Money missing() {
        return target.minus(raised).max(Money.ZERO);
    }

    public Fund donated(Money amount, long now) {
        Money total = raised.plus(amount);
        return new Fund(id, name, target, total, effect, created, total.isAtLeast(target) ? now : 0);
    }

    public double progress() {
        return target.isPositive() ? Math.min(1.0, (double) raised.minor() / target.minor()) : 1.0;
    }
}
