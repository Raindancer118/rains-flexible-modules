package de.raindancer.modules.hungergames.store;

import de.raindancer.core.content.loot.LootEntry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reading an old plugin's {@code loot.yml} — the tables it names, not the ones this module ships.
 *
 * <h2>Why this exists, when {@link LootDefaults} already has six tables</h2>
 * Because {@link LootDefaults}' six are the shipped default, and a server upgrading from the old plugin
 * has its own — tuned over its own tournaments, not the ones this port happened to inherit. A gamemaster
 * who spent evenings weighting a cornucopia table does not want that overwritten by whatever this module
 * decided was sensible; they want it read back. {@code LootCatalogue.defineIfAbsent} already protects a
 * table that has been defined once, so this class's whole job is to get that server's own tables defined
 * <em>before</em> the shipped defaults get a chance to fill the gap — the same order
 * {@code LegacyConfigImport} keeps for settings.
 *
 * <h2>What it will not do</h2>
 * <ul>
 *   <li><b>It never throws on a bad file.</b> A {@code loot.yml} that is not valid YAML, or that names a
 *       material this server does not have, or that writes an amount neither parser can make sense of, is
 *       reported — never a crash that stops the module loading the rest of a server's configuration.</li>
 *   <li><b>It never guesses at what a broken entry meant.</b> An unparsable amount becomes exactly one,
 *       the smallest lie a range can tell, and is named in the report; an unresolvable material is left
 *       out of the pool entirely rather than replaced with something plausible.</li>
 *   <li><b>It does not touch the old file, and it does not define anything itself.</b> Like
 *       {@link LegacyConfigImport}, this only reads and reports; the caller decides whether and how the
 *       tables it found are defined — through {@link LootCatalogue}, once a server has one.</li>
 * </ul>
 *
 * <h2>Why it discovers table names rather than expecting exactly six</h2>
 * {@link LootDefaults} ships six because that is what the live server had, but an old file is not
 * guaranteed to have the same six — a fork might have added a seventh, or never used the shelf at all.
 * Every list under {@code loot.*} other than {@code loot.fill-percentage} is read as a table named after
 * its key, so a server with its own extra table keeps it rather than having it silently dropped for not
 * matching a fixed list of expected names.
 */
public final class LegacyLootImport {

    /**
     * What the import found, table by table.
     *
     * @param tablesFound  the short names of every table the file defined a pool for
     * @param totalEntries how many entries were read across all of them, after unresolvable ones were
     *                     dropped
     * @param problems     everything that went wrong along the way — an unresolvable material, an
     *                     unparsable amount, or the file itself being unreadable
     */
    public record Report(List<String> tablesFound, int totalEntries, List<String> problems) {

        public Report {
            tablesFound = List.copyOf(tablesFound);
            problems = List.copyOf(problems);
        }

        public boolean hasProblems() {
            return !problems.isEmpty();
        }

        /** The whole thing as lines somebody can read, worst last — see {@code LegacyConfigImport.Report}. */
        public List<String> lines() {
            List<String> said = new ArrayList<>();
            said.add(tablesFound.size() + " loot table(s) found: " + String.join(", ", tablesFound)
                    + " (" + totalEntries + " entrie(s) in total).");
            problems.forEach(problem -> said.add("  ! " + problem));
            return List.copyOf(said);
        }
    }

    /** The tables an old file held, and the report of how reading them went. */
    public record Imported(Report report, Map<String, LootDefaults.Table> tables) {

        public Imported {
            tables = Map.copyOf(tables);
        }
    }

    /** The key every table's fill percentage sits under, e.g. {@code loot.fill-percentage.chest}. */
    private static final String FILL_PERCENTAGE_KEY = "fill-percentage";

    /** What a table is filled to when the old file does not say — the live server's own chest used 40%. */
    private static final int DEFAULT_FILL_PERCENT = 40;

    /** What an entry rolls when the old file does not give it a weight at all. */
    private static final int DEFAULT_WEIGHT = 1;

    private LegacyLootImport() {
    }

    /**
     * Reads an old {@code loot.yml} and returns every table it names.
     *
     * <p>Nothing is defined here — see the class javadoc — so a caller who only wants to know what is in
     * the file, without touching {@link LootCatalogue} at all, can call this too.
     */
    public static Imported from(Path oldLootFile) {
        List<String> problems = new ArrayList<>();
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(oldLootFile, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return new Imported(new Report(List.of(), 0,
                    List.of(oldLootFile.getFileName() + " could not be read (" + unreadable.getMessage()
                            + "). Nothing was imported.")),
                    Map.of());
        } catch (org.bukkit.configuration.InvalidConfigurationException | RuntimeException notYaml) {
            return new Imported(new Report(List.of(), 0,
                    List.of(oldLootFile.getFileName() + " is not valid YAML (" + notYaml.getMessage()
                            + "). Nothing was imported.")),
                    Map.of());
        }

        ConfigurationSection loot = yaml.getConfigurationSection("loot");
        if (loot == null) {
            return new Imported(new Report(List.of(), 0, List.of()), Map.of());
        }

        Map<String, LootDefaults.Table> found = new LinkedHashMap<>();
        int totalEntries = 0;
        for (String key : loot.getKeys(false)) {
            if (key.equals(FILL_PERCENTAGE_KEY)) {
                continue;   // read per-table below, not as a table of its own
            }
            List<?> rawEntries = loot.getList(key);
            if (rawEntries == null) {
                continue;   // not a list under loot.* — nothing this import understands how to read
            }
            int fillPercent = loot.getInt(FILL_PERCENTAGE_KEY + "." + key, DEFAULT_FILL_PERCENT);
            List<LootEntry> entries = readEntries(rawEntries, key, problems);
            found.put(key, new LootDefaults.Table(key, tierFor(key), fillPercent, entries));
            totalEntries += entries.size();
        }

        Report report = new Report(List.copyOf(found.keySet()), totalEntries, problems);
        return new Imported(report, found);
    }

    /** The tier a table would have shipped with, so an owner's own {@code chest} still reads as tier 1. */
    private static int tierFor(String tableName) {
        LootDefaults.Table shipped = LootDefaults.all().get(tableName);
        return shipped != null ? shipped.tier() : 1;
    }

    private static List<LootEntry> readEntries(List<?> rawEntries, String tableName, List<String> problems) {
        List<LootEntry> entries = new ArrayList<>();
        for (Object rawEntry : rawEntries) {
            if (!(rawEntry instanceof Map<?, ?> map)) {
                problems.add(tableName + ": an entry that is not a map was skipped.");
                continue;
            }
            Object itemName = map.get("item");
            if (!(itemName instanceof String name) || name.isBlank()) {
                problems.add(tableName + ": an entry with no 'item' was skipped.");
                continue;
            }

            int weight = intOf(map.get("weight"), DEFAULT_WEIGHT);
            int[] range = amountOf(map.get("amount"), tableName, name, problems);

            boolean custom = Boolean.TRUE.equals(map.get("custom"));
            LootEntry entry = custom
                    ? LootEntry.ofCustomItem(name, weight).amount(range[0], range[1])
                    : materialEntry(name, weight, range, tableName, problems);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private static LootEntry materialEntry(String name, int weight, int[] range, String tableName,
                                           List<String> problems) {
        try {
            Material material = Material.valueOf(name.toUpperCase(java.util.Locale.ROOT));
            return LootEntry.of(material, weight).amount(range[0], range[1]);
        } catch (IllegalArgumentException noSuchMaterial) {
            problems.add(tableName + ": '" + name + "' is not a material on this server; that entry "
                    + "was skipped.");
            return null;
        }
    }

    /**
     * The amount an entry gives — {@code 5} means exactly five, {@code "1-5"} means a range, and anything
     * else defaults to exactly one and is reported rather than guessed at.
     *
     * @return a two-element {@code {least, most}} pair
     */
    private static int[] amountOf(Object raw, String tableName, String itemName, List<String> problems) {
        if (raw == null) {
            return new int[] {1, 1};
        }
        if (raw instanceof Number number) {
            int exact = Math.max(1, number.intValue());
            return new int[] {exact, exact};
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            int dash = trimmed.indexOf('-');
            if (dash > 0) {
                try {
                    int least = Integer.parseInt(trimmed.substring(0, dash).trim());
                    int most = Integer.parseInt(trimmed.substring(dash + 1).trim());
                    return new int[] {least, most};
                } catch (NumberFormatException notARange) {
                    // falls through to the unparsable-amount report below
                }
            } else {
                try {
                    int exact = Math.max(1, Integer.parseInt(trimmed));
                    return new int[] {exact, exact};
                } catch (NumberFormatException notANumber) {
                    // falls through to the unparsable-amount report below
                }
            }
        }
        problems.add(tableName + ": '" + itemName + "' has an amount ('" + raw + "') neither parser could "
                + "read; it was given exactly 1.");
        return new int[] {1, 1};
    }

    private static int intOf(Object raw, int fallback) {
        return raw instanceof Number number ? number.intValue() : fallback;
    }
}
