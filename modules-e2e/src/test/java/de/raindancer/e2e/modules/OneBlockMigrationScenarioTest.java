package de.raindancer.e2e.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.Downloads;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.PaperServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Moving a real BentoBox island over, the way S15 will be moved: the island server first runs BentoBox and AOneBlock
 * (the versions S15 runs), a player makes an island, breaks the block, builds on it and logs out there; then BentoBox
 * goes, RainsOneBlock comes, and {@code /islandadmin migrate confirm} moves it.
 *
 * <p>What is proved: the block and the build keep their exact coordinates in the island's own world; the breaks pay
 * their shards; the player comes back exactly where they logged out; the owner is asked for the money; nothing logs an
 * error.
 */
@Tag("e2e")
class OneBlockMigrationScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(120);

    @Test
    @DisplayName("a BentoBox island moves over with its block, its build, its shards and its player's spot")
    void migrate() throws Exception {
        Path bentoBox = Downloads.pinned("BentoBox-3.23.3.jar",
                "https://cdn.modrinth.com/data/aBVLHiAW/versions/Wm9asg4I/BentoBox-3.23.3.jar",
                "c2496d2a8978fbd06a012b406d6787b9a6506c88");
        Path aOneBlock = Downloads.pinned("AOneBlock-1.28.0.jar",
                "https://cdn.modrinth.com/data/qq7CK8U4/versions/SO3SQFxw/AOneBlock-1.28.0.jar",
                "f5aea03a4e738a6bcd3d9e088bd0e657f5500918");
        PaperServer server = PaperServer.forRun().seed(1).in(E2e.serverFolder("oneblock-migrate"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(bentoBox)
                .build();
        Path plugins = server.folder().resolve("plugins");
        Path bentoConfig = plugins.resolve("BentoBox").resolve("config.yml");
        Files.createDirectories(bentoConfig.getParent());
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(bentoBox.toFile())) {
            String defaults = new String(zip.getInputStream(zip.getEntry("config.yml")).readAllBytes(),
                    StandardCharsets.UTF_8);
            Files.writeString(bentoConfig, defaults.replace("    confirmations: true", "    confirmations: false"));
        }
        Path addons = plugins.resolve("BentoBox").resolve("addons");
        Files.createDirectories(addons);
        Files.copy(aOneBlock, addons.resolve(aOneBlock.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        try (server) {
            // ── phase one: BentoBox. Ada makes an island, breaks the block twelve times, builds, logs out on it ──────
            server.start();
            Bot ada = server.bot("Ada").join();
            ada.run("ob");
            Await.until("Ada has an AOneBlock island", WAIT, () -> hasIsland(plugins)
                    && ada.world().contains("oneblock_world"));
            int[] center = center(plugins);
            int x = center[0];
            int y = center[1];
            int z = center[2];
            server.console("gamemode creative Ada");
            ada.flying(true);
            server.console("execute in minecraft:oneblock_world run tp Ada " + (x + 0.5) + " " + (y + 2) + " " + (z + 2.5) + " 180 60");
            Await.ticks(20);
            for (int i = 0; i < 12; i++) {
                ada.dig(x, y, z);
                Await.ticks(6);
            }
            server.console("execute in minecraft:oneblock_world run setblock " + (x + 3) + " " + y + " " + z + " minecraft:gold_block");
            server.console("execute in minecraft:oneblock_world run tp Ada " + (x + 3.5) + " " + (y + 1) + " " + (z + 0.5) + " 90 0");
            Await.ticks(20);
            ada.leave();
            server.console("save-all flush");
            Await.until("AOneBlock counted the breaks", WAIT, () -> count(plugins) >= 12);
            long counted = count(plugins);
            server.stop();

            // ── phase two: BentoBox out, RainsOneBlock in ─────────────────────────────────────────────────────────
            try (Stream<Path> jars = Files.list(plugins)) {
                for (Path jar : jars.filter(path -> path.getFileName().toString().startsWith("BentoBox")
                        && path.toString().endsWith(".jar")).toList()) {
                    Files.delete(jar);
                }
            }
            Files.copy(OneBlockNetworkScenarioTest.jar("oneblock-standalone", "RainsOneBlock-.*"),
                    plugins.resolve("RainsOneBlock.jar"), StandardCopyOption.REPLACE_EXISTING);
            Files.createDirectories(plugins.resolve("RainsOneBlock"));
            Files.writeString(plugins.resolve("RainsOneBlock").resolve("config.yml"), "hosts-islands: true\n");
            server.start();
            server.awaitLog("This is the island server", WAIT);

            assertThat(server.console("islandadmin migrate")).isNotNull();
            server.awaitLog("would move over", WAIT);
            assertThat(server.console("islandadmin info Ada")).contains("No island");
            server.console("islandadmin migrate confirm");
            server.awaitLog("Moved 1 island", WAIT);
            assertThat(server.console("islandadmin info Ada")).contains("Ada's island");

            // ── Ada is back where she logged out, the build where she left it, the shards paid ─────────────────────
            Bot back = server.bot("Ada").join();
            Await.until("Ada is on her island's own world", WAIT, () -> back.world().contains("island_"));
            String world = back.world().replace("minecraft:", "");
            Await.until("standing where she logged out", WAIT,
                    () -> Math.abs(back.position().getX() - (x + 3.5)) < 0.6 && Math.abs(back.position().getZ() - (z + 0.5)) < 0.6);
            assertThat(server.console("execute in minecraft:" + world + " if block " + (x + 3) + " " + y + " " + z
                    + " minecraft:gold_block")).contains("passed");
            assertThat(server.console("execute in minecraft:" + world + " if block " + x + " " + y + " " + z
                    + " minecraft:air")).doesNotContain("passed");
            Await.until("she is asked how her island plays", WAIT, () -> back.window()
                    .filter(window -> window.slotNamed("Pure OneBlock").isPresent()).isPresent());
            back.click("With the SMP economy");
            Await.until("asked again", WAIT, () -> back.window()
                    .filter(window -> window.slotNamed("Yes, do it").isPresent()).isPresent());
            back.click("Yes, do it");
            back.expectChat("plays with");
            back.expectSidebar("SMP economy");
            back.expectSidebar("Shards " + (counted / 10));
            back.expectSidebar(String.format("%,d blocks broken", counted));

            // ── and the block still works where it always stood ──────────────────────────────────────────────────
            server.console("gamemode creative Ada");
            back.flying(true);
            server.console("execute in minecraft:" + world + " run tp Ada " + (x + 0.5) + " " + (y + 2) + " " + (z + 2.5) + " 180 60");
            Await.ticks(20);
            back.dig(x, y, z);
            Await.until("the break counts on the moved island", WAIT, () -> back.sidebar().stream()
                    .anyMatch(line -> line.contains(String.format("%,d blocks broken", counted + 1))));

            server.console("islandadmin migrate confirm");
            server.awaitLog("moved already", WAIT);

            assertThat(server.errorsFrom("RainsOneBlock", "RainsCore").stream()
                    .filter(line -> !line.contains("resource pack server could not start")).toList()).isEmpty();
        } finally {
            server.keepLogsIn(E2e.logsFolder("oneblock-migrate"));
        }
    }

    private static boolean hasIsland(Path plugins) {
        try {
            return islandFiles(plugins).findAny().isPresent();
        } catch (java.io.IOException notYet) {
            return false;
        }
    }

    private static Stream<Path> islandFiles(Path plugins) throws java.io.IOException {
        Path folder = plugins.resolve("BentoBox").resolve("database").resolve("Island");
        if (!Files.isDirectory(folder)) {
            return Stream.empty();
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(folder)) {
            files = listed.filter(path -> path.toString().endsWith(".json")).toList();
        }
        return files.stream().filter(path -> {
            try {
                return Files.readString(path).contains("\"owner\"");
            } catch (java.io.IOException unreadable) {
                return false;
            }
        });
    }

    private static int[] center(Path plugins) throws Exception {
        Path file = islandFiles(plugins).findFirst().orElseThrow();
        JsonArray center = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("center");
        return new int[]{(int) Math.floor(center.get(1).getAsDouble()), (int) Math.floor(center.get(2).getAsDouble()),
                (int) Math.floor(center.get(3).getAsDouble())};
    }

    private static long count(Path plugins) {
        Path folder = plugins.resolve("BentoBox").resolve("database").resolve("OneBlockIslands");
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                return json.has("lifetime") ? json.get("lifetime").getAsLong() : json.get("blockNumber").getAsLong();
            }
        } catch (java.io.IOException | RuntimeException notYet) {
            return 0;
        }
        return 0;
    }
}
