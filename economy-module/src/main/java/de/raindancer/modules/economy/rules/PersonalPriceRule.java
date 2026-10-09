package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.PersonalPrice;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;

import java.util.UUID;

/**
 * One player's price for one item: the shop's, changed by whatever on the server changes prices per player
 * (roles, events), with the sell price kept a cent under that player's own buy price.
 *
 * <p>The cap is per player on purpose. The shop's own cap is against its own buy price; a player who buys
 * a quarter cheaper would otherwise buy and sell back the same stack at a profit.
 */
public final class PersonalPriceRule implements IEconomyRule {

    public YourPrice forPlayer(UUID player, PriceTag shop) {
        if (player == null || !PriceModifiers.isAnyProvided()) {
            return YourPrice.same(shop);
        }
        PersonalPrice buy = shop.buyable() ? PriceModifiers.buy(player, shop.material(), shop.buy())
                : PersonalPrice.unchanged(shop.buy());
        PersonalPrice sell = shop.sellable() ? PriceModifiers.sell(player, shop.material(), shop.sell())
                : PersonalPrice.unchanged(shop.sell());
        return new YourPrice(shop, buy, sell);
    }

    @Override
    public String describe() {
        return "what one player pays and is paid, after roles and the like, never selling back at a profit";
    }
}
