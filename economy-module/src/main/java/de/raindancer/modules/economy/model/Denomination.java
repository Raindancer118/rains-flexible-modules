package de.raindancer.modules.economy.model;

import de.raindancer.core.social.economy.Currency;
import de.raindancer.core.social.economy.Money;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** One piece of cash this server issues: what it is worth, what it is made of, and whether it stacks. */
public record Denomination(Money value, Material material, Form form) {

    /**
     * {@code "100 paper note"}, {@code "1 gold_nugget coin"}. The form may be left out: coins then.
     * Empty for anything unreadable, worthless or not an item.
     */
    public static Optional<Denomination> parse(String line, Currency currency) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        String[] words = line.trim().split("\\s+");
        if (words.length < 2 || words.length > 3) {
            return Optional.empty();
        }
        Optional<Money> value = currency.parse(words[0]).filter(Money::isPositive);
        Material material = Material.matchMaterial(words[1]);
        if (value.isEmpty() || material == null || material.isAir()) {
            return Optional.empty();
        }
        Form form = Form.COIN;
        if (words.length == 3) {
            String typed = words[2].toUpperCase(Locale.ROOT);
            if (typed.equals("NOTE") || typed.equals("BILL") || typed.equals("BANKNOTE")) {
                form = Form.NOTE;
            } else if (!typed.equals("COIN")) {
                return Optional.empty();
            }
        }
        return Optional.of(new Denomination(value.get(), material, form));
    }

    /** Every readable line, largest value first, one per value. */
    public static List<Denomination> parseAll(List<String> lines, Currency currency) {
        List<Denomination> read = new ArrayList<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            parse(line, currency).ifPresent(found -> {
                if (read.stream().noneMatch(each -> each.value().equals(found.value()))) {
                    read.add(found);
                }
            });
        }
        read.sort(Comparator.comparing(Denomination::value).reversed());
        return List.copyOf(read);
    }
}
