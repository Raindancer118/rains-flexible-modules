package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.PersonalPrice;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.modules.economy.model.Bulk;
import de.raindancer.modules.economy.model.PriceTag;
import de.raindancer.modules.economy.model.YourPrice;

import java.util.UUID;

/**
 * One player's price for one item: the shop's, changed by whatever on the server changes prices per player
 * (roles, events) and by buying in bulk, with the sell price kept a cent under the cheapest that player could
 * buy the same line for.
 *
 * <p>The cap is per player on purpose. The shop's own cap is against its own buy price; a player who buys
 * a quarter cheaper would otherwise buy and sell back the same stack at a profit.
 */
public final class PersonalPriceRule implements IEconomyRule {

    public YourPrice forPlayer(UUID player, PriceTag shop) {
        return forPlayer(player, shop, Bulk.NONE);
    }

    public YourPrice forPlayer(UUID player, PriceTag shop, Bulk bulk) {
        Bulk applied = shop.buyable() ? bulk : Bulk.NONE;
        if (player == null || !PriceModifiers.isAnyProvided()) {
            return new YourPrice(shop, PersonalPrice.unchanged(shop.buy()), PersonalPrice.unchanged(shop.sell()),
                    applied);
        }
        PersonalPrice buy = shop.buyable() ? PriceModifiers.buy(player, shop.material(), shop.buy())
                : PersonalPrice.unchanged(shop.buy());
        PersonalPrice sell = shop.sellable() ? PriceModifiers.sell(player, shop.material(), shop.sell())
                : PersonalPrice.unchanged(shop.sell());
        return new YourPrice(shop, buy, sell, applied);
    }

    @Override
    public String describe() {
        return "what one player pays and is paid, after roles and bulk, never selling back at a profit";
    }
}
