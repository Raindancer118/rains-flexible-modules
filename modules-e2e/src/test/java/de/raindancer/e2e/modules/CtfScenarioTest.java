package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A whole round of Capture the Flag on a real server: two players join from the world (what they carry kept), staff
 * start a round, the map is built, one side takes the other's flag, its carrier is struck down and the flag goes home
 * when its own side touches it, then two captures win the round, everybody is back in the lobby and leaving gives back
 * what they carried.
 *
 * <p>The map: a 40 × 1 × 9 stone strip, red's flag at its west end, blue's at its east end.
 */
@Tag("e2e")
class CtfScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    /** The map stands in slot 0: its lowest corner at 0/64/0, so its floor is y=64 and players stand at 65. */
    private static final double FEET = 65;
    private static final double RED_FLAG_X = 2.5;
    private static final double BLUE_FLAG_X = 37.5;
    private static final double LANE_Z = 4.5;

    @Test
    @DisplayName("join, take, drop, return, capture twice, win, back to the lobby, leave with your things")
    void aRound() throws Exception {
        Map<String, String> files = Map.of(
                "plugins/RainsCaptureTheFlag/config.yml", String.join("\n",
                        "pickup-seconds: 0", "respawn-seconds: 1", "captures-to-win: 2", "power-ups: false",
                        "supply-drops: false", "control-point: false", "auto-start: false", "glow-after: 0",
                        "slow-after: 0", "vote-maps: false", "vote-types: false", "vote-mutators: false",
                        "mutator-chance: 0", "classes: true", "default-class: soldier", ""),
                "plugins/RainsCaptureTheFlag/maps/test.yml", String.join("\n",
                        "file: test.schem", "title: Test Strip", "author: e2e", "size: 40 1 9", "floor: -10",
                        "bases:", "  red:", "    flag: 2 1 4 270 0", "    spawns:", "    - 4.5 1 4.5 270 0",
                        "  blue:", "    flag: 37 1 4 90 0", "    spawns:", "    - 35.5 1 4.5 90 0", ""));
        try (Server server = Server.start("ctf-round", List.of("ctf-standalone:RainsCaptureTheFlag-.*"), List.of(),
                files, List.of("Capture the Flag is up"))) {
            java.nio.file.Files.write(server.paper.folder().resolve("plugins/RainsCaptureTheFlag/maps/test.schem"),
                    strip(40, 9));
            Bot alice = server.player("alice");
            Bot bob = server.player("bob");
            server.paper.console("give alice minecraft:diamond 3");
            Await.until("alice holds the diamonds", WAIT, () -> alice.items().stream().anyMatch(item ->
                    item.is("diamond")));

            alice.run("ctf join");
            bob.run("ctf join");
            alice.expectChat("Welcome to Capture the Flag");
            Await.until("the lobby items", WAIT, () -> alice.items().stream().noneMatch(item -> item.is("diamond"))
                    && alice.items().stream().anyMatch(item -> item.is("red_banner")));

            server.paper.console("ctf admin start");
            server.paper.awaitLog("A round started on test", WAIT);
            Await.until("both are in the arena world", WAIT, () -> alice.world().contains("ctf_arena")
                    && bob.world().contains("ctf_arena"));
            Await.until("both stand at a spawn", WAIT, () -> Math.abs(alice.position().getX() - 4.5) < 1.5
                    || Math.abs(alice.position().getX() - 35.5) < 1.5);
            Bot red = Math.abs(alice.position().getX() - 4.5) < 1.5 ? alice : bob;
            Bot blue = red == alice ? bob : alice;
            Await.ticks(20 * 6);

            // Red takes blue's flag.
            walk(red, BLUE_FLAG_X, LANE_Z);
            red.expectChat("took the");
            Await.until("red wears blue's banner", WAIT, () -> red.items().stream().anyMatch(item ->
                    item.is("blue_banner")));
            blue.expectTitle("Your flag was taken");

            // Struck down halfway, red drops it; blue walks into it and it goes home.
            walk(red, 20.5, LANE_Z);
            red.forgetChat();
            server.paper.console("damage " + red.name() + " 100");
            red.expectChat("dropped the");
            walk(blue, 20.5, LANE_Z);
            blue.expectChat("brought the");

            // Red, back to life, captures twice.
            Await.until("red is back at its spawn", WAIT, () -> Math.abs(red.position().getX() - 4.5) < 1.5);
            Await.ticks(10);
            for (int capture = 1; capture <= 2; capture++) {
                red.forgetChat();
                walk(red, BLUE_FLAG_X, LANE_Z);
                red.expectChat("took the");
                walk(red, RED_FLAG_X, LANE_Z);
                red.expectChat("captured for");
            }
            red.expectChat("wins");
            server.paper.awaitLog("A round on test ended", WAIT);
            red.expectTitle("Victory");
            blue.expectTitle("Defeat");

            // Back in the lobby after the results, the map's stand banners put back as the schematic had them.
            Await.until("back in the lobby", Duration.ofSeconds(30), () -> !red.world().contains("ctf_arena")
                    && !blue.world().contains("ctf_arena"));
            Await.until("the red stand is empty again once the round is over", WAIT, () -> server.paper.console(
                    "execute in minecraft:ctf_arena if block 2 65 4 minecraft:air").contains("passed"));

            alice.run("ctf leave");
            alice.expectChat("left Capture the Flag");
            Await.until("alice has her diamonds back", WAIT, () -> alice.items().stream().anyMatch(item ->
                    item.is("diamond") && item.amount() == 3));
            assertThat(server.paper.errorsFrom("RainsCaptureTheFlag", "RainsCore")).isEmpty();
        }
    }

    /** Walks in small steps along the lane, as a client would. */
    private static void walk(Bot bot, double toX, double toZ) {
        double x = bot.position().getX();
        double z = bot.position().getZ();
        int steps = (int) Math.ceil(Math.hypot(toX - x, toZ - z) / 0.35);
        for (int step = 1; step <= steps; step++) {
            bot.moveTo(x + (toX - x) * step / steps, FEET, z + (toZ - z) * step / steps, true);
            Await.ticks(1);
        }
        Await.ticks(8);
    }

    /** A flat strip of stone, one block high, as a Sponge schematic. */
    private static byte[] strip(int width, int length) throws IOException {
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
            out.writeUTF("minecraft:stone");
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
}
