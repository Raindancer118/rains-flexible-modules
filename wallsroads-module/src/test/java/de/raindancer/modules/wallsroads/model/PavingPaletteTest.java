package de.raindancer.modules.wallsroads.model;

import de.raindancer.core.world.geometry.ColumnPolygon.Column;
import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Paving that reads as laid rather than as painted on. */
class PavingPaletteTest {

    @Test
    @DisplayName("a stone road is a mix of stone, not one block repeated")
    void mixesTheStones() {
        PavingPalette palette = PavingPalette.forMaterial(Material.STONE);

        Set<String> used = new HashSet<>();
        for (int x = 0; x < 200; x++) {
            used.add(palette.at(new Column(x, x % 7)));
        }

        assertThat(used).hasSizeGreaterThan(2);
        assertThat(used).contains("STONE");
    }

    @Test
    @DisplayName("the same block is picked for the same place every time — a rebuild is not a reshuffle")
    void isDeterministic() {
        PavingPalette palette = PavingPalette.forMaterial(Material.STONE);

        assertThat(palette.at(new Column(17, -4))).isEqualTo(palette.at(new Column(17, -4)));
    }

    @Test
    @DisplayName("neighbours differ often enough to look like paving and not like a checkerboard")
    void variesBetweenNeighbours() {
        PavingPalette palette = PavingPalette.forMaterial(Material.STONE);

        int different = 0;
        for (int x = 0; x < 100; x++) {
            if (!palette.at(new Column(x, 0)).equals(palette.at(new Column(x + 1, 0)))) {
                different++;
            }
        }
        assertThat(different).isBetween(30, 90);
    }

    @Test
    @DisplayName("a material with no family of its own is simply itself")
    void leavesAnUnknownMaterialAlone() {
        PavingPalette palette = PavingPalette.forMaterial(Material.EMERALD_BLOCK);

        for (int x = 0; x < 20; x++) {
            assertThat(palette.at(new Column(x, 0))).isEqualTo("EMERALD_BLOCK");
        }
    }

    @Test
    @DisplayName("the edging is a slab of the same family, so a kerb belongs to its road")
    void edgesInAMatchingSlab() {
        assertThat(PavingPalette.forMaterial(Material.STONE).slab()).isEqualTo("STONE_SLAB");
        assertThat(PavingPalette.forMaterial(Material.STONE_BRICKS).slab()).isEqualTo("STONE_BRICK_SLAB");
        assertThat(PavingPalette.forMaterial(Material.GRAVEL).slab()).isEqualTo("COBBLESTONE_SLAB");
    }

    @Test
    @DisplayName("a dirt track stays a dirt track — the mix is grey stone, not every road")
    void keepsATrackEarthen() {
        PavingPalette palette = PavingPalette.forMaterial(Material.DIRT_PATH);

        Set<String> used = new HashSet<>();
        for (int x = 0; x < 100; x++) {
            used.add(palette.at(new Column(x, 0)));
        }
        assertThat(used).allSatisfy(name ->
                assertThat(name).matches("DIRT_PATH|COARSE_DIRT|DIRT|GRAVEL"));
    }
}
