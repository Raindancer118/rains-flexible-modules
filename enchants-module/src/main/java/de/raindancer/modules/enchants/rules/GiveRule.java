package de.raindancer.modules.enchants.rules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The names {@code /give} knows Rain's enchantments by: {@code <name>_shard}, {@code <name>_book} (highest level)
 * and {@code <name>_book_<level>}, each with or without {@code rainsenchants:}. Everything else is the game's.
 */
public final class GiveRule {

    private static final String NAMESPACE = "rainsenchants:";
    private static final String SHARD = "_shard";
    private static final String BOOK = "_book";

    /** @param level 0 for a shard, else the book's level */
    public record Request(String enchantment, int level) {
        public boolean shard() {
            return level == 0;
        }
    }

    private final Map<String, Integer> maxLevels;

    /** @param maxLevels every enchantment's full key to its highest level */
    public GiveRule(Map<String, Integer> maxLevels) {
        this.maxLevels = new TreeMap<>(maxLevels);
    }

    public Optional<Request> parse(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String name = typed.strip().toLowerCase(Locale.ROOT);
        if (name.startsWith(NAMESPACE)) {
            name = name.substring(NAMESPACE.length());
        } else if (name.contains(":")) {
            return Optional.empty();
        }
        if (name.endsWith(SHARD)) {
            String key = NAMESPACE + name.substring(0, name.length() - SHARD.length());
            return maxLevels.containsKey(key) ? Optional.of(new Request(key, 0)) : Optional.empty();
        }
        int book = name.lastIndexOf(BOOK);
        if (book <= 0) {
            return Optional.empty();
        }
        String key = NAMESPACE + name.substring(0, book);
        Integer max = maxLevels.get(key);
        if (max == null) {
            return Optional.empty();
        }
        String rest = name.substring(book + BOOK.length());
        if (rest.isEmpty()) {
            return Optional.of(new Request(key, max));
        }
        if (!rest.startsWith("_")) {
            return Optional.empty();
        }
        try {
            int level = Integer.parseInt(rest.substring(1));
            return level >= 1 && level <= max ? Optional.of(new Request(key, level)) : Optional.empty();
        } catch (NumberFormatException notALevel) {
            return Optional.empty();
        }
    }

    public List<String> names() {
        List<String> names = new ArrayList<>();
        maxLevels.forEach((key, max) -> {
            names.add(key + SHARD);
            names.add(key + BOOK);
            for (int level = 1; level <= max; level++) {
                names.add(key + BOOK + "_" + level);
            }
        });
        return names;
    }
}
