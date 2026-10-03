package de.raindancer.modules.speedrun.manhunt.tracker;

import de.raindancer.modules.speedrun.manhunt.tracker.StructureChoices.Choice;
import org.bukkit.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("what a Runner's structure compass can point at")
class StructureChoicesTest {

    @Test
    @DisplayName("never a stronghold — in any dimension, under any variant")
    void noStronghold() {
        for (World.Environment environment : World.Environment.values()) {
            assertThat(StructureChoices.in(environment))
                    .flatExtracting(Choice::structureKeys)
                    .noneMatch(key -> key.contains("stronghold"));
        }
    }

    @Test
    @DisplayName("each dimension offers only what generates there")
    void perDimension() {
        assertThat(StructureChoices.in(World.Environment.NORMAL)).extracting(Choice::id)
                .contains("village", "desert_pyramid", "ruined_portal", "ancient_city")
                .doesNotContain("fortress", "bastion_remnant", "end_city");
        assertThat(StructureChoices.in(World.Environment.NETHER)).extracting(Choice::id)
                .containsExactlyInAnyOrder("fortress", "bastion_remnant", "ruined_portal_nether");
        assertThat(StructureChoices.in(World.Environment.THE_END)).extracting(Choice::id)
                .containsExactly("end_city");
    }

    @Test
    @DisplayName("a village is every kind of village")
    void variants() {
        assertThat(StructureChoices.byId("village").orElseThrow().structureKeys())
                .contains("village_plains", "village_desert", "village_snowy");
    }

    @Test
    @DisplayName("within 20 blocks, measured flat — an ancient city far below still counts as reached")
    void reached() {
        assertThat(StructureChoices.reached("w", 0, 0, "w", 12, 15)).isTrue();
        assertThat(StructureChoices.reached("w", 0, 0, "w", 15, 15)).isFalse();
        assertThat(StructureChoices.reached("w", 0, 0, "w_nether", 1, 1)).isFalse();
    }
}
