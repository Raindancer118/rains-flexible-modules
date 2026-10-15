package de.raindancer.modules.oneblock;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OneBlockCommandsTest {

    @Test
    @DisplayName("the command can be declared without the module being anywhere near running")
    void declaringNeedsNothingLive() {
        List<ModuleCommand> declared = OneBlockCommands.declared();

        assertThat(declared).extracting(ModuleCommand::name).containsExactly("island", "islandadmin");
        assertThat(declared.getFirst().aliases()).contains("is");
        assertThat(declared.getFirst().options()).contains("invite <player>", "guest <player>", "accept [player]",
                "nether", "end", "unlock <nether|end>", "upgrades [dimension]");
        assertThat(declared.get(1).options()).contains("up <island> [time|forever]", "down <island> [time|forever]",
                "autounload <island> on|off");
    }

    @Test
    @DisplayName("running one while the module is stopped says so rather than throwing a null")
    void aStoppedModuleIsNamed() {
        OneBlockCommands.stopped();

        assertThat(OneBlockCommands.isRunning()).isFalse();
        for (ModuleCommand command : OneBlockCommands.declared()) {
            assertThatThrownBy(() -> command.handler().execute(null, new String[]{"accept"}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }
    }
}
