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
        current = new Catalogue(palette(yaml.getConfigurationSection("palette"), warn),
                presets(yaml.getConfigurationSection("presets"), warn));
        return current;
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

    private static Map<String, String> shippedPaletteEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        for (DyeColor dye : ColorSwatches.DYES) {
            entries.put(dye.name().toLowerCase(Locale.ROOT),
                    ColorSwatches.ofDye(dye).asHexString().toLowerCase(Locale.ROOT));
        }
        for (NamedTextColor colour : CHAT_EXTRAS) {
            entries.put(NamedTextColor.NAMES.key(colour), colour.asHexString().toLowerCase(Locale.ROOT));
        }
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
                    List.of("#00c9ff", "#92fe9d", "#a18cd1"), List.of(), false, true));

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
            String path = "presets." + preset.id();
            yaml.set(path + ".title", preset.title());
            yaml.set(path + ".colours", preset.colours());
            yaml.set(path + ".decorations", preset.decorations());
            yaml.set(path + ".restricted", preset.restricted());
            if (preset.animated()) {
                yaml.set(path + ".animated", true);
            }
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
