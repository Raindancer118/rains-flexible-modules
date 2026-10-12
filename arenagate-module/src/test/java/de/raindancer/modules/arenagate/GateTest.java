package de.raindancer.modules.arenagate;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.arenagate.rules.GoRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GateTest {

    @Test
    @DisplayName("every default spelled out, and nonsense clamped")
    void settings() {
        assertThat(GateSettings.DEFAULTS.arenaServer()).isEqualTo("arena");
        assertThat(GateSettings.DEFAULTS.warmupSeconds()).isEqualTo(3);
        assertThat(GateSettings.DEFAULTS.houseCutPercent()).isZero();
        assertThat(GateSettings.DEFAULTS.withHouseCutPercent(10).warmupSeconds()).isEqualTo(3);
        assertThat(GateSettings.DEFAULTS.withWarmupSeconds(-1).warmupClamped()).isZero();
        assertThat(GateSettings.DEFAULTS.withWarmupSeconds(99).warmupClamped()).isEqualTo(30);
        assertThat(GateSettings.DEFAULTS.withArenaServer(" ").arenaOrDefault()).isEqualTo("arena");
        assertThat(GateSettings.DEFAULTS.withArenaServer("pvp").warmupSeconds()).isEqualTo(3);
    }

    @Test
    @DisplayName("each reason not to go is its own sentence, permission first")
    void rule() {
        GoRule rule = new GoRule();

        assertThat(rule.refusal(true, false)).isEmpty();
        assertThat(rule.refusal(false, true)).contains("arenagate.not-allowed");
        assertThat(rule.refusal(true, true)).contains("arenagate.already-going");
    }

    @Test
    @DisplayName("the command is declared without anything live, and says so when the module is stopped")
    void commands() {
        GateCommands.stopped();

        assertThat(GateCommands.declared()).extracting(ModuleCommand::name).containsExactly("arena", "pvp");
        for (ModuleCommand command : GateCommands.declared()) {
            assertThatThrownBy(() -> command.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("not running");
        }
    }
}
