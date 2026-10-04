package de.raindancer.e2e;

import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The harness itself, on a plain Paper server with nothing but its probe: a bot gets in, and sees what
 * the server sends it — an item with its name and tags, a game mode, a teleport, a title, a boss bar,
 * a sidebar, a chat line with a click on it — and a restart is survived.
 */
@Tag("e2e")
class HarnessTest {

    private static PaperServer server;

    @BeforeAll
    static void boot() {
        server = PaperServer.builder(E2e.PAPER_VERSION, E2e.PAPER_BUILD).seed(1)
                .in(E2e.serverFolder("harness")).build().start();
    }

    @AfterAll
    static void shut() {
        if (server != null) {
            server.keepLogsIn(E2e.logsFolder("harness"));
            server.close();
        }
    }

    @Test
    @DisplayName("a bot joins, and sees its items, game mode, place, titles, bars, sidebar and chat")
    void seesWhatItIsSent() {
        Bot alex = server.bot("Alex").join();

        server.console("give Alex minecraft:compass[custom_name='Finder',custom_data={PublicBukkitValues:{\"e2e:kind\":\"tracker\"}}] 1");
        Bot.Item compass = alex.expectItem("the compass", item -> item.is("COMPASS"));
        assertThat(compass.name()).isEqualTo("Finder");
        assertThat(compass.tag("kind")).contains("tracker");

        server.console("gamemode spectator Alex");
        alex.expectGameMode(GameMode.SPECTATOR);

        server.console("tp Alex 100 120 -50");
        Await.until("Alex stands at 100 120 -50", Duration.ofSeconds(10),
                () -> Math.floor(alex.position().getX()) == 100 && Math.floor(alex.position().getZ()) == -50);

        server.console("title Alex title {\"text\":\"Hello\"}");
        alex.expectTitle("Hello");
        server.console("bossbar add e2e:bar {\"text\":\"Ticking\"}");
        server.console("bossbar set e2e:bar players Alex");
        alex.expectBossBar("Ticking");
        server.console("scoreboard objectives add e2e dummy {\"text\":\"Board\"}");
        server.console("scoreboard objectives setdisplay sidebar e2e");
        server.console("scoreboard players set Line e2e 3");
        alex.expectSidebar("Line");
        assertThat(alex.sidebar().getFirst()).isEqualTo("Board");

        server.console("tellraw Alex {\"text\":\"[click]\",\"click_event\":{\"action\":\"run_command\",\"command\":\"/help\"}}");
        assertThat(alex.expectChat("[click]").clicks()).containsExactly("/help");
    }

    @Test
    @DisplayName("the probe records commands, and a restart is survived by a bot that rejoins")
    void probeAndRestart() {
        Bot sam = server.bot("Sam").join();
        sam.run("help");

        Await.until("the probe saw Sam's command", Duration.ofSeconds(10),
                () -> server.eventsText().contains("\"sender\":\"Sam\""));

        server.restart();
        server.bot("Sam").join();
        assertThat(server.errorsFrom("E2eProbe")).isEmpty();
    }
}
