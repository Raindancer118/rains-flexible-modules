package de.raindancer.modules.farmworld.rules;

import de.raindancer.core.social.economy.Money;

/**
 * What entering a farm world costs somebody, given the two prices an owner may set and the pass they may hold.
 *
 * <p>Decides and does nothing: taking the money is {@code FarmFees}, remembering the pass is
 * {@code FarmPasses}.
 */
public final class FarmEntryRule implements IFarmWorldRule {

    /** What they have said they would rather pay, from a button; {@link #NONE} before they have. */
    public enum Choice { NONE, ENTRY, PASS }

    public enum Entry {
        /** Nothing to pay: no price, a pass that is still running, or somebody who bypasses fees. */
        FREE,
        PAY_ENTRY,
        BUY_PASS,
        /** Both prices are set and they have not said which — offer both. */
        CHOOSE
    }

    public Entry decide(Money entry, Money pass, boolean passActive, boolean bypasses, Choice chosen) {
        boolean entryPriced = entry != null && entry.isPositive();
        boolean passPriced = pass != null && pass.isPositive();
        if (bypasses || passActive || (!entryPriced && !passPriced)) {
            return Entry.FREE;
        }
        if (!passPriced) {
            return Entry.PAY_ENTRY;
        }
        if (!entryPriced) {
            return Entry.BUY_PASS;
        }
        return switch (chosen == null ? Choice.NONE : chosen) {
            case ENTRY -> Entry.PAY_ENTRY;
            case PASS -> Entry.BUY_PASS;
            case NONE -> Entry.CHOOSE;
        };
    }

    @Override
    public String describe() {
        return "what entering a farm world costs somebody";
    }
}
