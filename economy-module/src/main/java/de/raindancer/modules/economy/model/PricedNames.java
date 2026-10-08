package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A list of {@code "<name> <amount>"} lines — mob rewards, mining rewards, custom prices — read into a map
 * keyed by the upper-case name, and written back the same way.
 *
 * <p>An unreadable line is skipped, not fatal: one typo must not switch off every other price.
 */
public record PricedNames(Map<String, Money> amounts) {

    public static final PricedNames NONE = new PricedNames(Map.of());

    public PricedNames {
        amounts = Map.copyOf(amounts);
    }

    public static PricedNames parse(List<String> lines, Currency currency) {
        Map<String, Money> read = new LinkedHashMap<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            if (line == null) {
                continue;
            }
            String[] words = line.trim().split("\\s+");
            if (words.length != 2) {
                continue;
            }
            currency.parse(words[1]).ifPresent(amount ->
                    read.put(words[0].toUpperCase(Locale.ROOT).replace("MINECRAFT:", ""), amount));
        }
        return new PricedNames(read);
    }

    public Optional<Money> of(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(amounts.get(name.toUpperCase(Locale.ROOT)));
    }

    public PricedNames with(String name, Money amount) {
        Map<String, Money> next = new LinkedHashMap<>(amounts);
        next.put(name.toUpperCase(Locale.ROOT), amount);
        return new PricedNames(next);
    }

    public PricedNames without(String name) {
        Map<String, Money> next = new LinkedHashMap<>(amounts);
        next.remove(name.toUpperCase(Locale.ROOT));
        return new PricedNames(next);
    }

    /** Back to lines, lower case, amounts as plain numbers the parser reads again. */
    public List<String> lines(Currency currency) {
        List<String> lines = new ArrayList<>();
        amounts.forEach((name, amount) -> lines.add(name.toLowerCase(Locale.ROOT) + " "
                + plain(amount, currency)));
        return lines;
    }

    private static String plain(Money amount, Currency currency) {
        return java.math.BigDecimal.valueOf(amount.minor()).movePointLeft(currency.decimals())
                .stripTrailingZeros().toPlainString();
    }
}
