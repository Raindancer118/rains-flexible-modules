package de.raindancer.modules.hungergames.store;

import de.raindancer.core.content.loot.LootEntry;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The six loot tables the live tournament server was actually running, written as code.
 *
 * <h2>Why this exists, and why it is not the empty list the module shipped with</h2>
 * {@code HungerGamesWiring.defineTheLootTables} used to call {@code defineIfAbsent} with an empty entry
 * list for two tables called {@code arena} and {@code supply-drop} — which is a loot table in name only.
 * Every chest, barrel and shelf in the arena came out empty, because nothing was ever there to roll
 * against. This class is what should have shipped instead: the six pools a real tournament was tuned on,
 * lifted verbatim from the server's own {@code loot.yml} rather than invented from scratch. Guessing at
 * plausible weights would have been indistinguishable from the empty list on day one and wrong forever
 * after — nobody would notice a made-up pool is wrong until a hundred people had already looted it.
 *
 * <h2>Why six tables, not one</h2>
 * The plugin this module replaces filled every container from a single pool, which is the one thing this
 * source file exists to stop repeating (see {@code HungerGamesWiring.fillTheArena}). A cornucopia's
 * trapped chest and a starter chest at the rim of the arena are not the same risk, and should not hold the
 * same odds of a diamond sword. Six tables is how the live server told that story to its own players —
 * fill percentage and pool both differ per container type, so a common chest tops out at leather armour
 * and stone tools while the cornucopia can hand out a totem of undying.
 *
 * <h2>Tiers, and why they are not read from the live file</h2>
 * {@code loot.yml} has no notion of tier — that concept belongs to
 * {@link de.raindancer.core.content.loot.LootTable}, added after this plugin's original design, and it is
 * metadata rather than something the roll or the fill percentage consults. The assignment here is a
 * judgement call, made once, in one place:
 * <ul>
 *   <li>{@code chest} — tier 1. The plainest pool: wooden and stone tools, leather armour, bread. This is
 *       the tier everything else is measured against.</li>
 *   <li>{@code barrel} — tier 1. Building and survival supplies rather than combat gear; no richer than a
 *       common chest, only differently useful.</li>
 *   <li>{@code copper-chest} — tier 2. Chainmail and early iron gear, plus the first two custom items
 *       (Krückauwasser, Smoke Bomb) — a deliberate step up from the starting chest.</li>
 *   <li>{@code trapped-chest} — tier 3, alongside {@code shelf}. The cornucopia's own table: diamond gear,
 *       a totem of undying and a trident, the best a tribute can find without a supply drop.</li>
 *   <li>{@code shelf} — tier 3. Three visible slots holding diamonds, emeralds and a golden apple —
 *       compact, but as valuable per slot as the cornucopia, which is why it shares the trapped chest's
 *       tier rather than sitting with the chest and barrel.</li>
 *   <li>{@code supply-drop} — tier 4. The Capitol's own delivery, richer again than the cornucopia: a
 *       totem of undying alongside diamond gear and a stack of experience bottles, dropped rather than
 *       found.</li>
 * </ul>
 *
 * <h2>Why {@code supply-drop} rather than the file's {@code supply_drop}</h2>
 * The live file spells it with an underscore because that is what the old plugin's config style used
 * throughout {@code loot.fill-percentage.*}. Every other table in {@link LootCatalogue} is named with a
 * hyphen — {@code copper-chest}, {@code trapped-chest} — and {@code HungerGamesWiring.SUPPLY_LOOT} already
 * says {@code "supply-drop"}. Keeping the underscore here would have given the catalogue two conventions
 * for the same kind of name; the entries moved, the spelling was normalised to match its neighbours.
 *
 * <h2>Why the materials are looked up by name rather than written as {@code Material.X} literals</h2>
 * A literal would compile against whatever {@code Material} this build happens to have and could never
 * fail — which is exactly the wrong shape for a table copied out of a file. The live file is not this
 * server's proof that every name is good; it is a tournament's tuning, ported once, that has to keep
 * working across whatever Bukkit this module is compiled against next. Looking names up the way
 * {@link LegacyLootImport} does — by string, through {@code Material.valueOf} — means a renamed or removed
 * vanilla item is skipped and reported rather than taking the module's start-up down with it, on this
 * build or a future one.
 */
public final class LootDefaults {

    /** One table, ready for {@code LootCatalogue.defineIfAbsent(name, tier, fillPercent, entries)}. */
    public record Table(String name, int tier, int fillPercent, List<LootEntry> entries) {

        public Table {
            entries = List.copyOf(entries);
        }
    }

    private static final List<String> PROBLEMS = new ArrayList<>();

    private static final Map<String, Table> ALL = buildAll();

    private LootDefaults() {
    }

    /** Every table this module ships, keyed by the short name {@link LootCatalogue} uses. */
    public static Map<String, Table> all() {
        return ALL;
    }

    /** Every material named here that this server's {@link Material} does not have, one line each. */
    public static List<String> problems() {
        return List.copyOf(PROBLEMS);
    }

    private static Map<String, Table> buildAll() {
        Map<String, Table> all = new LinkedHashMap<>();
        all.put("chest", chest());
        all.put("copper-chest", copperChest());
        all.put("trapped-chest", trappedChest());
        all.put("barrel", barrel());
        all.put("shelf", shelf());
        all.put("supply-drop", supplyDrop());
        return Map.copyOf(all);
    }

    // ==================== chest — tier 1, fill 40% ====================

    private static Table chest() {
        return new Table("chest", 1, 40, pool(
                item("WOODEN_SWORD", 25),
                item("STONE_SWORD", 15),
                item("WOODEN_AXE", 20),
                item("BOW", 10),
                item("LEATHER_HELMET", 20),
                item("LEATHER_CHESTPLATE", 15),
                item("LEATHER_LEGGINGS", 15),
                item("LEATHER_BOOTS", 20),
                item("BREAD", 30, 2, 4),
                item("APPLE", 25, 1, 3),
                item("COOKED_BEEF", 15, 1, 2),
                item("STICK", 30, 2, 8),
                item("FLINT", 20, 1, 3),
                item("STRING", 20, 1, 3),
                item("ARROW", 25, 3, 8),
                item("STONE", 20, 1, 101),
                item("OAK_PLANKS", 30, 1, 64)));
    }

    // ==================== copper chest — tier 2, fill 45% ====================

    private static Table copperChest() {
        return new Table("copper-chest", 2, 45, pool(
                item("STONE_SWORD", 20),
                item("IRON_SWORD", 8),
                item("STONE_AXE", 15),
                item("BOW", 15),
                item("CROSSBOW", 10),
                item("CHAINMAIL_HELMET", 12),
                item("CHAINMAIL_CHESTPLATE", 8),
                item("CHAINMAIL_LEGGINGS", 10),
                item("CHAINMAIL_BOOTS", 12),
                item("IRON_HELMET", 6),
                item("IRON_BOOTS", 6),
                item("GOLDEN_APPLE", 5),
                item("COOKED_BEEF", 20, 2, 4),
                item("COOKED_PORKCHOP", 18, 2, 3),
                item("IRON_INGOT", 15, 1, 3),
                item("ARROW", 25, 5, 12),
                item("ENDER_PEARL", 3),
                custom("hungergames:krueckauwasser", 10, 1, 10),
                custom("hungergames:smoke-bomb", 5, 1, 5),
                item("STONE", 10)));
    }

    // ==================== trapped chest — tier 3, fill 55% ====================
    // The cornucopia's own table, as rich as the shelf's and richer than everything else on the ground.

    private static Table trappedChest() {
        return new Table("trapped-chest", 3, 55, pool(
                item("IRON_SWORD", 15),
                item("DIAMOND_SWORD", 3),
                item("IRON_AXE", 12),
                item("DIAMOND_AXE", 2),
                item("BOW", 10),
                item("CROSSBOW", 8),
                item("TRIDENT", 1),
                item("IRON_HELMET", 10),
                item("IRON_CHESTPLATE", 8),
                item("IRON_LEGGINGS", 9),
                item("IRON_BOOTS", 10),
                item("DIAMOND_HELMET", 2),
                item("DIAMOND_CHESTPLATE", 1),
                item("DIAMOND_LEGGINGS", 1),
                item("DIAMOND_BOOTS", 2),
                item("GOLDEN_APPLE", 10, 1, 2),
                item("ENDER_PEARL", 8, 1, 2),
                item("TOTEM_OF_UNDYING", 1),
                item("DIAMOND", 3, 1, 2),
                item("ARROW", 20, 8, 16),
                custom("hungergames:fiendfinder", 2),
                custom("hungergames:stupidness-protector", 10),
                custom("hungergames:leap", 4, 1, 10),
                item("STONE", 10)));
    }

    // ==================== barrel — tier 1, fill 35% ====================

    private static Table barrel() {
        return new Table("barrel", 1, 35, pool(
                item("ARROW", 30, 8, 16),
                item("STICK", 25, 4, 12),
                item("FLINT", 20, 2, 5),
                item("STRING", 20, 2, 4),
                item("FEATHER", 20, 3, 8),
                item("BREAD", 25, 3, 6),
                item("DRIED_KELP", 20, 4, 8),
                item("BAKED_POTATO", 15, 2, 4),
                item("TORCH", 15, 4, 8),
                item("COAL", 18, 2, 4),
                item("COBBLESTONE", 15, 8, 16),
                custom("hungergames:repulse", 10, 1, 30)));
    }

    // ==================== shelf — tier 3, fill 80% ====================
    // Only three slots, so a high fill percentage is what makes a shelf worth breaking open at all —
    // and each of the three is drawn from a pool as valuable as the cornucopia's.

    private static Table shelf() {
        return new Table("shelf", 3, 80, pool(
                item("DIAMOND", 8, 1, 2),
                item("EMERALD", 10, 1, 3),
                item("GOLD_INGOT", 15, 1, 3),
                item("IRON_INGOT", 20, 2, 4),
                item("GOLDEN_APPLE", 5),
                item("ENDER_PEARL", 6),
                item("COMPASS", 10),
                item("CLOCK", 10),
                item("SPYGLASS", 8)));
    }

    // ==================== supply drop — tier 4, fill 45% ====================
    // The Capitol's own delivery. Named "supply-drop" here — see the class javadoc for why the file's
    // underscore did not survive the port.

    private static Table supplyDrop() {
        return new Table("supply-drop", 4, 45, pool(
                item("GOLDEN_APPLE", 20, 1, 2),
                item("DIAMOND", 15, 1, 3),
                item("DIAMOND_SWORD", 8),
                item("DIAMOND_CHESTPLATE", 6),
                item("ENDER_PEARL", 15, 1, 2),
                item("COOKED_BEEF", 25, 4, 8),
                item("ARROW", 20, 8, 16),
                item("BOW", 10),
                item("EXPERIENCE_BOTTLE", 15, 3, 6),
                item("TOTEM_OF_UNDYING", 1),
                custom("hungergames:fiendfinder", 5)));
    }

    // ==================== small builders ====================

    /**
     * A plain material entry, exactly one at a time — or {@code null} when this server's {@link Material}
     * has no such constant, in which case {@link #pool} drops it and the name is recorded.
     */
    private static LootEntry item(String materialName, int weight) {
        return material(materialName).map(material -> LootEntry.of(material, weight)).orElse(null);
    }

    /** A plain material entry, in a range — {@code "1-5"} in the live file becomes {@code (1, 5)} here. */
    private static LootEntry item(String materialName, int weight, int least, int most) {
        return material(materialName).map(material -> LootEntry.of(material, weight).amount(least, most))
                .orElse(null);
    }

    /** One of {@code core.items}' own definitions, by its short key, exactly one at a time. */
    private static LootEntry custom(String key, int weight) {
        return LootEntry.ofCustomItem(key, weight);
    }

    /** One of {@code core.items}' own definitions, by its short key, in a range. */
    private static LootEntry custom(String key, int weight, int least, int most) {
        return LootEntry.ofCustomItem(key, weight).amount(least, most);
    }

    /** This server's {@link Material} for a name out of the live file, or empty with a note in the log. */
    private static java.util.Optional<Material> material(String name) {
        try {
            return java.util.Optional.of(Material.valueOf(name));
        } catch (IllegalArgumentException noSuchMaterial) {
            PROBLEMS.add("'" + name + "' is not a material on this server; that entry was skipped.");
            return java.util.Optional.empty();
        }
    }

    /** Every entry a table is given, minus the ones {@link #item} could not resolve. */
    private static List<LootEntry> pool(LootEntry... entries) {
        List<LootEntry> kept = new ArrayList<>(entries.length);
        for (LootEntry entry : entries) {
            if (entry != null) {
                kept.add(entry);
            }
        }
        return List.copyOf(kept);
    }
}
