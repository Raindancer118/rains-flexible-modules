package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.economy.model.EnchantKind;

import java.util.List;

/**
 * Enchanted things sold to the shop, from the prices of the books in them. Each enchantment and level has a
 * book price of its own in the price book; what it adds to anything else is that price less a plain book's.
 */
public final class EnchantValueRule implements IEconomyRule {

    /** What an enchantment adds: its book's sell price over a plain book's. */
    public Money part(Money keySell, Money bookSell) {
        return keySell.minus(bookSell).max(Money.ZERO);
    }

    /** What a curse takes away from a sale: the size of its part at the sell ratio. */
    public Money cursePenalty(Money cursePart, double sellRatio) {
        return Money.of((long) Math.floor(Math.max(0, cursePart.minor()) * Math.max(0, sellRatio) + 1e-9));
    }

    /**
     * An enchanted or worn item: the plain item's sell price shrunk by the wear, its enchantments' parts on top,
     * its curses' taken away — never below nothing.
     */
    public Money itemSell(Money plainSell, double durabilityLeft, Money parts, Money curses) {
        if (Double.isNaN(durabilityLeft)) {
            return Money.ZERO;
        }
        long worn = (long) Math.floor(plainSell.minor() * Math.max(0, Math.min(1, durabilityLeft)) + 1e-9);
        return Money.of(Math.max(0, worn + parts.minor() - curses.minor()));
    }

    /**
     * An enchanted book: each enchantment as a book of its own, less the books used up combining them into
     * one, its curses taken away.
     */
    public Money bookSell(Money bookSell, List<Money> keySells, Money curses) {
        long total = keySells.isEmpty() ? bookSell.minor()
                : keySells.stream().mapToLong(Money::minor).sum() - (keySells.size() - 1L) * bookSell.minor();
        return Money.of(Math.max(0, total - curses.minor()));
    }

    /** Whether the shop sells it: never a curse, treasure only when allowed, nothing an owner closed. */
    public boolean offered(EnchantKind kind, boolean treasureAllowed, List<String> closed) {
        if (kind.curse() || kind.treasure() && !treasureAllowed) {
            return false;
        }
        return closed == null || closed.stream().map(key -> key.strip().toLowerCase(java.util.Locale.ROOT)
                .replace("minecraft:", "")).noneMatch(kind.id()::equals);
    }

    /** The level an enchantment is priced at: none below I (only commands make those), the maximum above it. */
    public java.util.OptionalInt level(int level, int maxLevel) {
        return level < 1 ? java.util.OptionalInt.empty() : java.util.OptionalInt.of(Math.min(level, Math.max(1, maxLevel)));
    }

    /** Whether a stack of this many is one the game makes: more than one of a thing that does not stack is not. */
    public boolean countable(int amount, int maxStackSize) {
        return amount >= 1 && amount <= Math.max(1, maxStackSize);
    }

    public double durabilityLeft(int maxDurability, int damage) {
        if (maxDurability <= 0) {
            return 1.0;
        }
        return Math.max(0, Math.min(1, (maxDurability - damage) / (double) maxDurability));
    }

    @Override
    public String describe() {
        return "what enchantments, curses and wear do to what the shop pays";
    }
}
