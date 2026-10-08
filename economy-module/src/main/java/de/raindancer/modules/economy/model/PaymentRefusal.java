package de.raindancer.modules.economy.model;

/** Why a payment was not allowed to start. */
public enum PaymentRefusal {
    TO_YOURSELF,
    NOT_POSITIVE,
    BELOW_MINIMUM
}
