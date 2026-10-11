package de.raindancer.modules.sends;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SendsCommandsTest {

    @Test
    @DisplayName("both commands can be declared without the module being anywhere near running")
    void declaringNeedsNothingLive() {
        List<ModuleCommand> declared = SendsCommands.declared();

        assertThat(declared).extracting(ModuleCommand::name).containsExactly("send", "sends");
        assertThat(declared.getFirst().options()).contains("create [player]", "accept [id]");
    }

    @Test
    @DisplayName("running one while the module is stopped says so rather than throwing a null")
    void aStoppedModuleIsNamed() {
        SendsCommands.stopped();

        assertThat(SendsCommands.isRunning()).isFalse();
        for (ModuleCommand command : SendsCommands.declared()) {
            assertThatThrownBy(() -> command.handler().execute(null, new String[]{"accept"}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }
    }
}
