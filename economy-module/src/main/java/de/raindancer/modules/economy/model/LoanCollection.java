package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

/**
 * What the bank did about one overdue loan in one pass.
 *
 * @param loan    the loan afterwards
 * @param cleared whether it is paid off now
 */
public record LoanCollection(Loan loan, Money feeAdded, Money taken, boolean cleared) {
}
