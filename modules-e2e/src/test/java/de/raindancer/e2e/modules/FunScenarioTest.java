package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** /roast and /joke on a real server: said in chat as whoever asked, with a wait in between. */
@Tag("e2e")
class FunScenarioTest {

    @Test
    @DisplayName("a roast names its target in the asker's chat line, a second line straight after has to wait")
    void roastAndJoke() {
        try (Server server = Server.start("fun",
                List.of("essentials-standalone:RainsEssentials-.*"),
                List.of("Essentials are up"))) {
            Bot bo = server.player("Bo");
            server.player("Cy");

            // Read from the server's log: a player's chat line is what the server handled, and the bots
            // only keep system messages.
            bo.run("roast Cy");
            Await.until("Bo roasts Cy in chat", Duration.ofSeconds(10),
                    () -> server.paper.logLines(line -> line.contains("<Bo> ") && line.contains("Cy")).size() == 1);

            bo.runAndExpect("joke", "to recover first");

            assertThat(server.console("settings set fun-cooldown-seconds 0")).contains("is now");
            bo.run("joke");
            Await.until("Bo tells a joke in chat", Duration.ofSeconds(10),
                    () -> server.paper.logLines(line -> line.contains("<Bo> ") && line.contains("?")).size() == 1);

            assertThat(server.console("settings set joke-enabled false")).contains("is now");
            bo.runAndExpect("joke", "switched off");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials")).isEmpty();
        }
    }
}
