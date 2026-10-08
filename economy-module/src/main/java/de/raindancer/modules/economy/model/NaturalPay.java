package de.raindancer.modules.economy.model;

/** What a blackjack natural pays. Six to five is what stingy tables pay; even money is stingier still. */
public enum NaturalPay {
    THREE_TO_TWO(2.5), SIX_TO_FIVE(2.2), EVEN_MONEY(2.0);

    private final double returns;

    NaturalPay(double returns) {
        this.returns = returns;
    }

    /** Per unit staked, stake included. */
    public double returns() {
        return returns;
    }
}
