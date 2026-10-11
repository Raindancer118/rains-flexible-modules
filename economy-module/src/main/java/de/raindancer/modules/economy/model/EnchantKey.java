package de.raindancer.modules.economy.model;

import java.util.Locale;
import java.util.Optional;

/**
 * One enchantment at one level, as the shop prices it: {@code ENCHANTED_BOOK:MENDING:1}. The same name in
 * the price book, in supply and demand, in the custom price lists ({@code enchanted_book:mending:1 5000})
 * and in the closed lists. A datapack's enchantment keeps its namespace: {@code ENCHANTED_BOOK:MYPACK:FOO:2}.
 *
 * @param enchantment the enchantment's key, lower case, without {@code minecraft:}
 */
public record EnchantKey(String enchantment, int level) {

    public static final String PREFIX = "ENCHANTED_BOOK:";

    public EnchantKey {
        enchantment = enchantment.toLowerCase(Locale.ROOT);
        if (enchantment.startsWith("minecraft:")) {
            enchantment = enchantment.substring("minecraft:".length());
        }
    }

    public String name() {
        return PREFIX + enchantment.toUpperCase(Locale.ROOT) + ":" + level;
    }

    public static boolean isKey(String name) {
        return parse(name).isPresent();
    }

    public static Optional<EnchantKey> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String upper = name.strip().toUpperCase(Locale.ROOT);
        if (!upper.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = upper.substring(PREFIX.length());
        int colon = rest.lastIndexOf(':');
        if (colon <= 0) {
            return Optional.empty();
        }
        try {
            int level = Integer.parseInt(rest.substring(colon + 1));
            return level < 1 ? Optional.empty() : Optional.of(new EnchantKey(rest.substring(0, colon), level));
        } catch (NumberFormatException notALevel) {
            return Optional.empty();
        }
    }

    /** For people: {@code Sharpness V}. */
    public String readable() {
        String path = enchantment.substring(enchantment.lastIndexOf(':') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            boolean small = !out.isEmpty() && (word.equals("of") || word.equals("the"));
            out.append(out.isEmpty() ? "" : " ").append(small ? word
                    : Character.toUpperCase(word.charAt(0)) + word.substring(1));
        }
        return out + " " + roman(level);
    }

    private static String roman(int number) {
        if (number < 1 || number > 39) {
            return String.valueOf(number);
        }
        String[] tens = {"", "X", "XX", "XXX"};
        String[] ones = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX"};
        return tens[number / 10] + ones[number % 10];
    }

    @Override
    public String toString() {
        return name();
    }
}
