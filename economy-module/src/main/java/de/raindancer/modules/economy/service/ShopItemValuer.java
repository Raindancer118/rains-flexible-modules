package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.ItemValuer;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.PriceTag;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The shop's prices, offered to the rest of the server through Core's {@code ItemValues}: one item is
 * worth what the shop would charge for it. Cash is left unpriced — its worth is its face value, and a
 * coin valued as the material it is made of would be a way to mint money.
 */
public final class ShopItemValuer implements ItemValuer {

    private final Function<String, PriceTag> tags;
    private final Predicate<ItemStack> isCash;
    private final Function<ItemStack, String> materialOf;

    /** @param materialOf the item's material name, or null for air */
    public ShopItemValuer(Function<String, PriceTag> tags, Predicate<ItemStack> isCash,
                          Function<ItemStack, String> materialOf) {
        this.tags = tags;
        this.isCash = isCash;
        this.materialOf = materialOf;
    }

    /** The material's name, as the shop's price list is keyed. */
    public static String materialName(ItemStack item) {
        return item.getType().isAir() ? null : item.getType().name();
    }

    @Override
    public Optional<Money> valueOf(ItemStack item) {
        if (item == null || isCash.test(item)) {
            return Optional.empty();
        }
        String material = materialOf.apply(item);
        if (material == null) {
            return Optional.empty();
        }
        PriceTag tag = tags.apply(material);
        return tag == null || !tag.buy().isPositive() ? Optional.empty() : Optional.of(tag.buy());
    }
}
