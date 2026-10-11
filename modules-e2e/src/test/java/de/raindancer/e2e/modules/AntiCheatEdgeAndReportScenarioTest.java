package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lilly's SMP, 11.10.2026: somebody sneaking along the edge of a wall was flagged 172 times in seven seconds, for
 * hovering over air and for NoSlow. Here a bot does what that player did — hangs over the edge of a wall top by a
 * sliver, as vanilla's sneak back-off lets a box, walks, taps shift and sprints into a sneak — and must come out
 * clean. A bot that hovers in mid-air for twenty seconds is reported to staff, once, and nothing is done to it.
 */
@Tag("e2e")
class AntiCheatEdgeAndReportScenarioTest {

    private static final double TOP = 102;
    /** The wall is the stone at x 0, y 101; this centre leaves 0.0004 of the box over it. */
    private static final double EDGE_X = 1.2996;

    private static void tick(Bot bot, Runnable sends) {
        sends.run();
        bot.tickEnd();
        sleep(50);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    @DisplayName("sneaking along a wall edge, tapping shift and sprinting into a sneak flag nothing; a hovering bot is reported once and not touched")
    void edgeAndReport() {
        try (Server server = Server.start("anticheat-edge",
                List.of("anticheat-standalone:RainsAntiCheat-.*", "moderation-standalone:RainsModeration-.*"),
                List.of("Anti-cheat is up", "Moderation is up"))) {
            server.console("forceload add -16 -16 16 64");
            server.console("fill -12 100 -12 12 100 60 minecraft:stone");
            server.console("fill -12 101 -12 12 125 60 minecraft:air");
            server.console("fill 0 101 0 0 101 55 minecraft:stone");
            server.console("gamerule advance_time false");

            Bot ada = server.admin("Ada");
            Bot edge = server.player("Edge");
            Bot floater = server.player("Floater");
            Bot flicker = server.player("Flicker");
            server.console("tp Ada -8.5 101 -8.5 0 0");
            server.console("tp Edge " + EDGE_X + " " + TOP + " 2.5 0 0");
            server.console("tp Floater 6.5 101 6.5 0 0");
            server.console("tp Flicker -6.5 101 -6.5 0 0");
            Await.ticks(80);
            ada.forgetChat();
            // The report is in this test's assertions, not a punishment: with every action at its default (off).
            server.console("tick rate 19");

            // Sneaking along the edge: forward and shift down, the vanilla sneak walk (0.066 a tick at most).
            double z = 2.5;
            double v = 0;
            edge.keys(true, false, false, false, false, true, false);
            for (int t = 0; t < 120; t++) {
                v = v * 0.546 + 0.1 * 0.3;
                z += v;
                double at = z;
                tick(edge, () -> edge.moveTo(EDGE_X, TOP, at, true));
            }
            // Tapping shift now and then: the first tick of a press not yet slowed. (Every other tick is flicking, which is flagged.)
            for (int t = 0; t < 60; t++) {
                boolean down = t % 15 == 0;
                edge.keys(true, false, false, false, false, down, false);
                v = v * 0.546 + (down ? 0.1 : 0.1 * 0.3);
                z += v;
                double at = z;
                tick(edge, () -> edge.moveTo(EDGE_X, TOP, at, true));
            }
            // Coasting to a stop at the edge, every key up.
            edge.keys(false, false, false, false, false, true, false);
            for (int t = 0; t < 30; t++) {
                v *= 0.546;
                z += v;
                double at = z;
                tick(edge, () -> edge.moveTo(EDGE_X, TOP, at, true));
            }
            // Sprinting, then shift down: the first sneaking tick still carries sprint speed.
            edge.keys(true, false, false, false, false, false, true).sprinting(true);
            for (int t = 0; t < 25; t++) {
                v = v * 0.546 + 0.13;
                z += v;
                double at = z;
                tick(edge, () -> edge.moveTo(EDGE_X, TOP, at, true));
            }
            edge.keys(true, false, false, false, false, true, false).sprinting(false);
            for (int t = 0; t < 25; t++) {
                v = v * 0.546 + (t < 2 ? 0.13 : 0.03);
                z += v;
                double at = z;
                tick(edge, () -> edge.moveTo(EDGE_X, TOP, at, true));
            }
            assertThat(server.console("anticheat info Edge")).as("an edge walker is clean").contains("Squeaky clean");
            assertThat(server.console("anticheat log Edge")).contains("No evidence");

            // Flicking sneak every other tick while walking at full pace: the slowdown is dodged tick by tick.
            double fx = -6.5;
            double fv = 0;
            for (int t = 0; t < 120; t++) {
                boolean down = t % 2 == 0;
                flicker.keys(true, false, false, false, false, down, false);
                fv = fv * 0.546 + 0.1;
                fx += fv;
                double at = fx;
                tick(flicker, () -> flicker.moveTo(at, 101, -6.5, true));
            }
            assertThat(server.console("anticheat info Flicker")).as("a sneak flicker is NoSlow").contains("NoSlow");

            // Hovering two blocks up for twenty seconds.
            for (int t = 0; t < 400; t++) {
                tick(floater, () -> floater.moveTo(6.5, 103, 6.5, false));
            }
            Await.until("the hover is reported to staff", Duration.ofSeconds(30), () -> {
                tick(floater, () -> floater.moveTo(6.5, 103, 6.5, false));
                return server.console("reports list").contains("Floater");
            });
            String queue = server.console("reports list");
            assertThat(queue).contains("movement: fly").contains("flags in");
            assertThat(queue).as("an edge walker is not reported").doesNotContain("Edge");
            for (int t = 0; t < 400; t++) {
                tick(floater, () -> floater.moveTo(6.5, 103, 6.5, false));
            }
            String later = server.console("reports list");
            assertThat(later.split("movement:", -1).length - 1).as("one open report, not one more for every minute").isEqualTo(1);
            assertThat(ada.chatText()).anyMatch(line -> line.contains("the console reported Floater"));
            assertThat(floater.isOnline()).as("reported, not kicked").isTrue();
            assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat", "RainsModeration")).isEmpty();
        }
    }
}
