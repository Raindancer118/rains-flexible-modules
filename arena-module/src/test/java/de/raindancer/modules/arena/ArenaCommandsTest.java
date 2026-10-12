package de.raindancer.modules.arena;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArenaCommandsTest {

    @Test
    @DisplayName("the command can be declared without the module being anywhere near running")
    void declaringNeedsNothingLive() {
        List<ModuleCommand> declared = ArenaCommands.declared();

        assertThat(declared).extracting(ModuleCommand::name).containsExactly("arena", "pvp");
        assertThat(declared.getFirst().options()).contains("leave", "hub");
    }

    @Test
    @DisplayName("running it while the module is stopped says so rather than throwing a null")
    void aStoppedModuleIsNamed() {
        ArenaCommands.stopped();

        assertThat(ArenaCommands.isRunning()).isFalse();
        for (ModuleCommand command : ArenaCommands.declared()) {
            assertThatThrownBy(() -> command.handler().execute(null, new String[]{"leave"}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }
    }
}
