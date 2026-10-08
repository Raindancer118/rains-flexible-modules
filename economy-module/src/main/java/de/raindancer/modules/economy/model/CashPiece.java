package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Money;

/**
 * What a stack of cash says it is worth.
 *
 * @param each   the value of one item in the stack
 * @param count  how many items
 * @param serial the note's number, or null for a coin
 */
public record CashPiece(Money each, int count, Form form, String serial, boolean cheque) {

    public Money total() {
        return each.times(Math.max(0, count));
    }

    public boolean numbered() {
        return serial != null && !serial.isBlank();
    }
}
