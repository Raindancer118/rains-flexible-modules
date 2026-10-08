package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Knockback with ping-confirmed timing, and the anti-ESP shield, on a real server. */
@Tag("e2e")
class AntiCheatShieldScenarioTest {

    private static final double FLOOR = 101;

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** Lets a push play out the way the vanilla client would: rise, drift, fall back to the floor. */
    private static void takePush(Bot bot, double[] push, double x, double z) {
        double vx = push[0];
        double vy = push[1];
        double vz = push[2];
        double y = FLOOR;
        for (int tick = 0; tick < 30; tick++) {
            x += vx;
            y += vy;
            z += vz;
            vy = (vy - 0.08) * 0.98;
            vx *= tick == 0 ? 0.546 : 0.91;
            vz *= tick == 0 ? 0.546 : 0.91;
            boolean landed = y <= FLOOR;
            bot.moveTo(x, landed ? FLOOR : y, z, landed).tickEnd();
            sleep(50);
            if (landed) {
                return;
            }
        }
    }

    @Test
    @DisplayName("knockback ignored is caught, knockback taken is not; players behind walls are hidden but stay listed")
    void shield() {
        try (Server server = Server.start("anticheat-shield",
                List.of("anticheat-standalone:RainsAntiCheat-.*"), List.of("Anti-cheat is up"))) {
            server.console("forceload add -32 -32 32 32");
            server.console("fill -24 100 -24 24 100 24 minecraft:stone");
            server.console("fill -24 101 -24 24 120 24 minecraft:air");

            Bot ada = server.admin("Ada");
            Bot stiff = server.player("Stiff");
            Bot taker = server.player("Taker");
            Bot seer = server.player("Seer");
            Bot hider = server.player("Hider");
            Bot clicker = server.player("Clicker");
            Bot dummy = server.player("Dummy");
            server.console("tp Ada -20.5 101 -20.5");
            server.console("tp Stiff -10.5 101 10.5");
            server.console("tp Taker 10.5 101 10.5");
            server.console("tp Seer 0.5 101 -8.5");
            server.console("tp Hider 0.5 101 8.5");
            server.console("tp Clicker 15.5 101 -15.5 0 0");
            server.console("tp Dummy 15.5 101 -14 180 0");
            Await.ticks(80);
            ada.forgetChat();
            for (Bot bot : List.of(stiff, taker, seer, hider, clicker, dummy)) {
                for (int t = 0; t < 5; t++) {
                    bot.tickEnd();
                    sleep(50);
                }
            }

            // Knockback: Stiff ignores every push, Taker moves with each one.
            for (int hit = 0; hit < 6; hit++) {
                int before = taker.pushes().size();
                server.console("damage Stiff 1 minecraft:player_attack by Ada");
                server.console("damage Taker 1 minecraft:player_attack by Ada");
                Await.until("the push reaches Taker", Duration.ofSeconds(5), () -> taker.pushes().size() > before);
                takePush(taker, taker.pushes().getLast(), taker.position().getX(), taker.position().getZ());
                for (int t = 0; t < 10; t++) {
                    stiff.moveTo(stiff.position().getX(), FLOOR, stiff.position().getZ(), true).tickEnd();
                    sleep(50);
                }
                server.console("effect give Stiff minecraft:instant_health 1 5 true");
                server.console("effect give Taker minecraft:instant_health 1 5 true");
                sleep(600);
            }
            Await.until("staff hear that Stiff takes no knockback", Duration.ofSeconds(15), () ->
                    ada.chatText().stream().anyMatch(line -> line.contains("Stiff") && line.contains("Velocity")));
            assertThat(ada.chatText()).noneMatch(line -> line.contains("Taker") && line.contains("failed"));

            // Anti-ESP: a wall between Seer and Hider.
            assertThat(server.console("settings set anti-esp true")).contains("is now");
            Await.until("Seer sees Hider in the open", Duration.ofSeconds(10), () -> seer.sees(hider.id()));
            server.console("fill -6 101 0 6 106 0 minecraft:stone");
            Await.until("the wall hides Hider from Seer", Duration.ofSeconds(10), () -> !seer.sees(hider.id()));
            assertThat(seer.unlisted()).as("hidden, but still in the tab list").doesNotContain(hider.id());
            server.console("fill -6 101 0 6 106 0 minecraft:air");
            Await.until("Hider is back once the wall is gone", Duration.ofSeconds(10), () -> seer.sees(hider.id()));
            assertThat(server.console("settings set anti-esp false")).contains("is now");

            // Dampening: an autoclicker past twice its alert level hits for half.
            assertThat(server.console("settings set dampen-suspects true")).contains("is now");
            for (int burst = 0; burst < 4; burst++) {
                for (int click = 0; click < 30; click++) {
                    clicker.swing();
                    if (click % 2 == 1) {
                        clicker.tickEnd();
                    }
                    sleep(25);
                }
                sleep(300);
            }
            Await.until("Clicker is an autoclicker suspect", Duration.ofSeconds(10), () ->
                    server.console("anticheat info Clicker").contains("AutoClicker"));
            sleep(1500);
            float before = dummy.health();
            clicker.attack(dummy.id()).swing().tickEnd();
            Await.until("the hit lands", Duration.ofSeconds(5), () -> dummy.health() < before);
            assertThat(before - dummy.health()).as("a bare-fisted hit of 1, halved").isBetween(0.45f, 0.55f);

            assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat")).isEmpty();
        }
    }
}
