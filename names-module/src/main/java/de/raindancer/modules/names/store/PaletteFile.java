package de.raindancer.modules.names.store;

import de.raindancer.core.data.store.YamlStore;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The palette on disk: what puts the shipped tables in a fresh {@code config.yml}, and what re-reads
 * them.
 *
 * <h2>Why the palette shares the settings' file</h2>
 * Because an owner should have one file to edit. The three sections sit alongside the settings Core
 * writes, and that is safe in both directions: {@code SettingsStore.save} keeps keys its schema does
 * not know, so writing a setting from {@code /settings} leaves the palette untouched, and nothing here
 * ever writes a key the schema owns.
 *
 * <p>The write is {@link YamlStore#update}, which is Core's read-modify-atomic-move — never a fresh
 * file. A server killed mid-write therefore has the old palette or the new one, and never half of the
 * file it needs to start.
 *
 * <p>It also only ever happens <em>once</em>, while the module is enabling and only when the three
 * sections are absent. That matters because Core's {@code SettingsStore} holds its own handle on the
 * same file: two whole-file writers are a lost update waiting to happen, and the way that is avoided
 * here is that this one has finished before anybody can reach {@code /settings}. A reload re-reads and
 * never writes.
 *
 * <h2>Why the current palette is volatile</h2>
 * It is read from whichever region thread owns the crafting grid and replaced from whichever thread ran
 * {@code /namestyle reload}. Without the barrier a craft can be resolved with the old palette and
 * charged against the new one, which is the one bug in this module that would cost a player an item.
 */
public final class PaletteFile {

    private final YamlStore store;
    private volatile Palette current = Palette.defaults();

    public PaletteFile(Path configFile) {
        this.store = new YamlStore(configFile);
    }

    /** The palette as it is now. Cheap; take a fresh one each craft rather than holding this. */
    public Palette current() {
        return current;
    }

    /**
     * Reads the file, having first written the shipped tables into it if they are not there.
     *
     * @param warn where a line that names an item, colour or decoration that does not exist is
     *             reported. Skipped rather than fatal: one typo in a sixteen-line table costs the
     *             server that one dye, not the feature
     * @return the palette that is now current
     */
    public Palette load(Consumer<String> warn) {
        YamlConfiguration yaml = store.read();
        for (String problem : store.problems()) {
            warn.accept("the file " + problem + ", so the shipped palette is used");
        }
        if (!hasAnySection(yaml)) {
            // A fresh file, or one from before this module existed. Written out in full rather than
            // used silently from memory, because the whole point of the palette being configurable is
            // that an owner can see what there is to change.
            if (writeDefaults()) {
                yaml = store.read();
            } else {
                warn.accept("the palette could not be written to " + store.file()
                        + ", so this boot uses the shipped one");
                current = Palette.defaults();
                return current;
            }
        }
        current = Palette.from(yaml, warn);
        return current;
    }

    /** Whether the file already says anything about the palette. */
    private static boolean hasAnySection(YamlConfiguration yaml) {
        return yaml.isConfigurationSection("colours")
                || yaml.isConfigurationSection("decorations")
                || yaml.isConfigurationSection("shades");
    }

    /**
     * Writes the shipped tables, with the lines above them that say what they are for.
     *
     * <p>The comments are the documentation an owner will actually meet — most of them will never open
     * the source, and there is no separate manual for a file the plugin generates.
     */
    private boolean writeDefaults() {
        return store.update(yaml -> {
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
        });
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
