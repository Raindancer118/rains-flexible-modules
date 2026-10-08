package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.EnchantLevel;

import java.util.List;

/**
 * What enchantments and wear do to what the shop pays: every level adds a fixed value (treasure double,
 * and curses — treasure too — take double away), and the plain part of the price shrinks with the durability used up.
 */
public final class EnchantValueRule implements IEconomyRule {

    /** May be negative when curses outweigh the rest. */
    public Money bonus(List<EnchantLevel> enchantments, Money perLevel) {
        long total = 0;
        for (EnchantLevel each : enchantments) {
            long value = Math.multiplyExact(perLevel.minor(), Math.max(0, each.level()));
            if (each.treasure()) {
                value = Math.multiplyExact(value, 2);
            }
            total = each.curse() ? total - value : total + value;
        }
        return Money.of(total);
    }

    /**
     * What one sells for.
     *
     * @param plainSell what the plain, unworn item sells for
     * @param bonus     the enchantments' worth, before the sell ratio
     */
    public Money sellValue(Money plainSell, double durabilityLeft, Money bonus, double sellRatio) {
        if (Double.isNaN(durabilityLeft)) {
            return Money.ZERO;
        }
        double worn = plainSell.minor() * Math.max(0, Math.min(1, durabilityLeft));
        double enchanted = bonus.minor() * Math.max(0, sellRatio);
        return Money.of(Math.max(0, (long) Math.floor(worn + enchanted + 1e-9)));
    }

    /**
     * What an enchanted book costs in the shop: the book, plus the levels at the buying price — treasure
     * double — and never less per level than the shop pays, so buying and selling back cannot make money.
     */
    public Money buyPrice(Money book, EnchantLevel enchantment, Money buyPerLevel, Money sellPerLevel) {
        long perLevel = Math.max(buyPerLevel.minor(), sellPerLevel.minor());
        long value = Math.multiplyExact(perLevel, Math.max(1, enchantment.level()));
        if (enchantment.treasure()) {
            value = Math.multiplyExact(value, 2);
        }
        return book.max(Money.ZERO).plus(Money.of(value));
    }

    /** Whether the shop sells it: never a curse, treasure only when allowed, nothing an owner closed. */
    public boolean offered(EnchantLevel enchantment, boolean treasureAllowed, List<String> closed) {
        if (enchantment.curse() || enchantment.treasure() && !treasureAllowed) {
            return false;
        }
        return closed.stream().noneMatch(key -> key.strip().equalsIgnoreCase(enchantment.key()));
    }

    public double durabilityLeft(int maxDurability, int damage) {
        if (maxDurability <= 0) {
            return 1.0;
        }
        return Math.max(0, Math.min(1, (maxDurability - damage) / (double) maxDurability));
    }

    @Override
    public String describe() {
        return "what enchantments and wear do to what the shop pays for an item";
    }
}
