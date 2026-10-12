package de.raindancer.modules.arena.command;

import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SpecArgsTest {

    @Test
    @DisplayName("words in any order: a mode is a mode, a number the stake, bo3 the rounds, anything else the kit")
    void anyOrder() {
        MatchSpec spec = SpecArgs.parse(new String[]{"invite", "Bo", "500", "knight", "bo3", "duel"}, 2, Mode.DUEL);

        assertThat(spec.mode()).isEqualTo(Mode.DUEL);
        assertThat(spec.kit()).isEqualTo("knight");
        assertThat(spec.stake()).isEqualTo(500);
        assertThat(spec.bestOf()).isEqualTo(3);
    }

    @Test
    @DisplayName("2v2 is teams of two, a preset's name is a mob run with it, 'own' is own gear")
    void shorthands() {
        assertThat(SpecArgs.parse(new String[]{"2v2"}, 0, Mode.DUEL).teamSize()).isEqualTo(2);
        assertThat(SpecArgs.parse(new String[]{"3v3"}, 0, Mode.DUEL).mode()).isEqualTo(Mode.TEAMS);
        MatchSpec nether = SpecArgs.parse(new String[]{"nether", "archer"}, 0, Mode.DUEL);
        assertThat(nether.mode()).isEqualTo(Mode.WAVES);
        assertThat(nether.waves().preset()).isEqualTo("nether");
        assertThat(nether.kit()).isEqualTo("archer");
        assertThat(SpecArgs.parse(new String[]{"own"}, 0, Mode.DUEL).isOwnGear()).isTrue();
    }

    @Test
    @DisplayName("nothing typed is the default fight of the command")
    void nothing() {
        assertThat(SpecArgs.parse(new String[]{"queue"}, 1, Mode.DUEL)).isEqualTo(MatchSpec.duel(null));
        assertThat(SpecArgs.parse(new String[0], 0, Mode.WAVES).mode()).isEqualTo(Mode.WAVES);
    }
}
