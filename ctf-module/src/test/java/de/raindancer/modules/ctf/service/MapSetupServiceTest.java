package de.raindancer.modules.ctf.service;

import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.world.build.Schematic;
import de.raindancer.modules.ctf.model.CtfMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("flags suggested from a downloaded map's banners")
class MapSetupServiceTest {

    /** A 20 × 3 × 5 box of air with banners put in. */
    private static Schematic withBanners(int[][] spots, String[] states) {
        int width = 20;
        int height = 3;
        int length = 5;
        String[] palette = new String[states.length + 1];
        palette[0] = "minecraft:air";
        System.arraycopy(states, 0, palette, 1, states.length);
        int[] blocks = new int[width * height * length];
        for (int index = 0; index < spots.length; index++) {
            int[] spot = spots[index];
            blocks[spot[0] + spot[2] * width + spot[1] * width * length] = index + 1;
        }
        return new Schematic(width, height, length, 0, List.of(palette), blocks);
    }

    @Test
    @DisplayName("red banners on one end and blue on the other become two bases, facing the middle")
    void twoBases() {
        Schematic schematic = withBanners(new int[][]{{1, 1, 2}, {2, 1, 2}, {18, 1, 2}},
                new String[]{"minecraft:red_banner[rotation=0]", "minecraft:red_wall_banner[facing=east]",
                        "minecraft:blue_banner[rotation=8]"});
        CtfMap map = MapSetupService.suggestFlags(CtfMap.fresh("t", "t.schem", 20, 3, 5), schematic);
        assertThat(map.completeBases()).extracting(base -> base.colour())
                .containsExactly(TeamColour.RED, TeamColour.BLUE);
        assertThat(map.base(TeamColour.RED).orElseThrow().spawns().getFirst().x())
                .as("the spawn is towards the middle").isGreaterThan(map.base(TeamColour.RED).orElseThrow().flag().x());
    }

    @Test
    @DisplayName("a colour with banners on both ends is decoration, not a base")
    void decoration() {
        Schematic schematic = withBanners(new int[][]{{1, 1, 2}, {18, 1, 2}},
                new String[]{"minecraft:white_banner", "minecraft:white_banner"});
        CtfMap map = MapSetupService.suggestFlags(CtfMap.fresh("t", "t.schem", 20, 3, 5), schematic);
        assertThat(map.bases()).isEmpty();
    }
}
