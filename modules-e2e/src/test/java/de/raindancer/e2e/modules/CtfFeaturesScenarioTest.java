package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
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
 * The extras of a Capture the Flag round on a real server: a neutral-flag round with the "tiny" twist, a scout taking
 * the middle flag at once where everybody else would wait, a power-up taken by walking into it, a block placed with
 * building on — and, once the round is over, the block gone again and everybody their own size.
 */
@Tag("e2e")
class CtfFeaturesScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final double FEET = 65;
    private static final double LANE_Z = 4.5;

    @Test
    @DisplayName("neutral flag, tiny twist, scout, power-up, building — and all of it undone afterwards")
    void extras() throws Exception {
        Map<String, String> files = Map.of(
                "plugins/RainsCaptureTheFlag/config.yml", String.join("\n",
                        "default-type: neutral", "vote-maps: false", "vote-types: false", "vote-mutators: false",
                        "mutators:", "- tiny", "mutator-chance: 100", "building: own_blocks", "keep-clear: 2",
                        "pickup-seconds: 3", "power-ups: true", "power-up-seconds: 120", "supply-drops: false",
                        "control-point: false", "respawn-seconds: 1", "captures-to-win: 1", "auto-start: false",
                        "glow-after: 0", "slow-after: 0", ""),
                "plugins/RainsCaptureTheFlag/maps/strip.yml", String.join("\n",
                        "file: strip.schem", "title: Strip", "size: 40 1 9", "floor: -10",
                        "neutral: 20 1 4 0 0", "power-ups:", "- 10.5 1 7.5 0 0",
                        "bases:", "  red:", "    flag: 2 1 4 270 0", "    spawns:", "    - 4.5 1 4.5 270 0",
                        "  blue:", "    flag: 37 1 4 90 0", "    spawns:", "    - 35.5 1 4.5 90 0", ""));
        try (Server server = Server.start("ctf-features", List.of("ctf-standalone:RainsCaptureTheFlag-.*"),
                List.of(), files, List.of("Capture the Flag is up"))) {
            java.nio.file.Files.write(server.paper.folder().resolve("plugins/RainsCaptureTheFlag/maps/strip.schem"),
                    strip(40, 9));
            Bot alice = server.player("alice");
            Bot bob = server.player("bob");
            alice.run("ctf join");
            bob.run("ctf join");
            alice.run("ctf class scout");
            alice.expectChat("you play Scout");

            server.paper.console("ctf admin start");
            server.paper.awaitLog("A round started on strip: NEUTRAL, TINY", WAIT);
            Await.until("both stand at a spawn", WAIT, () -> alice.world().contains("ctf_arena")
                    && (Math.abs(alice.position().getX() - 4.5) < 1.5 || Math.abs(alice.position().getX() - 35.5) < 1.5));
            Await.until("alice is tiny", WAIT, () -> server.paper.console("attribute alice minecraft:scale get")
                    .contains("0.5"));
            boolean aliceRed = Math.abs(alice.position().getX() - 4.5) < 1.5;
            Await.ticks(20 * 6);

            // A block placed with building on: one of the player's own, so it may also be broken again.
            server.paper.console("give bob minecraft:oak_planks 4");
            Await.until("bob has planks", WAIT, () -> bob.items().stream().anyMatch(item -> item.is("oak_planks")));
            bob.hold(bob.hotbarSlotOf(item -> item.is("oak_planks")));
            walk(bob, 28.5, 6.5);
            bob.useOn(28, 64, 7);
            Await.until("the plank stands", WAIT, () -> server.paper.console(
                    "execute in minecraft:ctf_arena if block 28 65 7 minecraft:oak_planks").contains("passed"));

            // The power-up: there, then taken by walking into it.
            Await.until("a power-up waits", WAIT, () -> server.paper.console(
                    "execute in minecraft:ctf_arena if entity @e[type=minecraft:item_display,x=10,y=65,z=7,distance=..3]")
                    .contains("passed"));
            Bot taker = aliceRed ? alice : bob;
            walk(taker, 10.5, 7.5);
            Await.until("the power-up is taken", WAIT, () -> server.paper.console(
                    "execute in minecraft:ctf_arena if entity @e[type=minecraft:item_display,x=10,y=65,z=7,distance=..3]")
                    .contains("failed"));

            // The scout takes the middle flag at once and carries it into the other side's base.
            alice.forgetChat();
            walk(alice, 20.5, LANE_Z);
            alice.expectChat("took the");

            // Thrown with the drop key: it flies, lands as a dropped flag, and she picks it up again where it lies.
            alice.look(aliceRed ? -90f : 90f, 25f);
            alice.dropHeld();
            Await.until("the flag lies somewhere", WAIT, () -> server.paper.console("ctf admin info")
                    .contains("DROPPED"));
            String info = server.paper.console("ctf admin info");
            java.util.regex.Matcher lying = java.util.regex.Pattern.compile("DROPPED at (-?\\d+) (-?\\d+) (-?\\d+)")
                    .matcher(info);
            assertThat(lying.find()).as(info).isTrue();
            double landedX = Integer.parseInt(lying.group(1)) + 0.5;
            assertThat(Math.abs(landedX - 20.5)).as("it flew some way").isGreaterThan(1.5);
            Await.ticks(40);
            walk(alice, landedX, LANE_Z);
            alice.expectChat("took the");
            walk(alice, aliceRed ? 37.5 : 2.5, LANE_Z);
            alice.expectChat("captured for");
            server.paper.awaitLog("A round on strip ended", WAIT);

            // After the results: the plank gone, everybody their own size again.
            Await.until("back in the lobby", Duration.ofSeconds(30), () -> !alice.world().contains("ctf_arena"));
            Await.until("the plank is gone", WAIT, () -> server.paper.console(
                    "execute in minecraft:ctf_arena if block 28 65 7 minecraft:air").contains("passed"));
            assertThat(server.paper.console("attribute alice minecraft:scale get")).contains("1");
            assertThat(server.paper.errorsFrom("RainsCaptureTheFlag", "RainsCore")).isEmpty();
        }
    }

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
