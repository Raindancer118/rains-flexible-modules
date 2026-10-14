package de.raindancer.modules.wallsroads.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.core.world.geometry.ColumnPolygon;
import de.raindancer.core.world.geometry.Polyline;
import de.raindancer.modules.wallsroads.model.CornerStyle;
import de.raindancer.modules.wallsroads.model.ElevationMode;
import de.raindancer.modules.wallsroads.model.RoadPath;
import de.raindancer.modules.wallsroads.model.Wall;
import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Walls and roads as documents in Core's database — and the 1.x files still lying in the folders. */
@Tag("database")
class WallsRoadsStorageDocumentsTest {

    private static final String BROKEN = "id: [unclosed\n  : : :\n";

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Path wallsFolder() {
        return plugins.resolve("RainsWallsRoads").resolve("walls");
    }

    private Path roadsFolder() {
        return plugins.resolve("RainsWallsRoads").resolve("roads");
    }

    private DocumentFolder walls() {
        return DocumentFolder.on(wallsFolder(), documents, Documents.Scope.SERVER);
    }

    private DocumentFolder roads() {
        return DocumentFolder.on(roadsFolder(), documents, Documents.Scope.SERVER);
    }

    private WallsRoadsStorage storage() {
        return new WallsRoadsStorage(walls(), roads());
    }

    private static Wall wall(String id) {
        return new Wall(id, "Town Wall " + id, UUID.randomUUID(), "world", ColumnPolygon.rectangle(0, 0, 20, 20),
                64, 6, Material.STONE_BRICKS, 2, CornerStyle.SHARP);
    }

    private static RoadPath road(String id) {
        Polyline path = new Polyline(List.of(new ColumnPolygon.Column(0, 0), new ColumnPolygon.Column(10, 5)));
        return new RoadPath(id, "Road " + id, UUID.randomUUID(), "world", path, 3, Material.DIRT_PATH,
                ElevationMode.FIXED_Y, 64);
    }

    @Test
    @DisplayName("a wall and a road saved come back whole, from the database and not from a file")
    void roundTrip() {
        Wall wall = wall("w1");
        RoadPath road = road("r1");
        assertThat(storage().saveWall(wall)).isTrue();
        assertThat(storage().saveRoad(road)).isTrue();

        List<Wall> loadedWalls = storage().loadAllWalls();
        List<RoadPath> loadedRoads = storage().loadAllRoads();

        assertThat(loadedWalls).hasSize(1);
        assertThat(loadedWalls.get(0).name()).isEqualTo("Town Wall w1");
        assertThat(loadedWalls.get(0).owner()).isEqualTo(wall.owner());
        assertThat(loadedRoads).extracting(RoadPath::id).containsExactly("r1");
        assertThat(wallsFolder().resolve("w1.yml")).doesNotExist();
        assertThat(roadsFolder().resolve("r1.yml")).doesNotExist();
    }

    @Test
    @DisplayName("a deleted wall and road are gone from the listing, and the other kind is left alone")
    void delete() {
        storage().saveWall(wall("a"));
        storage().saveWall(wall("b"));
        storage().saveRoad(road("a"));

        storage().deleteWall("a");

        assertThat(storage().loadAllWalls()).extracting(Wall::id).containsExactly("b");
        assertThat(storage().loadAllRoads()).extracting(RoadPath::id).containsExactly("a");
        storage().deleteRoad("a");
        assertThat(storage().loadAllRoads()).isEmpty();
    }

    @Test
    @DisplayName("a 1.x file is listed, brought in on its first read and renamed")
    void oldFileIsBroughtIn() throws IOException {
        Files.createDirectories(wallsFolder());
        Files.writeString(wallsFolder().resolve("old.yml"), """
                id: old
                name: Old Wall
                world: world
                vertices:
                - 0,0
                - 10,0
                - 10,10
                - 0,10
                min-y: 64
                """);

        List<Wall> loaded = storage().loadAllWalls();

        assertThat(loaded).extracting(Wall::name).containsExactly("Old Wall");
        assertThat(wallsFolder().resolve("old.yml")).doesNotExist();
        assertThat(storage().loadAllWalls()).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable entry is not loaded, not lost and not overwritten")
    void unreadableIsKept() {
        storage().saveWall(wall("fine"));
        assertThat(walls().store("broken.yml").writeText(BROKEN)).isTrue();

        assertThat(storage().loadAllWalls()).extracting(Wall::id).containsExactly("fine");

        assertThat(walls().names(".yml")).contains("broken.yml");
        assertThat(walls().store("broken.yml").readText()).contains(BROKEN);
    }

    @Test
    @DisplayName("an unreadable 1.x file stays a file, untouched")
    void unreadableFileStays() throws IOException {
        Files.createDirectories(roadsFolder());
        Files.writeString(roadsFolder().resolve("broken.yml"), BROKEN);

        assertThat(storage().loadAllRoads()).isEmpty();

        assertThat(roadsFolder().resolve("broken.yml")).exists().hasContent(BROKEN);
    }
}
