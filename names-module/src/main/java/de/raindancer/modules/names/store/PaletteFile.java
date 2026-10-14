package de.raindancer.modules.names.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The palette: what puts the shipped tables in a fresh {@code palette.yml}, and what re-reads them.
 *
 * <h2>A document of the network</h2>
 * The palette is the owner's table, edited once for every server, so it is {@code YamlStore.network}:
 * {@link #watch} reads it again when another server writes it. It used to share {@code config.yml} with the
 * settings; those are layered per server now and a shared file would be rewritten under the palette, so the
 * palette has its own document. The first time, the three sections are taken over from the old
 * {@code config.yml} (left as it is) — or, with none there, the shipped tables are written.
 *
 * <p>That happens only while the document has no section at all, in one {@link YamlStore#update}: a server
 * that comes second finds the first one's palette and changes nothing. The write is Core's
 * read-modify-compare-and-set, so a palette another server just wrote is never replaced.
 *
 * <h2>Why the current palette is volatile</h2>
 * It is read from whichever region thread owns the crafting grid and replaced from whichever thread ran
 * {@code /namestyle reload}. Without the barrier a craft can be resolved with the old palette and
 * charged against the new one, which is the one bug in this module that would cost a player an item.
 */
public final class PaletteFile {

    private static final List<String> SECTIONS = List.of("colours", "decorations", "shades");

    private final YamlStore store;
    private final YamlStore legacy;
    private volatile Palette current = Palette.defaults();

    /**
     * @param legacy the 1.x {@code config.yml} the palette may still be in, read once and never written; null for none
     */
    public PaletteFile(YamlStore store, YamlStore legacy) {
        this.store = store;
        this.legacy = legacy;
    }

    /** The module's folder: {@code palette.yml} of the network, with this server's {@code config.yml} as the old home. */
    public static PaletteFile in(Path folder) {
        return new PaletteFile(YamlStore.network(folder.resolve("palette.yml")), new YamlStore(folder.resolve("config.yml")));
    }

    /** The palette as it is now. Cheap; take a fresh one each craft rather than holding this. */
    public Palette current() {
        return current;
    }

    /**
     * Reads the document, having first written the palette into it if it has none.
     *
     * @param warn where a line that names an item, colour or decoration that does not exist is
     *             reported. Skipped rather than fatal: one typo in a sixteen-line table costs the
     *             server that one dye, not the feature
     * @return the palette that is now current; the one in force when the document could not be read
     */
    public synchronized Palette load(Consumer<String> warn) {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            for (String problem : store.problems()) {
                warn.accept("the palette " + problem + ", so the palette in force is kept");
            }
            return current;
        }
        YamlConfiguration yaml = read.get();
        if (!hasAnySection(yaml)) {
            if (!writeInitial(warn)) {
                warn.accept("the palette could not be written to " + store.file()
                        + ", so this boot uses the shipped one");
                return current;
            }
            yaml = store.readIfReadable().orElse(yaml);
        }
        current = Palette.from(yaml, warn);
        return current;
    }

    /** Reads again when another server writes the palette. Close to stop. */
    public NetworkBus.Subscription watch(Consumer<String> warn) {
        return store.onChange(() -> load(warn));
    }

    /** Whether the file already says anything about the palette. */
    private static boolean hasAnySection(YamlConfiguration yaml) {
        return SECTIONS.stream().anyMatch(yaml::isConfigurationSection);
    }

    /**
     * Takes the palette over from the old {@code config.yml} when that has one, otherwise writes the shipped
     * tables, with the lines above them that say what they are for.
     *
     * <p>The comments are the documentation an owner will actually meet — most of them will never open
     * the source, and there is no separate manual for a file the plugin generates.
     */
    private boolean writeInitial(Consumer<String> warn) {
        Optional<YamlConfiguration> old = Optional.empty();
        if (legacy != null) {
            old = legacy.readIfReadable();
            if (old.isEmpty()) {
                // Not knowing is not "there was none": writing the shipped palette now would bury the owner's.
                legacy.problems().forEach(problem -> warn.accept("the old config.yml " + problem));
                return false;
            }
        }
        YamlConfiguration before = old.filter(PaletteFile::hasAnySection).orElse(null);
        return store.update(yaml -> {
            if (hasAnySection(yaml)) {
                return;   // another server was first
            }
            if (before != null) {
                for (String section : SECTIONS) {
                    ConfigurationSection taken = before.getConfigurationSection(section);
                    if (taken == null) {
                        continue;
                    }
                    yaml.createSection(section);   // an owner who emptied a section meant it
                    for (String path : taken.getKeys(true)) {
                        if (!taken.isConfigurationSection(path)) {
                            yaml.set(section + "." + path, taken.get(path));
                        }
                    }
                    yaml.setComments(section, before.getComments(section));
                }
                return;
            }
            defaults(yaml);
        });
    }

    private static void defaults(YamlConfiguration yaml) {
        for (Map.Entry<Material, String> dye : Palette.shippedColours().entrySet()) {
            yaml.set("colours." + dye.getKey().name(), dye.getValue());
        }
        yaml.setComments("colours", List.of("",
                "--- What dyes a name tag, and to what ---",
                "Every dye gives the colour it actually is — the same values Minecraft uses for",
                "leather armour and concrete. Pink dye makes pink.",
                "The value is a hex code (#f38baa) or one of Minecraft's colour names (dark_red,",
                "aqua, light_purple ...). Any item can be listed here, not just dyes."));

        for (Map.Entry<Material, String> decoration : Palette.shippedDecorations().entrySet()) {
            yaml.set("decorations." + decoration.getKey().name(), decoration.getValue());
        }
        yaml.setComments("decorations", List.of("",
                "--- What toggles bold, italic and the rest ---",
                "Crafting the same item in again turns it back off, so a mistake costs one ingot",
                "and nothing else. Deleting a line removes that recipe outright — which is how you",
                "forbid obfuscated names, since a name nobody can read is a real thing to keep off",
                "a server."));

        for (Map.Entry<Material, Palette.ShadeSpec> shade : Palette.shippedShades().entrySet()) {
            String path = "shades." + shade.getKey().name();
            yaml.set(path + ".towards", shade.getValue().towards());
            yaml.set(path + ".step", shade.getValue().step());
            yaml.set(path + ".label", shade.getValue().label());
        }
        yaml.setComments("shades", List.of("",
                "--- Darkening and lightening ---",
                "Each craft moves every colour on the tag one step towards the target, so these are",
                "a dial rather than a switch and the way back is the other item. This is what turns",
                "sixteen dyes into as many colours as anyone will sit and craft.",
                "  step: how far per craft, between 0 and 1 (exclusive)."));
    }

    /** What was wrong with the file last time it was read, in Core's words. */
    public List<String> problems() {
        return new ArrayList<>(store.problems());
    }

    /** Where the palette lives, for the console line that says where to edit it. */
    public Path file() {
        return store.file();
    }
}
