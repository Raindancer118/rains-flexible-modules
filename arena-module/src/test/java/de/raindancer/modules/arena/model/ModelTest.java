package de.raindancer.modules.arena.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelTest {

    private static final Place HUB = new Place("arena", 0.5, 70, 0.5, 90f, 0f);

    @Test
    @DisplayName("a box made from two corners in any order holds every block between them, edges included")
    void boxFromCorners() {
        Box box = Box.of("arena", 10, 80, -5, -10, 60, 5);

        assertThat(box).isEqualTo(new Box("arena", -10, 60, -5, 10, 80, 5));
        assertThat(box.contains("arena", -10, 60, -5)).isTrue();
        assertThat(box.contains("arena", 10.99, 80.5, 5.2)).as("the far edge block counts, all of it").isTrue();
        assertThat(box.contains("arena", 11.0, 70, 0)).isFalse();
        assertThat(box.contains("ARENA", 0, 70, 0)).as("world names are compared the way Bukkit does").isTrue();
        assertThat(box.contains("world", 0, 70, 0)).isFalse();
    }

    @Test
    @DisplayName("a box knows its middle and its footprint")
    void boxCentre() {
        Box box = new Box("arena", 0, 64, 0, 230, 127, 195);

        assertThat(box.width()).isEqualTo(231);
        assertThat(box.length()).isEqualTo(196);
        assertThat(box.centreX()).isEqualTo(115.5);
        assertThat(box.centreZ()).isEqualTo(98.0);
    }

    @Test
    @DisplayName("a place lives in a world and is compared exactly")
    void place() {
        assertThat(HUB.world()).isEqualTo("arena");
        assertThat(HUB.blockY()).isEqualTo(70);
        assertThatThrownBy(() -> new Place(" ", 0, 0, 0, 0, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an arena world names its map file and where the map stands")
    void arenaWorld() {
        ArenaWorld world = new ArenaWorld("Arena", "coliseum.schem", new Box("Arena", 0, 64, 0, 230, 127, 195));

        assertThat(world.name()).isEqualTo("Arena");
        assertThatThrownBy(() -> new ArenaWorld("arena", "m.schem", new Box("other", 0, 0, 0, 1, 1, 1)))
                .as("the map has to be in the world it belongs to")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a pit has a floor and spawn points, and adding one keeps the others")
    void pit() {
        Pit pit = Pit.named("main", "arena")
                .withFloor(new Box("arena", 0, 60, 0, 40, 80, 30))
                .withSpawn(new Place("arena", 5, 61, 5, 0, 0))
                .withSpawn(new Place("arena", 35, 61, 25, 180, 0));

        assertThat(pit.spawns()).hasSize(2);
        assertThat(pit.isReady()).isTrue();
        assertThat(pit.withoutSpawns().spawns()).isEmpty();
        assertThat(Pit.named("empty", "arena").isReady()).as("no floor, no spawns: nobody can fight there").isFalse();
        assertThatThrownBy(() -> pit.withSpawn(new Place("elsewhere", 0, 0, 0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the layout answers which worlds are the arena's, ignoring case")
    void layoutLookups() {
        Layout layout = Layout.EMPTY
                .withWorld(new ArenaWorld("arena", "b.schem", new Box("arena", 0, 64, 0, 10, 70, 10)))
                .withWorld(new ArenaWorld("arena_2", "a.schem", new Box("arena_2", 0, 64, 0, 10, 70, 10)))
                .withHub(HUB);

        assertThat(layout.isArenaWorld("ARENA_2")).isTrue();
        assertThat(layout.isArenaWorld("world")).isFalse();
        assertThat(layout.isArenaWorld(null)).isFalse();
        assertThat(layout.world("Arena")).map(ArenaWorld::map).contains("b.schem");
        assertThat(layout.hub()).contains(HUB);
        assertThat(layout.worlds()).extracting(ArenaWorld::name).containsExactly("arena", "arena_2");
    }

    @Test
    @DisplayName("replacing a world or a pit by name keeps one of each, in place")
    void layoutReplaces() {
        Layout layout = Layout.EMPTY
                .withWorld(new ArenaWorld("arena", "old.schem", new Box("arena", 0, 64, 0, 10, 70, 10)))
                .withWorld(new ArenaWorld("arena", "new.schem", new Box("arena", 0, 64, 0, 10, 70, 10)))
                .withPit(Pit.named("main", "arena"))
                .withPit(Pit.named("main", "arena").withFloor(new Box("arena", 0, 0, 0, 1, 1, 1)));

        assertThat(layout.worlds()).extracting(ArenaWorld::map).containsExactly("new.schem");
        assertThat(layout.pits()).hasSize(1);
        assertThat(layout.pit("MAIN")).map(Pit::floor).isPresent();
        assertThat(layout.withoutPit("main").pits()).isEmpty();
    }

    @Test
    @DisplayName("the pit somebody stands in is found by its floor")
    void pitAt() {
        Pit main = Pit.named("main", "arena").withFloor(new Box("arena", 0, 60, 0, 40, 80, 30));
        Layout layout = Layout.EMPTY.withPit(main).withPit(Pit.named("unfinished", "arena"));

        assertThat(layout.pitAt("arena", 20, 61, 10)).contains(main);
        assertThat(layout.pitAt("arena", 50, 61, 10)).isEmpty();
        assertThat(layout.pitsIn("arena")).extracting(Pit::id).containsExactly("main", "unfinished");
        assertThat(layout.pitsIn("arena_2")).isEmpty();
        assertThat(List.copyOf(layout.pits())).hasSize(2);
    }
}
