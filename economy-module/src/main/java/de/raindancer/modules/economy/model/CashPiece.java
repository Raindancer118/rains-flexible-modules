package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

/**
 * What a stack of cash says it is worth.
 *
 * @param each   the value of one item in the stack
 * @param count  how many items
 * @param serial a cheque's number, or null for a coin
 * @param seal   the server's signature over value, form and serial; null when there is none
 */
public record CashPiece(Money each, int count, Form form, String serial, boolean cheque, String seal) {

    public Money total() {
        return each.times(Math.max(0, count));
    }

    public boolean numbered() {
        return serial != null && !serial.isBlank();
    }
}
