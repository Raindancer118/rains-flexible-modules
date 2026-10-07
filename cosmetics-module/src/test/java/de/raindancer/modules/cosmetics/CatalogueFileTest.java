package de.raindancer.modules.cosmetics;

import de.raindancer.modules.cosmetics.model.Catalogue;
import de.raindancer.modules.cosmetics.store.CatalogueFile;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The palette and the presets on disk: written once, read forgivingly, never overwritten. */
class CatalogueFileTest {

    @TempDir
    Path folder;

    private Path config() {
        return folder.resolve("config.yml");
    }

    @Test
    @DisplayName("a fresh file gets the shipped palette and presets written into it")
    void freshFileGetsDefaults() throws IOException {
        CatalogueFile file = new CatalogueFile(config());
        Catalogue loaded = file.load(problem -> { });

        assertThat(loaded.palette()).hasSizeGreaterThanOrEqualTo(16);
        assertThat(loaded.presets()).isNotEmpty();
        assertThat(loaded.colourNamed("pink")).isPresent();
        assertThat(loaded.colourNamed("pink").orElseThrow().icon()).isEqualTo(Material.PINK_DYE);
        assertThat(loaded.preset("rainbow").orElseThrow().style().isAnimated()).as("the rainbow flows").isTrue();
        assertThat(Files.readString(config())).contains("palette:").contains("presets:");
    }

    @Test
    @DisplayName("what an owner wrote is read, and a broken line costs only that line")
    void ownersFileIsRead() throws IOException {
        Files.writeString(config(), """
                some-setting: true
                palette:
                  sky: '#87ceeb'
                  nonsense: 'not a colour'
                presets:
                  dusk:
                    title: Dusk
                    colours: ['#0b486b', '#f56217']
                    decorations: [bold, sideways]
                    restricted: true
                    animated: true
                  broken:
                    title: Broken
                    colours: []
                """);
        List<String> problems = new ArrayList<>();
        Catalogue loaded = new CatalogueFile(config()).load(problems::add);

        assertThat(loaded.palette()).hasSize(1);
        assertThat(loaded.palette().getFirst().colour()).isEqualTo(TextColor.fromHexString("#87ceeb"));
        assertThat(loaded.presets()).hasSize(1);
        assertThat(loaded.presets().getFirst().restricted()).isTrue();
        assertThat(loaded.presets().getFirst().style().isAnimated()).isTrue();
        assertThat(loaded.presets().getFirst().style().decorations()).containsExactly(TextDecoration.BOLD);
        assertThat(problems).anyMatch(line -> line.contains("nonsense"))
                .anyMatch(line -> line.contains("sideways"))
                .anyMatch(line -> line.contains("broken"));
        assertThat(Files.readString(config()))
                .as("the owner's file is never rewritten").contains("some-setting: true").contains("nonsense");
    }

    @Test
    @DisplayName("an owner who emptied both lists meant it")
    void emptyIsEmpty() throws IOException {
        Files.writeString(config(), "palette: {}\npresets: {}\n");
        Catalogue loaded = new CatalogueFile(config()).load(problem -> { });
        assertThat(loaded.palette()).isEmpty();
        assertThat(loaded.presets()).isEmpty();
    }

    /** A file as an older version wrote it: the first eleven presets and the first palette. */
    private void olderFile(String presetsYaml) throws IOException {
        Files.writeString(config(), """
                palette:
                  white: '#f9fffe'
                  pink: '#f38baa'
                  gold: '#ffaa00'
                presets:
                """ + presetsYaml);
    }

    @Test
    @DisplayName("the shipped catalogue is plenty, every preset in it loads, and no two share a name")
    void shippedCatalogueIsSound() {
        List<String> problems = new ArrayList<>();
        Catalogue loaded = new CatalogueFile(config()).load(problems::add);
        assertThat(problems).isEmpty();
        assertThat(loaded.presets()).hasSizeGreaterThanOrEqualTo(50);
        assertThat(loaded.presets()).extracting(preset -> preset.title().toLowerCase())
                .doesNotHaveDuplicates();
        assertThat(loaded.palette()).hasSizeGreaterThanOrEqualTo(30);
        assertThat(loaded.preset("creeper")).isPresent();
        assertThat(loaded.preset("trans")).isPresent();
        assertThat(loaded.colourNamed("peach")).isPresent();
    }

    @Test
    @DisplayName("a server with an older file gets the new presets and colours; its own edits and deletions stay")
    void olderFilesUpgrade() throws IOException {
        olderFile("""
                  sunset:
                    title: Abendrot
                    colours: ['#ff5f6d', '#ffc371']
                  ocean:
                    title: Ocean
                    colours: ['#2193b0', '#6dd5ed']
                """);
        Catalogue loaded = new CatalogueFile(config()).load(problem -> { });

        assertThat(loaded.preset("creeper")).as("a new one arrived").isPresent();
        assertThat(loaded.preset("lava")).as("0.4.0's flowing ones never reached older files; now they do").isPresent();
        assertThat(loaded.colourNamed("peach")).as("a new colour arrived").isPresent();
        assertThat(loaded.preset("sunset").orElseThrow().title()).as("the owner's rename stays").isEqualTo("Abendrot");
        assertThat(loaded.preset("fire")).as("an original the owner deleted stays deleted").isEmpty();
        assertThat(loaded.colourNamed("cyan")).as("an original colour they deleted stays deleted").isEmpty();
    }

    @Test
    @DisplayName("a new preset is offered once: deleted afterwards, it does not come back")
    void offeredOnce() throws IOException {
        olderFile("""
                  ocean:
                    title: Ocean
                    colours: ['#2193b0', '#6dd5ed']
                """);
        new CatalogueFile(config()).load(problem -> { });
        String upgraded = Files.readString(config());
        Files.writeString(config(), upgraded.replaceAll("(?m)^  creeper:\\n(    .*\\n)+", ""));
        assertThat(Files.readString(config())).doesNotContain("creeper:");

        Catalogue again = new CatalogueFile(config()).load(problem -> { });
        assertThat(again.preset("creeper")).isEmpty();
        assertThat(again.preset("emerald")).isPresent();
    }
}
