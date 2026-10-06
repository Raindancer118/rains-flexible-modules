package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RainsEssentials beside RainsCosmetics: the Say Hi! button, and a nickname reaching the nametag. */
@Tag("e2e")
class EssentialsScenarioTest {

    private static final List<String> GREETINGS = List.of("Hi", "Hey", "Hello", "Welcome", "Welcome back", "Yo",
            "Hiya", "Howdy", "Good to see you", "Heyo");

    @Test
    @DisplayName("a join line carries Say Hi!, which greets the newcomer once; /nick shows above the head")
    void sayHiAndNicknames() {
        try (Server server = Server.start("essentials",
                List.of("essentials-standalone:RainsEssentials-.*", "cosmetics-standalone:RainsCosmetics-.*"),
                List.of("Cosmetics is up"))) {
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");

            // Bo saw Cy join, with a button; clicking it makes Bo say a greeting and Cy's name in chat.
            // Read from the server's log: that is the chat line as the server handled it.
            bo.clickButtonOn("Cy", 0);
            Await.until("Bo greets Cy in chat", Duration.ofSeconds(10), () -> greetingsTo(server, "Cy") == 1);
            bo.answer(() -> bo.clickButtonOn("Cy", 0), answer -> answer.says("would be a bit awkward"));
            assertThat(greetingsTo(server, "Cy")).as("a second click greets nobody").isEqualTo(1);

            // A nickname reaches the nametag above the head.
            bo.runAndExpect("nick Rainbow", "Rainbow");
            Await.until("Bo's nametag says Rainbow", Duration.ofSeconds(10),
                    () -> server.console("execute as Bo on passengers run data get entity @s text")
                            .contains("Rainbow"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials", "RainsCosmetics")).isEmpty();
        }
    }

    private static long greetingsTo(Server server, String name) {
        return server.paper.logLines(line -> line.contains("<Bo> ")
                && GREETINGS.stream().anyMatch(hi -> line.endsWith(hi + " " + name))).size();
    }
}
