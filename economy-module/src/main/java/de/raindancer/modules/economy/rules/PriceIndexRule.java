package de.raindancer.modules.economy.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The basket of goods the server's prices are measured with, and how far they have moved. */
public final class PriceIndexRule implements IEconomyRule {

    /** {@code 'bread 16'} lines as items and counts; lines that cannot be read are left out. */
    public List<Map.Entry<String, Integer>> basket(List<String> lines) {
        List<Map.Entry<String, Integer>> basket = new ArrayList<>();
        for (String line : lines == null ? List.<String>of() : lines) {
            String[] words = line == null ? new String[0] : line.strip().split("\\s+");
            if (words.length != 2) {
                continue;
            }
            try {
                int count = Integer.parseInt(words[1]);
                if (count > 0) {
                    basket.add(Map.entry(words[0].toUpperCase(Locale.ROOT), count));
                }
            } catch (NumberFormatException notANumber) {
                // left out, as documented
            }
        }
        return basket;
    }

    /** Today's basket over the first day it was priced at all; 1.0 without both. */
    public double level(Map<Long, Long> basketByDay, long today) {
        Long now = basketByDay.get(today);
        if (now == null || now <= 0) {
            return 1.0;
        }
        long first = basketByDay.entrySet().stream().filter(day -> day.getValue() > 0)
                .mapToLong(Map.Entry::getKey).min().orElse(today);
        return (double) now / basketByDay.get(first);
    }

    @Override
    public String describe() {
        return "the basket the server's prices are measured with";
    }
}
