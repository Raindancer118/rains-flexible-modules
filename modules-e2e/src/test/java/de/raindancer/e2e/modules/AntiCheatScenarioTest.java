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
            Bot strafer = server.player("Strafer");
            Bot miner = server.player("Miner");
            Bot climber = server.player("Climber");
            server.console("setblock -11 99 -1 minecraft:stone");
            server.console("fill 7 101 5 9 101 5 minecraft:stone");

            place(server, "Ada", -8.5, -8.5);
            place(server, "Legit", -6.5, 6.5);
            place(server, "Hover", 3.5, 0.5);
            place(server, "Speedy", 6.5, -6.5);
            place(server, "Far", 0.5, -5);
            place(server, "Target", 0.5, 0.3);
            server.console("tp Faller 9.5 111 9.5 0 0");
            place(server, "Weird", -3.5, -3.5);
            place(server, "Multi", 1.2, 1.2);
            place(server, "Strafer", -9.5, 9.5);
            place(server, "Miner", -10.5, -0.5);
            place(server, "Climber", 6.0, 5.5);
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

            // The ground mined away under a lagging client: it stands on until the change reaches it, then falls.
            for (int t = 0; t < 5; t++) {
                tick(miner, () -> miner.moveTo(-10.5, FLOOR, -0.5, true));
            }
            miner.answerPings(false);
            server.console("setblock -11 100 -1 minecraft:air");
            for (int t = 0; t < 40 && miner.blockUpdates().stream().noneMatch(b -> b[0] == -11 && b[1] == 100 && b[2] == -1); t++) {
                tick(miner, () -> miner.moveTo(-10.5, FLOOR, -0.5, true));
            }
            // The update is in, the answer held back: a quarter of a second of lag, as from a far-away player.
            for (int t = 0; t < 5; t++) {
                tick(miner, () -> miner.moveTo(-10.5, FLOOR, -0.5, true));
            }
            miner.answerPings(true);
            double my = FLOOR;
            double mv = 0;
            while (my > FLOOR - 1) {
                mv = (mv - 0.08) * 0.98;
                my = Math.max(FLOOR - 1, my + mv);
                double at = my;
                boolean down = my <= FLOOR - 1;
                tick(miner, () -> miner.moveTo(-10.5, at, -0.5, down));
            }
            for (int t = 0; t < 5; t++) {
                tick(miner, () -> miner.moveTo(-10.5, FLOOR - 1, -0.5, true));
            }

            // Sprint-jumping up a step: the feet pass the step's top on the way up, still in the air.
            for (int climb = 0; climb < 10; climb++) {
                server.console("tp Climber 6.0 " + FLOOR + " 5.5 0 0");
                sleep(600);
                for (int t = 0; t < 3; t++) {
                    tick(climber, () -> climber.moveTo(6.0, FLOOR, 5.5, true));
                }
                double cx = 6.0;
                double cy = FLOOR;
                double up = 0.42;
                double h = 0.33;
                boolean firstAir = true;
                boolean rising = true;
                while (true) {
                    cx += h;
                    cy += up;
                    h = h * (firstAir ? 0.546 : 0.91) + 0.026;
                    firstAir = false;
                    rising = up > 0;
                    up = (up - 0.08) * 0.98;
                    boolean landed = !rising && cy <= FLOOR + 1;
                    double ax = cx;
                    double ay = landed ? FLOOR + 1 : cy;
                    tick(climber, () -> climber.moveTo(ax, ay, 5.5, landed));
                    if (landed) {
                        break;
                    }
                }
                for (int t = 0; t < 3; t++) {
                    h *= 0.546;
                    cx += h;
                    double ax = cx;
                    tick(climber, () -> climber.moveTo(ax, FLOOR + 1, 5.5, true));
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

            // Jumping east, then turning a right angle in mid-air at full speed, again and again.
            for (int hop = 0; hop < 8; hop++) {
                double sx = strafer.position().getX();
                double sz = strafer.position().getZ();
                double sy = FLOOR;
                double up = 0.42;
                tick(strafer, () -> { });
                for (int t = 0; t < 10; t++) {
                    sy += up;
                    up = (up - 0.08) * 0.98;
                    if (t < 2) {
                        sx += 0.28;
                    } else {
                        sz -= 0.28;
                    }
                    boolean landed = sy <= FLOOR;
                    double ax = sx;
                    double ay = landed ? FLOOR : sy;
                    double az = sz;
                    tick(strafer, () -> strafer.moveTo(ax, ay, az, landed));
                    if (landed) {
                        break;
                    }
                }
                sleep(300);
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
                return List.of("Hover", "Speedy", "Far", "Faller", "Weird", "Multi", "Strafer").stream()
                        .allMatch(name -> chat.stream().anyMatch(line -> line.contains(name) && line.contains("failed")));
            });
            List<String> chat = ada.chatText();
            assertThat(chat).anyMatch(line -> line.contains("Hover") && line.contains("Fly"));
            assertThat(chat).anyMatch(line -> line.contains("Speedy") && line.contains("Timer"));
            assertThat(chat).anyMatch(line -> line.contains("Far") && line.contains("Reach"));
            assertThat(chat).anyMatch(line -> line.contains("Faller") && line.contains("NoFall"));
            assertThat(chat).anyMatch(line -> line.contains("Weird") && line.contains("BadPackets"));
            assertThat(chat).anyMatch(line -> line.contains("Multi") && line.contains("MultiAura"));
            assertThat(chat).anyMatch(line -> line.contains("Strafer") && line.contains("Strafe"));
            assertThat(faller.health()).as("NoFall still costs the fall damage").isLessThan(20);

            assertThat(server.console("anticheat info Miner")).as("standing on ground mined away a ping ago").contains("Squeaky clean");
            assertThat(server.console("anticheat info Climber")).as("jumping up a step").contains("Squeaky clean");
            String legitRecord = server.console("anticheat info Legit");
            assertThat(legitRecord).as("vanilla walking and jumping is clean").contains("Squeaky clean");
            assertThat(chat).noneMatch(line -> line.contains("Legit"));
            assertThat(server.console("anticheat log Far")).contains("Reach");
            ada.runAndExpect("anticheat replay Hover", "Replay 1/");
            assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat")).isEmpty();
        }
    }
}
