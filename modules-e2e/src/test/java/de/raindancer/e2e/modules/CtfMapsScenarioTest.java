package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real maps a server is given, each played for a moment: built, both players standing on solid ground at their
 * spawns (not falling, not stuck in a wall), every flag on its stand. The maps are downloads that may not be published,
 * so they are not in this repository: point {@code CTF_MAPS} at the folder holding their {@code .schem} and {@code .yml}
 * files to run this.
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "CTF_MAPS", matches = ".+")
class CtfMapsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(120);
    private static final Pattern FLAG = Pattern.compile("flag of side (-?\\d+): HOME at (-?\\d+) (-?\\d+) (-?\\d+)");

    @Test
    @DisplayName("every map builds, puts both players on their feet and every flag on its stand")
    void everyMap() throws Exception {
        Path source = Path.of(System.getenv("CTF_MAPS"));
        List<String> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(source)) {
            files.filter(file -> file.toString().endsWith(".yml")).sorted()
                    .forEach(file -> ids.add(file.getFileName().toString().replace(".yml", "")));
        }
        assertThat(ids).as("maps in " + source).isNotEmpty();
        Map<String, String> files = Map.of("plugins/RainsCaptureTheFlag/config.yml", String.join("\n",
                "auto-start: false", "vote-maps: false", "vote-types: false", "vote-mutators: false",
                "mutator-chance: 0", "power-ups: true", "supply-drops: false", ""));
        try (Server server = Server.start("ctf-maps", List.of("ctf-standalone:RainsCaptureTheFlag-.*"), List.of(),
                files, List.of("Capture the Flag is up"))) {
            Path maps = server.paper.folder().resolve("plugins/RainsCaptureTheFlag/maps");
            try (Stream<Path> all = Files.list(source)) {
                for (Path file : all.filter(path -> path.toString().endsWith(".schem")
                        || path.toString().endsWith(".yml")).toList()) {
                    Files.copy(file, maps.resolve(file.getFileName()));
                }
            }
            Bot alice = server.player("alice");
            Bot bob = server.player("bob");
            alice.run("ctf join");
            bob.run("ctf join");
            List<String> problems = new ArrayList<>();
            for (String id : ids) {
                server.paper.console("ctf admin start " + id);
                server.paper.awaitLog("A round started on " + id, WAIT);
                Await.until("both are in the map", WAIT, () -> alice.world().contains("ctf_arena")
                        && bob.world().contains("ctf_arena"));
                Await.ticks(20 * 7);
                double aliceY = alice.position().getY();
                double bobY = bob.position().getY();
                Await.ticks(40);
                String info = server.paper.console("ctf admin info");
                if (Math.abs(alice.position().getY() - aliceY) > 0.6 || Math.abs(bob.position().getY() - bobY) > 0.6) {
                    problems.add(id + ": somebody is falling at their spawn (" + aliceY + " → " + alice.position().getY()
                            + ", " + bobY + " → " + bob.position().getY() + ")");
                }
                if (info.contains(", down,")) {
                    problems.add(id + ": somebody died standing at their spawn — " + info);
                }
                Matcher flag = FLAG.matcher(info);
                int stands = 0;
                while (flag.find()) {
                    stands++;
                    String block = "execute in minecraft:ctf_arena if block " + flag.group(2) + " " + flag.group(3) + " "
                            + flag.group(4) + " #minecraft:banners";
                    if (!server.paper.console(block).contains("passed")) {
                        problems.add(id + ": no banner on the stand of side " + flag.group(1) + " at " + flag.group(2)
                                + " " + flag.group(3) + " " + flag.group(4));
                    }
                }
                if (stands < 2) {
                    problems.add(id + ": fewer than two flags at home — " + info);
                }
                System.out.println("[ctf-maps] " + id + ": " + info.replace('\n', ' '));
                server.paper.console("ctf admin stop");
                server.paper.awaitLog("A round on " + id + " ended", WAIT);
                Await.until("back in the lobby", WAIT, () -> !alice.world().contains("ctf_arena")
                        && !bob.world().contains("ctf_arena"));
            }
            assertThat(problems).isEmpty();
            assertThat(server.paper.errorsFrom("RainsCaptureTheFlag", "RainsCore")).isEmpty();
        }
    }
}
