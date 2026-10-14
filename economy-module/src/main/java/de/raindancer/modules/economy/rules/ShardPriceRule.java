package de.raindancer.modules.economy.rules;

import de.raindancer.core.social.economy.Money;

import java.util.ArrayList;
import java.util.List;

/** What a book of one of Rain's enchantments costs at the shop: the level's shards, and money per level. */
public final class ShardPriceRule implements IEconomyRule {

    private static final List<Integer> BUILT_IN = List.of(8, 24, 64, 128, 256);

    private final List<Integer> steps;
    private final Money perLevel;

    /** @param steps shards for level I, II, …, as written in the settings */
    public ShardPriceRule(List<String> steps, Money perLevel) {
        List<Integer> read = new ArrayList<>();
        for (String step : steps) {
            try {
                int value = Integer.parseInt(step.strip());
                if (value <= 0) {
                    read.clear();
                    break;
                }
                read.add(value);
            } catch (NumberFormatException mangled) {
                read.clear();
                break;
            }
        }
        this.steps = read.isEmpty() ? BUILT_IN : List.copyOf(read);
        this.perLevel = perLevel;
    }

    public int shards(int level) {
        if (level < 1) {
            return Integer.MAX_VALUE;
        }
        if (level <= steps.size()) {
            return steps.get(level - 1);
        }
        long value = steps.getLast();
        for (int extra = steps.size(); extra < level; extra++) {
            value = Math.min(Integer.MAX_VALUE, value * 2);
        }
        return (int) value;
    }

    public Money money(int level) {
        return new Money(perLevel.minor() * Math.max(1, level));
    }

    @Override
    public String describe() {
        return "shards and money for a book of Rain's enchantments, by level";
    }
}
