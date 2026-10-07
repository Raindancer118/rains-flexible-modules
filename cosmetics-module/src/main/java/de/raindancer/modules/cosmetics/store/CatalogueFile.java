package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.choose.ColorSwatches;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.model.PaletteColour;
import de.raindancer.modules.cosmetics.model.Preset;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The palette and the presets, in the same {@code config.yml} as the settings.
 *
 * <p>Sharing the file is safe both ways: Core's {@code SettingsStore} keeps keys its schema does not
 * know, and this only writes when both sections are missing — once, while enabling, before anybody can
 * reach {@code /settings}. A reload only reads. Same arrangement as names-module's {@code PaletteFile}.
 *
 * <p>Volatile because screens on region threads read it while {@code /cosmetics reload} replaces it.
 */
public final class CatalogueFile {

    private final YamlStore store;
    private volatile Catalogue current = Catalogue.EMPTY;

    public CatalogueFile(Path configFile) {
        this.store = new YamlStore(configFile);
    }

    public Catalogue current() {
        return current;
    }

    public Path file() {
        return store.file();
    }

    /**
     * Reads the file, writing the shipped palette and presets first if neither section is there.
     *
     * @param warn a line that cannot be read is reported here and skipped; the rest still loads
     */
    public Catalogue load(Consumer<String> warn) {
        YamlConfiguration yaml = store.read();
        for (String problem : store.problems()) {
            warn.accept("the file " + problem);
        }
        if (!yaml.contains("palette") && !yaml.contains("presets")) {
            if (writeDefaults()) {
                yaml = store.read();
            } else {
                warn.accept("the palette could not be written to " + store.file()
                        + ", so this boot uses the shipped one");
                current = new Catalogue(shippedPalette(), shippedPresets());
                return current;
            }
        }
        if (needsUpgrade(yaml)) {
            if (store.update(CatalogueFile::upgrade)) {
                yaml = store.read();
            } else {
                warn.accept("the new presets and colours could not be added to " + store.file()
                        + "; the file is read as it is");
            }
        }
        current = new Catalogue(palette(yaml.getConfigurationSection("palette"), warn),
                presets(yaml.getConfigurationSection("presets"), warn));
        return current;
    }

    // ------------------------------------------------------------------ newer versions' additions

    /** Where the file keeps what it has been offered, so something an owner deleted is not offered again. */
    private static final String PRESETS_OFFERED = "offered.presets";
    private static final String PALETTE_OFFERED = "offered.palette";

    private static boolean needsUpgrade(YamlConfiguration yaml) {
        return !missing(yaml, "presets", PRESETS_OFFERED, ORIGINAL_PRESETS, shippedPresetIds()).isEmpty()
                || !missing(yaml, "palette", PALETTE_OFFERED, originalPalette(),
                        List.copyOf(shippedPaletteEntries().keySet())).isEmpty();
    }

    /**
     * The shipped entries this file has never been offered. Before the file remembered, everything the first
     * versions shipped counts as offered — but only for a list built on ours: an owner who replaced it with
     * their own, or emptied it, is not sent ours.
     */
    private static List<String> missing(YamlConfiguration yaml, String section, String offeredPath,
                                        List<String> original, List<String> shipped) {
        ConfigurationSection present = yaml.getConfigurationSection(section);
        java.util.Set<String> have = present == null ? java.util.Set.of() : present.getKeys(false);
        List<String> offered;
        if (yaml.isList(offeredPath)) {
            offered = yaml.getStringList(offeredPath);
        } else if (have.stream().anyMatch(original::contains)) {
            offered = original;
        } else {
            return List.of();
        }
        return shipped.stream().filter(id -> !offered.contains(id) && !have.contains(id)).toList();
    }

    private static void upgrade(YamlConfiguration yaml) {
        List<String> presetIds = missing(yaml, "presets", PRESETS_OFFERED, ORIGINAL_PRESETS, shippedPresetIds());
        for (ShippedPreset preset : SHIPPED_PRESETS) {
            if (presetIds.contains(preset.id())) {
                writePreset(yaml, preset);
            }
        }
        List<String> colours = missing(yaml, "palette", PALETTE_OFFERED, originalPalette(),
                List.copyOf(shippedPaletteEntries().keySet()));
        shippedPaletteEntries().forEach((key, hex) -> {
            if (colours.contains(key)) {
                yaml.set("palette." + key, hex);
            }
        });
        remember(yaml);
    }

    /** Everything this version ships, marked as offered. */
    private static void remember(YamlConfiguration yaml) {
        yaml.set(PRESETS_OFFERED, shippedPresetIds());
        yaml.set(PALETTE_OFFERED, List.copyOf(shippedPaletteEntries().keySet()));
        yaml.setComments("offered", List.of("",
                "--- Kept by the plugin ---",
                "Which shipped presets and colours this file has been given, so one you delete stays deleted",
                "and a newer version only adds the ones that are new. Nothing to edit here."));
    }

    public static List<String> shippedPresetIds() {
        return SHIPPED_PRESETS.stream().map(ShippedPreset::id).toList();
    }

    // ------------------------------------------------------------------ reading

    private static List<PaletteColour> palette(ConfigurationSection section, Consumer<String> warn) {
        List<PaletteColour> palette = new ArrayList<>();
        if (section == null) {
            return palette;
        }
        for (String key : section.getKeys(false)) {
            TextColor colour = NameStyle.colourOf(section.getString(key, ""));
            if (colour == null) {
                warn.accept("palette colour '" + key + "' is '" + section.getString(key, "")
                        + "', which is not a colour. Skipped.");
                continue;
            }
            String label = key.replace('_', ' ').toLowerCase(Locale.ROOT);
            palette.add(new PaletteColour(label, colour, iconFor(key, colour)));
        }
        return palette;
    }

    private static List<Preset> presets(ConfigurationSection section, Consumer<String> warn) {
        List<Preset> presets = new ArrayList<>();
        if (section == null) {
            return presets;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(id);
            if (entry == null || !id.matches("[A-Za-z0-9_-]+")) {
                warn.accept("preset '" + id + "' is not a section with a plain id. Skipped.");
                continue;
            }
            NameStyle style = NameStyle.NONE;
            for (String typed : entry.getStringList("colours")) {
                TextColor colour = NameStyle.colourOf(typed);
                if (colour == null) {
                    warn.accept("preset '" + id + "': '" + typed + "' is not a colour. Left out.");
                    continue;
                }
                style = style.withStop(colour);
            }
            for (String typed : entry.getStringList("decorations")) {
                TextDecoration decoration = TextDecoration.NAMES.value(typed.toLowerCase(Locale.ROOT));
                if (decoration == null) {
                    warn.accept("preset '" + id + "': '" + typed + "' is not a decoration. Left out.");
                    continue;
                }
                style = style.with(decoration, true);
            }
            if (style.colours().isEmpty() || style.colours().size() > NameStyle.MAX_STOPS) {
                warn.accept("preset '" + id + "' needs between 1 and " + NameStyle.MAX_STOPS
                        + " colours. Skipped.");
                continue;
            }
            presets.add(new Preset(id, entry.getString("title", id),
                    style.animated(entry.getBoolean("animated", false)),
                    entry.getBoolean("restricted", false)));
        }
        return presets;
    }

    /**
     * The dye a swatch is named after, or the concrete block the chat colour of that value looks like.
     * {@code Material.getMaterial}, not {@code matchMaterial}: this runs in tests without a server.
     */
    private static Material iconFor(String key, TextColor colour) {
        Material dye = Material.getMaterial(key.trim().toUpperCase(Locale.ROOT).replace(' ', '_') + "_DYE");
        if (dye != null) {
            return dye;
        }
        NamedTextColor named = NamedTextColor.namedColor(colour.value());
        return named != null ? ColorSwatches.materialFor(named) : ColorSwatches.materialFor(
                NamedTextColor.nearestTo(colour));
    }

    // ------------------------------------------------------------------ the shipped tables

    /** The bright chat colours no dye produces, beside the sixteen dyes. */
    private static final List<NamedTextColor> CHAT_EXTRAS = List.of(NamedTextColor.GOLD,
            NamedTextColor.AQUA, NamedTextColor.DARK_AQUA, NamedTextColor.DARK_RED,
            NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_PURPLE,
            NamedTextColor.LIGHT_PURPLE);

    /** Named colours that are neither a dye nor a chat colour — pastels and in-betweens. */
    private static final Map<String, String> SOFT_COLOURS = softColours();

    private static Map<String, String> softColours() {
        Map<String, String> soft = new LinkedHashMap<>();
        soft.put("peach", "#ffcba4");
        soft.put("coral", "#ff7f50");
        soft.put("rose", "#ff66a3");
        soft.put("crimson", "#dc143c");
        soft.put("sand", "#e2c290");
        soft.put("mint", "#98ff98");
        soft.put("teal", "#2a9d8f");
        soft.put("sky", "#87ceeb");
        soft.put("lavender", "#c8a2c8");
        soft.put("violet", "#8f00ff");
        return soft;
    }

    /**
     * What the first versions shipped, before the file remembered what it had been offered. A list that
     * still holds one of these was built on ours and may be added to; one that holds none is the owner's own.
     */
    private static final List<String> ORIGINAL_PRESETS = List.of("sunset", "ocean", "fire", "mint", "aurora",
            "candy", "royal", "gold", "rainbow", "lava", "northern-lights");

    private static List<String> originalPalette() {
        List<String> keys = new ArrayList<>();
        for (DyeColor dye : ColorSwatches.DYES) {
            keys.add(dye.name().toLowerCase(Locale.ROOT));
        }
        for (NamedTextColor colour : CHAT_EXTRAS) {
            keys.add(NamedTextColor.NAMES.key(colour));
        }
        return keys;
    }

    private static Map<String, String> shippedPaletteEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (DyeColor dye : ColorSwatches.DYES) {
            entries.put(dye.name().toLowerCase(Locale.ROOT),
                    ColorSwatches.ofDye(dye).asHexString().toLowerCase(Locale.ROOT));
        }
        for (NamedTextColor colour : CHAT_EXTRAS) {
            entries.put(NamedTextColor.NAMES.key(colour), colour.asHexString().toLowerCase(Locale.ROOT));
        }
        entries.putAll(SOFT_COLOURS);
        return entries;
    }

    private record ShippedPreset(String id, String title, List<String> colours, List<String> decorations,
                                 boolean restricted, boolean animated) {

        ShippedPreset(String id, String title, List<String> colours, List<String> decorations,
                      boolean restricted) {
            this(id, title, colours, decorations, restricted, false);
        }
    }

    private static final List<ShippedPreset> SHIPPED_PRESETS = List.of(
            new ShippedPreset("sunset", "Sunset", List.of("#ff5f6d", "#ffc371"), List.of(), false),
            new ShippedPreset("ocean", "Ocean", List.of("#2193b0", "#6dd5ed"), List.of(), false),
            new ShippedPreset("fire", "Fire", List.of("#f12711", "#f5af19"), List.of(), false),
            new ShippedPreset("mint", "Mint", List.of("#00b09b", "#96c93d"), List.of(), false),
            new ShippedPreset("aurora", "Aurora", List.of("#00c9ff", "#92fe9d"), List.of(), false),
            new ShippedPreset("candy", "Candy", List.of("#ff9a9e", "#fad0c4", "#a18cd1"), List.of(), false),
            new ShippedPreset("royal", "Royal", List.of("#8e2de2", "#4a00e0"), List.of("bold"), false),
            new ShippedPreset("gold", "Gold", List.of("#f7971e", "#ffd200"), List.of("bold"), false),
            new ShippedPreset("rainbow", "Rainbow",
                    List.of("#ff5555", "#ffaa00", "#ffff55", "#55ff55", "#55ffff", "#5555ff", "#ff55ff"),
                    List.of("bold"), true, true),
            new ShippedPreset("lava", "Lava lamp", List.of("#ff512f", "#f09819", "#dd2476"), List.of(), false,
                    true),
            new ShippedPreset("northern-lights", "Northern lights",
                    List.of("#00c9ff", "#92fe9d", "#a18cd1"), List.of(), false, true),

            new ShippedPreset("creeper", "Creeper", List.of("#0da70b", "#5ed15a"), List.of(), false, false),
            new ShippedPreset("diamond", "Diamond", List.of("#4aedd9", "#a1fbe8"), List.of(), false, false),
            new ShippedPreset("emerald", "Emerald", List.of("#00a83b", "#17dd62"), List.of(), false, false),
            new ShippedPreset("redstone", "Redstone", List.of("#ff2a2a", "#8b0000"), List.of(), false, false),
            new ShippedPreset("netherite", "Netherite", List.of("#5a4f52", "#a39695"), List.of("bold"), false, false),
            new ShippedPreset("amethyst", "Amethyst", List.of("#9a5cc6", "#e3b6ff"), List.of(), false, false),
            new ShippedPreset("copper", "Copper", List.of("#e77c56", "#c15a36", "#5fb39b"), List.of(), false, true),
            new ShippedPreset("prismarine", "Prismarine", List.of("#63b7a5", "#a3e0d6"), List.of(), false, false),
            new ShippedPreset("glowstone", "Glowstone", List.of("#ffbc5e", "#fff3a3"), List.of(), false, false),
            new ShippedPreset("ender", "Ender", List.of("#258474", "#cc00fa"), List.of(), false, true),
            new ShippedPreset("sculk", "Sculk", List.of("#0e6b7a", "#29dfeb"), List.of(), false, true),
            new ShippedPreset("cherry", "Cherry blossom", List.of("#ffb7c5", "#ff8fab"), List.of(), false, false),
            new ShippedPreset("lapis", "Lapis", List.of("#1d4bd1", "#6b8cff"), List.of(), false, false),
            new ShippedPreset("slime", "Slime", List.of("#7ebf6e", "#b8f2a0"), List.of(), false, false),
            new ShippedPreset("honey", "Honey", List.of("#f5a623", "#ffd36e"), List.of(), false, false),
            new ShippedPreset("nether", "Nether", List.of("#b3001b", "#ff4500", "#ffae42"), List.of(), false, false),
            new ShippedPreset("end", "The End", List.of("#e8e9b4", "#b38bd9"), List.of(), false, false),
            new ShippedPreset("frost", "Frost", List.of("#a0e9ff", "#ffffff"), List.of(), false, false),
            new ShippedPreset("quartz", "Quartz", List.of("#ffffff", "#e8dccf"), List.of(), false, false),
            new ShippedPreset("forest", "Forest", List.of("#2e8b57", "#71b280"), List.of(), false, false),
            new ShippedPreset("lagoon", "Lagoon", List.of("#43cea2", "#2a9df4"), List.of(), false, false),
            new ShippedPreset("desert", "Desert", List.of("#edc967", "#d38f4a"), List.of(), false, false),
            new ShippedPreset("autumn", "Autumn", List.of("#d1495b", "#edae49", "#a0522d"), List.of(), false, false),
            new ShippedPreset("spring", "Spring", List.of("#a8e063", "#f9f586"), List.of(), false, false),
            new ShippedPreset("peach", "Peach", List.of("#ffb88c", "#ff8c94"), List.of(), false, false),
            new ShippedPreset("lavender", "Lavender", List.of("#b57edc", "#e6ccff"), List.of(), false, false),
            new ShippedPreset("berry", "Berry", List.of("#8e2de2", "#ff0080"), List.of(), false, false),
            new ShippedPreset("cotton-candy", "Cotton candy", List.of("#ffb6f0", "#a0e7ff"), List.of(), false, false),
            new ShippedPreset("bubblegum", "Bubblegum", List.of("#ff69b4", "#ffc0e3"), List.of("bold"), false, false),
            new ShippedPreset("midnight", "Midnight", List.of("#4b6cb7", "#89253e"), List.of(), false, false),
            new ShippedPreset("sunrise", "Sunrise", List.of("#ff512f", "#f9d423"), List.of(), false, false),
            new ShippedPreset("coral", "Coral", List.of("#ff7e5f", "#feb47b"), List.of(), false, false),
            new ShippedPreset("storm", "Storm", List.of("#4b79a1", "#a5b8cf"), List.of(), false, false),
            new ShippedPreset("ghost", "Ghost", List.of("#e0e0e0", "#a8b2c1"), List.of("italic"), false, false),
            new ShippedPreset("deep-sea", "Deep sea", List.of("#1e3c72", "#2a9df4"), List.of(), false, false),
            new ShippedPreset("matcha", "Matcha", List.of("#8db255", "#c8e6a0"), List.of(), false, false),
            new ShippedPreset("toxic", "Toxic", List.of("#b6ff00", "#39ff14"), List.of(), false, false),
            new ShippedPreset("blood-moon", "Blood moon", List.of("#870000", "#ff3c3c"), List.of("bold"), false, false),
            new ShippedPreset("galaxy", "Galaxy", List.of("#6a11cb", "#2575fc", "#e100ff"), List.of(), false, true),
            new ShippedPreset("neon", "Neon", List.of("#39ff14", "#00ffff", "#ff00ff"), List.of("bold"), false, true),
            new ShippedPreset("vaporwave", "Vaporwave", List.of("#ff71ce", "#01cdfe", "#05ffa1", "#b967ff"), List.of(), false, true),
            new ShippedPreset("ember", "Ember", List.of("#ff4e00", "#ec9f05"), List.of(), false, true),
            new ShippedPreset("twilight", "Twilight", List.of("#4b6cb7", "#a17fe0", "#ff9a8b"), List.of(), false, true),
            new ShippedPreset("royal-gold", "Royal gold", List.of("#ffd700", "#b8860b"), List.of("bold"), true, false),
            new ShippedPreset("prism", "Prism", List.of("#ff0000", "#ff8000", "#ffff00", "#00ff00", "#00ffff", "#0080ff", "#8000ff", "#ff00ff"), List.of("bold"), true, true),
            new ShippedPreset("pride", "Pride", List.of("#e40303", "#ff8c00", "#ffed00", "#008026", "#004dff", "#750787"), List.of(), false, true),
            new ShippedPreset("trans", "Trans", List.of("#5bcefa", "#f5a9b8", "#ffffff", "#f5a9b8", "#5bcefa"), List.of(), false, false),
            new ShippedPreset("bi", "Bi", List.of("#d60270", "#9b4f96", "#0038a8"), List.of(), false, false),
            new ShippedPreset("pan", "Pan", List.of("#ff218c", "#ffd800", "#21b1ff"), List.of(), false, false),
            new ShippedPreset("lesbian", "Lesbian", List.of("#d52d00", "#ff9a56", "#ffffff", "#d362a4", "#a30262"), List.of(), false, false),
            new ShippedPreset("nonbinary", "Non-binary", List.of("#fcf434", "#ffffff", "#9c59d1", "#6b6b6b"), List.of(), false, false),
            new ShippedPreset("ace", "Ace", List.of("#a3a3a3", "#ffffff", "#800080"), List.of(), false, false));

    private static List<PaletteColour> shippedPalette() {
        List<PaletteColour> palette = new ArrayList<>();
        shippedPaletteEntries().forEach((key, hex) -> {
            TextColor colour = NameStyle.colourOf(hex);
            palette.add(new PaletteColour(key.replace('_', ' '), colour, iconFor(key, colour)));
        });
        return palette;
    }

    private static List<Preset> shippedPresets() {
        YamlConfiguration yaml = new YamlConfiguration();
        writePresets(yaml);
        return presets(yaml.getConfigurationSection("presets"), problem -> { });
    }

    private static void writePresets(YamlConfiguration yaml) {
        for (ShippedPreset preset : SHIPPED_PRESETS) {
            writePreset(yaml, preset);
        }
    }

    private static void writePreset(YamlConfiguration yaml, ShippedPreset preset) {
        String path = "presets." + preset.id();
        yaml.set(path + ".title", preset.title());
        yaml.set(path + ".colours", preset.colours());
        yaml.set(path + ".decorations", preset.decorations());
        yaml.set(path + ".restricted", preset.restricted());
        if (preset.animated()) {
            yaml.set(path + ".animated", true);
        }
    }

    private boolean writeDefaults() {
        return store.update(yaml -> {
            shippedPaletteEntries().forEach((key, hex) -> yaml.set("palette." + key, hex));
            yaml.setComments("palette", List.of("",
                    "--- The colours players pick from ---",
                    "A name and a hex code (#f38baa) or a chat colour name (dark_red). The name is what",
                    "the picker and /cosmetics name set call it; a name ending in a dye's name shows",
                    "that dye. Colours outside this list need rainscosmetics.name.any-colour."));
            writePresets(yaml);
            remember(yaml);
            yaml.setComments("presets", List.of("",
                    "--- Ready-made styles ---",
                    "colours: 1 to 8 stops, left to right. decorations: bold, italic, underlined,",
                    "strikethrough, obfuscated. animated: true lets the gradient flow along the name.",
                    "A preset needs no other permission to wear;",
                    "restricted: true means only holders of rainscosmetics.preset.<id> may."));
        });
    }

    public List<String> problems() {
        return new ArrayList<>(store.problems());
    }
}
