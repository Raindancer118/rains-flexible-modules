package de.raindancer.modules.enchants.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlightBudgetTest {

    private static final long CAPACITY = 30_000;

    @Test
    @DisplayName("a budget nobody has touched is full, once it knows its capacity")
    void startsFull() {
        FlightBudget budget = new FlightBudget();

        budget.fitTo(CAPACITY);

        assertThat(budget.remainingMillis()).isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("draining says when it ran out, and never goes below zero")
    void drains() {
        FlightBudget budget = FlightBudget.of(1_000);

        assertThat(budget.drain(400)).isFalse();
        assertThat(budget.remainingMillis()).isEqualTo(600);
        assertThat(budget.drain(5_000)).as("this is the call that emptied it").isTrue();
        assertThat(budget.remainingMillis()).isZero();
        assertThat(budget.drain(100)).as("already empty — not news a second time").isFalse();
    }

    @Test
    @DisplayName("recharging is proportional: a full tank takes exactly the recharge time")
    void recharges() {
        FlightBudget budget = FlightBudget.of(0);

        budget.fitTo(CAPACITY);
        budget.recharge(10_000, CAPACITY, 20_000);

        assertThat(budget.remainingMillis()).isEqualTo(15_000);
        budget.recharge(60_000, CAPACITY, 20_000);
        assertThat(budget.remainingMillis()).as("never over capacity").isEqualTo(CAPACITY);
    }

    @Test
    @DisplayName("a lower level cuts what is left to the new capacity; a higher one does not top it up")
    void followsTheLevel() {
        FlightBudget budget = FlightBudget.of(90_000);

        budget.fitTo(60_000);
        assertThat(budget.remainingMillis()).isEqualTo(60_000);

        budget.fitTo(120_000);
        assertThat(budget.remainingMillis()).isEqualTo(60_000);
    }

    @Test
    @DisplayName("a zero recharge time cannot divide by zero")
    void instantRecharge() {
        FlightBudget budget = FlightBudget.of(0);
        budget.fitTo(CAPACITY);

        budget.recharge(1, CAPACITY, 0);

        assertThat(budget.remainingMillis()).isEqualTo(CAPACITY);
    }
}
