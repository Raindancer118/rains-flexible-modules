package de.raindancer.modules.arena.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpecCodecTest {

    @Test
    @DisplayName("every kind of spec survives the trip as it was")
    void roundTrip() {
        List<MatchSpec> specs = List.of(
                MatchSpec.duel("knight").withBestOf(3).withStake(250),
                new MatchSpec(Mode.TEAMS, "netherite", 1, 0, 3, null),
                new MatchSpec(Mode.FFA, MatchSpec.OWN_GEAR, 1, 0, 1, null),
                new MatchSpec(Mode.SUMO, null, 5, 10, 1, null),
                MatchSpec.waves(WaveSpec.PRESETS.get("nether"), "archer"),
                MatchSpec.waves(new WaveSpec("custom", 12, WaveSpec.Selection.CYCLE,
                        List.of("RAVAGER", "BLAZE"), 2, 3), "knight"));

        for (MatchSpec spec : specs) {
            assertThat(SpecCodec.decode(SpecCodec.encode(spec))).isEqualTo(spec);
        }
    }

    @Test
    @DisplayName("garbled or missing fields fall back to the defaults instead of failing")
    void garbled() {
        Map<String, String> data = new HashMap<>();
        data.put("mode", "nonsense");
        data.put("bestOf", "lots");
        data.put("stake", "-5");

        MatchSpec spec = SpecCodec.decode(data);

        assertThat(spec.mode()).isEqualTo(Mode.DUEL);
        assertThat(spec.bestOf()).isEqualTo(1);
        assertThat(spec.stake()).isZero();
        assertThat(SpecCodec.decode(Map.of()).kit()).isEqualTo("gladiator");
    }
}
