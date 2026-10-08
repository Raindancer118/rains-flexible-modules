package de.raindancer.modules.economy.service;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.PriceTag;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** What the shop says one item is worth to the rest of the server: its buy price. */
class ShopItemValuerTest {

    private final Map<String, PriceTag> tags = Map.of(
            "DIAMOND", new PriceTag("DIAMOND", Money.of(800), Money.of(1000), Money.of(700), true, true, PriceTag.Source.BASE),
            "BEDROCK", PriceTag.unpriced("BEDROCK"));
    private final Map<ItemStack, String> materials = new java.util.IdentityHashMap<>();
    private final ShopItemValuer valuer = new ShopItemValuer(tags::get, stack -> false, materials::get);

    private ItemStack of(String material) {
        ItemStack stack = mock(ItemStack.class);
        materials.put(stack, material);
        return stack;
    }

    @Test
    @DisplayName("an item is worth what the shop would charge for one")
    void buyPrice() {
        assertThat(valuer.valueOf(of("DIAMOND"))).contains(Money.of(1000));
    }

    @Test
    @DisplayName("air has no value")
    void air() {
        assertThat(valuer.valueOf(of(null))).isEmpty();
    }

    @Test
    @DisplayName("something the shop has no price for has no value")
    void unpriced() {
        assertThat(valuer.valueOf(of("BEDROCK"))).isEmpty();
    }

    @Test
    @DisplayName("cash is never valued by its material — a coin is not a gold nugget")
    void cash() {
        ShopItemValuer cashAware = new ShopItemValuer(tags::get, stack -> true, materials::get);
        assertThat(cashAware.valueOf(of("DIAMOND"))).isEmpty();
    }
}
