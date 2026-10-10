package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.EnchantLevel;

import java.util.List;

/**
 * What enchantments and wear do to what the shop pays: each enchantment adds what it is worth
 * ({@link EnchantWorthRule} — the useful ones more, curses take away), and the plain part of the price
 * shrinks with the durability used up.
 */
public final class EnchantValueRule implements IEconomyRule {

    /** May be negative when curses outweigh the rest. */
    public Money bonus(List<EnchantLevel> enchantments, Money perLevel, EnchantWorthRule worth) {
        double total = 0;
        for (EnchantLevel each : enchantments) {
            total += perLevel.minor() * worth.worth(each);
        }
        return Money.of(Math.round(total));
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
     * What an enchanted book costs in the shop: the book, plus what the enchantment is worth at the buying
     * price per level — and never less per level than the shop pays, so buying and selling back cannot make money.
     */
    public Money buyPrice(Money book, EnchantLevel enchantment, Money buyPerLevel, Money sellPerLevel, EnchantWorthRule worth) {
        long perLevel = Math.max(buyPerLevel.minor(), sellPerLevel.minor());
        long value = Math.round(perLevel * Math.max(0, worth.worth(enchantment)));
        return book.max(Money.ZERO).plus(Money.of(value));
    }

    /**
     * What the shop pays for taking the enchantments off an item, the item kept: what they would add to its
     * sale. Curses cannot be sold off — they stay on, and take nothing away.
     */
    public Money enchantsOff(List<EnchantLevel> enchantments, Money perLevel, EnchantWorthRule worth, double sellRatio) {
        List<EnchantLevel> sellable = enchantments.stream().filter(each -> !each.curse()).toList();
        return sellValue(Money.ZERO, 1.0, bonus(sellable, perLevel, worth), sellRatio);
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
