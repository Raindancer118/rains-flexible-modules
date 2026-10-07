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
 * The anti-cheat against clients that really cheat: bots that hover, run their clock fast, hit from
 * too far, fake landing, send impossible rotations and hit two players in one tick — and one that
 * walks and jumps exactly like vanilla, which must come out clean.
 */
@Tag("e2e")
class AntiCheatScenarioTest {

    private static final double FLOOR = 101;

    /** One client tick: whatever the tick sends, then the tick-end packet, then real time passes. */
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

    private static void place(Server server, String who, double x, double z) {
        server.console("tp " + who + " " + x + " " + FLOOR + " " + z + " 0 0");
    }

    @Test
    @DisplayName("hovering, timer, reach, nofall, bad pitch and multi-aura are caught; vanilla movement is not")
    void cheaters() {
        try (Server server = Server.start("anticheat",
                List.of("anticheat-standalone:RainsAntiCheat-.*"), List.of("Anti-cheat is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -12 100 -12 12 100 12 minecraft:stone");
            server.console("fill -12 101 -12 12 125 12 minecraft:air");
            server.console("gamerule doDaylightCycle false");

            Bot ada = server.admin("Ada");
            Bot legit = server.player("Legit");
            Bot hover = server.player("Hover");
            Bot speedy = server.player("Speedy");
            Bot far = server.player("Far");
            Bot target = server.player("Target");
            Bot faller = server.player("Faller");
            Bot weird = server.player("Weird");
            Bot multi = server.player("Multi");

            place(server, "Ada", -8.5, -8.5);
            place(server, "Legit", -6.5, 6.5);
            place(server, "Hover", 3.5, 0.5);
            place(server, "Speedy", 6.5, -6.5);
            place(server, "Far", 0.5, -5);
            place(server, "Target", 0.5, 0.3);
            server.console("tp Faller 9.5 111 9.5 0 0");
            place(server, "Weird", -3.5, -3.5);
            place(server, "Multi", 1.2, 1.2);
            Await.ticks(80);   // past the join and teleport grace
            ada.forgetChat();

            // Vanilla walking and one jump, simulated with vanilla's own numbers.
            double x = -6.5;
            double v = 0;
            for (int t = 0; t < 20; t++) {
                v = v * 0.546 + 0.098;
                double at = x += v;
                tick(legit, () -> legit.moveTo(at, FLOOR, 6.5, true));
            }
            double y = FLOOR;
            double dy = 0.42;
            boolean first = true;
            while (true) {
                v = v * (first ? 0.546 : 0.91) + 0.0196;
                first = false;
                x += v;
                y += dy;
                dy = (dy - 0.08) * 0.98;
                boolean landed = y <= FLOOR;
                double atX = x;
                double atY = landed ? FLOOR : y;
                tick(legit, () -> legit.moveTo(atX, atY, 6.5, landed));
                if (landed) {
                    break;
                }
            }

            // Hovering two blocks up.
            for (int t = 0; t < 60; t++) {
                tick(hover, () -> hover.moveTo(3.5, FLOOR + 2, 0.5, false));
            }

            // Running the clock at double speed.
            for (int t = 0; t < 120; t++) {
                speedy.moveTo(6.5, FLOOR, -6.5, true);
                speedy.tickEnd();
                sleep(25);
            }

            // Hitting from 4.7 blocks — inside what vanilla lets through, outside the real range.
            for (int hit = 0; hit < 4; hit++) {
                tick(far, () -> far.attack(target.id()).swing());
                sleep(500);
            }

            // Falling ten blocks while claiming to stand on the ground the whole way down.
            double fy = 111;
            double fv = 0;
            while (fy > FLOOR) {
                fv = (fv - 0.08) * 0.98;
                fy = Math.max(FLOOR, fy + fv);
                double at = fy;
                tick(faller, () -> faller.moveTo(9.5, at, 9.5, true));
            }
            for (int t = 0; t < 5; t++) {
                tick(faller, () -> faller.moveTo(9.5, FLOOR, 9.5, true));
            }

            // Looking further down than straight down.
            tick(weird, () -> weird.look(0, 95));

            // Two players in one tick, from a client that has been ticking normally.
            for (int t = 0; t < 5; t++) {
                tick(multi, () -> { });
            }
            tick(multi, () -> multi.attack(target.id()).attack(ada.id()).swing());

            Await.until("staff hear about every cheater", Duration.ofSeconds(20), () -> {
                List<String> chat = ada.chatText();
                return List.of("Hover", "Speedy", "Far", "Faller", "Weird", "Multi").stream()
                        .allMatch(name -> chat.stream().anyMatch(line -> line.contains(name) && line.contains("failed")));
            });
            List<String> chat = ada.chatText();
            assertThat(chat).anyMatch(line -> line.contains("Hover") && line.contains("Fly"));
            assertThat(chat).anyMatch(line -> line.contains("Speedy") && line.contains("Timer"));
            assertThat(chat).anyMatch(line -> line.contains("Far") && line.contains("Reach"));
            assertThat(chat).anyMatch(line -> line.contains("Faller") && line.contains("NoFall"));
            assertThat(chat).anyMatch(line -> line.contains("Weird") && line.contains("BadPackets"));
            assertThat(chat).anyMatch(line -> line.contains("Multi") && line.contains("MultiAura"));
            assertThat(faller.health()).as("NoFall still costs the fall damage").isLessThan(20);

            String legitRecord = server.console("anticheat info Legit");
            assertThat(legitRecord).as("vanilla walking and jumping is clean").contains("Squeaky clean");
            assertThat(chat).noneMatch(line -> line.contains("Legit"));
            assertThat(server.console("anticheat log Far")).contains("Reach");
            assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat")).isEmpty();
        }
    }
}
