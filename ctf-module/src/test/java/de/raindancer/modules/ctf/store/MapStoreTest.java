package de.raindancer.modules.ctf.store;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.modules.ctf.model.Base;
import de.raindancer.modules.ctf.model.CtfMap;
import de.raindancer.modules.ctf.model.GameType;
import de.raindancer.modules.ctf.model.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("maps on disk")
class MapStoreTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a map comes back exactly as it was saved")
    void roundTrip() {
        MapStore store = new MapStore(folder);
        CtfMap map = CtfMap.fresh("sky-flag", "sky-flag.schem", 120, 65, 109)
                .withTitle("Sky Flag", "DearDeath")
                .withBase(new Base(TeamColour.RED, new Point(13, 26, 54, 90f, 0f), List.of(Point.of(10, 26, 54))))
                .withBase(new Base(TeamColour.BLUE, Point.of(105, 26, 54), List.of(Point.of(108, 26, 54),
                        Point.of(108, 26, 50))))
                .withNeutral(Point.of(59, 26, 54))
                .withControl(Point.of(59, 30, 54))
                .withPowerUp(Point.of(40, 26, 54))
                .withSupply(Point.of(59, 40, 54))
                .withFloor(10)
                .withSpectate(Point.of(59, 60, 54));

        assertThat(store.save(map)).isTrue();
        CtfMap read = new MapStore(folder).load("sky-flag").orElseThrow();

        assertThat(read).isEqualTo(map);
        assertThat(read.supports(GameType.NEUTRAL)).isTrue();
        assertThat(new MapStore(folder).all()).extracting(CtfMap::id).containsExactly("sky-flag");
    }

    @Test
    @DisplayName("a file written by hand, lists at the key's own indent, is read in full")
    void byHand() throws Exception {
        Files.writeString(folder.resolve("test.yml"), String.join("\n",
                "file: test.schem", "title: Test Strip", "author: e2e", "size: 40 1 9", "floor: -10",
                "bases:", "  red:", "    flag: 2 1 4 270 0", "    spawns:", "    - 4.5 1 4.5 270 0",
                "  blue:", "    flag: 37 1 4 90 0", "    spawns:", "    - 35.5 1 4.5 90 0", ""));
        CtfMap map = new MapStore(folder).load("test").orElseThrow();
        assertThat(map.completeBases()).hasSize(2);
        assertThat(map.isPlayable()).isTrue();
    }

    @Test
    @DisplayName("an id can never lead out of the folder")
    void ids() {
        assertThat(MapStore.isId("../evil")).isFalse();
        assertThat(MapStore.isId("Sky")).isFalse();
        assertThat(MapStore.isId("sky-flag-2")).isTrue();
        assertThat(new MapStore(folder).load("../evil")).isEmpty();
        assertThat(MapStore.idOf("Empire War A.schem")).isEqualTo("empire-war-a");
    }

    @Test
    @DisplayName("a file naming no schematic is left out, a base of an unknown colour too")
    void broken() throws Exception {
        Files.writeString(folder.resolve("broken.yml"), "title: x\n");
        Files.writeString(folder.resolve("odd.yml"), "file: odd.schem\nbases:\n  mauve:\n    flag: 1 2 3\n");
        MapStore store = new MapStore(folder);
        assertThat(store.load("broken")).isEmpty();
        assertThat(store.load("odd").orElseThrow().bases()).isEmpty();
    }

    @Test
    @DisplayName("the schematics in the folder are listed, nothing else")
    void schematics() throws Exception {
        Files.writeString(folder.resolve("a.schem"), "");
        Files.writeString(folder.resolve("b.yml"), "");
        assertThat(new MapStore(folder).schematics()).containsExactly("a.schem");
    }
}
