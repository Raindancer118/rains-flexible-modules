package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.RealClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A replay acted out, photographed with the real game: a cheater in diamond armour with a sword flies in
 * circles until the anti-cheat alerts; a staff member watches the replay — taken to the scene as a
 * spectator, a figure in the cheater's skin and gear flying the recorded path — and is put back afterwards.
 * {@code mvn -o -Pscreenshots test -pl modules-e2e -Dtest=ReplayScreenshotsTest}; pictures under target/e2e/screenshots.
 */
@Tag("screenshots")
class ReplayScreenshotsTest {

    private static final double FLOOR = 101;

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    @Test
    @DisplayName("a fly cheater's replay: the figure in their gear, watched as a spectator from the scene")
    void replay() {
        try (Server server = Server.start("replay-screenshots",
                List.of("anticheat-standalone:RainsAntiCheat-.*"), List.of("Anti-cheat is up"))) {
            server.console("forceload add -16 -16 16 16");
            server.console("fill -12 100 -12 12 100 12 minecraft:grass_block");
            server.console("fill -12 101 -12 12 125 12 minecraft:air");
            server.console("gamerule advance_time false");
            server.console("time set noon");

            Bot flyer = server.player("Flyer");
            server.console("gamemode survival Flyer");
            for (String gear : List.of("armor.head with minecraft:diamond_helmet", "armor.chest with minecraft:diamond_chestplate",
                    "armor.legs with minecraft:diamond_leggings", "armor.feet with minecraft:diamond_boots",
                    "hotbar.0 with minecraft:netherite_sword", "weapon.offhand with minecraft:shield")) {
                server.console("item replace entity Flyer " + gear);
            }
            server.console("tp Flyer 0.5 " + FLOOR + " 0.5 0 0");
            Await.ticks(40);

            // Rising in circles, far longer than any jump: fly, alerted, kept as a replay.
            for (int t = 0; t < 240; t++) {
                double angle = t / 12.0;
                double x = 0.5 + 4 * Math.cos(angle);
                double z = 0.5 + 4 * Math.sin(angle);
                double y = FLOOR + 1 + Math.min(6, t / 30.0);
                flyer.moveTo(x, y, z, false).tickEnd();
                sleep(50);
            }
            assertThat(server.console("anticheat log Flyer")).contains("Fly");

            try (RealClient viewer = RealClient.join(server.paper, "Viewer", 6)) {
                server.console("op Viewer");
                server.console("gamemode creative Viewer");
                server.console("e2e as Viewer anticheat replay Flyer");
                Await.until("the replay's figure stands", Duration.ofSeconds(10),
                        () -> server.console("execute if entity @e[type=minecraft:mannequin]").contains("passed"));
                Await.until("the watcher is a spectator at the scene", Duration.ofSeconds(10),
                        () -> server.console("execute if entity @a[name=Viewer,gamemode=spectator]").contains("passed"));
                Await.ticks(30);
                Path first = viewer.screenshot(E2e.out().resolve("screenshots").resolve("replay-1.png"));
                Await.ticks(20);
                Path second = viewer.screenshot(E2e.out().resolve("screenshots").resolve("replay-2.png"));

                server.console("e2e as Viewer anticheat replay stop");
                Await.until("the figure is gone", Duration.ofSeconds(10),
                        () -> server.console("execute if entity @e[type=minecraft:mannequin]").contains("failed"));
                Await.until("the watcher is put back as they were", Duration.ofSeconds(10),
                        () -> server.console("execute if entity @a[name=Viewer,gamemode=creative]").contains("passed"));

                assertThat(List.of(first, second)).allSatisfy(path -> assertThat(path).exists());
                assertThat(server.paper.errorsFrom("RainsCore", "RainsAntiCheat")).isEmpty();
            }
        }
    }
}
