package de.raindancer.modules.essentials.store;

import de.raindancer.core.data.store.Documents;
import de.raindancer.modules.essentials.model.HouseRule;
import de.raindancer.modules.essentials.model.RulePreset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** The rules are the owner's: edited once, read by every server, kept in the database. */
@Tag("database")
class RuleBookNetworkTest {

    private static final String BUNDLED = """
            friendly-smp:
              description: Be nice, build things
              rules:
                - title: Be kind
                  text: No insults, no harassment.
            anarchy:
              description: Anything goes
              rules:
                - title: No rules
                  text: Except this one.
            """;

    @TempDir
    Path plugins;

    private Servers servers;

    private RuleBook on(String id) {
        if (servers == null) {
            servers = new Servers(plugins);
        }
        Documents documents = servers.server(id);
        RuleBook book = new RuleBook(servers.network(documents, "rules.yml"), servers.network(documents, "rule-presets.yml"),
                () -> new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8)), "friendly-smp");
        book.load();
        book.watch();
        return book;
    }

    @AfterEach
    void close() {
        if (servers != null) {
            servers.close();
        }
    }

    @Test
    @DisplayName("a rule the owner adds on one server is shown on the other")
    void editOnceEverywhere() {
        RuleBook smp = on("smp");
        RuleBook arena = on("arena");

        smp.add("No lag machines", "Redstone clocks get removed.");
        servers.heard();

        assertThat(arena.rules()).extracting(HouseRule::title).containsExactly("Be kind", "No lag machines");
        arena.remove(arena.rules().getFirst().id());
        servers.heard();
        assertThat(smp.rules()).extracting(HouseRule::title).containsExactly("No lag machines");
    }

    @Test
    @DisplayName("a preset saved on one server can be applied on the other")
    void presetsAreShared() {
        RuleBook smp = on("smp");
        RuleBook arena = on("arena");

        smp.add("Extra", "Only here.");
        smp.savePreset("mine", "Our own");
        servers.heard();

        assertThat(arena.presets()).extracting(RulePreset::name).containsExactlyInAnyOrder("friendly-smp", "anarchy", "mine");
        assertThat(arena.applyPreset("mine")).isTrue();
        servers.heard();
        assertThat(smp.rules()).extracting(HouseRule::title).containsExactly("Be kind", "Extra");
    }

    @Test
    @DisplayName("two servers starting for the first time at once end up with the same rules, ids included")
    void firstStartRace() throws Exception {
        servers = new Servers(plugins);
        Documents first = servers.server("smp");
        Documents second = servers.server("arena");
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            var one = threads.submit(() -> {
                together.await();
                RuleBook book = new RuleBook(servers.network(first, "rules.yml"), servers.network(first, "rule-presets.yml"),
                        () -> new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8)), "friendly-smp");
                book.load();
                return book;
            });
            var two = threads.submit(() -> {
                together.await();
                RuleBook book = new RuleBook(servers.network(second, "rules.yml"), servers.network(second, "rule-presets.yml"),
                        () -> new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8)), "friendly-smp");
                book.load();
                return book;
            });
            assertThat(one.get().rules()).isEqualTo(two.get().rules());
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("nothing is written to disk: the rules and presets are documents only")
    void noFiles() throws Exception {
        RuleBook smp = on("smp");
        smp.add("Extra", "Only here.");
        smp.savePreset("mine", "");

        try (Stream<Path> files = Files.walk(plugins)) {
            assertThat(files.filter(Files::isRegularFile).map(file -> file.getFileName().toString()))
                    .noneMatch(name -> name.endsWith(".yml"));
        }
    }

    @Test
    @DisplayName("an owner's 1.x rules.yml is brought in, and its rules are what every server shows")
    void oldFileIsBroughtIn() throws Exception {
        servers = new Servers(plugins);
        Files.createDirectories(servers.file("rules.yml").getParent());
        Files.writeString(servers.file("rules.yml"), """
                rules:
                  - id: abc
                    title: Owner's own
                    text: Hand-written.
                """);

        RuleBook smp = on("smp");
        RuleBook arena = on("arena");

        assertThat(smp.rules()).extracting(HouseRule::title).containsExactly("Owner's own");
        assertThat(arena.rules()).extracting(HouseRule::id).containsExactly("abc");
    }
}
