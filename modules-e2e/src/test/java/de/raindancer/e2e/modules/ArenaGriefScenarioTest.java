package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.PaperServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing that could tear a map apart gets to: a wither or an ender dragon is not let into an arena world at all, not
 * even by /summon, and an explosion there — TNT, a creeper — breaks no block.
 */
@Tag("e2e")
class ArenaGriefScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(40);

    /** Every block of the floor within this radius of the middle, checked one by one. */
    private static boolean floorWhole(PaperServer server, int radius) {
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (!server.console("execute in minecraft:arena if block " + x + " 64 " + z + " minecraft:smooth_stone")
                        .contains("passed")) {
                    return false;
                }
            }
        }
        return true;
    }

    @Test
    @DisplayName("no wither, no dragon, and explosions break nothing in an arena world")
    void nothingTearsTheMapApart() throws Exception {
        PaperServer arena = PaperServer.forRun().seed(1).in(E2e.serverFolder("arena-grief"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("arena-standalone", "RainsArena-.*"))
                .build();
        Path maps = arena.folder().resolve("plugins").resolve("RainsArena").resolve("maps");
        Files.createDirectories(maps);
        Files.write(maps.resolve("floor.schem"), floorSchematic(31, 31));
        try (arena) {
            arena.start();
            arena.awaitLog("Arena is up", WAIT);
            arena.console("arena admin build arena floor.schem");
            arena.awaitLog("'arena' is built", WAIT);
            // Keep the chunks loaded with nobody online.
            arena.console("execute in minecraft:arena run forceload add -16 -16 15 15");
            assertThat(floorWhole(arena, 6)).isTrue();

            arena.console("execute in minecraft:arena run summon wither 0 66 0");
            arena.console("execute in minecraft:arena run summon ender_dragon 0 70 0");
            Await.ticks(20);
            assertThat(arena.console("execute in minecraft:arena if entity @e[type=wither]")).contains("failed");
            assertThat(arena.console("execute in minecraft:arena if entity @e[type=ender_dragon]")).contains("failed");

            arena.console("execute in minecraft:arena run summon tnt 0 65 0 {fuse:1}");
            arena.console("execute in minecraft:arena run summon creeper 3 65 3 {Fuse:1,ignited:1b,powered:1b}");
            Await.ticks(80);
            assertThat(floorWhole(arena, 6)).as("the floor where TNT and a charged creeper went off").isTrue();
        } finally {
            arena.keepLogsIn(E2e.logsFolder("arena-grief"));
        }
    }

    private static byte[] floorSchematic(int width, int length) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(3);
            out.writeUTF("Version");
            out.writeInt(2);
            out.writeByte(3);
            out.writeUTF("DataVersion");
            out.writeInt(4440);
            for (String[] size : new String[][]{{"Width", String.valueOf(width)}, {"Height", "1"},
                    {"Length", String.valueOf(length)}}) {
                out.writeByte(2);
                out.writeUTF(size[0]);
                out.writeShort(Integer.parseInt(size[1]));
            }
            out.writeByte(10);
            out.writeUTF("Palette");
            out.writeByte(3);
            out.writeUTF("minecraft:smooth_stone");
            out.writeInt(0);
            out.writeByte(0);
            out.writeByte(7);
            out.writeUTF("BlockData");
            out.writeInt(width * length);
            out.write(new byte[width * length]);
            out.writeByte(0);
        }
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(zipped)) {
            gzip.write(raw.toByteArray());
        }
        return zipped.toByteArray();
    }

    private static Path jar(String module, String pattern) {
        Path folder = Path.of(System.getProperty("e2e.reactor")).resolve(module).resolve("target");
        try (Stream<Path> jars = Files.list(folder)) {
            return jars.filter(path -> path.getFileName().toString().matches(pattern + "\\.jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + pattern + " in " + folder));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
