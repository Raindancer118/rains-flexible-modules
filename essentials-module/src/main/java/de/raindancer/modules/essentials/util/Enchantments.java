package de.raindancer.modules.essentials.util;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Enchantments by the name somebody types, from Paper's registry — so another plugin's, registered by a
 * datapack or at bootstrap, is as findable as vanilla's.
 */
public final class Enchantments {

    private static final String VANILLA = "minecraft:";

    private Enchantments() {
    }

    /** {@code "minecraft:Fire Aspect"} → {@code "fire_aspect"}; another namespace is kept. */
    public static String normalise(String typed) {
        String key = typed == null ? "" : typed.strip().toLowerCase(Locale.ROOT).replace(' ', '_');
        return key.startsWith(VANILLA) ? key.substring(VANILLA.length()) : key;
    }

    /** {@code "fire_aspect"} → {@code "Fire Aspect"}. */
    public static String readable(String key) {
        String name = normalise(key);
        int colon = name.indexOf(':');
        String path = colon < 0 ? name : name.substring(colon + 1);
        StringBuilder words = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!words.isEmpty()) {
                words.append(' ');
            }
            words.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return words.toString();
    }

    /** "Sharpness 255". The client has no translation for a level above ten, so this is spelled out. */
    public static String line(String key, int level) {
        return readable(key) + " " + level;
    }

    public static String line(Enchantment enchantment, int level) {
        return line(keyOf(enchantment), level);
    }

    public static Registry<Enchantment> registry() {
        return RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
    }

    /** The key as it is typed: vanilla without its prefix, anything else with its namespace. */
    public static String keyOf(Enchantment enchantment) {
        return normalise(enchantment.getKey().asString());
    }

    public static String readable(Enchantment enchantment) {
        return readable(keyOf(enchantment));
    }

    public static Optional<Enchantment> find(String typed) {
        String key = normalise(typed);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        NamespacedKey wanted = key.contains(":") ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key);
        return wanted == null ? Optional.empty() : Optional.ofNullable(registry().get(wanted));
    }

    /** Every enchantment the server knows, by key. */
    public static List<Enchantment> all() {
        List<Enchantment> found = new ArrayList<>();
        registry().forEach(found::add);
        found.sort(Comparator.comparing(Enchantments::keyOf));
        return found;
    }

    public static List<String> keys() {
        return all().stream().map(Enchantments::keyOf).toList();
    }
}
