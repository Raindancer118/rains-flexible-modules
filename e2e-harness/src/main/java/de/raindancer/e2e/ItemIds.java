package de.raindancer.e2e;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * What the item ids on the wire are called — {@code 1063} is {@code COMPASS} — straight from the game:
 * the vanilla server of the same version writes its registries out ({@code --reports}), once, into the
 * cache. Never a table copied by hand that is right for one version only.
 */
public final class ItemIds {

    private static volatile Map<Integer, String> names;

    private ItemIds() {
    }

    /** The item's name as Bukkit spells it — {@code COMPASS}, {@code LIME_CONCRETE}. */
    public static String name(int id, String version) {
        return table(version).getOrDefault(id, "UNKNOWN_" + id);
    }

    /** The id of a Bukkit-spelled item name, for asking "is there a compass". */
    public static int id(String material, String version) {
        String wanted = material.toUpperCase(Locale.ROOT);
        return table(version).entrySet().stream().filter(entry -> entry.getValue().equals(wanted))
                .map(Map.Entry::getKey).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no item " + material));
    }

    private static Map<Integer, String> table(String version) {
        Map<Integer, String> known = names;
        if (known != null) {
            return known;
        }
        synchronized (ItemIds.class) {
            if (names == null) {
                names = read(version);
            }
            return names;
        }
    }

    private static Map<Integer, String> read(String version) {
        Path reports = Downloads.cache().resolve("reports-" + version);
        Path registries = reports.resolve("reports").resolve("registries.json");
        try {
            if (!Files.exists(registries)) {
                Path server = Downloads.vanillaServer(version);
                Process generator = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-DbundlerMainClass=net.minecraft.data.Main", "-jar", server.toString(),
                        "--reports", "--output", reports.toString())
                        .directory(Downloads.cache().toFile()).redirectErrorStream(true)
                        .redirectOutput(Downloads.cache().resolve("reports-" + version + ".log").toFile()).start();
                if (!generator.waitFor(5, TimeUnit.MINUTES) || generator.exitValue() != 0 || !Files.exists(registries)) {
                    throw new IllegalStateException("the vanilla server could not write its reports");
                }
            }
            JsonObject items = JsonParser.parseString(Files.readString(registries)).getAsJsonObject()
                    .getAsJsonObject("minecraft:item").getAsJsonObject("entries");
            Map<Integer, String> table = new HashMap<>();
            for (String key : items.keySet()) {
                table.put(items.getAsJsonObject(key).get("protocol_id").getAsInt(),
                        key.substring(key.indexOf(':') + 1).toUpperCase(Locale.ROOT));
            }
            return Map.copyOf(table);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    /** Every item name the game has — for a test that checks its own spelling. */
    public static List<String> all(String version) {
        return table(version).values().stream().sorted().toList();
    }
}
