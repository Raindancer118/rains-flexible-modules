package de.raindancer.modules.names;

import de.raindancer.modules.names.model.Reagent;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.store.PaletteFile;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The palette on disk.
 *
 * <p>Every test here is about a way the file and the code can disagree, and each of them produces a
 * server that starts perfectly and then behaves wrongly:
 *
 * <ul>
 *   <li><b>A fresh install with no palette at all</b> — the feature is silently off, and the owner has
 *       no file to look at to find out why.</li>
 *   <li><b>Defaults written in a form the reader cannot read back</b> — a hex value written as a number,
 *       a shade written flat instead of as a section. The file looks right and dyes nothing.</li>
 *   <li><b>An owner's edit lost</b> — the shipped tables written over a file that already had some.</li>
 * </ul>
 */
class PaletteFileTest {

    @TempDir
    Path folder;

    private PaletteFile fileIn(Path where) {
        return new PaletteFile(where.resolve("config.yml"));
    }

    @Test
    @DisplayName("a fresh file is given the shipped palette, and reads back as exactly that")
    void defaultsAreWrittenAndReadBack() {
        PaletteFile file = fileIn(folder);
        List<String> warnings = new ArrayList<>();

        Palette loaded = file.load(warnings::add);

        assertThat(Files.exists(folder.resolve("config.yml")))
                .as("nothing was written, so the owner has no file to edit")
                .isTrue();
        assertThat(warnings).isEmpty();
        // The round trip is the point: written by one half of this class and read by the other, so a
        // value written in a form the reader does not understand fails here rather than on a server.
        assertThat(loaded.reagents()).isEqualTo(Palette.defaults().reagents());
    }

    @Test
    @DisplayName("what was written is what the reader wants: hex strings and shade sections")
    void theWrittenFormIsTheReadableForm() throws IOException {
        fileIn(folder).load(warning -> {
        });
        String written = Files.readString(folder.resolve("config.yml"), StandardCharsets.UTF_8);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                folder.resolve("config.yml").toFile());

        // Quoted, because #f38baa unquoted is a YAML comment and the value would come back empty.
        assertThat(yaml.getString("colours.PINK_DYE")).isEqualToIgnoringCase("#f38baa");
        assertThat(yaml.isConfigurationSection("shades.COAL")).isTrue();
        assertThat(yaml.getDouble("shades.COAL.step")).isEqualTo(0.2);
        // And the comments that are the only documentation most owners will ever meet.
        assertThat(written).contains("Every dye gives the colour it actually is");
    }

    @Test
    @DisplayName("a file that already has a palette is left exactly as it is")
    void anOwnersPaletteIsNeverOverwritten() {
        Path config = folder.resolve("config.yml");
        YamlConfiguration mine = new YamlConfiguration();
        mine.set("colours.RED_DYE", "#ff0000");
        try {
            mine.save(config.toFile());
        } catch (IOException cannot) {
            throw new AssertionError("could not write the fixture", cannot);
        }

        Palette loaded = fileIn(folder).load(warning -> {
        });

        assertThat(loaded.reagentFor(Material.RED_DYE)).isNotNull();
        assertThat(loaded.reagentFor(Material.BLUE_DYE))
                .as("the shipped table was written over a palette the owner had already cut down")
                .isNull();
        assertThat(((Reagent.Colour) loaded.reagentFor(Material.RED_DYE)).colour().asHexString())
                .isEqualToIgnoringCase("#ff0000");
    }

    @Test
    @DisplayName("a second load re-reads the file rather than answering from memory")
    void reloadingSeesAnEdit() {
        PaletteFile file = fileIn(folder);
        file.load(warning -> {
        });
        assertThat(file.current().reagentFor(Material.RED_DYE)).isNotNull();

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(folder.resolve("config.yml").toFile());
        yaml.set("colours.RED_DYE", null);
        try {
            yaml.save(folder.resolve("config.yml").toFile());
        } catch (IOException cannot) {
            throw new AssertionError("could not rewrite the file", cannot);
        }

        file.load(warning -> {
        });

        assertThat(file.current().reagentFor(Material.RED_DYE))
                .as("/namestyle reload answered 'reloaded' and changed nothing")
                .isNull();
    }

    @Test
    @DisplayName("the settings living in the same file are not disturbed by writing the palette")
    void theSettingsKeysSurvive() {
        Path config = folder.resolve("config.yml");
        YamlConfiguration settings = new YamlConfiguration();
        settings.set("max-gradient-stops", 3);
        settings.set("wash-in-cauldron", false);
        try {
            settings.save(config.toFile());
        } catch (IOException cannot) {
            throw new AssertionError("could not write the fixture", cannot);
        }

        fileIn(folder).load(warning -> {
        });

        YamlConfiguration after = YamlConfiguration.loadConfiguration(config.toFile());
        assertThat(after.getInt("max-gradient-stops")).isEqualTo(3);
        assertThat(after.getBoolean("wash-in-cauldron")).isFalse();
        assertThat(after.isConfigurationSection("colours"))
                .as("a file with settings but no palette is one this has to fill in")
                .isTrue();
    }
}
