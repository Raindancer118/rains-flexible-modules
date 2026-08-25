package de.raindancer.modules.wallsroads.rules;

import de.raindancer.core.world.build.BuildSnapshot;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.wallsroads.store.Occupancy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Who may break a wall, and who may open a gate. Asked speculatively; decides nothing itself. */
class StructureRulesTest {

    private static final Spot IN_THE_WALL = new Spot("world", 5, 70, 5);
    private static final Spot OPEN_GROUND = new Spot("world", 500, 70, 500);

    private final UUID owner = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();
    private final Occupancy occupancy = new Occupancy();
    private final ProtectRule protect = new ProtectRule();
    private final GateRule gate = new GateRule();

    private void wallStandsAt(Spot spot) {
        occupancy.claim("wall-1", new BuildSnapshot(
                List.of(new BuildSnapshot.Placement(spot, "GRASS_BLOCK"))));
    }

    @Test
    @DisplayName("a stranger may not break a block that belongs to somebody's wall")
    void protectsAStructure() {
        wallStandsAt(IN_THE_WALL);

        assertThat(protect.mayChange(occupancy, IN_THE_WALL, id -> Optional.of(owner), stranger, false))
                .isFalse();
    }

    @Test
    @DisplayName("the owner may")
    void ownerMayChangeTheirOwn() {
        wallStandsAt(IN_THE_WALL);

        assertThat(protect.mayChange(occupancy, IN_THE_WALL, id -> Optional.of(owner), owner, false))
                .isTrue();
    }

    @Test
    @DisplayName("somebody who may manage any structure may too")
    void staffMayChangeAnything() {
        wallStandsAt(IN_THE_WALL);

        assertThat(protect.mayChange(occupancy, IN_THE_WALL, id -> Optional.of(owner), stranger, true))
                .isTrue();
    }

    @Test
    @DisplayName("a block belonging to nothing is nothing to do with this module")
    void ignoresOpenGround() {
        wallStandsAt(IN_THE_WALL);

        assertThat(protect.mayChange(occupancy, OPEN_GROUND, id -> Optional.of(owner), stranger, false))
                .isTrue();
    }

    @Test
    @DisplayName("a structure whose owner cannot be found is still protected")
    void protectsAnOrphanedStructure() {
        wallStandsAt(IN_THE_WALL);

        assertThat(protect.mayChange(occupancy, IN_THE_WALL, id -> Optional.empty(), stranger, false))
                .isFalse();
    }

    @Test
    @DisplayName("an open gate is anybody's to work, when the wall says so")
    void publicGatesAreForEverybody() {
        assertThat(gate.mayOperate(true, owner, stranger, false)).isTrue();
    }

    @Test
    @DisplayName("a private gate answers only to its owner and to staff")
    void privateGatesAreNot() {
        assertThat(gate.mayOperate(false, owner, stranger, false)).isFalse();
        assertThat(gate.mayOperate(false, owner, owner, false)).isTrue();
        assertThat(gate.mayOperate(false, owner, stranger, true)).isTrue();
    }
}
