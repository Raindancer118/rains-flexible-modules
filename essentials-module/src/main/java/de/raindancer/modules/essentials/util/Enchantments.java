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
        List<NamespacedKey> known = new ArrayList<>();
        registry().forEach(enchantment -> known.add(enchantment.getKey()));
        return resolve(typed, known).map(key -> registry().get(key));
    }

    /**
     * Which key a typed name means: a namespaced one as it is, a bare one as vanilla's — or, with no vanilla
     * enchantment by that name, the one plugin enchantment that has it ({@code telekinesis}). Two plugins
     * sharing a name have to be told apart by namespace.
     */
    static Optional<NamespacedKey> resolve(String typed, java.util.Collection<NamespacedKey> known) {
        String key = normalise(typed);
        if (key.isEmpty()) {
            return Optional.empty();
        }
        if (key.contains(":")) {
            NamespacedKey wanted = NamespacedKey.fromString(key);
            return wanted != null && known.contains(wanted) ? Optional.of(wanted) : Optional.empty();
        }
        NamespacedKey vanilla = NamespacedKey.fromString(VANILLA + key);
        if (vanilla != null && known.contains(vanilla)) {
            return Optional.of(vanilla);
        }
        List<NamespacedKey> named = known.stream().filter(each -> each.getKey().equals(key)).toList();
        return named.size() == 1 ? Optional.of(named.get(0)) : Optional.empty();
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
