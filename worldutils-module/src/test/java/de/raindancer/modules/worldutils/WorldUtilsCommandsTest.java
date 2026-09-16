package de.raindancer.modules.worldutils;

import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.worldutils.util.PermissionNodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** What the module declares at bootstrap, and the state it must survive: registered before it runs. */
class WorldUtilsCommandsTest {

    @Test
    @DisplayName("/w, /dim and /worlds, under the names that were asked for")
    void theCommands() {
        List<ModuleCommand> declared = WorldUtilsCommands.declared();

        assertThat(declared).extracting(ModuleCommand::name).containsExactly("w", "dim", "worlds");
        assertThat(declared.get(0).permission()).isEqualTo(PermissionNodes.WORLD);
        assertThat(declared.get(1).permission()).isEqualTo(PermissionNodes.DIMENSION);
        assertThat(declared.get(2).permission()).isEqualTo(PermissionNodes.ADMIN);
        assertThat(declared.get(2).audited()).as("resetting and deleting worlds belongs in the journal").isTrue();
    }

    @Test
    @DisplayName("declared before anything is built, and refusing rather than returning nothing")
    void beforeTheModuleRuns() {
        assertThat(WorldUtilsCommands.isRunning()).isFalse();
        for (ModuleCommand command : WorldUtilsCommands.declared()) {
            assertThatCode(() -> command.handler().execute(null, new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not running");
        }
    }

    @Test
    @DisplayName("every permission a command asks for is one the module registers")
    void permissionsAreDeclared() {
        List<String> declared = PermissionNodes.declared().stream().map(p -> p.getName()).toList();

        assertThat(declared).contains(PermissionNodes.WORLD, PermissionNodes.WORLD_OTHERS,
                PermissionNodes.DIMENSION, PermissionNodes.DIMENSION_OTHERS, PermissionNodes.ADMIN);
    }
}
