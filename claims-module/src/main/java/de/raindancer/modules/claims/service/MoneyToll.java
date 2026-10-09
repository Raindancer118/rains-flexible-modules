package de.raindancer.modules.claims.service;

import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.Economy;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;

import java.util.Optional;
import java.util.UUID;

/**
 * An entry fee paid in money: the visitor pays, the owner receives it, and the server's cut is destroyed.
 *
 * <p>The cut is a sink ({@code claims.entry-fee-cut}) and is taken first, so a visitor who cannot cover the
 * whole fee pays nothing; if the owner then cannot be paid the cut is given back. Land with no owner is all
 * sink ({@code claims.entry-fee}).
 */
public final class MoneyToll {

    /** @param shortfall what the visitor is missing, when {@code paid} is false */
    public record Result(boolean paid, String shortfall) {
        static Result ok() {
            return new Result(true, "");
        }

        static Result lacking(String what) {
            return new Result(false, what);
        }
    }

    private MoneyToll() {
    }

    public static Result pay(UUID visitor, UUID owner, int units, double cutPercent) {
        Money written = CostService.money(units);
        double share = Math.max(0.0D, Math.min(100.0D, cutPercent)) / 100.0D;
        Money cutWritten = owner == null ? written : written.share(share);
        Money ownerWritten = owner == null ? Money.ZERO : written.minus(cutWritten);

        Money cutPrice = Fees.quote(CostService.cutSource(owner), cutWritten);
        Money ownerPrice = Fees.quote(CostService.ENTRY_FEE_SOURCE, ownerWritten);
        Money total = cutPrice.plus(ownerPrice);

        Optional<Economy> economy = Economies.current();
        if (total.isPositive() && economy.isEmpty()) {
            return Result.lacking("an economy (none is installed)");
        }
        if (total.isPositive() && !economy.get().balance(visitor).isAtLeast(total)) {
            return Result.lacking(Fees.format(total.minus(economy.get().balance(visitor))) + " more");
        }

        EconomyResult cut = Fees.charge(visitor, cutWritten,
                "Claim entry fee", CostService.cutSource(owner));
        if (!cut.succeeded()) {
            return Result.lacking(Fees.format(cut.amount()));
        }
        if (ownerPrice.isPositive()) {
            EconomyResult sent = economy.get().transfer(visitor, owner, ownerPrice, "Claim entry fee");
            if (!sent.succeeded()) {
                Fees.refund(visitor, cut.amount(), "Claim entry fee (not completed)",
                        CostService.cutSource(owner));
                return Result.lacking(Fees.format(ownerPrice));
            }
        }
        return Result.ok();
    }
}
