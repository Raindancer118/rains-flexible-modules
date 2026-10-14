package de.raindancer.modules.names;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.names.store.Palette;
import de.raindancer.modules.names.store.PaletteFile;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What dyes a name tag is one table for the whole network. */
@Tag("database")
class PaletteNetworkTest {

    @TempDir
    Path plugins;

    private Servers network;
    private final List<String> warnings = new ArrayList<>();

    private PaletteFile on(String id) {
        if (network == null) {
            network = new Servers(plugins);
        }
        Documents documents = network.server(id);
        PaletteFile file = new PaletteFile(network.network(documents, "palette.yml"), network.own(documents, "config.yml"));
        file.load(warnings::add);
        file.watch(warnings::add);
        return file;
    }

    @AfterEach
    void close() {
        if (network != null) {
            network.close();
        }
    }

    private void edit(String path, Object value) {
        YamlStore store = network.network(network.server("owner"), "palette.yml");
        store.update(yaml -> yaml.set(path, value));
    }

    @Test
    @DisplayName("an edit of the palette on one server is read by the other")
    void editTravels() {
        PaletteFile smp = on("smp");
        PaletteFile arena = on("arena");
        assertThat(arena.current().reagentFor(Material.RED_DYE)).isNotNull();

        edit("colours.RED_DYE", null);
        network.heard();

        assertThat(arena.current().reagentFor(Material.RED_DYE)).isNull();
        assertThat(smp.current().reagentFor(Material.RED_DYE)).isNull();
    }

    @Test
    @DisplayName("the shipped palette is written once; the second server finds it and changes nothing")
    void shippedIsWrittenOnce() {
        on("smp");
        YamlStore store = network.network(network.server("probe"), "palette.yml");
        String before = store.readText().orElseThrow();

        on("arena");

        assertThat(store.readText().orElseThrow()).isEqualTo(before);
    }

    @Test
    @DisplayName("the first server's 1.x palette becomes the network's; a later server's own is not used")
    void firstOldPaletteWins() throws Exception {
        network = new Servers(plugins);
        Path folder = Files.createDirectories(plugins.resolve("RainsColouredNames"));
        YamlConfiguration old = new YamlConfiguration();
        old.set("colours.RED_DYE", "#ff0000");
        old.save(folder.resolve("config.yml").toFile());
        PaletteFile smp = on("smp");

        YamlConfiguration other = new YamlConfiguration();
        other.set("colours.BLUE_DYE", "#0000ff");
        other.save(folder.resolve("config.yml").toFile());
        PaletteFile arena = on("arena");

        assertThat(smp.current().reagentFor(Material.RED_DYE)).isNotNull();
        assertThat(arena.current().reagentFor(Material.RED_DYE)).isNotNull();
        assertThat(arena.current().reagentFor(Material.BLUE_DYE)).isNull();
    }

    @Test
    @DisplayName("a palette that cannot be read keeps the one in force")
    void unreadableKeepsThePalette() {
        Database database = TestDatabases.open(plugins.resolve("lost.db"), Documents.SCHEMA);
        Documents documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
        PaletteFile file = new PaletteFile(
                new YamlStore(plugins.resolve("RainsColouredNames").resolve("palette.yml"), documents, Documents.Scope.NETWORK),
                null);
        Palette loaded = file.load(warnings::add);
        assertThat(loaded.reagentFor(Material.RED_DYE)).isNotNull();

        database.close();
        file.load(warnings::add);

        assertThat(file.current().reagents()).isEqualTo(loaded.reagents());
        assertThat(warnings).isNotEmpty();
    }
}
