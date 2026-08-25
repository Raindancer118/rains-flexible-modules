package de.raindancer.modules.wallsroads;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every word this module answers to, and that none of them collides with another. */
class CommandsTest {

    private static List<ModuleCommand> declared() {
        return WallsRoadsCommands.declared();
    }

    @Test
    @DisplayName("the front page, and one word each for walls and for roads")
    void declaresTheThree() {
        assertThat(declared()).extracting(ModuleCommand::name)
                .containsExactlyInAnyOrder("wallsroads", "walls", "roads");
    }

    @Test
    @DisplayName("the singular of each is an alias, because people type both")
    void answersToTheSingularToo() {
        assertThat(everyWord()).contains("wall", "road", "walls", "roads", "wallsroads", "wr");
    }

    @Test
    @DisplayName("no word is claimed twice — Paper gives the second registration to whoever asked first")
    void nothingIsClaimedTwice() {
        assertThat(everyWord()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("each one is guarded by the module's own permission")
    void everyCommandAsksForPermission() {
        assertThat(declared()).allSatisfy(command ->
                assertThat(command.permission()).isEqualTo("rainswallsandroads.use"));
    }

    private static List<String> everyWord() {
        return declared().stream().flatMap(command -> command.names().stream()).toList();
    }
}
