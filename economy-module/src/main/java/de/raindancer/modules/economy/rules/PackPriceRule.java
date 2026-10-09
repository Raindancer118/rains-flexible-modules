package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.Pack;
import de.raindancer.modules.economy.model.PackItem;
import de.raindancer.modules.economy.model.PackPrice;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Function;

/**
 * What a pack costs: its contents at the buyer's own prices (so a role's discount on rockets is a discount on
 * the rockets in a pack too), less the pack discount; or the owner's fixed price.
 */
public final class PackPriceRule implements IEconomyRule {

    /**
     * @param linePrice what the buyer pays for one line of the pack, or empty when the shop does not sell it
     * @return empty when the pack is empty or holds something without a price
     */
    public Optional<PackPrice> price(Pack pack, Function<PackItem, Optional<Money>> linePrice, int discountPercent) {
        if (pack.ownPrice().isPresent()) {
            return Optional.of(new PackPrice(pack.ownPrice().get(), pack.ownPrice().get()));
        }
        if (pack.contents().isEmpty()) {
            return Optional.empty();
        }
        long sum = 0;
        try {
            for (PackItem each : pack.contents()) {
                Optional<Money> line = linePrice.apply(each);
                if (line == null || line.isEmpty() || !line.get().isPositive()) {
                    return Optional.empty();
                }
                sum = Math.addExact(sum, line.get().minor());
            }
        } catch (ArithmeticException overflow) {
            return Optional.empty();
        }
        int off = Math.clamp(discountPercent, 0, 90);
        BigInteger[] split = BigInteger.valueOf(sum).multiply(BigInteger.valueOf(100L - off))
                .divideAndRemainder(BigInteger.valueOf(100));
        long price = split[0].longValueExact() + (split[1].signum() > 0 ? 1 : 0);
        return Optional.of(new PackPrice(Money.of(sum), Money.of(Math.max(1, price))));
    }

    @Override
    public String describe() {
        return "what a pack costs: its contents at the buyer's prices less the pack discount, or the owner's price";
    }
}
