package de.raindancer.e2e.modules;

import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Core's Message tone, switched on a running server: a module's refusal and Core's own reply both follow it. */
@Tag("e2e")
class ToneScenarioTest {

    @Test
    @DisplayName("SERIOUS drops the remark from a module's refusal and from Core's own reply, PLAYFUL brings it back")
    void toneSwitchesLive() {
        try (Server server = Server.start("tone",
                List.of("essentials-standalone:RainsEssentials-.*"),
                List.of("Essentials are up"))) {
            Bot bo = server.player("Bo");

            bo.runAndExpect("msg Bo hello", "Try a notebook");

            String switched = server.console("settings set message-tone serious");
            assertThat(switched).contains("is now").doesNotContain("Done and dusted");

            bo.forgetChat();
            bo.runAndExpect("msg Bo hello", "You cannot message yourself");
            bo.expectNoChat("notebook", Duration.ofSeconds(2));

            assertThat(server.console("settings set message-tone playful")).contains("is now playful. Done and dusted");
            bo.forgetChat();
            bo.runAndExpect("msg Bo hello", "Try a notebook");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials")).isEmpty();
        }
    }
}
