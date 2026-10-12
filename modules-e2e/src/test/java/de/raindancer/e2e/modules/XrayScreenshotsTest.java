package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.RealClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an x-ray user sees of the bait layer, taken with the real, unmodified game: the probe hides plain
 * rock from this one client the way an x-ray does, so real ore and the bait veins hang in empty space.
 * Then the same views in admin mode, where staff see every bait as a block of redstone: a wide one, and
 * a close-up of one bait vein that a bot found for the camera.
 * {@code mvn -o -Pscreenshots test -pl modules-e2e -Dtest=XrayScreenshotsTest}; pictures under target/e2e/screenshots.
 */
@Tag("screenshots")
class XrayScreenshotsTest {

    private static final int X = 40;
    private static final int Y = -40;
    private static final int Z = 40;
    private static final int CLOSE = 6;

    @Test
    @DisplayName("the bait layer through an x-ray, as a player and as staff in admin mode, wide and close")
    void screenshots() {
        try (Server server = Server.start("xray-screenshots",
                List.of("moderation-standalone:RainsModeration-.*", "essentials-standalone:RainsEssentials-.*"),
                List.of("Moderation is up"))) {
            server.console("forceload add -32 -32 112 112");
            pocket(server, X, Y, Z);

            // A bot standing where the camera will be is shown the same bait layer: it finds a vein to look at.
            Bot scout = server.player("Scout");
            server.console("gamemode survival Scout");
            server.console("tp Scout " + (X + 0.5) + " " + Y + " " + (Z + 0.5));
            Await.ticks(200);
            int[] bait = nearestBait(server, scout);
            assertThat(bait).as("a bait vein near the camera").isNotNull();
            server.console("kick Scout");
            int[] eye = {bait[0] + CLOSE, bait[1], bait[2] + 2};
            // Only a floor: digging a room could let water in, and open water near a vein takes it back.
            // Inside the rock the camera sees nothing of it — the x-ray hides it — and resistance V keeps it alive.
            server.console("setblock " + eye[0] + " " + (eye[1] - 1) + " " + eye[2] + " minecraft:barrier");

            server.console("e2e xray Viewer on");
            try (RealClient viewer = RealClient.join(server.paper, "Viewer", 6)) {
                server.console("op Viewer");
                server.console("gamemode survival Viewer");
                Path shots = E2e.out().resolve("screenshots");

                look(server, X, Y, Z, -135, 20);
                Await.ticks(200);
                Path wide = viewer.screenshot(shots.resolve("xray-as-player.png"));
                look(server, eye[0], eye[1], eye[2], yaw(eye, bait), 14);
                Await.ticks(80);
                Path close = viewer.screenshot(shots.resolve("xray-close-as-player.png"));

                server.console("e2e as Viewer admin");
                Await.ticks(60);
                look(server, X, Y, Z, -135, 20);
                Await.ticks(120);
                Path wideStaff = viewer.screenshot(shots.resolve("xray-as-staff-in-admin-mode.png"));
                look(server, eye[0], eye[1], eye[2], yaw(eye, bait), 14);
                Await.ticks(80);
                Path closeStaff = viewer.screenshot(shots.resolve("xray-close-as-staff-in-admin-mode.png"));

                assertThat(List.of(wide, close, wideStaff, closeStaff)).allSatisfy(path -> assertThat(path).exists());
                assertThat(server.paper.errorsFrom("RainsCore", "RainsModeration", "RainsEssentials")).isEmpty();
            }
        }
    }

    /** A room to stand in on an invisible floor, so the server keeps the camera where the picture is. */
    private static void pocket(Server server, int x, int y, int z) {
        server.console("fill " + (x - 1) + " " + y + " " + (z - 1) + " " + (x + 1) + " " + (y + 2) + " " + (z + 1) + " minecraft:air");
        server.console("setblock " + x + " " + (y - 1) + " " + z + " minecraft:barrier");
    }

    private static void look(Server server, int x, int y, int z, float yaw, float pitch) {
        server.console("effect give Viewer minecraft:night_vision infinite 0 true");
        server.console("effect give Viewer minecraft:resistance infinite 4 true");
        server.console(String.format(Locale.ROOT, "tp Viewer %.1f %d %.1f %.1f %.1f", x + 0.5, y, z + 0.5, yaw, pitch));
    }

    /** Minecraft's yaw from the eye towards a block: 0 looks south (+z), 90 west (-x). */
    private static float yaw(int[] eye, int[] at) {
        double dx = at[0] - eye[0];
        double dz = at[2] - eye[2];
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /**
     * The closest block the scout was sent on its own where the server has plain rock, far enough from the
     * camera's room: a bait.
     */
    private static int[] nearestBait(Server server, Bot scout) {
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] update : scout.blockUpdates()) {
            if (update[3] == 0 || update[1] < Y - 12 || update[1] > Y + 12) {
                continue;
            }
            double distance = Math.sqrt(Math.pow(update[0] - X, 2) + Math.pow(update[1] - Y, 2) + Math.pow(update[2] - Z, 2));
            // A bait's real block is plain rock; a real block that changed (water flowing) is not.
            if (distance >= 12 && distance < bestDistance && server.console("execute if block " + update[0] + " "
                    + update[1] + " " + update[2] + " #minecraft:base_stone_overworld").contains("passed")) {
                bestDistance = distance;
                best = update;
            }
        }
        return best;
    }
}
