package de.raindancer.modules.worldutils.store;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.world.poi.PoiStore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/** Against Core's real place store, in the real schema — the table a server gets. */
class LastPositionsTest {

    @TempDir
    Path folder;

    private Database database;
    private PoiStore places;
    private MockedStatic<Bukkit> bukkit;
    private World farm;

    @BeforeEach
    void setUp() {
        database = Database.open(folder.resolve("core.db"), CoreSchema.CORE, () -> false);
        places = new PoiStore(database);
        places.load();
        bukkit = mockStatic(Bukkit.class);
        farm = mock(World.class);
        UUID farmId = UUID.randomUUID();
        when(farm.getName()).thenReturn("farm");
        when(farm.getUID()).thenReturn(farmId);
        bukkit.when(() -> Bukkit.getWorld(farmId)).thenReturn(farm);
        bukkit.when(() -> Bukkit.getWorld("farm")).thenReturn(farm);
    }

    @AfterEach
    void tearDown() {
        bukkit.close();
        database.close();
    }

    @Test
    @DisplayName("the place a player left a world is where they go back to, per player and per world")
    void remembers() {
        LastPositions positions = new LastPositions(places);
        UUID alex = UUID.randomUUID();
        UUID sam = UUID.randomUUID();

        positions.remember(alex, new Location(farm, 10.5, 70, -3.5, 90f, 0f));
        positions.remember(alex, new Location(farm, 20.5, 71, -4.5, 0f, 0f));
        positions.remember(sam, new Location(farm, 1, 64, 1));

        Location back = positions.in(alex, "FARM").orElseThrow();
        assertThat(back.getX()).isEqualTo(20.5);
        assertThat(back.getWorld()).isSameAs(farm);
        assertThat(positions.in(sam, "farm")).isPresent();
        assertThat(positions.in(alex, "elsewhere")).isEmpty();
    }

    @Test
    @DisplayName("a reset world forgets every position in it, and only in it")
    void forgetsAWorld() {
        LastPositions positions = new LastPositions(places);
        UUID alex = UUID.randomUUID();
        positions.remember(alex, new Location(farm, 1, 64, 1));

        assertThat(positions.forgetWorld("farm")).isEqualTo(1);
        assertThat(positions.in(alex, "farm")).isEmpty();
        assertThat(places.ofKind(LastPositions.KIND)).isEmpty();
    }

    @Test
    @DisplayName("kept apart from homes and warps, so nothing listing those shows them")
    void ownKind() {
        new LastPositions(places).remember(UUID.randomUUID(), new Location(farm, 1, 64, 1));

        assertThat(places.ofKind("home")).isEmpty();
        assertThat(places.ofKind(LastPositions.KIND)).hasSize(1);
    }
}
