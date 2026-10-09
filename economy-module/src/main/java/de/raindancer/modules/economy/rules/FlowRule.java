package de.raindancer.modules.economy.rules;

import de.raindancer.modules.economy.model.TransactionKind;

/**
 * Whether a statement line printed money, destroyed it, or only moved it — read from the line alone, so a
 * week of the ledger says where money comes from and where it goes.
 */
public final class FlowRule implements IEconomyRule {

    public enum Flow { CREATED, DESTROYED, MOVED }

    /**
     * @param system   whether the line is on one of the server's own accounts (a pot, escrow)
     * @param hasOther whether the line names an account on the other side
     */
    public Flow classify(boolean system, boolean hasOther, TransactionKind kind, long delta) {
        if (delta == 0 || kind == TransactionKind.WITHDRAW || kind == TransactionKind.DEPOSIT) {
            return Flow.MOVED;
        }
        if (system) {
            // A pot or escrow only ever destroys what it holds as a fee or a cut; everything else is passing through.
            return delta < 0 && (kind == TransactionKind.FEE || kind == TransactionKind.TAX) ? Flow.DESTROYED : Flow.MOVED;
        }
        if (hasOther) {
            return Flow.MOVED;
        }
        return delta > 0 ? Flow.CREATED : Flow.DESTROYED;
    }

    @Override
    public String describe() {
        return "whether a statement line printed, destroyed or moved money";
    }
}
