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
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The arena network as Lilly runs it: a proxy, a survival server ("lobby") with the gate and the chat, and an arena
 * server with Rain's Arena and the chat — the two linked through RainsCore's server link.
 *
 * <p>What is proved here and nowhere else: {@code /arena} really moves a player through the proxy and
 * {@code /arena leave} brings them back; chat said on either server is read on the other; and an arena world is built
 * from a schematic and becomes the hub somebody arriving is put in.
 */
@Tag("e2e")
class ArenaNetworkScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String LINK_SECRET = "e2e-link-secret-0123456789abcdef";
    private static final String FORWARDING_SECRET = "e2e-forwarding-secret";

    @Test
    @DisplayName("through the proxy to the arena and back, chat across both servers, a map built into a hub")
    void network() throws Exception {
        int linkPort = freePort();
        String paperGlobal = VelocityProxy.paperGlobal(FORWARDING_SECRET);

        PaperServer lobby = PaperServer.forRun().seed(1).in(E2e.serverFolder("arena-net-lobby"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("chat-standalone", "RainsChat-.*"))
                .plugin(jar("arenagate-standalone", "RainsArenaGate-.*"))
                .plugin(jar("essentials-standalone", "RainsEssentials-.*"))
                .plugin(jar("economy-standalone", "RainsEconomy-.*"))
                .plugin(jar("cosmetics-standalone", "RainsCosmetics-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/link.yml", "role: guest\nhost-url: http://127.0.0.1:" + linkPort
                        + "\nsecret: " + LINK_SECRET + "\n")
                .file("plugins/RainsChat/config.yml", "network:\n  enabled: true\n  tag: SMP\n")
                .file("plugins/RainsArenaGate/config.yml", "arena-server: arena\nwarmup-seconds: 0\n")
                .file("plugins/RainsEconomy/config.yml", "pay:\n  cooldown-seconds: 60\n")
                .build();
        PaperServer arena = PaperServer.forRun().seed(1).in(E2e.serverFolder("arena-net-arena"))
                .plugin(Path.of(System.getProperty("e2e.rainscore")))
                .plugin(jar("chat-standalone", "RainsChat-.*"))
                .plugin(jar("arena-standalone", "RainsArena-.*"))
                .plugin(jar("essentials-standalone", "RainsEssentials-.*"))
                .plugin(jar("cosmetics-standalone", "RainsCosmetics-.*"))
                .file("config/paper-global.yml", paperGlobal)
                .file("plugins/RainsCore/link.yml", "role: host\nlisten-address: 127.0.0.1\nlisten-port: " + linkPort
                        + "\nsecret: " + LINK_SECRET + "\nfollows: true\n")
                .file("plugins/RainsChat/config.yml", "network:\n  enabled: true\n  tag: Arena\n")
                .file("plugins/RainsArena/config.yml", "lobby-server: lobby\n")
                .build();
        Path maps = arena.folder().resolve("plugins").resolve("RainsArena").resolve("maps");
        Files.createDirectories(maps);
        Files.write(maps.resolve("floor.schem"), floorSchematic(21, 21));

        Map<String, Integer> servers = new LinkedHashMap<>();
        servers.put("lobby", lobby.port());
        servers.put("arena", arena.port());
        try (VelocityProxy proxy = VelocityProxy.in(E2e.serverFolder("arena-net-proxy"), servers, FORWARDING_SECRET);
             lobby; arena) {
            arena.start();
            arena.awaitLog("Arena is up", WAIT);
            arena.awaitLog("Server link: listening", WAIT);
            lobby.start();
            lobby.awaitLog("Arena gate is up", WAIT);
            proxy.start();

            // ── a map is built and becomes the hub ──────────────────────────────────────────────────
            arena.console("arena admin build arena floor.schem");
            arena.awaitLog("'arena' is built", WAIT);
            assertThat(arena.console("arena admin sethub arena 0.5 65 0.5 0 0")).doesNotContain("not one of");

            Bot ada = lobby.bot("Ada").through(proxy.port()).join();
            Bot bo = lobby.bot("Bo").through(proxy.port()).join();
            assertThat(lobby.console("list")).contains("Ada").contains("Bo");

            // ── through the proxy to the arena ──────────────────────────────────────────────────────
            bo.run("arena");
            Await.until("Bo is on the arena server", WAIT, () -> arena.console("list").contains("Bo"));
            Await.until("Bo is in the hub, on the built floor", WAIT, () -> arena.console(
                    "execute in minecraft:arena if entity @a[name=Bo,x=0.5,y=65,z=0.5,distance=..3]")
                    .contains("passed"));
            Await.until("Bo was welcomed", WAIT, () -> bo.chatText().stream()
                    .anyMatch(line -> line.contains("Welcome to the arena")));

            // ── one chat over both servers ──────────────────────────────────────────────────────────
            ada.say("hello from the survival side");
            Await.until("Bo, in the arena, reads Ada", WAIT, () -> bo.chatText().stream()
                    .anyMatch(line -> line.contains("SMP") && line.contains("hello from the survival side")));
            bo.say("hello from the arena");
            Await.until("Ada, on survival, reads Bo", WAIT, () -> ada.chatText().stream()
                    .anyMatch(line -> line.contains("Arena") && line.contains("hello from the arena")));

            // ── a private message across, and the answer back ───────────────────────────────────────
            Await.until("survival knows who is in the arena", WAIT, () -> {
                ada.run("msg Bo psst from survival");
                return bo.chatText().stream().anyMatch(line -> line.contains("psst from survival"));
            });
            bo.run("r hi back from the arena");
            Await.until("Ada, on survival, reads Bo's answer", WAIT, () -> ada.chatText().stream()
                    .anyMatch(line -> line.contains("hi back from the arena")));

            // ── cosmetics chosen on survival are worn in the arena ──────────────────────────────────────
            lobby.console("op Ada");
            ada.run("cosmetics particle flame");
            Await.until("Ada wears a flame on survival (was sent " + ada.chatText().subList(Math.max(0, ada.chatText().size() - 4),
                    ada.chatText().size()) + "; data " + lobby.console("data get entity Ada BukkitValues") + ")", WAIT,
                    () -> lobby.console("data get entity Ada BukkitValues").toLowerCase(java.util.Locale.ROOT).contains("flame"));
            proxy.command("send Ada arena");
            Await.until("Ada's flame came with her to the arena", WAIT, () -> arena.console(
                    "data get entity Ada BukkitValues").toLowerCase(java.util.Locale.ROOT).contains("flame"));
            proxy.command("send Ada lobby");
            Await.until("Ada is back on survival", WAIT, () -> lobby.console("list").contains("Ada"));

            // ── one balance on both servers: Bo, in the arena, pays Ada on survival ────────────────────────
            arena.awaitLog("money is the other server's", WAIT);
            Await.until("the economy knows them", WAIT, () -> !lobby.console("eco set Ada 1000").contains("unknown")
                    && !lobby.console("eco set Bo 500").contains("unknown"));
            bo.run("balance");
            Await.until("Bo sees his survival balance in the arena (was sent " + bo.chatText() + ")", WAIT, () -> bo.chatText().stream()
                    .anyMatch(line -> line.contains("500") && line.contains("same money")));
            bo.run("pay Ada 120");
            Await.until("Bo is told it went through", WAIT, () -> bo.chatText().stream()
                    .anyMatch(line -> line.contains("You paid") && line.contains("Ada")));
            ada.run("balance");
            Await.until("the money arrived on survival", WAIT, () -> ada.chatText().stream()
                    .anyMatch(line -> line.contains("1,120")));
            bo.run("pay Ada 5");
            Await.until("survival's own rule holds for a payment from the arena: the wait between payments", WAIT,
                    () -> bo.chatText().stream().anyMatch(line -> line.contains("before paying again")));

            // ── and back ────────────────────────────────────────────────────────────────────────────
            bo.run("arena leave");
            Await.until("Bo is back on the survival server", WAIT, () -> lobby.console("list").contains("Bo")
                    && !arena.console("list").contains("Bo"));

            // ── an update: everybody waits in the arena's hub through the restart and comes back by themselves ─────
            proxy.command("send Ada arena");
            Await.until("Ada waits in the arena", WAIT, () -> arena.console("list").contains("Ada"));
            assertThat(arena.console("arena admin hold Ada")).contains("1");
            Await.until("Ada was told why", WAIT, () -> ada.chatText().stream()
                    .anyMatch(line -> line.contains("restarting for an update")));
            lobby.restartKeepingBots();
            lobby.awaitLog("Arena gate is up", WAIT);
            Await.until("Ada is back on survival without doing anything", WAIT, () -> lobby.console("list").contains("Ada")
                    && !arena.console("list").contains("Ada"));
        } finally {
            lobby.keepLogsIn(E2e.logsFolder("arena-net-lobby"));
            arena.keepLogsIn(E2e.logsFolder("arena-net-arena"));
        }
    }

    /** A Sponge v2 schematic of a stone floor, one block thick — written by hand, it is a dozen fields. */
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
            intTag(out, "minecraft:stone", 0);
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
