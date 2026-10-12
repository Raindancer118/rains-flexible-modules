package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.E2e;
import de.raindancer.e2e.PaperServer;
import de.raindancer.e2e.VelocityProxy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fights on the arena network, end to end: a duel for a stake queued from survival and decided in the arena with the
 * pot paid out on survival, a mob run, and an invite from survival to somebody already in the arena.
 */
@Tag("e2e")
class ArenaFightsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(40);
    private static final String LINK_SECRET = "e2e-link-secret-0123456789abcdef";
    private static final String FORWARDING_SECRET = "e2e-forwarding-secret";
    private static final Pattern BALANCE = Pattern.compile("You have ⛃([0-9,]+)");

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    private static long balance(Bot bot) {
        int before = bot.chatText().size();
        bot.run("balance");
        return Await.value(() -> bot.name() + " is told their balance (was sent " + bot.chatText() + ")", WAIT, () -> {
            var lines = bot.chatText();
            for (int index = lines.size() - 1; index >= before; index--) {
                Matcher found = BALANCE.matcher(lines.get(index));
                if (found.find()) {
                    return Long.parseLong(found.group(1).replace(",", ""));
                }
            }
            return null;
        });
    }

    @Test
    @DisplayName("a duel for a stake, a mob run, and an invite across the two servers")
    void fights() throws Exception {
        int linkPort = freePort();
        String paperGlobal = VelocityProxy.paperGlobal(FORWARDING_SECRET);

        PaperServer lobby = PaperServer.forRun().seed(1).in(E2e.serverFolder("arena-fights-lobby"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("economy-standalone", "RainsEconomy-.*"))
                .plugin(jar("arenagate-standalone", "RainsArenaGate-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/link.yml", "role: guest\nhost-url: http://127.0.0.1:" + linkPort
                        + "\nsecret: " + LINK_SECRET + "\n")
                .file("plugins/RainsArenaGate/config.yml", "arena-server: arena\nwarmup-seconds: 0\n")
                .build();
        PaperServer arena = PaperServer.forRun().seed(1).in(E2e.serverFolder("arena-fights-arena"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("arena-standalone", "RainsArena-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/link.yml", "role: host\nlisten-address: 127.0.0.1\nlisten-port: " + linkPort
                        + "\nsecret: " + LINK_SECRET + "\n")
                .file("plugins/RainsArena/config.yml", "lobby-server: lobby\ncountdown-seconds: 2\n")
                .build();
        Path maps = arena.folder().resolve("plugins").resolve("RainsArena").resolve("maps");
        Files.createDirectories(maps);
        Files.write(maps.resolve("floor.schem"), floorSchematic(31, 31));

        Map<String, Integer> servers = new LinkedHashMap<>();
        servers.put("lobby", lobby.port());
        servers.put("arena", arena.port());
        try (VelocityProxy proxy = VelocityProxy.in(E2e.serverFolder("arena-fights-proxy"), servers, FORWARDING_SECRET);
             lobby; arena) {
            arena.start();
            arena.awaitLog("Arena is up", WAIT);
            lobby.start();
            lobby.awaitLog("Arena gate is up", WAIT);
            proxy.start();

            // The map is 31 x 31 with its corner at -15/64/-15: the floor is the top of it, y 64.
            arena.console("arena admin build arena floor.schem");
            arena.awaitLog("'arena' is built", WAIT);
            arena.console("arena admin sethub arena 0.5 65 12.5 180 0");
            arena.console("arena admin pit p1 floor arena -12 64 -12 12 78 8");
            arena.console("arena admin pit p1 spawn arena -8.5 65 0.5 -90 0");
            arena.console("arena admin pit p1 spawn arena 8.5 65 0.5 90 0");
            assertThat(arena.console("arena admin info")).contains("ready");

            Bot ada = lobby.bot("Ada").through(proxy.port()).join();
            Bot bo = lobby.bot("Bo").through(proxy.port()).join();
            Await.until("the economy knows them", WAIT, () -> !lobby.console("eco set Ada 1000").contains("unknown")
                    && !lobby.console("eco set Bo 1000").contains("unknown"));
            assertThat(balance(ada)).isEqualTo(1000);
            assertThat(balance(bo)).isEqualTo(1000);

            // ── a duel for a stake, queued from survival ────────────────────────────────────────────
            ada.run("pvp queue duel gladiator 100");
            bo.run("pvp queue duel gladiator 100");
            Await.until("both are in the arena (Ada was sent " + ada.chatText() + ")", WAIT,
                    () -> arena.console("list").contains("Ada") && arena.console("list").contains("Bo"));
            Await.until("the fight began for both", WAIT, () -> said(ada, "Round 1") && said(bo, "Round 1"));
            Await.until("both stand on the pit floor", WAIT, () -> arena.console(
                    "execute in minecraft:arena if entity @a[x=-12,y=64,z=-12,dx=24,dy=14,dz=20]").contains("2"));
            // Past the two-second countdown, so the blow lands in a running fight.
            Await.ticks(80);
            // What a fight leaves lying about: an arrow, a lingering cloud, a little experience.
            arena.console("execute in minecraft:arena run summon arrow 2 65 2");
            arena.console("execute in minecraft:arena run summon area_effect_cloud 0 65 -3 {Duration:6000,Radius:2f}");
            arena.console("execute in minecraft:arena run summon experience_orb -2 65 2 {Value:5}");
            assertThat(arena.console("execute in minecraft:arena if entity @e[type=area_effect_cloud]")).contains("passed");
            arena.console("kill Bo");
            Await.until("Ada won", WAIT, () -> said(ada, "Victory"));
            Await.until("the pit is clear again", WAIT, () ->
                    arena.console("execute in minecraft:arena if entity @e[type=arrow]").contains("failed")
                            && arena.console("execute in minecraft:arena if entity @e[type=area_effect_cloud]").contains("failed")
                            && arena.console("execute in minecraft:arena if entity @e[type=experience_orb]").contains("failed"));
            Await.until("Ada was told she won the pot", WAIT, () -> said(ada, "You won"));
            assertThat(arena.console("list")).as("a defeat is not a death: Bo is still here").contains("Bo");

            ada.run("arena leave");
            bo.run("arena leave");
            Await.until("both are back on survival", WAIT, () -> lobby.console("list").contains("Ada")
                    && lobby.console("list").contains("Bo"));
            Await.until("the pot reached Ada", WAIT, () -> balance(ada) == 1100);
            assertThat(balance(bo)).as("Bo paid his stake and got nothing back").isEqualTo(900);
            assertThat(lobby.log()).contains("Bo went to the arena").contains("Ada is back from the arena");

            // ── a mob run ───────────────────────────────────────────────────────────────────────────
            bo.run("arena wave warmup gladiator");
            Await.until("Bo is in the arena", WAIT, () -> arena.console("list").contains("Bo"));
            Await.until("the first wave is out", WAIT, () -> arena.console(
                    "execute in minecraft:arena if entity @e[type=!player,type=!item,x=-12,y=64,z=-12,dx=24,dy=14,dz=20]")
                    .contains("passed"));
            arena.console("kill Bo");
            Await.until("the mobs won", WAIT, () -> said(bo, "The mobs won"));
            Await.until("the mobs are cleared away", WAIT, () -> arena.console(
                    "execute in minecraft:arena if entity @e[type=zombie]").contains("failed")
                    && arena.console("execute in minecraft:arena if entity @e[type=skeleton]").contains("failed"));

            // ── an invite from survival to somebody in the arena ────────────────────────────────────
            ada.run("pvp invite Bo duel knight");
            Await.until("Bo, in the arena, is asked", WAIT, () -> said(bo, "asks you to a"));
            bo.run("pvp accept");
            Await.until("Ada was brought over", WAIT, () -> arena.console("list").contains("Ada"));
            Await.until("the fight began", WAIT, () -> bo.chatText().stream()
                    .filter(line -> line.contains("Round 1")).count() >= 2);
        } finally {
            lobby.keepLogsIn(E2e.logsFolder("arena-fights-lobby"));
            arena.keepLogsIn(E2e.logsFolder("arena-fights-arena"));
        }
    }

    private static byte[] floorSchematic(int width, int length) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeByte(10);
            out.writeUTF("");
            intTag(out, "Version", 2);
            intTag(out, "DataVersion", 4440);
            shortTag(out, "Width", width);
            shortTag(out, "Height", 1);
            shortTag(out, "Length", length);
            out.writeByte(10);
            out.writeUTF("Palette");
            intTag(out, "minecraft:smooth_stone", 0);
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

    private static void intTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void shortTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(2);
        out.writeUTF(name);
        out.writeShort(value);
    }

    private static Path jar(String module, String pattern) {
        Path folder = Path.of(System.getProperty("e2e.reactor")).resolve(module).resolve("target");
        try (Stream<Path> jars = Files.list(folder)) {
            return jars.filter(path -> path.getFileName().toString().matches(pattern + "\\.jar"))
                    .filter(path -> !path.getFileName().toString().startsWith("original-"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no " + pattern + " in " + folder + " — build it first"));
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
