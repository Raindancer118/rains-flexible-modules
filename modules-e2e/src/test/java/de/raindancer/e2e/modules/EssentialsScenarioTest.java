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

    /** The shipped greetings — {name} is where the newcomer's name goes. */
    private static final List<String> GREETINGS = List.of("Hi {name}!", "Look who finally showed up — {name}!",
            "{name} has entered the chat", "Welcome back {name}, we missed you (a bit)", "Oh no, it's {name}",
            "Hey {name}, wipe your boots", "Yo {name}", "{name}! The legend returns",
            "Hide your diamonds, {name} is here", "Howdy {name}");

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
            bo.answer(() -> bo.clickButtonOn("Cy", 0), answer -> answer.says("You already said hi"));
            assertThat(greetingsTo(server, "Cy")).as("a second click greets nobody").isEqualTo(1);

            // An advancement line carries Congrats!, which cheers Bo on once.
            server.console("advancement grant Bo only minecraft:story/mine_stone");
            cy.clickButtonOn("[Congrats!]", 0);
            Await.until("Cy cheers Bo in chat", Duration.ofSeconds(10), () -> cheersFor(server, "Bo") == 1);
            cy.answer(() -> cy.clickButtonOn("[Congrats!]", 0), answer -> answer.says("You already cheered"));
            assertThat(cheersFor(server, "Bo")).isEqualTo(1);

            // A nickname reaches the nametag above the head.
            bo.runAndExpect("nick Rainbow", "Rainbow");
            Await.until("Bo's nametag says Rainbow", Duration.ofSeconds(10),
                    () -> server.console("execute as Bo at @s as @e[type=minecraft:text_display,distance=..3,"
                            + "sort=nearest,limit=1] run data get entity @s text").contains("Rainbow"));

            // A plugin teleport with a styled nametag on arrives — a name that rode the player made
            // Paper refuse every one — and the name comes along.
            Bot ada = server.admin("Ada");
            ada.run("setspawn");
            Await.ticks(10);
            server.console("tp Bo 400 120 400");
            Await.until("Bo is far out", Duration.ofSeconds(10), () -> bo.position().getX() > 390);
            // Landed first: /spawn waits for its wearer to stand still, and falling is moving.
            Await.until("Bo has landed", Duration.ofSeconds(20), () -> {
                double before = bo.position().getY();
                Await.ticks(10);
                return Math.abs(bo.position().getY() - before) < 0.01;
            });
            bo.forgetChat();
            bo.run("spawn");
            try {
                Await.until("Bo arrives at spawn", Duration.ofSeconds(15), () -> bo.position().getX() < 300);
            } catch (AssertionError stuck) {
                throw new AssertionError("Bo did not arrive; he was told " + bo.chatText()
                        + " and stands at " + bo.position(), stuck);
            }
            assertThat(bo.chatText()).noneMatch(line -> line.contains("stopped you"));
            Await.until("the nametag followed", Duration.ofSeconds(5),
                    () -> server.console("execute as Bo at @s if entity @e[type=minecraft:text_display,distance=..3]")
                            .contains("passed"));

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials", "RainsCosmetics")).isEmpty();
        }
    }

    private static long greetingsTo(Server server, String name) {
        return server.paper.logLines(line -> line.contains("<Bo> ")
                && GREETINGS.stream().anyMatch(hi -> line.endsWith(hi.replace("{name}", name)))).size();
    }

    /** The shipped cheers. */
    private static final List<String> CHEERS = List.of("GG {name}", "Took you long enough, {name}",
            "{name} is carrying the server", "Look at {name} go", "Absolute legend, {name}",
            "{name}'s mom would be proud", "Someone call the news, {name} did it", "Huge W for {name}",
            "Not bad for a beginner, {name}", "And they said {name} couldn't do it");

    private static long cheersFor(Server server, String name) {
        return server.paper.logLines(line -> line.contains("<Cy> ")
                && CHEERS.stream().anyMatch(cheer -> line.endsWith(cheer.replace("{name}", name)))).size();
    }
}
