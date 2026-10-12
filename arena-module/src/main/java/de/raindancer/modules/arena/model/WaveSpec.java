package de.raindancer.modules.arena.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * How a mob-wave run goes.
 *
 * @param waves     how many waves to clear, 0 for endless
 * @param selection which mobs a wave is made of
 * @param mobs      the mob types the selection draws from — entity type names
 * @param size      creatures in the first wave
 * @param growth    creatures each later wave adds
 */
public record WaveSpec(String preset, int waves, Selection selection, List<String> mobs, int size, int growth) {

    public static final int MOST_WAVES = 50;
    public static final int MOST_SIZE = 30;

    public enum Selection {
        /** Each creature drawn at random from the list. */
        RANDOM,
        /** Wave n is the n-th type of the list, round and round — one kind of mob per wave. */
        CYCLE,
        /** Only the first type of the list. */
        ONE
    }

    /** What a mob may be: hostile, fair, and not a world-ending boss. */
    public static final List<String> ALLOWED_MOBS = List.of(
            "ZOMBIE", "HUSK", "DROWNED", "SKELETON", "STRAY", "BOGGED", "SPIDER", "CAVE_SPIDER", "CREEPER", "WITCH",
            "SLIME", "MAGMA_CUBE", "BLAZE", "WITHER_SKELETON", "PIGLIN_BRUTE", "ZOMBIFIED_PIGLIN", "HOGLIN", "ZOGLIN",
            "PILLAGER", "VINDICATOR", "EVOKER", "RAVAGER", "VEX", "ENDERMAN", "SILVERFISH", "ENDERMITE", "PHANTOM",
            "GUARDIAN", "BREEZE", "IRON_GOLEM", "WOLF", "POLAR_BEAR", "CREAKING");

    public WaveSpec {
        preset = preset == null || preset.isBlank() ? "custom" : preset.toLowerCase(Locale.ROOT);
        waves = Math.max(0, Math.min(MOST_WAVES, waves));
        selection = selection == null ? Selection.RANDOM : selection;
        mobs = mobs == null ? List.of() : mobs.stream().map(mob -> mob.toUpperCase(Locale.ROOT))
                .filter(ALLOWED_MOBS::contains).distinct().toList();
        if (mobs.isEmpty()) {
            mobs = List.of("ZOMBIE", "SKELETON");
        }
        size = Math.max(1, Math.min(MOST_SIZE, size));
        growth = Math.max(0, Math.min(10, growth));
    }

    public boolean isEndless() {
        return waves == 0;
    }

    public static final Map<String, WaveSpec> PRESETS = presets();

    private static Map<String, WaveSpec> presets() {
        Map<String, WaveSpec> presets = new LinkedHashMap<>();
        presets.put("warmup", new WaveSpec("warmup", 5, Selection.RANDOM, List.of("ZOMBIE", "SKELETON"), 3, 1));
        presets.put("undead", new WaveSpec("undead", 10, Selection.RANDOM,
                List.of("ZOMBIE", "HUSK", "SKELETON", "STRAY", "DROWNED", "WITHER_SKELETON"), 4, 2));
        presets.put("nether", new WaveSpec("nether", 8, Selection.RANDOM,
                List.of("BLAZE", "WITHER_SKELETON", "PIGLIN_BRUTE", "MAGMA_CUBE", "HOGLIN"), 3, 1));
        presets.put("illagers", new WaveSpec("illagers", 8, Selection.CYCLE,
                List.of("PILLAGER", "VINDICATOR", "EVOKER", "RAVAGER"), 3, 1));
        presets.put("bossrush", new WaveSpec("bossrush", 6, Selection.CYCLE,
                List.of("RAVAGER", "IRON_GOLEM", "EVOKER", "PIGLIN_BRUTE", "BREEZE", "WITHER_SKELETON"), 1, 1));
        presets.put("endless", new WaveSpec("endless", 0, Selection.RANDOM,
                List.of("ZOMBIE", "SKELETON", "SPIDER", "CREEPER", "WITCH", "PILLAGER", "VINDICATOR", "BLAZE"), 3, 1));
        return Map.copyOf(presets);
    }

    public static Optional<WaveSpec> preset(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(PRESETS.get(name.toLowerCase(Locale.ROOT)));
    }

    public WaveSpec withWaves(int value) {
        return new WaveSpec("custom", value, selection, mobs, size, growth);
    }

    public WaveSpec withSelection(Selection value) {
        return new WaveSpec("custom", waves, value, mobs, size, growth);
    }

    public WaveSpec withMobs(List<String> value) {
        return new WaveSpec("custom", waves, selection, value, size, growth);
    }

    public WaveSpec withSize(int value) {
        return new WaveSpec("custom", waves, selection, mobs, value, growth);
    }

    public WaveSpec withGrowth(int value) {
        return new WaveSpec("custom", waves, selection, mobs, size, value);
    }
}
