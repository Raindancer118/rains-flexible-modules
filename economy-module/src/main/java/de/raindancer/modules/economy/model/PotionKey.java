package de.raindancer.modules.economy.model;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One kind of bottle holding one thing, as the shop prices it: {@code POTION:STRONG_HEALING},
 * {@code SPLASH_POTION:LONG_SWIFTNESS}, {@code LINGERING_POTION:WEAKNESS}, {@code OMINOUS_BOTTLE:3}. The same name
 * in the price book, in supply and demand and in the custom price lists ({@code potion:healing 120}) — as an
 * enchanted book's key is. Every potion looks like every other to Bukkit's {@code Material}, so what is inside is
 * part of the name.
 *
 * @param item one of {@link #ITEMS}
 * @param type the potion type's key, lower case, without {@code minecraft:}; for an ominous bottle its level, 1 to 5
 *             (Bad Omen I to V — the bottle's amplifier plus one)
 */
public record PotionKey(String item, String type) {

    public static final String POTION = "POTION";
    public static final String SPLASH = "SPLASH_POTION";
    public static final String LINGERING = "LINGERING_POTION";
    public static final String OMINOUS = "OMINOUS_BOTTLE";
    /** An arrow tipped in a lingering potion — the same potion data on an arrow. */
    public static final String TIPPED = "TIPPED_ARROW";

    public static final Set<String> ITEMS = Set.of(POTION, SPLASH, LINGERING, OMINOUS, TIPPED);

    public static final int MOST_OMINOUS = 5;

    /** The few effects whose name is not just the type's words. */
    private static final Map<String, String> EFFECTS = Map.of(
            "turtle_master", "the Turtle Master",
            "wind_charged", "Wind Charging",
            "infested", "Infestation");

    public PotionKey {
        item = item.toUpperCase(Locale.ROOT);
        type = type.toLowerCase(Locale.ROOT);
        if (type.startsWith("minecraft:")) {
            type = type.substring("minecraft:".length());
        }
        if (!ITEMS.contains(item)) {
            throw new IllegalArgumentException("not a bottle: " + item);
        }
    }

    public static PotionKey ominous(int level) {
        return new PotionKey(OMINOUS, String.valueOf(level));
    }

    public boolean isOminous() {
        return item.equals(OMINOUS);
    }

    public boolean isArrow() {
        return item.equals(TIPPED);
    }

    /** An ominous bottle's level, 1 to 5; zero for any other bottle. */
    public int level() {
        return isOminous() ? Integer.parseInt(type) : 0;
    }

    public String name() {
        return item + ":" + type.toUpperCase(Locale.ROOT);
    }

    public static boolean isKey(String name) {
        return parse(name).isPresent();
    }

    public static Optional<PotionKey> parse(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String upper = name.strip().toUpperCase(Locale.ROOT);
        for (String item : ITEMS) {
            if (!upper.startsWith(item + ":")) {
                continue;
            }
            String rest = upper.substring(item.length() + 1);
            if (rest.isBlank()) {
                return Optional.empty();
            }
            if (item.equals(OMINOUS)) {
                try {
                    int level = Integer.parseInt(rest);
                    return level < 1 || level > MOST_OMINOUS ? Optional.empty() : Optional.of(ominous(level));
                } catch (NumberFormatException notALevel) {
                    return Optional.empty();
                }
            }
            return Optional.of(new PotionKey(item, rest));
        }
        return Optional.empty();
    }

    /** For people: {@code Splash Potion of Healing II}. */
    public String readable() {
        if (isOminous()) {
            return "Ominous Bottle (Bad Omen " + roman(level()) + ")";
        }
        String prefix = item.equals(SPLASH) ? "Splash " : item.equals(LINGERING) ? "Lingering " : "";
        if (isArrow()) {
            return new PotionKey(POTION, type).readable().replaceFirst("^Potion of ", "Arrow of ");
        }
        String path = type.substring(type.lastIndexOf(':') + 1);
        if (path.equals("water")) {
            return prefix + "Water Bottle";
        }
        if (path.equals("awkward") || path.equals("mundane") || path.equals("thick")) {
            return prefix + words(path) + " Potion";
        }
        String suffix = "";
        if (path.startsWith("long_")) {
            path = path.substring("long_".length());
            suffix = " (long)";
        } else if (path.startsWith("strong_")) {
            path = path.substring("strong_".length());
            suffix = " II";
        }
        return prefix + "Potion of " + EFFECTS.getOrDefault(path, words(path)) + suffix;
    }

    private static String words(String path) {
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (!word.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
            }
        }
        return out.toString();
    }

    private static String roman(int number) {
        return new String[]{"", "I", "II", "III", "IV", "V"}[Math.max(0, Math.min(MOST_OMINOUS, number))];
    }

    @Override
    public String toString() {
        return name();
    }
}
