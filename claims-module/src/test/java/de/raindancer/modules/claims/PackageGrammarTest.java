package de.raindancer.modules.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That each package holds what its name says.
 *
 * <p>A package name is a promise, and the way it stops being one is a class at a time: something that formats a
 * name lands in {@code rules} because it was convenient, then something that stores a policy follows it, and
 * within a month the name means nothing and nobody can find anything. Two of those had already happened here
 * before this test existed.
 *
 * <p>Held as a source scan because the question is about where files are, which the filesystem answers exactly.
 */
class PackageGrammarTest {

    private static final Path CLAIMS = Path.of("src/main/java/de/raindancer/modules/claims");

    private record Source(String pkg, String name, String body) {
    }

    private static List<Source> in(String pkg) {
        Path dir = CLAIMS.resolve(pkg);
        if (!Files.isDirectory(dir)) {
            throw new AssertionError("the " + pkg + " package is gone — this test is about where things live");
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                found.add(new Source(pkg, file.getFileName().toString().replace(".java", ""),
                        Files.readString(file)));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + pkg, unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the packages, so a rename cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(in("rules")).isNotEmpty();
        assertThat(in("model")).isNotEmpty();
        assertThat(in("service")).isNotEmpty();
    }

    @Test
    @DisplayName("everything in rules is named as a rule")
    void rulesAreCalledRules() {
        List<String> misnamed = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IClaimRule")) {
                continue;   // the interface itself
            }
            if (!source.name().endsWith("Rule") && !source.name().endsWith("Rules")) {
                misnamed.add(source.name());
            }
        }
        assertThat(misnamed)
                .as("these are in rules and are not called one — either rename them or move them out")
                .isEmpty();
    }

    @Test
    @DisplayName("everything in rules is a rule")
    void rulesImplementTheInterface() {
        List<String> strangers = new ArrayList<>();
        for (Source source : in("rules")) {
            if (source.name().equals("IClaimRule")) {
                continue;
            }
            // Either the module's own promise, or Core's generic rule — the eight chained ones extend
            // AbstractRule, which is Core's.
            boolean isARule = source.body().contains("IClaimRule")
                    || source.body().contains("AbstractRule<")
                    || source.body().contains("implements IRule<");
            if (!isARule) {
                strangers.add(source.name());
            }
        }
        assertThat(strangers)
                .as("these live in rules without being one. A rule decides and does nothing else; what a "
                        + "claim *is* belongs in model, what stores it in store, what acts in service")
                .isEmpty();
    }

    @Test
    @DisplayName("a rule does not save, send or schedule")
    void rulesHaveNoSideEffects() {
        List<String> busy = new ArrayList<>();
        for (Source source : in("rules")) {
            boolean acts = source.body().contains("saveAsync(")
                    || source.body().contains("messages().send(")
                    || source.body().contains("Scheduling.")
                    || source.body().contains(".sendMessage(");
            if (acts) {
                busy.add(source.name());
            }
        }
        assertThat(busy)
                .as("a rule that acts cannot be asked speculatively — and 'would this be allowed?' is what a "
                        + "screen asks to decide whether to grey a button")
                .isEmpty();
    }

    @Test
    @DisplayName("the model does not reach for the server")
    void theModelStaysPlain() {
        List<String> reaching = new ArrayList<>();
        for (Source source : in("model")) {
            // Bukkit *types* are fine — a claim holds ItemStacks and Materials. Reaching for the running
            // server is not: it is what makes a model class impossible to test without one.
            if (source.body().contains("Bukkit.get") || source.body().contains("getServer()")) {
                reaching.add(source.name());
            }
        }
        assertThat(reaching)
                .as("these reach for the running server from the model, which is what makes a model class "
                        + "impossible to test without booting one")
                .isEmpty();
    }

    @Test
    @DisplayName("screens and listeners do not talk to the disk")
    void onlyTheStoreStores() {
        List<String> writing = new ArrayList<>();
        for (String pkg : List.of("screen", "listener")) {
            for (Source source : in(pkg)) {
                if (source.body().contains("Files.write") || source.body().contains("new YamlConfiguration")) {
                    writing.add(pkg + "/" + source.name());
                }
            }
        }
        assertThat(writing)
                .as("writing belongs in store, behind the services — a screen that writes its own file is one "
                        + "the auto-save does not know about")
                .isEmpty();
    }
}
